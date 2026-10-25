import Foundation
import os
import WidgetKit

// Kurse aus dem Widget heraus holen (Knopf und alter Stand beim Zeichnen). Eigene Datei neben `WidgetData.swift`.

// MARK: Aktualisieren aus dem Widget

/// Holt die Kurse wie die App (Kurse, Kurs-Mitteilungen), speichert sie und prüft mit ihnen die
/// Kursalarme — gleicher Ablauf wie App und Hintergrund-Aufgabe: `AlarmLease`, Alarme frisch von der
/// Platte, Nachtruhe und Abklingzeit (`checkAlarms`), zuerst melden und erst nach bestätigter Mitteilung
/// speichern (`Outcome.deliverAlarms`). So meldet kein zweiter Prozess dieselbe Auslösung; die App
/// übernimmt sie beim Speichern (`SharedStorage.mergeNewerAlarmTriggers`) statt sie zurückzusetzen.
/// Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) prüfen weiter nur App und
/// Hintergrund-Aufgabe: Kerzen und Futures-Daten sprengten Zeit und Speicher der Erweiterung. Die Alarmtöne
/// liegen auch im Bundle der Erweiterung (`tools/gen_xcodeproj.py`), Ansagen gibt es im Widget nicht.
enum WidgetRefresh {
    /// Älter als das → beim Zeichnen zuerst neu holen (`WidgetCadence`).
    static let staleAfterMillis = WidgetCadence.staleAfterMillis
    /// Nach einem misslungenen Versuch so lange keinen neuen aus dem Zeichnen heraus: Sonst bleiben die
    /// Daten «alt», die Widgets laden sich gegenseitig neu und verbrauchen das Neuladen-Kontingent von
    /// WidgetKit (dann stehen sie den Rest des Tages still).
    static let retryAfterMillis = WidgetCadence.retryAfterMillis
    private static let runningKey = "widget_refresh_running_at"
    /// Zeitpunkt des letzten misslungenen Versuchs (App-Group-Speicher, gilt für alle Widgets).
    private static let failedAtKey = "widget_refresh_failed_at"
    /// So lange gilt eine laufende Aktualisierung als aktiv (danach verwaist).
    private static let runningTimeoutMillis: Int64 = 60_000
    /// Endzeit eines Durchlaufs: begrenzt Kurse, Lease und Kursalarme, damit gespeichert und gemeldet ist,
    /// bevor WidgetKit die Erweiterung beendet; Alarme mit Zusatzdaten stellt sie zurück (`deferredAlarms`).
    /// Knopf (App-Intent); aus dem Zeichnen kürzer (`WidgetCadence.drawRunBudgetMillis`).
    static let runBudgetMillis: Int64 = 25_000

    /// Ergebnis von `run()`.
    enum RunResult {
        /// Es lief schon eine Aktualisierung (die zeichnet selbst neu, sobald sie gespeichert hat).
        case busy
        /// Mindestens ein Kurs geholt und gespeichert.
        case saved
        /// Kein Kurs oder nicht gespeichert — vermerkt, siehe `retryAfterMillis`.
        case failed
    }

    static var isStale: Bool {
        TimeUtils.nowMillis - SharedStorage.lastRefreshAt >= staleAfterMillis
    }

    /// Knopf gesperrt bis (Wanduhr, ms; 0 = nie) — nach einem Tipp in der 15-s-Sperre (`WidgetRefreshState`).
    /// Gilt für alle Merkliste-Widgets, wie die Sperre selbst.
    static var blockedUntil: Int64 {
        get { Int64(SharedStorage.defaults.double(forKey: blockedUntilKey)) }
        set { SharedStorage.defaults.set(Double(newValue), forKey: blockedUntilKey) }
    }
    private static let blockedUntilKey = "widget_refresh_blocked_until"

    /// Läuft gerade eine Aktualisierung aus den Widgets (in diesem Prozess oder, innerhalb der Frist, anderswo)?
    static var isRunning: Bool {
        let inProcess = runningInProcess.withLock { $0 }
        return inProcess || runningSince > 0
    }

    /// Beginn der laufenden Aktualisierung aus den Widgets (ms, innerhalb der Frist); 0 = keine. Das
    /// Merkliste-Widget unterscheidet so eine neu begonnene von der eben fertigen (`WidgetRefreshState.blockedUntil`).
    static var runningSince: Int64 {
        runningInProcess.withLock { _ in
            let running = Int64(SharedStorage.defaults.double(forKey: runningKey))
            let age = TimeUtils.nowMillis - running
            return running > 0 && age >= 0 && age < runningTimeoutMillis ? running : 0
        }
    }

    /// Schützt Prüfen und Setzen von `runningKey` im selben Prozess: zwei gleichzeitige Timeline-Anfragen sahen sonst
    /// beide «frei» und aktualisierten doppelt. Prozessübergreifend bleibt der Zeitstempel in den App-Group-Defaults.
    /// Zustand der Sperre: Läuft in diesem Prozess gerade eine Aktualisierung?
    private static let runningInProcess = OSAllocatedUnfairLock(initialState: false)

    /// Aktualisierung übernehmen (atomar im Prozess); false = läuft schon (hier oder, innerhalb der Frist, anderswo).
    private static func claimRun(started: Int64) -> Bool {
        runningInProcess.withLock { inProcess in
            guard !inProcess else { return false }
            let defaults = SharedStorage.defaults
            let running = Int64(defaults.double(forKey: runningKey))
            if running > 0, started - running >= 0, started - running < runningTimeoutMillis { return false }
            defaults.set(Double(started), forKey: runningKey)
            inProcess = true
            return true
        }
    }

    /// Nur der Besitzer gibt frei: Den Zeitstempel löschen, solange es noch der eigene ist (hat nach Ablauf der Frist
    /// ein anderer Prozess übernommen, bleibt dessen Merker stehen).
    private static func releaseRun(started: Int64) {
        runningInProcess.withLock { inProcess in
            inProcess = false
            let defaults = SharedStorage.defaults
            if Int64(defaults.double(forKey: runningKey)) == started { defaults.removeObject(forKey: runningKey) }
        }
    }

    /// Ist der letzte misslungene Versuch jünger als `retryAfterMillis`? (Uhr zurückgestellt: nein.)
    static var recentlyFailed: Bool {
        let failedAt = Int64(SharedStorage.defaults.double(forKey: failedAtKey))
        let age = TimeUtils.nowMillis - failedAt
        return failedAt > 0 && age >= 0 && age < retryAfterMillis
    }

    /// Volle Aktualisierung. Der Knopf «Aktualisieren» ruft sie direkt auf, auch kurz nach einem
    /// misslungenen Versuch (`retryAfterMillis` gilt nur für `refreshIfStale`).
    /// - Parameter budgetMillis: Endzeit ab Beginn (Kurse, Lease, Kursalarme); aus dem Zeichnen kürzer.
    /// - Parameter snapshot: eben gelesene Merkliste (aus dem Zeichnen, `refreshIfStale`); nil = hier lesen.
    @discardableResult
    static func run(budgetMillis: Int64 = runBudgetMillis, snapshot read: SharedStorage.Snapshot? = nil) async -> RunResult {
        let defaults = SharedStorage.defaults
        let started = TimeUtils.nowMillis
        guard claimRun(started: started) else { return .busy }
        defer { releaseRun(started: started) }

        let settings = SharedStorage.loadSettings()
        let snapshot = read ?? SharedStorage.loadSnapshot()
        // Speichergrenze der Erweiterung (~30 MB): keine grossen ungefilterten Sammellisten (`LowMemoryBulk`).
        // Mit Endzeit prüft `refresh` nur die Kursalarme; die mit Zusatzdaten (`deferredAlarms`) bleiben liegen.
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings,
                                                   deadline: RefreshDeadline(endMillis: started + budgetMillis),
                                                   lowMemory: true)

        // Frisch lesen und nur Kurse und Alarm-Zustand je Id übertragen — als ein abgestimmter Schritt mit der
        // App (sonst ginge ein dort gerade hinzugefügtes Paar verloren). Unlesbare oder nicht zugreifbare
        // Datei: nichts schreiben (`SharedStorage.updateSnapshot`) und nichts melden — die Alarme bleiben scharf.
        guard let saved = SharedStorage.updateSnapshot({ outcome.apply(to: &$0) }) else {
            outcome.releaseAlarmLease()
            // Speichern gescheitert (mit Paaren) — nur gezählt (Diagnosebericht)
            if outcome.checked > 0 { BackgroundErrorCounter.record(BackgroundErrors.storage, kind: BackgroundErrors.saveFailedKind) }
            defaults.set(Double(TimeUtils.nowMillis), forKey: failedAtKey)
            CheckRunStore.log(.widget, .failed, startedAt: started)
            return .failed
        }
        CheckRunStore.log(.widget, CheckRunLog.result(checked: outcome.checked, failed: outcome.failed), startedAt: started)
        let gotPrices = outcome.failed < outcome.checked
        if gotPrices {
            // Nur Kursalarme: `lastAlarmCheckAt` bleibt, sonst übersprünge die Hintergrund-Aufgabe die Alarme
            // mit Zusatzdaten
            SharedStorage.markFullRefresh()
            SharedStorage.lastRefreshDuration = outcome.durationMillis
        }
        if let report = outcome.report { SharedStorage.lastRefreshReport = report }
        // Mit dieser %-Basis gerechnet (Pille, Widgets und Live-Aktivität prüfen das)
        if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
        // Erst jetzt Kursalarme melden, je bestätigter Mitteilung speichern und die Lease freigeben — nach der
        // Uhrzeit, damit sie zur eben geschriebenen Datei passt (`WidgetDataFreshness`), auch wenn das Melden dauert
        // Paar seit der Abfrage geändert (Bearbeiten, Wiederherstellung mit gleicher Id): nicht melden
        _ = await outcome.keepingCurrentPairs(saved.watches)
            .deliverAlarms(settings: settings, current: saved.alarms,
                           save: { PriceRefresher.saveAlarmOnDisk($0, $1) },
                           recordLog: { AlarmLogStore.record(AlarmLog.withSource($0, .widget), live: $1) })
        // Ohne einen einzigen Kurs (offline, alle Börsen mit Fehler oder pausiert) bleiben die Daten
        // alt: vermerken, damit das Zeichnen nicht gleich wieder einen Versuch startet
        if gotPrices {
            defaults.removeObject(forKey: failedAtKey)
            return .saved
        }
        defaults.set(Double(TimeUtils.nowMillis), forKey: failedAtKey)
        return .failed
    }

    /// Wartezeit beim Zeichnen: danach zeichnet das Widget sofort mit den gespeicherten Kursen. Kurz gehalten, weil
    /// WidgetKit eine Timeline, die zu lange braucht, verwirft (dann bliebe das Widget leer oder auf dem alten Stand
    /// stehen). Der Durchlauf endet selbst vorher (`WidgetCadence.drawRunBudgetMillis`); nur wenn Speichern
    /// oder Melden unerwartet hängt, läuft er nach der Rückgabe noch weiter und lädt bei Erfolg nach.
    static let drawDeadlineSeconds: Double = 8

    /// Aktualisiert, wenn die Daten alt sind — wartet aber höchstens `deadline` Sekunden.
    /// Kein neuer Versuch innerhalb von `retryAfterMillis` nach einem misslungenen.
    /// Die anderen Widget-Arten werden nur neu gezeichnet, wenn wirklich Kurse gespeichert wurden:
    /// rechtzeitig → `otherKinds`; erst später fertig → `otherKinds` und die eigene Art (`ownKind`), die schon
    /// mit den alten Kursen gezeichnet hat. Keine Schleife: nach dem Speichern sind die Daten nicht mehr alt,
    /// das erneute Zeichnen holt also nichts. Andere Arten (Portfolio, Puls) zeigen diese Kurse nicht.
    /// Die Merkliste wird dabei höchstens einmal gelesen (`snapshot`: schon gelesen vom Aufrufer) und an `run`
    /// weitergegeben. Ergebnis: true = eine Aktualisierung wurde gestartet (die Kurse können sich geändert haben).
    @discardableResult
    static func refreshIfStale(deadline: Double = drawDeadlineSeconds, ownKind: String, otherKinds: [String],
                               snapshot known: SharedStorage.Snapshot? = nil) async -> Bool {
        guard isStale, !recentlyFailed else { return false }
        let snapshot = known ?? SharedStorage.loadSnapshot()
        guard !snapshot.watches.isEmpty else { return false }
        let gate = WidgetDeadlineGate()
        let savedInTime: Bool = await withCheckedContinuation { continuation in
            gate.set(continuation)
            Task {
                let budget = WidgetCadence.drawRunBudgetMillis(deadlineSeconds: deadline)
                let saved = (await run(budgetMillis: budget, snapshot: snapshot)) == .saved
                if !gate.finish(saved), saved {
                    WidgetData.reloadKinds([ownKind] + otherKinds, reason: "spät gespeichert")
                }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
                _ = gate.finish(false)
            }
        }
        if savedInTime {
            WidgetData.reloadKinds(otherKinds, reason: "gespeichert")
        } else {
            AppLog.widget.info("Zeichnen mit gespeicherten Kursen (Netz langsamer als \(Int(deadline), privacy: .public) s)")
        }
        return true
    }
}

/// Lässt eine Fortsetzung genau einmal weiterlaufen (Ergebnis oder Zeitgrenze).
private final class WidgetDeadlineGate: Sendable {
    private struct State: Sendable {
        var continuation: CheckedContinuation<Bool, Never>?
        var done = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    func set(_ c: CheckedContinuation<Bool, Never>) {
        state.withLock { $0.continuation = c }
    }

    /// true, wenn dieser Aufruf die Fortsetzung ausgelöst hat.
    func finish(_ value: Bool) -> Bool {
        state.withLock { s in
            guard !s.done, let c = s.continuation else { return false }
            s.done = true
            s.continuation = nil
            c.resume(returning: value)
            return true
        }
    }
}
