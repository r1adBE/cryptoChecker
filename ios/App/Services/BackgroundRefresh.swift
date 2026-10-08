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

    static func scheduleZone(settings: AppSettings) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: zoneId)
        if settings.zoneAlerts || settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0 {
            // Wie Android: zweimal täglich
            let request = BGAppRefreshTaskRequest(identifier: zoneId)
            request.earliestBeginDate = Date(timeIntervalSinceNow: 12 * 3600)
            try? BGTaskScheduler.shared.submit(request)
        }
    }

    private static func handleRefresh(_ task: BGAppRefreshTask) {
        let work = Task { @MainActor in
            let settings = SharedStorage.loadSettings()
            scheduleRefresh(settings: settings) // nächste Runde gleich vormerken
            let snapshot = SharedStorage.loadSnapshot()
            let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings)
            // Von iOS abgebrochen: abgebrochene Anfragen nicht als Fehler speichern.
            if Task.isCancelled {
                // Nicht gespeichert → auch nichts melden; die Alarme bleiben scharf.
                outcome.releaseAlarmLease()
                task.setTaskCompleted(success: false)
                return
            }
            // Frisch laden: Der Nutzer könnte inzwischen etwas geändert haben.
            var current = SharedStorage.loadSnapshot()
            outcome.apply(to: &current)
            SharedStorage.saveSnapshot(current)
            // Erst jetzt melden (Zustand ist gespeichert) und die Alarm-Lease freigeben
            outcome.deliverAlarms()
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
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { work.cancel() }
    }

    private static func handleZone(_ task: BGAppRefreshTask) {
        let work = Task {
            let settings = SharedStorage.loadSettings()
            scheduleZone(settings: settings)
            let ok = await ZoneCheck.run(settings: settings)
            task.setTaskCompleted(success: ok)
        }
        task.expirationHandler = { work.cancel() }
    }
}
