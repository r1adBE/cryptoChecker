import Foundation

/// Speicher des Alarm-Protokolls: `alarm_log.json` im App-Group-Ordner neben «Letzte Prüfungen» (`CheckRunStore`) —
/// App, Hintergrund-Aufgabe und Widget schreiben, abgestimmt über `SharedStorage.coordinatedWrite`. Inhalt mit Paaren und
/// Schwellen wie die Merkliste, darum verschlüsselt wie sie (`ProtectedFile`, in `ProtectedStorage.files`) und aus den
/// Geräte-Backups ausgenommen. Darf die Prüfung nie stören: Fehler bleiben still, Live-Kurse höchstens alle
/// `AlarmLog.liveWriteIntervalMillis` (ein neuer Protokoll-Eintrag sofort, `AlarmLog.startsEvent`). Ohne Schlüssel nie
/// Klartext (`requireSealed`): die Einträge warten dann im Speicher. Wie `AlarmLogStore.kt`.
enum AlarmLogStore {
    static let fileName = "alarm_log.json"

    static var url: URL { SharedStorage.directory.appendingPathComponent(fileName) }

    private static let lock = NSLock()

    /// Je Prozess: noch nicht geschriebene Live-Einträge, Zeit des letzten Schreibens und der zuletzt gelesene bzw.
    /// geschriebene Stand (`known`, zu `knownURL`) — daran misst `record`, ob Einträge einen neuen Protokoll-Eintrag
    /// beginnen, ohne die Datei jede Sekunde zu lesen. Schrieb inzwischen ein anderer Prozess, irrt das höchstens um
    /// ein Schreiben zu früh bzw. bis zu `liveWriteIntervalMillis` zu spät.
    private struct Pending {
        var entries: [AlarmLogEntry] = []
        var lastWriteAt: Int64 = 0
        var known: AlarmLogState?
        var knownURL: URL?
    }
    private static let pending = LockedBox(Pending())
    /// Höchstens so viele Einträge warten im Speicher.
    private static let maxPending = 2_000

    private enum Read {
        case ok(AlarmLogState)
        /// Verschlüsselt, Schlüssel gerade nicht verfügbar: nichts überschreiben.
        case locked
    }

    private static func read(from url: URL, keys: StorageKey) -> Read {
        guard FileManager.default.fileExists(atPath: url.path) else { return .ok(.empty) }
        do {
            return .ok(AlarmLog.decode(try ProtectedFile.read(url, keys: keys)))
        } catch ProtectedFile.ReadError.damaged(_) {
            // Nur ein Blick zurück: neu beginnen, das nächste Schreiben ersetzt die Datei
            return .ok(.empty)
        } catch ProtectedFile.ReadError.locked {
            return .locked
        } catch {
            return .locked
        }
    }

    /// Für die Anzeige: Datei samt der noch nicht geschriebenen Live-Einträge dieses Prozesses.
    static func load(from url: URL = AlarmLogStore.url, now: Int64 = EpochTime.nowMillis,
                     keys: StorageKey = .shared) -> AlarmLogState {
        var base = AlarmLogState.empty
        if case .ok(let state) = read(from: url, keys: keys) { base = state }
        let waiting = pending.current.entries
        return waiting.isEmpty ? base : AlarmLog.record(base, waiting, now: now)
    }

    /// Einträge eines Durchlaufs übernehmen und (ausser bei gedrosselten Live-Kursen) schreiben. Wirft nie.
    static func record(_ entries: [AlarmLogEntry], live: Bool, to url: URL = AlarmLogStore.url,
                       now: Int64 = EpochTime.nowMillis, keys: StorageKey = .shared) {
        guard !entries.isEmpty else { return }
        var toWrite: [AlarmLogEntry] = []
        pending.write { p in
            // Sofort nur, was einen neuen Eintrag beginnt; das Verlängern eines «von … bis …» wartet wie jede Prüfung.
            // Noch kein bekannter Stand (erstes Schreiben des Prozesses): bemerkenswerte Einträge sofort
            let startsEvent: Bool
            if live, p.knownURL == url, let known = p.known {
                startsEvent = AlarmLog.startsEvent(AlarmLog.record(known, p.entries, now: now), entries)
            } else {
                startsEvent = entries.contains { $0.notable }
            }
            p.entries.append(contentsOf: entries)
            if p.entries.count > maxPending { p.entries.removeFirst(p.entries.count - maxPending) }
            if !live || startsEvent || now - p.lastWriteAt >= AlarmLog.liveWriteIntervalMillis || now < p.lastWriteAt {
                toWrite = p.entries
                p.entries = []
                p.lastWriteAt = now
            }
        }
        guard !toWrite.isEmpty else { return }
        if !modify(at: url, keys: keys, { AlarmLog.record($0, toWrite, now: now) }) {
            // Nicht geschrieben (Schlüssel gesperrt, Schreibfehler): beim nächsten Mal nachtragen
            pending.write { p in
                p.entries = toWrite + p.entries
                if p.entries.count > maxPending { p.entries.removeFirst(p.entries.count - maxPending) }
            }
        }
    }

    /// Alarm gelöscht: seine letzte Prüfung entfernen.
    static func removeAlarm(_ alarmId: Int64, at url: URL = AlarmLogStore.url, keys: StorageKey = .shared) {
        pending.write { $0.entries.removeAll { $0.alarmId == alarmId } }
        modify(at: url, keys: keys) { AlarmLog.removeAlarm($0, alarmId) }
    }

    /// Nur letzte Prüfungen bestehender Alarme behalten.
    static func retainAlarms(_ alarmIds: Set<Int64>, at url: URL = AlarmLogStore.url, keys: StorageKey = .shared) {
        modify(at: url, keys: keys) { AlarmLog.retainAlarms($0, alarmIds) }
    }

    /// «Protokoll löschen».
    static func clearEvents(at url: URL = AlarmLogStore.url, keys: StorageKey = .shared) {
        // Noch wartende Einträge kommen nach dem Löschen nicht zurück ins Protokoll — nur noch als letzte Prüfung
        pending.write { p in p.entries = p.entries.map { var e = $0; e.notable = false; return e } }
        modify(at: url, keys: keys) { AlarmLog.clearEvents($0) }
    }

    /// Lesen, ändern und verschlüsselt schreiben als ein abgestimmter Schritt über die Prozesse. false = nicht geschrieben.
    @discardableResult
    private static func modify(at url: URL, keys: StorageKey,
                               _ change: (AlarmLogState) -> AlarmLogState) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return SharedStorage.coordinatedWrite(at: url) { () -> Bool in
            guard case .ok(let current) = read(from: url, keys: keys) else { return false }
            let next = change(current)
            if next == current && FileManager.default.fileExists(atPath: url.path) {
                remember(next, url)
                return true
            }
            guard let data = AlarmLog.encode(next) else { return false }
            do {
                // Ohne Schlüssel nie Klartext (wie Android): `locked`, der Aufrufer behält die Einträge im Speicher
                try ProtectedFile.write(data, to: url, keys: keys, requireSealed: true)
                // Atomares Schreiben ersetzt die Datei: Merkmal jedes Mal neu setzen
                BackupExclusion.set(excluded: true, for: url)
                remember(next, url)
                return true
            } catch {
                return false
            }
        }
    }

    private static func remember(_ state: AlarmLogState, _ url: URL) {
        pending.write { p in
            p.known = state
            p.knownURL = url
        }
    }
}
