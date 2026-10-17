import Foundation

/// Wer die Prüfung ausgelöst hat; `rawValue` steht in der Datei (gleich auf Android, `CheckTrigger`).
enum CheckTrigger: String, CaseIterable, Sendable {
    /// Android: WorkManager (periodisch).
    case worker
    /// Android: exakter Wecker «Pünktlich prüfen».
    case exact
    /// iOS: BGAppRefreshTask.
    case bgTask = "bgtask"
    /// Widget (iOS: Zeichnen bzw. Knopf — Kurse und Kursalarme, keine Alarme mit Zusatzdaten).
    case widget
    /// Per Knopf bzw. Ziehen in der offenen App.
    case foreground

    /// Vom System geplant (nur diese werden kurz nach einer Prüfung übersprungen).
    var scheduled: Bool {
        switch self {
        case .worker, .exact, .bgTask: return true
        case .widget, .foreground: return false
        }
    }
}

/// Ergebnis einer Prüfung; `rawValue` steht in der Datei (gleich auf Android, `CheckResult`).
enum CheckResult: String, CaseIterable, Sendable {
    case ok
    case partial
    case failed
    /// Nicht gelaufen: eben erst geprüft, offline oder nichts zu tun.
    case skipped
}

/// Ein Lauf: Ende, Auslöser, Ergebnis, Dauer.
struct CheckRun: Equatable, Sendable {
    let at: Int64
    let trigger: CheckTrigger
    let result: CheckResult
    let durationMillis: Int64
}

/// «Letzte Prüfungen» (Build 46): die letzten `max` Läufe der Hintergrund-Prüfung, neueste zuerst, in
/// einer kleinen Textdatei ohne Sicherung (eine Zeile je Lauf: `at;trigger;result;dauer`). Zeigt
/// ehrlich, wann iOS wirklich geprüft hat. Reine Logik — wie `CheckRunLog.kt`.
enum CheckRunLog {
    static let max = 20

    /// Neuen Lauf vorne anfügen, höchstens `max` behalten (Ringpuffer).
    static func add(_ runs: [CheckRun], _ run: CheckRun, max: Int = CheckRunLog.max) -> [CheckRun] {
        Array(([run] + runs).prefix(Swift.max(max, 0)))
    }

    /// Ergebnis aus geprüften und gescheiterten Paaren; nichts zu prüfen (alles live, keine Paare) = ok.
    static func result(checked: Int, failed: Int) -> CheckResult {
        if checked <= 0 || failed <= 0 { return .ok }
        return failed >= checked ? .failed : .partial
    }

    static func encode(_ runs: [CheckRun]) -> String {
        runs.map { "\($0.at);\($0.trigger.rawValue);\($0.result.rawValue);\($0.durationMillis)" }
            .joined(separator: "\n")
    }

    /// Unlesbare Zeilen fallen weg; höchstens `max` Läufe, Reihenfolge der Datei.
    static func decode(_ text: String?) -> [CheckRun] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        var runs: [CheckRun] = []
        for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            let parts = line.split(separator: ";", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 4,
                  let at = Int64(parts[0]),
                  let trigger = CheckTrigger(rawValue: parts[1]),
                  let result = CheckResult(rawValue: parts[2]),
                  let duration = Int64(parts[3]) else { continue }
            runs.append(CheckRun(at: at, trigger: trigger, result: result, durationMillis: Swift.max(duration, 0)))
            if runs.count >= CheckRunLog.max { break }
        }
        return runs
    }
}

/// Speicher von «Letzte Prüfungen»: `check_runs.txt` im App-Group-Ordner (App, Hintergrund-Aufgabe und
/// Widget schreiben), aus den Geräte-Backups ausgenommen. Fehler bleiben still — nur ein Blick zurück.
/// Wie `CheckRunStore.kt`.
enum CheckRunStore {
    static let fileName = "check_runs.txt"
    private static let lock = NSLock()

    static var url: URL { SharedStorage.directory.appendingPathComponent(fileName) }

    /// Neueste zuerst.
    static func load(from url: URL = CheckRunStore.url) -> [CheckRun] {
        CheckRunLog.decode(try? String(contentsOf: url, encoding: .utf8))
    }

    /// Lauf vorne anfügen (höchstens `CheckRunLog.max`) und schreiben.
    static func add(_ run: CheckRun, to url: URL = CheckRunStore.url) {
        lock.lock()
        defer { lock.unlock() }
        let next = CheckRunLog.add(load(from: url), run)
        guard let data = CheckRunLog.encode(next).data(using: .utf8) else { return }
        do {
            try data.write(to: url, options: .atomic)
            // Atomares Schreiben ersetzt die Datei: Merkmal jedes Mal neu setzen
            BackupExclusion.set(excluded: true, for: url)
        } catch {
            return
        }
    }

    /// Lauf vermerken, der bei `startedAt` begann und jetzt endet.
    static func log(_ trigger: CheckTrigger, _ result: CheckResult, startedAt: Int64) {
        let now = TimeUtils.nowMillis
        add(CheckRun(at: now, trigger: trigger, result: result, durationMillis: Swift.max(now - startedAt, 0)))
    }
}
