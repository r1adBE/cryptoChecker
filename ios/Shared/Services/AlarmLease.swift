import Foundation

/// Nur eine Alarm-Auswertung zur Zeit — über alle Prozesse der App-Gruppe (App im
/// Vordergrund, Hintergrund-Aufgabe; das Widget wertet keine Alarme aus).
///
/// Umgesetzt als «Lease»: Die Datei `alarm.lease` im App-Group-Container wird exklusiv
/// angelegt (`O_CREAT | O_EXCL` ist atomar, auch zwischen Prozessen) und beim Freigeben
/// gelöscht. Bewusst kein gehaltenes Datei-Lock (`flock`): Hält ein angehaltener Prozess
/// ein Lock auf eine Datei im geteilten Container, beendet iOS ihn (0xdead10cc).
/// Bleibt eine Lease liegen (Prozess beendet), gilt sie nach `staleAfterSeconds` als verwaist.
///
/// Ablauf (siehe `PriceRefresher.refresh`): Kurse holen → Lease nehmen → Alarme frisch von
/// der Platte lesen und auswerten → Aufrufer speichert → `Outcome.deliverAlarms()` meldet
/// und gibt die Lease frei.
final class AlarmLease: @unchecked Sendable {
    /// Älter als das → verwaist (die Auswertung selbst dauert Millisekunden).
    static let staleAfterSeconds: TimeInterval = 30

    private let url: URL
    private let token: String
    private let lock = NSLock()
    private var held = true

    private init(url: URL, token: String, held: Bool = true) {
        self.url = url
        self.token = token
        self.held = held
    }

    deinit { release() }

    static var fileURL: URL { SharedStorage.directory.appendingPathComponent("alarm.lease") }

    /// Versucht einmal, die Lease zu nehmen; nil = ein anderer Prozess wertet gerade aus.
    static func tryAcquire(now: Date = Date()) -> AlarmLease? {
        let url = fileURL
        let token = UUID().uuidString
        if let lease = create(url: url, token: token) { return lease }
        // Verwaist (Prozess während der Auswertung beendet)? Dann übernehmen.
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attributes[.modificationDate] as? Date,
              now.timeIntervalSince(modified) > staleAfterSeconds else { return nil }
        _ = Darwin.unlink(url.path)
        return create(url: url, token: token)
    }

    /// Wartet höchstens `timeoutSeconds` (in Schritten von 0,1 s) auf die Lease.
    static func acquire(timeoutSeconds: Double = 5) async -> AlarmLease? {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while true {
            if let lease = tryAcquire() { return lease }
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
