import Foundation

/// Nur eine Alarm-Auswertung zur Zeit — über alle Prozesse der App-Gruppe (App im
/// Vordergrund, Hintergrund-Aufgabe, Widget mit den Kursalarmen).
///
/// Umgesetzt als «Lease»: Die Datei `alarm.lease` im App-Group-Container wird exklusiv
/// angelegt (`O_CREAT | O_EXCL` ist atomar, auch zwischen Prozessen) und beim Freigeben
/// gelöscht. Bewusst kein gehaltenes Datei-Lock (`flock`): Hält ein angehaltener Prozess
/// ein Lock auf eine Datei im geteilten Container, beendet iOS ihn (0xdead10cc).
/// Bleibt eine Lease liegen (Prozess beendet), gilt sie nach `staleAfterSeconds` ohne Auffrischen
/// (`refresh`, vor jedem gemeldeten Alarm) als verwaist. Übernehmen: nur löschen, wenn die Datei noch dieselbe
/// verwaiste ist, dann exklusiv neu anlegen und nach kurzer Pause nachlesen, ob der eigene Token noch drinsteht —
/// haben zwei Prozesse gleichzeitig übernommen, gibt der unterlegene auf. Ist die Lease beim Auffrischen nicht
/// mehr die eigene (`refresh` = false), meldet der Durchlauf keine weiteren Alarme.
///
/// Ablauf (siehe `PriceRefresher.refresh`): Kurse holen → Lease nehmen → Alarme frisch von
/// der Platte lesen und auswerten → Aufrufer speichert die Kurse → `Outcome.deliverAlarms` meldet,
/// speichert je bestätigter Mitteilung den Alarm und gibt die Lease frei.
final class AlarmLease: @unchecked Sendable {
    /// Älter als das → verwaist (Auswertung in Millisekunden, Melden je Alarm höchstens rund 1 s).
    static let staleAfterSeconds: TimeInterval = 30

    /// So lange nach dem Übernehmen einer verwaisten Lease warten, bevor nachgelesen wird.
    static let takeoverVerifyNanos: UInt64 = 50_000_000

    private let url: URL
    private let token: String
    private let lock = NSLock()
    private var held = true
    /// Ohne Datei (Container nicht beschreibbar): Auswertung ohne Lease wie früher — gilt nie als verloren.
    private let failOpen: Bool
    /// Als verwaist übernommen: vor dem Gebrauch nachprüfen (`acquire`).
    private(set) var takenOver = false

    private init(url: URL, token: String, held: Bool = true) {
        self.url = url
        self.token = token
        self.held = held
        self.failOpen = !held
    }

    deinit { release() }

    static var fileURL: URL { SharedStorage.directory.appendingPathComponent("alarm.lease") }

    /// Versucht einmal, die Lease zu nehmen; nil = ein anderer Prozess wertet gerade aus.
    static func tryAcquire(now: Date = Date()) -> AlarmLease? {
        let url = fileURL
        let token = UUID().uuidString
        if let lease = create(url: url, token: token) { return lease }
        // Verwaist (Prozess während der Auswertung beendet)? Dann übernehmen.
        let staleToken = try? String(contentsOf: url, encoding: .utf8)
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attributes[.modificationDate] as? Date,
              now.timeIntervalSince(modified) > staleAfterSeconds else { return nil }
        // Inzwischen von einem anderen Prozess übernommen (anderer Inhalt)? Dann nicht dessen Lease löschen
        guard (try? String(contentsOf: url, encoding: .utf8)) == staleToken else { return nil }
        _ = Darwin.unlink(url.path)
        guard let lease = create(url: url, token: token) else { return nil }
        lease.takenOver = !lease.failOpen
        return lease
    }

    /// Wartet höchstens `timeoutSeconds` (in Schritten von 0,1 s) auf die Lease.
    static func acquire(timeoutSeconds: Double = 5) async -> AlarmLease? {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while true {
            if let lease = tryAcquire() {
                guard lease.takenOver else { return lease }
                // Übernommen: kurz warten und nachlesen — hat ein zweiter Prozess gleichzeitig übernommen,
                // steht dessen Token drin und diese Lease gilt nicht
                try? await Task.sleep(nanoseconds: takeoverVerifyNanos)
                if lease.refresh() { return lease }
                lease.release()
            }
            if Date() >= deadline || Task.isCancelled { return nil }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    private static func create(url: URL, token: String) -> AlarmLease? {
        let fd = Darwin.open(url.path, O_CREAT | O_EXCL | O_WRONLY, 0o644)
        guard fd >= 0 else {
            // Nur «gibt es schon» heisst: ein anderer Prozess wertet aus. Jeder andere Fehler
            // (Container nicht beschreibbar o. Ä.) darf die Alarme nicht dauerhaft abschalten:
            // dann ohne Lease auswerten wie früher.
            return errno == EEXIST ? nil : AlarmLease(url: url, token: token, held: false)
        }
        let bytes = Array(token.utf8)
        _ = bytes.withUnsafeBytes { buffer in Darwin.write(fd, buffer.baseAddress, buffer.count) }
        _ = Darwin.close(fd)
        return AlarmLease(url: url, token: token)
    }

    /// Lease auffrischen (Änderungsdatum der Datei = jetzt), z. B. vor jedem gemeldeten Alarm: Das
    /// Alter zählt ab dem letzten Auffrischen, so verliert ein langes Melden die Lease nicht nach
    /// `staleAfterSeconds`. Nur die eigene, noch gehaltene Lease; ohne Lease (Fail-open) nichts.
    /// - Returns: true = Lease noch die eigene (bzw. Fail-open); false = freigegeben oder an einen anderen Prozess
    ///   verloren — dann keine weiteren Alarme melden.
    @discardableResult
    func refresh() -> Bool {
        if failOpen { return true }
        lock.lock()
        let isHeld = held
        lock.unlock()
        guard isHeld else { return false }
        guard let content = try? String(contentsOf: url, encoding: .utf8), content == token else {
            // Verloren: nicht mehr als gehalten führen (`release` löscht ohnehin nur die eigene Datei)
            lock.lock()
            held = false
            lock.unlock()
            return false
        }
        _ = Darwin.utimes(url.path, nil)
        return true
    }

    /// Freigeben (mehrfach aufrufbar). Löscht nur die eigene Lease — wurde sie als verwaist
    /// übernommen, gehört die Datei inzwischen einem anderen Prozess.
    func release() {
        lock.lock()
        let wasHeld = held
        held = false
        lock.unlock()
        guard wasHeld else { return }
        if let content = try? String(contentsOf: url, encoding: .utf8), content == token {
            _ = Darwin.unlink(url.path)
        }
    }
}
