import ActivityKit
import BackgroundTasks
import Foundation
import os
import WidgetKit

// Planen und Absagen der Hintergrund-Aktualisierung (Teil von `BackgroundRefresh`, eigene Datei seit Build 103). Seit
// Build 103 wie Android nur bei Bedarf (`BackgroundWorkDemand.periodicNeeded`, `PriceUpdateScheduler.syncPeriodic`).

extension BackgroundRefresh {
    static func schedule(settings: AppSettings) {
        scheduleRefresh(settings: settings)
    }

    /// Uhr oder Zeitzone geändert (`significantTimeChange`, `NSSystemClockDidChange`): vorgemerkte Anfrage prüfen.
    static func clockChanged() {
        scheduleRefresh(settings: SharedStorage.loadSettings())
    }

    /// Plant die Kurs-Aktualisierung im Hintergrund. Ist schon eine vorgemerkt, bleibt sie stehen — ähnlich Android
    /// (`ExistingPeriodicWorkPolicy.UPDATE`: der laufende Takt bleibt, nur geänderte Vorgaben gelten; ein neues Intervall
    /// ersetzt hier über `replace`). Vorher wurde sie bei jedem Start und jedem Wechsel in den Hintergrund abgesagt und
    /// «Intervall ab jetzt» neu gelegt; wer die App oft öffnete, schob den Alarm bei geschlossener App so immer weiter
    /// hinaus. Ist Fear & Greed früher fällig, beginnt sie dann (`RefreshSchedule.nextBegin`).
    /// Seit Build 103 nur bei Bedarf (`workNeeded`, wie Android `periodicNeeded`): Vorgemerkt wird sofort, der Bedarf
    /// danach abseits des Aufrufers ermittelt (Widgets, Merkliste von der Platte); ohne Bedarf wird wieder abgesagt.
    /// - Parameter replace: true = vorhandene Anfrage ersetzen (Intervall geändert, oder aus der laufenden
    ///   Aufgabe heraus, die selbst nicht mehr vorgemerkt ist).
    /// - Parameter fromNow: true = Intervall ab jetzt (aus der laufenden Aufgabe: `lastAlarmCheckAt` ist dort noch
    ///   der vorige Lauf, sonst käme die nächste Runde schon nach einer Minute).
    /// - Parameter zoneRunsNow: true = die laufende Aufgabe prüft Fear & Greed gleich selbst (zählt als Lauf jetzt).
    static func scheduleRefresh(settings: AppSettings, replace: Bool = false, fromNow: Bool = false,
                                zoneRunsNow: Bool = false) {
        // «Wie aktuell?»: Die Hintergrund-Aktualisierung läuft in jeder Wahl (auch «Sparsam»)
        let minutes = max(settings.backgroundIntervalMinutes, AppSettings.minBackgroundIntervalMinutes)
        let zoneOn = zoneWanted(settings)
        // Zuerst wie bisher vormerken (aus der laufenden Aufgabe synchron, bevor sie ihr Ende meldet und iOS die App
        // anhält), danach den Bedarf prüfen und ohne Bedarf wieder absagen — nie umgekehrt: Die Bedarfsprüfung wartet
        // auf WidgetKit und die Platte, und ein angehaltener Prozess hätte sonst gar nichts vorgemerkt.
        let cancelIfUnneeded: @Sendable () -> Void = {
            workNeeded(settings: settings) { needed in
                guard !needed else { return }
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshId)
                AppLog.background.info("Hintergrund-Aktualisierung abgesagt: nichts zu prüfen")
            }
        }
        if replace {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshId)
            submit(minutes: minutes, fromNow: fromNow, zoneOn: zoneOn, zoneRunsNow: zoneRunsNow)
            cancelIfUnneeded()
            return
        }
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
            if let pending = requests.first(where: { $0.identifier == refreshId }) {
                // Uhr verstellt: iOS hält den alten absoluten Beginn fest (Stunden weg) → ersetzen; sonst bleibt sie
                let begin = pending.earliestBeginDate.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
                guard RefreshSchedule.isImplausiblyFar(earliestBeginMillis: begin, intervalMinutes: minutes,
                                                       now: TimeUtils.nowMillis) else { return cancelIfUnneeded() }
                AppLog.background.notice("BG-Anfrage unplausibel fern (Uhr verstellt?) – neu vorgemerkt")
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshId)
            }
            submit(minutes: minutes, fromNow: fromNow, zoneOn: zoneOn, zoneRunsNow: zoneRunsNow)
            cancelIfUnneeded()
        }
    }

    private static func submit(minutes: Int, fromNow: Bool, zoneOn: Bool, zoneRunsNow: Bool) {
        let request = BGAppRefreshTaskRequest(identifier: refreshId)
        let now = TimeUtils.nowMillis
        // Basis ist die letzte Prüfung MIT allen Alarmen: das Widget prüft nur Kursalarme (keine mit
        // Zusatzdaten, setzt nur `lastRefreshAt`) und darf die volle Alarmprüfung nicht hinausschieben
        request.earliestBeginDate = RefreshSchedule.nextBegin(
            lastRunMillis: fromNow ? now : SharedStorage.lastAlarmCheckAt, intervalMinutes: minutes,
            zoneLastRunMillis: zoneOn ? (zoneRunsNow ? now : lastZoneCheckAt) : nil, now: now)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Sonst bliebe still, dass gar nichts vorgemerkt ist (z. B. Hintergrundaktualisierung aus)
            logSubmitError(error)
        }
    }

    /// Vormerken gescheitert: in «Letzte Prüfungen» als fehlgeschlagener Lauf ohne Dauer — nur einmal, solange
    /// der neueste Eintrag schon so ein Fehler ist (sonst füllte jeder Wechsel in den Hintergrund die Liste).
    private static func logSubmitError(_ error: Error) {
        guard BackgroundSchedule.shouldLogSubmitError(newest: CheckRunStore.load().first) else { return }
        let now = TimeUtils.nowMillis
        CheckRunStore.log(.bgTask, .failed, startedAt: now)
    }

    /// Braucht es die Aufgabe? Ermittelt abseits des Aufrufers: Widgets über WidgetKit (ohne Auskunft: vorhanden),
    /// Merkliste und Portfolio-Alarme von der Platte. Nur iOS (plattformspezifisch) zählen zusätzlich: Fear & Greed bzw.
    /// Kennzahlen (laufen hier in derselben Aufgabe, Android hat dafür den eigenen `ZoneCheckWorker`) und eine laufende
    /// Live-Aktivität (wird von der Aufgabe aktualisiert; Android kennt sie nicht).
    static func workNeeded(settings: AppSettings, completion: @escaping @Sendable (Bool) -> Void) {
        if zoneWanted(settings) {
            completion(true)
            return
        }
        WidgetCenter.shared.getCurrentConfigurations { result in
            let widgets: BackgroundWorkDemand.WidgetPresence
            switch result {
            case .success(let infos): widgets = BackgroundWorkDemand.WidgetPresence(kinds: infos.map(\.kind))
            case .failure: widgets = .assumeAll
            }
            DispatchQueue.global(qos: .utility).async {
                let liveActivity = Activity<PriceActivityAttributes>.activities.contains {
                    $0.activityState == .active || $0.activityState == .stale
                }
                guard !liveActivity else { return completion(true) }
                // Blockiert nur diesen Faden: `readSnapshot` wartet die Schreib-Warteschlange ab und stimmt sich ab
                let read = SharedStorage.readSnapshot()
                let alarmsKept = read.snapshot.map {
                    $0.portfolioAlarms == nil && FileManager.default.fileExists(atPath: SharedStorage.portfolioAlarmsURL.path)
                } ?? false
                let demand = BackgroundWorkDemand.demand(settings: settings, read: read, widgets: widgets,
                                                         portfolioAlarmsKept: alarmsKept)
                completion(BackgroundWorkDemand.periodicNeeded(demand))
            }
        }
    }
}
