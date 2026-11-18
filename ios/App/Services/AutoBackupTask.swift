import BackgroundTasks
import Foundation

/// Automatische Sicherung ohne geöffnete App: eigene Hintergrund-Aufgabe (`BGProcessingTask`, neben der einen
/// Kurs-Aktualisierung), vorgemerkt auf den Zeitpunkt, an dem die Sicherung fällig wird (`earliestBegin`). Wann sie
/// wirklich läuft, entscheidet iOS (oft nachts am Ladegerät) — gesichert wird nur, wenn fällig (`AutoBackupPolicy.isDue`).
/// Wie Android `AutoBackupWorker`.
enum AutoBackupTask {
    static let taskId = "com.cryptochecker.app.autobackup"

    /// Muss vor dem Ende von `application(_:didFinishLaunchingWithOptions:)` aufgerufen werden.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            let completion = BackgroundTaskCompletion(task)
            let work = TaskCancelBox()
            task.onExpiration {
                work.cancel()
                completion.complete(success: false)
            }
            work.set(Task { @MainActor in
                // Stand von der Platte abwarten: Eine Sicherung ohne ihn wäre leer und ersetzte gute Dateien
                let data = AppData.shared
                await data.waitForSnapshot()
                await AutoBackup.shared.runIfDue(data)?.value
                // Ohne Lauf (nicht fällig, ausgeschaltet) ebenso: nächsten Termin vormerken
                schedule(AutoBackup.shared.state)
                completion.complete(success: true)
            })
        }
    }

    /// Eingeschaltet: auf den nächsten fälligen Zeitpunkt vormerken (ersetzt eine frühere Vormerkung); aus: absagen.
    @MainActor
    static func schedule(_ state: AutoBackup.State) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskId)
        guard state.enabled else { return }
        let request = BGProcessingTaskRequest(identifier: taskId)
        let begin = earliestBegin(lastSuccessAt: state.lastSuccessAt, lastAttemptAt: state.lastAttemptAt,
                                  now: EpochTime.nowMillis)
        request.earliestBeginDate = Date(timeIntervalSince1970: Double(begin) / 1000)
        request.requiresNetworkConnectivity = false
        request.requiresExternalPower = false
        AppLog.attempt("Automatische Sicherung vormerken") { try BGTaskScheduler.shared.submit(request) }
    }

    /// Frühester Zeitpunkt, an dem `AutoBackupPolicy.isDue` wieder gilt (Zeiten in der Zukunft — Uhr zurückgestellt —
    /// zählen wie dort als fällig).
    static func earliestBegin(lastSuccessAt: Int64, lastAttemptAt: Int64, now: Int64) -> Int64 {
        var begin = now
        if lastSuccessAt > 0, lastSuccessAt <= now { begin = max(begin, lastSuccessAt + AutoBackupPolicy.intervalMillis) }
        if lastAttemptAt > 0, lastAttemptAt <= now { begin = max(begin, lastAttemptAt + AutoBackupPolicy.retryAfterMillis) }
        return begin
    }
}
