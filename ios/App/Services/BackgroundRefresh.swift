import BackgroundTasks
import Foundation
import os
import WidgetKit

/// Hintergrund-Aktualisierung über `BGTaskScheduler` — Ersatz für WorkManager.
///
/// iOS entscheidet selbst, wann die Aufgabe läuft: frühestens nach dem
/// eingestellten Intervall, oft später (je nach Nutzung, Akku und Netz).
enum BackgroundRefresh {
    static let refreshId = "com.cryptochecker.app.refresh"
    /// Eigene Aufgabe für Fear & Greed bis Build 46. iOS erlaubt nur EINE vorgemerkte Aktualisierung je App
    /// (`tooManyPendingTaskRequests`) — die Prüfung läuft darum jetzt als Schritt in `refreshId`. Bleibt in
    /// `BGTaskSchedulerPermittedIdentifiers` und registriert, damit eine alte Anfrage abgesagt (und, falls sie
    /// vorher noch startet, sauber beendet) werden kann.
    static let legacyZoneId = "com.cryptochecker.app.zone"

    /// Muss vor dem Ende von `application(_:didFinishLaunchingWithOptions:)` aufgerufen werden.
    static func register() {
        // Unerwarteter Aufgabentyp: trotzdem abschliessen (sonst beendet iOS die App und gibt künftig
        // weniger Hintergrundzeit) und neu vormerken — diese Anfrage ist verbraucht.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshId, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                scheduleRefresh(settings: SharedStorage.loadSettings(), replace: true, fromNow: true)
                return
            }
            handleRefresh(refresh)
        }
        // Übrig gebliebene Anfrage eines alten Builds: nichts tun, nur abschliessen
        BGTaskScheduler.shared.register(forTaskWithIdentifier: legacyZoneId, using: nil) { task in
            task.setTaskCompleted(success: true)
        }
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: legacyZoneId)
    }

    static func schedule(settings: AppSettings) {
        scheduleRefresh(settings: settings)
    }

    /// Uhr oder Zeitzone geändert (`significantTimeChange`, `NSSystemClockDidChange`): vorgemerkte Anfrage prüfen.
    static func clockChanged() {
        scheduleRefresh(settings: SharedStorage.loadSettings())
    }

    /// Plant die Kurs-Aktualisierung im Hintergrund — wie Android (`ExistingPeriodicWorkPolicy.KEEP`):
    /// Ist schon eine vorgemerkt, bleibt sie stehen. Vorher wurde sie bei jedem Start und jedem Wechsel in
    /// den Hintergrund abgesagt und «Intervall ab jetzt» neu gelegt; wer die App oft öffnete, schob den
    /// Alarm bei geschlossener App so immer weiter hinaus. Ist Fear & Greed früher fällig, beginnt sie
    /// dann (`RefreshSchedule.nextBegin`).
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
        func submit() {
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
        if replace {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshId)
            submit()
            return
        }
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
            if let pending = requests.first(where: { $0.identifier == refreshId }) {
                // Uhr verstellt: iOS hält den alten absoluten Beginn fest (Stunden weg) → ersetzen; sonst bleibt sie
                let begin = pending.earliestBeginDate.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
                guard RefreshSchedule.isImplausiblyFar(earliestBeginMillis: begin, intervalMinutes: minutes,
                                                       now: TimeUtils.nowMillis) else { return }
                AppLog.background.notice("BG-Anfrage unplausibel fern (Uhr verstellt?) – neu vorgemerkt")
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshId)
            }
            submit()
        }
    }

    /// Vormerken gescheitert: in «Letzte Prüfungen» als fehlgeschlagener Lauf ohne Dauer — nur einmal, solange
    /// der neueste Eintrag schon so ein Fehler ist (sonst füllte jeder Wechsel in den Hintergrund die Liste).
    private static func logSubmitError(_ error: Error) {
        guard BackgroundSchedule.shouldLogSubmitError(newest: CheckRunStore.load().first) else { return }
        let now = TimeUtils.nowMillis
        CheckRunStore.log(.bgTask, .failed, startedAt: now)
    }

    // MARK: Fear & Greed (wie `ZoneCheckWorker`, zweimal täglich)

    private static let lastZoneCheckKey = "zone_check_last_run"

    /// Zuletzt erfolgreich geprüft (ms); 0 = noch nie. Für den Plan und das Nachholen beim Öffnen.
    static var lastZoneCheckAt: Int64 {
        get { Int64(SharedStorage.defaults.double(forKey: lastZoneCheckKey)) }
        set { SharedStorage.defaults.set(Double(newValue), forKey: lastZoneCheckKey) }
    }

    static func zoneWanted(_ settings: AppSettings) -> Bool {
        settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0 || !IndicatorAlertRules.active(settings.indicatorAlerts).isEmpty
    }

    /// Grenzen von Fear & Greed bzw. Kennzahlen geändert: die eine Hintergrund-Aufgabe neu planen, damit ihr
    /// Beginn die Prüfung berücksichtigt (oder nicht mehr). Eine eigene Aufgabe gibt es nicht mehr (`legacyZoneId`).
    static func scheduleZone(settings: AppSettings) {
        scheduleRefresh(settings: settings, replace: true)
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

    /// Fear & Greed und Kennzahlen als Schritt der Hintergrund-Aufgabe (nur aufrufen, wenn fällig) — höchstens
    /// bis zur Endzeit. Nicht geschafft (keine Zeit, abgebrochen, fehlgeschlagen): die nächste Runde neu planen,
    /// dann richtet sie sich nach dem alten `lastZoneCheckAt` (frühestens `ZoneSchedule.minDelayMillis`).
    @MainActor
    private static func runZoneStep(settings: AppSettings, deadline: RefreshDeadline) async {
        var done = false
        if !Task.isCancelled && RefreshBudget.shouldRun(.zone, remaining: deadline.remaining()) {
            let result = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) { @MainActor in
                await runZoneCheck(settings: settings)
            }
            // nil = Zeit um; innen nil = es lief schon eine (zählt als erledigt)
            done = result.map { $0 ?? true } ?? false
        }
        if !done { scheduleRefresh(settings: settings, replace: true, fromNow: true) }
    }

    private static func handleRefresh(_ task: BGAppRefreshTask) {
        let completion = BackgroundTaskCompletion(task)
        let startedAt = TimeUtils.nowMillis
        // Fear & Greed / Kennzahlen fällig? Läuft als Schritt in diesem Lauf (keine eigene Aufgabe mehr)
        let zoneDue = ZoneSchedule.shouldRunInRefresh(wanted: zoneWanted(SharedStorage.loadSettings()),
                                                      lastRunMillis: lastZoneCheckAt, now: startedAt)
        // Nächste Runde SOFORT vormerken — vor jeder Arbeit, damit auch ein abgelaufener oder abgebrochener
        // Lauf die Kette nicht abreisst (diese Anfrage ist verbraucht, also ersetzen, Intervall ab jetzt)
        scheduleRefresh(settings: SharedStorage.loadSettings(), replace: true, fromNow: true, zoneRunsNow: zoneDue)
        // «Letzte Prüfungen» (Build 46): Alarme eben erst geprüft (z. B. App offen) → kurz und erfolgreich enden.
        // Massgebend ist die letzte Prüfung MIT allen Alarmen — das Widget prüft nur Kursalarme (`lastAlarmCheckAt`).
        if BackgroundSchedule.shouldSkip(trigger: .bgTask, lastFinishedAt: SharedStorage.lastAlarmCheckAt, now: startedAt) {
            CheckRunStore.log(.bgTask, .skipped, startedAt: startedAt)
            AppLog.background.info("BG-Aufgabe übersprungen (Alarme eben erst geprüft)")
            guard zoneDue else {
                completion.complete(success: true)
                return
            }
            // Kurse eben erst geprüft, Fear & Greed aber fällig: nur diese Prüfung. Den `expirationHandler` ZUERST
            // setzen: iOS darf ihn sofort nach dem Start rufen; ein Handler, der erst nach dem Task gesetzt wird,
            // könnte einen frühen Ablauf verpassen (dann meldete niemand das Ende rechtzeitig).
            let zoneBox = TaskCancelBox()
            task.expirationHandler = {
                AppLog.background.notice("BG-Aufgabe abgelaufen (nur Fear & Greed)")
                zoneBox.cancel()
                scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
                completion.complete(success: false)
            }
            zoneBox.set(Task { @MainActor in
                await runZoneStep(settings: SharedStorage.loadSettings(), deadline: RefreshBudget(start: startedAt).deadline)
                completion.complete(success: !Task.isCancelled)
            })
            return
        }
        // Genau ein Eintrag je Lauf: vom Ablauf selbst oder aus dem `expirationHandler`
        let logGate = OnceGate()
        // Ergebnis der Kurse, sobald gespeichert — läuft danach die Zeit ab, zählt es trotzdem
        let priceBox = CheckResultBox()
        // Zeit abgelaufen: Arbeit abbrechen UND sofort das Ende melden — sonst beendet iOS die App.
        // Der Ablauf meldet danach nicht noch einmal (`BackgroundTaskCompletion`). Nächste Runde zur
        // Sicherheit noch einmal vormerken: eine schon vorgemerkte bleibt stehen (`scheduleRefresh` ohne replace).
        // Gesetzt VOR dem Start der Arbeit: läuft die Zeit ab, bevor der Task angelegt ist, bricht die Box ihn
        // gleich beim Anlegen ab (`TaskCancelBox.set`).
        let workBox = TaskCancelBox()
        task.expirationHandler = {
            AppLog.background.notice("BG-Aufgabe abgelaufen nach \(TimeUtils.nowMillis - startedAt, privacy: .public) ms")
            workBox.cancel()
            scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
            if logGate.claim() { CheckRunStore.log(.bgTask, priceBox.get(), startedAt: startedAt) }
            completion.complete(success: false)
        }
        AppLog.background.info("BG-Aufgabe gestartet")
        workBox.set(Task { @MainActor in
            let settings = SharedStorage.loadSettings()
            let snapshot = SharedStorage.loadSnapshot()
            // Zeitbudget: iOS gibt der Aufgabe nur rund 30 s. Eine Endzeit gilt für ALLE Schritte (Kurse,
            // Tages-Bezüge, Lease, Alarm-Daten, Nebenprüfungen). Reihenfolge nach Wichtigkeit
            // (`RefreshBudget.Step`): Kurse und Kursalarme speichern und melden, dann Portfolio (braucht nur
            // Kurse und Devisen), dann Alarme mit Zusatzdaten, Fear & Greed (wenn fällig), Aktivität, Gas — jeweils nur, solange Zeit
            // bleibt. Sonst endet alles im Ablauf, nichts ist gespeichert, und iOS gibt künftig weniger Hintergrundzeit.
            let budget = RefreshBudget(start: TimeUtils.nowMillis)
            let deadline = budget.deadline
            let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings,
                                                       networkBudgetMillis: RefreshBudget.networkMillis,
                                                       deadline: deadline)
            // Von iOS abgebrochen: abgebrochene Anfragen nicht als Fehler speichern.
            if Task.isCancelled {
                // Nicht gespeichert → auch nichts melden; die Alarme bleiben scharf.
                outcome.releaseAlarmLease()
                if logGate.claim() { CheckRunStore.log(.bgTask, .failed, startedAt: startedAt) }
                completion.complete(success: false)
                return
            }
            // 1) Kurse und Kursalarme ZUERST speichern. Frisch lesen und nur Kurse/Alarm-Zustand je Id
            // übertragen — als ein abgestimmter Schritt mit Widget und App (der Nutzer könnte inzwischen
            // etwas geändert haben). Unlesbare oder gerade nicht zugreifbare Datei: nichts schreiben.
            let saved = SharedStorage.updateSnapshot { outcome.apply(to: &$0) }
            let current = saved ?? snapshot
            // Ergebnis für «Letzte Prüfungen»: Kurse geholt, aber nicht gespeichert zählt als fehlgeschlagen
            let priceResult: CheckResult = (saved == nil && outcome.checked > 0)
                ? .failed : CheckRunLog.result(checked: outcome.checked, failed: outcome.failed)
            priceBox.set(priceResult)
            // Uhrzeit nur mit gespeichertem Stand: Ohne (unlesbar, nicht zugreifbar) hielten die Widgets die alten Kurse
            // auf der Platte sonst für frisch (`WidgetDataFreshness`)
            if saved != nil && outcome.failed < outcome.checked {
                SharedStorage.lastRefreshAt = TimeUtils.nowMillis
                SharedStorage.lastRefreshDuration = outcome.durationMillis
                // Alarme ausgewertet, gemeldet gleich danach (nur mit voller Prüfung — die meisten Paare mit Kurs,
                // wie `AppData.apply` und Android `lastFullCheck`)
                if BackgroundSchedule.isFullCheck(attempted: outcome.checked, failed: outcome.failed) {
                    SharedStorage.lastAlarmCheckAt = TimeUtils.nowMillis
                }
            }
            if let report = outcome.report { SharedStorage.lastRefreshReport = report }
            // Mit dieser %-Basis gerechnet (Änderung, Widgets und Live-Aktivität prüfen das)
            if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
            if let saved {
                // Erst jetzt Alarme melden, je bestätigter Mitteilung den Alarm speichern und die Lease freigeben —
                // nach der Uhrzeit, damit sie zur eben geschriebenen Datei passt (`WidgetDataFreshness`)
                _ = await outcome.deliverAlarms(settings: settings, current: saved.alarms,
                                                save: { PriceRefresher.saveAlarmOnDisk($0, $1) },
                                                recordLog: { AlarmLogStore.record(AlarmLog.withSource($0, .bgTask), live: $1) })
            } else {
                // Nicht gespeichert (keine Merkliste, unlesbar, nicht zugreifbar) → nichts melden; die
                // Alarme bleiben scharf. Portfolio-Widget, Portfolio- und Gas-Alarm laufen trotzdem.
                outcome.releaseAlarmLease()
            }
            AppData.shared.reloadFromDisk()
            // Widgets nur neu laden, wenn sich der gespeicherte Stand geändert hat (Kurse, Fehler, %-Basis) — sonst
            // verbraucht jeder Lauf ohne Ergebnis (offline, alles pausiert) Teile des Widget-Budgets
            if saved != nil && (outcome.checked > 0 || outcome.changeStampChanged) {
                WidgetCenter.shared.reloadAllTimelines()
            }
            // Live-Aktivität (Sperrbildschirm) mit dem neuen Kurs
            await LiveActivityController.update(watches: current.watches)

            // 2) Portfolio-Widget: eigener Stand, damit es nicht vom Öffnen der App abhängt — gleich nach den
            // Kursalarmen (braucht nur Kurse und Devisen), vor den Alarmen mit Zusatzdaten (nur mit Restzeit,
            // höchstens bis zur Endzeit; ohne Ergebnis keine Portfolio-Alarme diesmal)
            if !Task.isCancelled && RefreshBudget.shouldRun(.portfolio, remaining: deadline.remaining()) {
                // Warten Alarme mit Zusatzdaten, bleibt ihnen wenn möglich ihre Mindestzeit
                let allowance = RefreshBudget.portfolioAllowance(
                    remaining: deadline.remaining(),
                    extraAlarmsWaiting: saved != nil && !outcome.deferredAlarms.isEmpty)
                // Fällige Adress-Guthaben zuerst (10 min Zwischenspeicher; ohne Adressen keine Anfrage) — mit eigener
                // Grenze (höchstens 8 s, je Anfrage 5 s), die dem Portfolio-Stand seine Mindestzeit lässt; reicht
                // die Zeit nicht, bleiben die Adressen bis zum nächsten Lauf fällig (Kurse und Alarme gehen vor)
                let portfolioEnd = TimeUtils.nowMillis + allowance
                let addressMillis = min(WatchedAddressSource.backgroundTotalMillis,
                                        allowance - RefreshBudget.portfolioMinMillis)
                if addressMillis >= 1_000 {
                    _ = await RefreshDeadline.run(within: addressMillis) { @MainActor in
                        await PortfolioModel.shared.refreshWatchedAddresses(force: false, budgetMillis: addressMillis - 500)
                    }
                }
                let widget = await RefreshDeadline.run(within: max(0, portfolioEnd - TimeUtils.nowMillis)) { @MainActor in
                    await PortfolioWidgetStore.refresh()
                }
                if let widget, !Task.isCancelled {
                    // Portfolio-Alarme mit dem neuen Stand prüfen (nur mit eingeschaltetem Portfolio). Devisenkurse
                    // nur mit Restzeit und höchstens `portfolioFxMaxMillis` (je Währung bis zu zwei Quellen mit
                    // langen Timeouts); was nicht kommt, nimmt die Prüfung aus dem Zwischenspeicher (`cached`)
                    let fxMillis = RefreshBudget.portfolioFxAllowance(portfolioEndMillis: portfolioEnd,
                                                                      deadline: deadline)
                    if fxMillis > 0 {
                        _ = await RefreshDeadline.run(within: fxMillis) { @MainActor in
                            await AppData.shared.prefetchPortfolioAlarmRates(widget)
                        }
                    }
                    if !Task.isCancelled { await AppData.shared.evaluatePortfolioAlarms(widget) }
                }
            }
            // 3) Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) — nur mit Restzeit;
            // was nicht rechtzeitig kommt, bleibt diese Runde ungeprüft (Alarm bleibt scharf).
            if saved != nil && !outcome.deferredAlarms.isEmpty && !Task.isCancelled
                && RefreshBudget.shouldRun(.extraAlarms, remaining: deadline.remaining()) {
                let extra = await PriceRefresher.checkDeferredAlarms(outcome.deferredAlarms, settings: settings,
                                                                     deadline: deadline)
                if Task.isCancelled {
                    extra.releaseAlarmLease()
                } else if extra.alarms.isEmpty && extra.pendingAlarms.isEmpty {
                    extra.releaseAlarmLease()
                    // Nichts zu speichern, aber geprüft: fürs Alarm-Protokoll festhalten — abseits des Hauptfadens
                    // (verschlüsselte, abgestimmte Datei), abgewartet, damit es vor dem Ende der Aufgabe geschrieben ist
                    let log = AlarmLog.withSource(extra.alarmLog, .bgTask)
                    await Task.detached(priority: .utility) { AlarmLogStore.record(log, live: false) }.value
                } else if let stored = SharedStorage.updateSnapshot({ extra.apply(to: &$0) }) {
                    _ = await extra.deliverAlarms(settings: settings, current: stored.alarms,
                                                  save: { PriceRefresher.saveAlarmOnDisk($0, $1) },
                                                  recordLog: { AlarmLogStore.record(AlarmLog.withSource($0, .bgTask), live: $1) })
                    AppData.shared.reloadFromDisk()
                } else {
                    extra.releaseAlarmLease()
                }
            }
            // USDT/USD messen und Depeg-Alarm prüfen (ein Alarm, darum vor Aktivität und Gas): nur mit Alarm oder
            // Portfolio (Live-Kurs USDT/USD der Bewertung), höchstens alle 5 Minuten, höchstens bis zur Endzeit —
            // gleiche Restzeit-Regel wie Gas
            let hasPortfolio = await MainActor.run { AppData.shared.hasPortfolioData }
            if DepegAlarm.shouldMeasure(depegAlarm: settings.depegAlarm, portfolioEnabled: settings.portfolioEnabled,
                                        hasPortfolioData: hasPortfolio)
                && !Task.isCancelled && RefreshBudget.shouldRun(.gas, remaining: deadline.remaining()) {
                _ = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) {
                    await DepegAlarmCheck.runIfDue(settings: settings, hasPortfolioData: hasPortfolio)
                }
            }
            // Fear & Greed und Kennzahlen (bis Build 46 eigene Aufgabe): nur wenn fällig, höchstens bis zur Endzeit
            if zoneDue && !Task.isCancelled {
                await runZoneStep(settings: settings, deadline: deadline)
            }
            // 4) Ungewöhnliche Aktivität: erst nach gespeicherten Kursen und neu
            // gezeichneten Widgets, abgekoppelt vom Speichern (eigener Task, höchstens bis zur Endzeit).
            // Hier wird darauf gewartet, sonst friert iOS die App mitten in der
            // Auswertung ein. Bricht iOS ab oder ist die Zeit um, endet auch die Auswertung.
            // Im Hintergrund nur, wenn die Mitteilung eingeschaltet ist (sonst braucht es niemand,
            // bis die App wieder offen ist — dann holt die nächste Aktualisierung es nach).
            if ActivityAnalysisGate.shouldRun(alertsEnabled: settings.activityAlerts, appVisible: false)
                && !Task.isCancelled && RefreshBudget.shouldRun(.activity, remaining: deadline.remaining()) {
                let watches = current.watches
                _ = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) {
                    await ActivityMonitor.analyzeAll(watches: watches, settings: settings)
                }
                AppData.shared.reloadActivity()
            }
            // 5) Gas-Alarm (#167): höchstens alle 10 Minuten, nur wenn eingestellt — höchstens bis zur Endzeit
            if !Task.isCancelled && RefreshBudget.shouldRun(.gas, remaining: deadline.remaining()) {
                _ = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) {
                    await GasAlertCheck.runIfDue(settings: settings)
                }
            }
            // Alles Geschriebene auf die Platte, bevor iOS die App wieder anhält
            SharedStorage.flushSnapshotWrites()
            if logGate.claim() { CheckRunStore.log(.bgTask, priceResult, startedAt: startedAt) }
            AppLog.background.info("BG-Aufgabe beendet nach \(TimeUtils.nowMillis - startedAt, privacy: .public) ms, geprüft \(outcome.checked, privacy: .public), fehlgeschlagen \(outcome.failed, privacy: .public)")
            completion.complete(success: !Task.isCancelled)
        })
    }
}
