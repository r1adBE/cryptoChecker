import BackgroundTasks
import Foundation
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
            guard !requests.contains(where: { $0.identifier == refreshId }) else { return }
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
            guard zoneDue else {
                completion.complete(success: true)
                return
            }
            // Kurse eben erst geprüft, Fear & Greed aber fällig: nur diese Prüfung
            let zoneWork = Task { @MainActor in
                await runZoneStep(settings: SharedStorage.loadSettings(), deadline: RefreshBudget(start: startedAt).deadline)
                completion.complete(success: !Task.isCancelled)
            }
            task.expirationHandler = {
                zoneWork.cancel()
                scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
                completion.complete(success: false)
            }
            return
        }
        // Genau ein Eintrag je Lauf: vom Ablauf selbst oder aus dem `expirationHandler`
        let logGate = OnceGate()
        // Ergebnis der Kurse, sobald gespeichert — läuft danach die Zeit ab, zählt es trotzdem
        let priceBox = CheckResultBox()
        let work = Task { @MainActor in
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
            if outcome.failed < outcome.checked {
                SharedStorage.lastRefreshAt = TimeUtils.nowMillis
                SharedStorage.lastRefreshDuration = outcome.durationMillis
                // Alarme ausgewertet, gemeldet gleich danach (nur mit gespeichertem Stand und voller Prüfung — die meisten
                // Paare mit Kurs, wie `AppData.apply` und Android `lastFullCheck`)
                if saved != nil && BackgroundSchedule.isFullCheck(attempted: outcome.checked, failed: outcome.failed) {
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
                                                save: { PriceRefresher.saveAlarmOnDisk($0, $1) })
            } else {
                // Nicht gespeichert (keine Merkliste, unlesbar, nicht zugreifbar) → nichts melden; die
                // Alarme bleiben scharf. Portfolio-Widget, Portfolio- und Gas-Alarm laufen trotzdem.
                outcome.releaseAlarmLease()
            }
            AppData.shared.reloadFromDisk()
            WidgetCenter.shared.reloadAllTimelines()
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
                } else if let stored = SharedStorage.updateSnapshot({ extra.apply(to: &$0) }) {
                    _ = await extra.deliverAlarms(settings: settings, current: stored.alarms,
                                                  save: { PriceRefresher.saveAlarmOnDisk($0, $1) })
                    AppData.shared.reloadFromDisk()
                } else {
                    extra.releaseAlarmLease()
                }
            }
            // USDT-Depeg-Alarm (ein Alarm, darum vor Aktivität und Gas): nur wenn eingeschaltet, höchstens alle
            // 5 Minuten, höchstens bis zur Endzeit — gleiche Restzeit-Regel wie Gas
            if settings.depegAlarm && !Task.isCancelled && RefreshBudget.shouldRun(.gas, remaining: deadline.remaining()) {
                _ = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) {
                    await DepegAlarmCheck.runIfDue(settings: settings)
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
            completion.complete(success: !Task.isCancelled)
        }
        // Zeit abgelaufen: Arbeit abbrechen UND sofort das Ende melden — sonst beendet iOS die App.
        // Der Ablauf meldet danach nicht noch einmal (`BackgroundTaskCompletion`). Nächste Runde zur
        // Sicherheit noch einmal vormerken: eine schon vorgemerkte bleibt stehen (`scheduleRefresh` ohne replace).
        task.expirationHandler = {
            work.cancel()
            scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
            if logGate.claim() { CheckRunStore.log(.bgTask, priceBox.get(), startedAt: startedAt) }
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

    /// Prüfung in diesem Lauf der Hintergrund-Aufgabe? Nur eingeschaltet und fällig.
    static func shouldRunInRefresh(wanted: Bool, lastRunMillis: Int64, now: Int64) -> Bool {
        wanted && isDue(lastRunMillis: lastRunMillis, now: now)
    }
}

/// Zeitbudget der Hintergrund-Aktualisierung (iOS gibt rund 30 s).
struct RefreshBudget {
    /// Kursabfrage höchstens so lange; langsamere Börsen fehlen diesmal.
    static let networkMillis: Int64 = 15_000
    /// Gesamtbudget, deutlich unter den ~30 s von iOS.
    static let totalMillis: Int64 = 25_000
    /// Portfolio-Stand nur mit so viel Restzeit.
    static let portfolioMinMillis: Int64 = 6_000
    /// Aktivität und Gas nur mit so viel Restzeit.
    static let sideCheckMinMillis: Int64 = 5_000
    /// Alarme mit Zusatzdaten nur mit so viel Restzeit (kürzeste Stufe + Lease + Speichern).
    static let extraAlarmsMinMillis: Int64 = 4_000
    /// So viel bleibt immer für Speichern und Abschluss.
    static let reserveMillis: Int64 = RefreshDeadline.saveReserveMillis

    /// Schritte nach dem Speichern der Kurse, in dieser Reihenfolge (wichtigste zuerst). Portfolio vor den
    /// Alarmen mit Zusatzdaten: braucht nur Kurse und Devisen, sonst bliebe ihm nach langsamen Zusatzdaten oft keine Zeit.
    enum Step: CaseIterable {
        case portfolio, extraAlarms, zone, activity, gas

        /// Mindest-Restzeit, damit der Schritt beginnt (Reserve fürs Speichern schon abgezogen).
        var minMillis: Int64 {
            switch self {
            case .extraAlarms: return RefreshBudget.extraAlarmsMinMillis
            case .portfolio: return RefreshBudget.portfolioMinMillis
            case .zone, .activity, .gas: return RefreshBudget.sideCheckMinMillis
            }
        }
    }

    /// Beginnt `step` noch? Nur wenn nach der Reserve mindestens seine Mindestzeit bleibt.
    static func shouldRun(_ step: Step, remaining: Int64) -> Bool {
        remaining - reserveMillis >= step.minMillis
    }

    /// Zeit für den Portfolio-Schritt: alles bis zur Reserve; warten danach Alarme mit Zusatzdaten
    /// (`extraAlarmsWaiting`), bleibt ihnen ihre Mindestzeit — aber nur, solange dem Portfolio selbst
    /// noch seine Mindestzeit bleibt (sonst hat das Portfolio als früherer Schritt Vorrang).
    static func portfolioAllowance(remaining: Int64, extraAlarmsWaiting: Bool) -> Int64 {
        let all = max(0, remaining - reserveMillis)
        guard extraAlarmsWaiting else { return all }
        let shared = all - extraAlarmsMinMillis
        return shared >= portfolioMinMillis ? shared : all
    }

    /// Devisenkurse für Portfolio-Alarme höchstens so lange (sonst sprengen zwei Quellen je Währung das Budget).
    static let portfolioFxMaxMillis: Int64 = 5_000
    /// Darunter lohnt keine Abfrage (Zwischenspeicher reicht).
    static let portfolioFxMinMillis: Int64 = 500

    /// Zeit für die Devisenkurse der Portfolio-Alarme: höchstens `portfolioFxMaxMillis`, nur bis zum Ende des
    /// Portfolio-Schritts (`portfolioEndMillis`) und mit Reserve; 0 = nicht abfragen.
    static func portfolioFxAllowance(portfolioEndMillis: Int64, deadline: RefreshDeadline,
                                     now: Int64 = TimeUtils.nowMillis) -> Int64 {
        let wanted = min(portfolioFxMaxMillis, max(0, portfolioEndMillis - now))
        let millis = deadline.allowance(wanted: wanted, now: now)
        return millis >= portfolioFxMinMillis ? millis : 0
    }

    let start: Int64

    /// Endzeit für alle Schritte (an `PriceRefresher` und die Nebenprüfungen weitergereicht).
    var deadline: RefreshDeadline { RefreshDeadline(endMillis: start + Self.totalMillis) }

    func remaining(now: Int64 = TimeUtils.nowMillis) -> Int64 { deadline.remaining(now: now) }
}

/// Wann die Kurs-Aktualisierung im Hintergrund frühestens beginnen soll.
enum RefreshSchedule {
    /// Frühestens so lange nach jetzt planen.
    static let minDelayMillis: Int64 = 60_000

    /// Intervall nach der letzten Aktualisierung (noch nie bzw. Uhr zurückgestellt: ab jetzt), aber
    /// mindestens `minDelayMillis` ab jetzt — so verschiebt ein Öffnen der App die Prüfung nicht.
    static func earliestBegin(lastRunMillis: Int64, intervalMinutes: Int, now: Int64) -> Date {
        let base = (lastRunMillis > 0 && lastRunMillis <= now) ? lastRunMillis : now
        // Überlaufsicher (Intervall aus alter/fremder Sicherung kann riesig sein)
        let next = max(base + SafeMillis.minutes(intervalMinutes), now + minDelayMillis)
        return Date(timeIntervalSince1970: TimeInterval(next) / 1000)
    }

    /// Beginn der einen Hintergrund-Aufgabe: Kurs-Intervall (`earliestBegin`), oder früher, wenn Fear & Greed
    /// vorher fällig wird (`ZoneSchedule.earliestBegin`). `zoneLastRunMillis` nil = Prüfung ausgeschaltet.
    static func nextBegin(lastRunMillis: Int64, intervalMinutes: Int, zoneLastRunMillis: Int64?, now: Int64) -> Date {
        let refresh = earliestBegin(lastRunMillis: lastRunMillis, intervalMinutes: intervalMinutes, now: now)
        guard let zoneLastRunMillis else { return refresh }
        return min(refresh, ZoneSchedule.earliestBegin(lastRunMillis: zoneLastRunMillis, now: now))
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

/// Zwischenstand für «Letzte Prüfungen» (thread-sicher): `.failed`, bis die Kurse gespeichert sind.
final class CheckResultBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: CheckResult = .failed

    func set(_ result: CheckResult) {
        lock.lock()
        defer { lock.unlock() }
        value = result
    }

    func get() -> CheckResult {
        lock.lock()
        defer { lock.unlock() }
        return value
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
