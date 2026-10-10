import BackgroundTasks
import Foundation
import WidgetKit

/// Hintergrund-Aktualisierung über `BGTaskScheduler` — Ersatz für WorkManager.
///
/// iOS entscheidet selbst, wann die Aufgabe läuft: frühestens nach dem
/// eingestellten Intervall, oft später (je nach Nutzung, Akku und Netz).
enum BackgroundRefresh {
    static let refreshId = "com.cryptochecker.app.refresh"
    static let zoneId = "com.cryptochecker.app.zone"

    /// Muss vor dem Ende von `application(_:didFinishLaunchingWithOptions:)` aufgerufen werden.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            handleRefresh(task)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: zoneId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            handleZone(task)
        }
    }

    static func schedule(settings: AppSettings) {
        scheduleRefresh(settings: settings)
        scheduleZone(settings: settings)
    }

    static func scheduleRefresh(settings: AppSettings) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshId)
        if settings.backgroundUpdates {
            let request = BGAppRefreshTaskRequest(identifier: refreshId)
            let minutes = max(settings.backgroundIntervalMinutes, AppSettings.minBackgroundIntervalMinutes)
            request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(minutes * 60))
            try? BGTaskScheduler.shared.submit(request)
        }
    }

    // MARK: Marktphase & Fear & Greed (wie `ZoneCheckWorker`, zweimal täglich)

    private static let lastZoneCheckKey = "zone_check_last_run"

    /// Zuletzt erfolgreich geprüft (ms); 0 = noch nie. Für den Plan und das Nachholen beim Öffnen.
    static var lastZoneCheckAt: Int64 {
        get { Int64(SharedStorage.defaults.double(forKey: lastZoneCheckKey)) }
        set { SharedStorage.defaults.set(Double(newValue), forKey: lastZoneCheckKey) }
    }

    static func zoneWanted(_ settings: AppSettings) -> Bool {
        settings.zoneAlerts || settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0
    }

    /// Plant die Prüfung nur, wenn noch keine vorgemerkt ist — wie Android
    /// (`ExistingPeriodicWorkPolicy.KEEP`). Vorher wurde sie bei jedem Wechsel in den Hintergrund
    /// abgesagt und frühestens 12 h später neu geplant und lief so praktisch nie.
    static func scheduleZone(settings: AppSettings) {
        guard zoneWanted(settings) else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: zoneId)
            return
        }
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
            guard !requests.contains(where: { $0.identifier == zoneId }),
                  // Inzwischen ausgeschaltet (Antwort kommt verzögert)? Dann nicht mehr planen.
                  zoneWanted(SharedStorage.loadSettings()) else { return }
            let request = BGAppRefreshTaskRequest(identifier: zoneId)
            request.earliestBeginDate = ZoneSchedule.earliestBegin(lastRunMillis: lastZoneCheckAt, now: TimeUtils.nowMillis)
            try? BGTaskScheduler.shared.submit(request)
        }
    }

    /// Beim Öffnen der App: Prüfung nachholen, wenn die letzte 12 h oder länger her ist (oder noch nie
    /// lief) — iOS startet die Hintergrund-Aufgabe oft spät oder gar nicht.
    @MainActor
    static func runZoneCheckIfDue(settings: AppSettings) {
        guard zoneWanted(settings),
              ZoneSchedule.isDue(lastRunMillis: lastZoneCheckAt, now: TimeUtils.nowMillis),
              !zoneRunning else { return }
        Task { @MainActor in _ = await runZoneCheck(settings: settings) }
    }

    /// Läuft gerade eine Prüfung (Hintergrund-Aufgabe oder Nachholen)? Nur eine zur Zeit, sonst
    /// könnte ein Wechsel doppelt gemeldet werden.
    @MainActor private static var zoneRunning = false

    /// - Returns: nil = es lief schon eine; sonst das Ergebnis von `ZoneCheck.run`.
    @MainActor
    private static func runZoneCheck(settings: AppSettings) async -> Bool? {
        guard !zoneRunning else { return nil }
        zoneRunning = true
        defer { zoneRunning = false }
        let ok = await ZoneCheck.run(settings: settings)
        // Nur ein gelungener Lauf zählt (wie `Result.retry()`): sonst holt das nächste Öffnen ihn nach
        if ok { lastZoneCheckAt = TimeUtils.nowMillis }
        return ok
    }

    private static func handleRefresh(_ task: BGAppRefreshTask) {
        let completion = BackgroundTaskCompletion(task)
        let work = Task { @MainActor in
            let settings = SharedStorage.loadSettings()
            scheduleRefresh(settings: settings) // nächste Runde gleich vormerken
            let snapshot = SharedStorage.loadSnapshot()
            let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings)
            // Von iOS abgebrochen: abgebrochene Anfragen nicht als Fehler speichern.
            if Task.isCancelled {
                // Nicht gespeichert → auch nichts melden; die Alarme bleiben scharf.
                outcome.releaseAlarmLease()
                completion.complete(success: false)
                return
            }
            // Frisch lesen und nur Kurse/Alarm-Zustand je Id übertragen — als ein abgestimmter Schritt
            // mit Widget und App (der Nutzer könnte inzwischen etwas geändert haben). Unlesbare oder
            // gerade nicht zugreifbare Datei: nichts schreiben (`SharedStorage.updateSnapshot`).
            let saved = SharedStorage.updateSnapshot { outcome.apply(to: &$0) }
            if saved != nil {
                // Erst jetzt melden (Zustand ist gespeichert) und die Alarm-Lease freigeben
                outcome.deliverAlarms()
            } else {
                // Nicht gespeichert (keine Merkliste, unlesbar, nicht zugreifbar) → nichts melden; die
                // Alarme bleiben scharf. Portfolio-Widget, Portfolio- und Gas-Alarm laufen trotzdem.
                outcome.releaseAlarmLease()
            }
            let current = saved ?? snapshot
            if outcome.failed < outcome.checked {
                SharedStorage.lastRefreshAt = TimeUtils.nowMillis
                SharedStorage.lastRefreshDuration = outcome.durationMillis
            }
            if let report = outcome.report { SharedStorage.lastRefreshReport = report }
            // Mit dieser %-Basis gerechnet (Pille, Widgets und Live-Aktivität prüfen das)
            if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
            AppData.shared.reloadFromDisk()
            WidgetCenter.shared.reloadAllTimelines()
            // Live-Aktivität (Sperrbildschirm) mit dem neuen Kurs
            await LiveActivityController.update(watches: current.watches)
            // Portfolio-Widget: eigener Stand, damit es nicht vom Öffnen der App abhängt
            if !Task.isCancelled {
                let widget = await PortfolioWidgetStore.refresh()
                // Portfolio-Alarme mit dem neuen Stand prüfen (nur mit eingeschaltetem Portfolio)
                AppData.shared.evaluatePortfolioAlarms(widget)
            }
            // Ungewöhnliche Aktivität: erst nach gespeicherten Kursen und neu
            // gezeichneten Widgets, abgekoppelt vom Speichern (eigener Task, max. 20 s).
            // Hier wird darauf gewartet, sonst friert iOS die App mitten in der
            // Auswertung ein. Bricht iOS ab, endet auch die Auswertung.
            // Im Hintergrund nur, wenn die Mitteilung eingeschaltet ist (sonst braucht es niemand,
            // bis die App wieder offen ist — dann holt die nächste Aktualisierung es nach).
            if ActivityAnalysisGate.shouldRun(alertsEnabled: settings.activityAlerts, appVisible: false) {
                let watches = current.watches
                let analysis = Task.detached(priority: .utility) {
                    await ActivityMonitor.analyzeAll(watches: watches, settings: settings)
                }
                await withTaskCancellationHandler {
                    _ = await analysis.value
                } onCancel: {
                    analysis.cancel()
                }
                AppData.shared.reloadActivity()
            }
            // Gas-Alarm (#167): höchstens alle 10 Minuten, nur wenn eingestellt
            if !Task.isCancelled { await GasAlertCheck.runIfDue(settings: settings) }
            completion.complete(success: true)
        }
        // Zeit abgelaufen: Arbeit abbrechen UND sofort das Ende melden — sonst beendet iOS die App.
        // Der Ablauf meldet danach nicht noch einmal (`BackgroundTaskCompletion`).
        task.expirationHandler = {
            work.cancel()
            completion.complete(success: false)
        }
    }

    private static func handleZone(_ task: BGAppRefreshTask) {
        let completion = BackgroundTaskCompletion(task)
        let work = Task { @MainActor in
            let settings = SharedStorage.loadSettings()
            guard zoneWanted(settings) else {
                completion.complete(success: true)
                return
            }
            let ok = await runZoneCheck(settings: settings)
            // Erst nach dem Lauf planen: Die nächste Prüfung richtet sich nach `lastZoneCheckAt`
            // (diese Aufgabe ist nicht mehr vorgemerkt, also wird neu geplant).
            scheduleZone(settings: settings)
            completion.complete(success: ok ?? true)
        }
        task.expirationHandler = {
            work.cancel()
            // Nächste Prüfung trotzdem vormerken (der Ablauf kommt vielleicht nicht mehr dazu)
            scheduleZone(settings: SharedStorage.loadSettings())
            completion.complete(success: false)
        }
    }
}

/// Wann die Prüfung von Marktphase und Fear & Greed fällig ist (zweimal täglich, wie Android).
enum ZoneSchedule {
    static let intervalMillis: Int64 = 12 * 3_600_000
    /// Frühestens so lange nach jetzt planen (kein Dauerlauf, wenn die Prüfung fehlschlägt).
    static let minDelayMillis: Int64 = 15 * 60_000

    /// Fällig: noch nie gelaufen, vor mindestens 12 h, oder der letzte Lauf liegt in der Zukunft
    /// (Uhr zurückgestellt).
    static func isDue(lastRunMillis: Int64, now: Int64) -> Bool {
        lastRunMillis <= 0 || lastRunMillis > now || now - lastRunMillis >= intervalMillis
    }

    /// Frühester Beginn der nächsten Hintergrund-Prüfung: 12 h nach dem letzten Lauf (noch nie bzw.
    /// Uhr zurückgestellt: 12 h ab jetzt), aber mindestens `minDelayMillis` ab jetzt.
    static func earliestBegin(lastRunMillis: Int64, now: Int64) -> Date {
        let base = (lastRunMillis > 0 && lastRunMillis <= now) ? lastRunMillis : now
        let next = max(base + intervalMillis, now + minDelayMillis)
        return Date(timeIntervalSince1970: TimeInterval(next) / 1000)
    }
}

/// Meldet das Ende einer Hintergrund-Aufgabe genau einmal — vom Ablauf selbst oder aus dem
/// `expirationHandler` (iOS ruft ihn auf einem beliebigen Thread). Thread-sicher.
final class BackgroundTaskCompletion: @unchecked Sendable {
    private let gate = OnceGate()
    private let task: BGTask

    init(_ task: BGTask) {
        self.task = task
    }

    func complete(success: Bool) {
        if gate.claim() { task.setTaskCompleted(success: success) }
    }
}

/// Lässt genau einen Aufruf durch (thread-sicher).
final class OnceGate: @unchecked Sendable {
    private let lock = NSLock()
    private var claimed = false

    /// true nur beim ersten Aufruf.
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if claimed { return false }
        claimed = true
        return true
    }
}
