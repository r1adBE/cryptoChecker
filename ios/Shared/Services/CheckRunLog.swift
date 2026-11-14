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
    /// Nicht gelaufen: eben erst geprüft bzw. läuft gerade (zählt als Prüfung), nichts zu tun oder offline (nur die
    /// App; ein geplanter Lauf offline wird nicht vermerkt).
    case skipped
    /// Gelaufen, aber kein Alarm geprüft: alle Börsen pausiert bzw. ohne Alarm-Lease. Zählt nicht als gelungene Prüfung.
    case paused
    /// Geplanter Lauf ohne Netz nicht versucht (Android: exakter Wecker). Zählt nicht als Prüfung; die Zeile nennt das Netz.
    case offline

    /// Zählt als Prüfung für «Letzte Prüfung» (gelungen, teilweise oder eben erst geprüft). Wie Android.
    var countsAsCheck: Bool { self == .ok || self == .partial || self == .skipped }
}

/// Ein Lauf: Ende, Auslöser, Ergebnis, Dauer.
struct CheckRun: Equatable, Sendable {
    let at: Int64
    let trigger: CheckTrigger
    let result: CheckResult
    let durationMillis: Int64
}

/// «Letzte Prüfungen»: die letzten `max` Läufe der Hintergrund-Prüfung, neueste zuerst, in
/// einer kleinen Textdatei ohne Sicherung (eine Zeile je Lauf: `at;trigger;result;dauer`). Zeigt
/// ehrlich, wann iOS wirklich geprüft hat. Reine Logik — wie `CheckRunLog.kt`.
enum CheckRunLog {
    static let max = 20

    /// Neuen Lauf vorne anfügen, höchstens `max` behalten (Ringpuffer).
    static func add(_ runs: [CheckRun], _ run: CheckRun, max: Int = CheckRunLog.max) -> [CheckRun] {
        Array(([run] + runs).prefix(Swift.max(max, 0)))
    }

    /// Ergebnis aus geprüften und gescheiterten Paaren; nichts zu prüfen (alles live, keine Paare) = ok. Nichts geprüft,
    /// weil alle Börsen pausiert waren (`paused` Paare), bzw. Alarme ohne Lease ausgelassen (`alarmsUnchecked`): `.paused`.
    static func result(checked: Int, failed: Int, paused: Int = 0, alarmsUnchecked: Bool = false) -> CheckResult {
        if alarmsUnchecked || (checked <= 0 && paused > 0) { return .paused }
        if checked <= 0 || failed <= 0 { return .ok }
        return failed >= checked ? .failed : .partial
    }

    /// Ende der letzten vom System geplanten Prüfung für «Letzte Prüfung» (Zeile über der Merkliste, «Damit Alarme
    /// ankommen»); App und Widget zählen nicht. Gescheiterte Läufe auch nicht — etwa ein misslungenes Vormerken
    /// (Dauer 0): Sonst gälte eine Prüfung als eben gelaufen, die gar keine Kurse geholt hat. nil = keine. Wie Android.
    static func lastBackgroundCheck(_ runs: [CheckRun]) -> Int64? {
        runs.first { $0.trigger.scheduled && $0.result.countsAsCheck }?.at
    }

    /// Der neueste geplante Lauf wurde offline nicht versucht (`.offline`): die Zeile nennt das Netz. Wie Android.
    static func offlineSkipped(_ runs: [CheckRun]) -> Bool {
        runs.first { $0.trigger.scheduled }?.result == .offline
    }

    /// Ende des neuesten geplanten Laufs, wenn unter den gespeicherten (`max`) keiner gelang — sonst nil. Scheitert
    /// jede Prüfung, fiele die letzte gelungene sonst aus dem Ringpuffer, und «Letzte Prüfung» sähe aus wie «noch nie
    /// geprüft» (grau) statt wie eine Warnung. Wie Android.
    static func lastFailedBackgroundCheck(_ runs: [CheckRun]) -> Int64? {
        lastBackgroundCheck(runs) != nil ? nil : runs.first { $0.trigger.scheduled }?.at
    }

    /// Ende des ältesten gespeicherten geplanten Laufs, wenn keiner gelang — sonst nil: Seit mindestens dann scheitert
    /// jede Prüfung. Die Zuverlässigkeitszeile warnt erst, wenn das länger als ihre Grenze anhält (ein einzelner
    /// gescheiterter Lauf kurz nach dem Einrichten ist noch keine Verspätung). Wie Android.
    static func failingSince(_ runs: [CheckRun]) -> Int64? {
        lastBackgroundCheck(runs) != nil ? nil : runs.last { $0.trigger.scheduled }?.at
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

    /// Lauf vorne anfügen (höchstens `CheckRunLog.max`) und schreiben. Lesen und Schreiben als ein abgestimmter
    /// Schritt über die Prozesse (App, Hintergrund-Aufgabe, Widget: `SharedStorage.coordinatedWrite`), sonst ginge
    /// ein gleichzeitig vermerkter Lauf verloren; `lock` hält zusätzlich den eigenen Prozess in Reihe.
    ///
    /// Jeder Lauf ausser in der offenen App (`.foreground`) zählt für «Akku & Aktivität» als Hintergrund-Lauf
    /// (`UsageMeter`); der Lauf ist hier zu Ende, darum die Zähler gleich mitschreiben. Wie Android.
    static func add(_ run: CheckRun, to url: URL = CheckRunStore.url, meter: UsageMeter = .shared) {
        if run.trigger != .foreground {
            meter.countRun()
            meter.flush()
        }
        lock.lock()
        defer { lock.unlock() }
        SharedStorage.coordinatedWrite(at: url) { () -> Void in
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
    }

    /// Lauf vermerken, der bei `startedAt` begann und jetzt endet.
    static func log(_ trigger: CheckTrigger, _ result: CheckResult, startedAt: Int64) {
        let now = EpochTime.nowMillis
        add(CheckRun(at: now, trigger: trigger, result: result, durationMillis: Swift.max(now - startedAt, 0)))
    }

    /// Wie `log`, aber abseits des Aufrufers (die Datei-Abstimmung kann auf einen anderen Prozess warten) —
    /// für Hauptfaden und `expirationHandler`. Die Dauer zählt bis jetzt; mit `await …value` abwarten, wo der Eintrag vor
    /// dem Ende der Aufgabe stehen muss.
    @discardableResult
    static func logDetached(_ trigger: CheckTrigger, _ result: CheckResult, startedAt: Int64) -> Task<Void, Never> {
        let now = EpochTime.nowMillis
        let run = CheckRun(at: now, trigger: trigger, result: result, durationMillis: Swift.max(now - startedAt, 0))
        return Task.detached(priority: .utility) { add(run) }
    }
}
