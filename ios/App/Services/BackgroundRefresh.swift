import BackgroundTasks
import Foundation
import os
import WidgetKit

/// Hintergrund-Aktualisierung über `BGTaskScheduler` — Ersatz für WorkManager.
///
/// iOS entscheidet selbst, wann die Aufgabe läuft: frühestens nach dem
/// eingestellten Intervall, oft später (je nach Nutzung, Akku und Netz).
enum BackgroundRefresh {
    /// Die einzige Aufgabe: iOS erlaubt nur EINE vorgemerkte Aktualisierung je App (`tooManyPendingTaskRequests`) —
    /// die Prüfung von Fear & Greed läuft darum als Schritt darin.
    static let refreshId = "com.cryptochecker.app.refresh"

    /// Muss vor dem Ende von `application(_:didFinishLaunchingWithOptions:)` aufgerufen werden.
    static func register() {
        // Unerwarteter Aufgabentyp: trotzdem abschliessen (sonst beendet iOS die App und gibt künftig
        // weniger Hintergrundzeit) und neu vormerken — diese Anfrage ist verbraucht.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshId, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                // Lauf gescheitert — nur gezählt (Diagnosebericht)
                BackgroundErrorCounter.record(BackgroundErrors.refresh)
                task.setTaskCompleted(success: false)
                scheduleRefresh(settings: SharedStorage.loadSettings(), replace: true, fromNow: true)
                return
            }
            _ = handleRefresh(refresh)
        }
    }

    // Planen und Absagen (`schedule`, `scheduleRefresh`, Bedarf): BackgroundRefresh+Planning.swift.

    // Fear & Greed und Kennzahlen (`zoneWanted`, `runZoneCheckIfDue`, `runZoneStep`): BackgroundRefresh+Zone.swift.

    /// Ein Lauf der Hintergrund-Aufgabe. Hinter `BackgroundRefreshTask` (Tests: Fälschung statt `BGTask`);
    /// liefert den Arbeits-Task (nil = sofort beendet), damit Tests sein Ende abwarten können.
    /// - Parameter seams: Kurse, Melde-Weg und Haken nach dem Speichern (Tests: Fälschungen).
    @discardableResult
    static func handleRefresh(_ task: any BackgroundRefreshTask,
                              seams: BackgroundRunSeams = .live) -> Task<Void, Never>? {
        let completion = BackgroundTaskCompletion(task)
        let startedAt = EpochTime.nowMillis
        // Fear & Greed / Kennzahlen fällig? Läuft als Schritt in diesem Lauf (keine eigene Aufgabe mehr)
        let zoneDue = ZoneSchedule.shouldRunInRefresh(wanted: zoneWanted(SharedStorage.loadSettings()),
                                                      lastRunMillis: lastZoneCheckAt, now: startedAt)
        // Nächste Runde SOFORT vormerken — vor jeder Arbeit, damit auch ein abgelaufener oder abgebrochener
        // Lauf die Kette nicht abreisst (diese Anfrage ist verbraucht, also ersetzen, Intervall ab jetzt)
        scheduleRefresh(settings: SharedStorage.loadSettings(), replace: true, fromNow: true, zoneRunsNow: zoneDue)
        // «Letzte Prüfungen»: Alarme eben erst geprüft (z. B. App offen) → kurz und erfolgreich enden.
        // Massgebend ist die letzte Prüfung MIT allen Alarmen — das Widget prüft nur Kursalarme (`lastAlarmCheckAt`).
        if BackgroundSchedule.shouldSkip(trigger: .bgTask, lastFinishedAt: SharedStorage.lastAlarmCheckAt, now: startedAt) {
            CheckRunStore.log(.bgTask, .skipped, startedAt: startedAt)
            AppLog.background.info("BG-Aufgabe übersprungen (Alarme eben erst geprüft)")
            guard zoneDue else {
                completion.complete(success: true)
                return nil
            }
            // Kurse eben erst geprüft, Fear & Greed aber fällig: nur diese Prüfung
            return runZoneOnly(task, completion: completion, startedAt: startedAt)
        }
        // Genau ein Eintrag je Lauf: vom Ablauf selbst oder aus dem `expirationHandler`
        let logGate = OnceGate()
        // Ergebnis der Kurse, sobald gespeichert — läuft danach die Zeit ab, zählt es trotzdem
        let priceBox = CheckResultBox()
        // Zeit abgelaufen: Arbeit abbrechen UND sofort das Ende melden — sonst beendet iOS die App.
        // Der Ablauf meldet danach nicht noch einmal (`BackgroundTaskCompletion`). Erst danach und abseits
        // des Aufrufers (`BackgroundExpiry`): Eintrag in «Letzte Prüfungen» (Datei-Abstimmung) und zur Sicherheit noch
        // einmal vormerken (eine schon vorgemerkte bleibt stehen, `scheduleRefresh` ohne replace).
        // Gesetzt VOR dem Start der Arbeit: läuft die Zeit ab, bevor der Task angelegt ist, bricht die Box ihn
        // gleich beim Anlegen ab (`TaskCancelBox.set`).
        let workBox = TaskCancelBox()
        task.onExpiration {
            expire(startedAt: startedAt, priceBox: priceBox, logGate: logGate, workBox: workBox, completion: completion,
                   seams: seams)
        }
        AppLog.background.info("BG-Aufgabe gestartet")
        let work = Task { @MainActor in
            await runWork(seams: seams, startedAt: startedAt, zoneDue: zoneDue, logGate: logGate, priceBox: priceBox,
                          completion: completion)
        }
        workBox.set(work)
        return work
    }

    /// Zeit abgelaufen (`task.onExpiration` in `handleRefresh`): Arbeit abbrechen UND sofort das Ende melden; danach
    /// abseits des Aufrufers (`BackgroundExpiry`) Eintrag in «Letzte Prüfungen» und noch einmal vormerken.
    private static func expire(startedAt: Int64, priceBox: CheckResultBox, logGate: OnceGate, workBox: TaskCancelBox,
                               completion: BackgroundTaskCompletion, seams: BackgroundRunSeams) {
        let endedAt = EpochTime.nowMillis
        let result = priceBox.get()
        let logs = logGate.claim()
        BackgroundExpiry(cancel: { workBox.cancel() }, complete: { completion.complete(success: false) },
                         afterwards: {
                             AppLog.background.notice("BG-Aufgabe abgelaufen nach \(endedAt - startedAt, privacy: .public) ms")
                             // Dauer bis zum Ablauf, nicht bis zum (vielleicht späteren) Schreiben
                             if logs {
                                 CheckRunStore.add(CheckRun(at: endedAt, trigger: .bgTask, result: result,
                                                            durationMillis: max(endedAt - startedAt, 0),
                                                            reason: CheckRunLog.cancelledReason("expired")))
                             }
                             scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
                         }, detach: seams.detachAfterExpiry).run()
    }

    /// Speichern bzw. Kursabruf komplett gescheitert — nur gezählt (wie Android BackgroundCheck).
    @MainActor static func countFailures(_ outcome: PriceRefresher.Outcome, saved: Bool) {
        if !saved && outcome.checked > 0 {
            BackgroundErrorCounter.record(BackgroundErrors.storage, kind: BackgroundErrors.saveFailedKind)
        } else if outcome.checked > 0 && outcome.failed >= outcome.checked {
            BackgroundErrorCounter.record(BackgroundErrors.refresh, kind: BackgroundErrors.allFailedKind)
        }
    }

    /// Uhrzeit und «Alarme geprüft» nach dem Speichern; Rückgabe: volle Prüfung. Uhrzeit nur mit gespeichertem Stand:
    /// Ohne (unlesbar, nicht zugreifbar) hielten die Widgets die alten Kurse auf der Platte sonst für frisch
    /// (`WidgetDataFreshness`).
    @MainActor static func markRefreshed(_ outcome: PriceRefresher.Outcome, saved: Bool, expired: Bool) -> Bool {
        var fullCheck = false
        if saved && BackgroundSchedule.refreshed(attempted: outcome.checked, failed: outcome.failed,
                                                 notTraded: outcome.notTraded) {
            SharedStorage.markFullRefresh()
            SharedStorage.lastRefreshDuration = outcome.durationMillis
            // Alarme ausgewertet, gemeldet gleich danach (nur mit voller Prüfung — die meisten Paare mit Kurs,
            // wie `AppData.apply` und Android `lastFullCheck`); abgelaufen: nicht gemeldet, zählt nicht.
            // Warten Alarme mit Zusatzdaten (Schritt 3), zählt der Lauf erst, wenn auch sie geprüft sind —
            // sonst bleibt der alte Wert (`BackgroundSchedule.marksAlarmCheck`)
            // Ohne Alarm-Lease bzw. mit Budget-Lücken keine volle Prüfung (der nächste Lauf wird nicht übersprungen)
            fullCheck = outcome.isFullCheck(expired: expired)
            if BackgroundSchedule.marksAlarmCheck(fullCheck: fullCheck, deferredWaiting: !outcome.deferredAlarms.isEmpty,
                                                  deferredChecked: false) {
                SharedStorage.lastAlarmCheckAt = EpochTime.nowMillis
            }
        }
        return fullCheck
    }

    /// Erst jetzt Alarme melden, je bestätigter Mitteilung den Alarm speichern und die Lease freigeben — nach der
    /// Uhrzeit, damit sie zur eben geschriebenen Datei passt (`WidgetDataFreshness`). Nicht gespeichert (keine Merkliste,
    /// unlesbar, nicht zugreifbar) → nichts melden; die Alarme bleiben scharf. Portfolio-Widget, Portfolio- und
    /// Gas-Alarm laufen trotzdem.
    @MainActor static func deliverAlarms(_ outcome: PriceRefresher.Outcome, saved: SharedStorage.Snapshot?,
                                         settings: AppSettings, seams: BackgroundRunSeams) async {
        guard let saved else {
            outcome.releaseAlarmLease()
            return
        }
        // Paar seit der Abfrage geändert (Bearbeiten, Wiederherstellung mit gleicher Id): nicht melden
        _ = await outcome.keepingCurrentPairs(saved.watches)
            .deliverAlarms(settings: settings, current: saved.alarms,
                           save: { PriceRefresher.saveAlarmOnDisk($0, $1) }, notifier: seams.notifier,
                           recordLog: { AlarmLogStore.record(AlarmLog.withSource($0, .bgTask), live: $1) })
    }

    /// Kurse eben erst geprüft, Fear & Greed aber fällig: nur diese Prüfung. Den `expirationHandler` ZUERST
    /// setzen: iOS darf ihn sofort nach dem Start rufen; ein Handler, der erst nach dem Task gesetzt wird,
    /// könnte einen frühen Ablauf verpassen (dann meldete niemand das Ende rechtzeitig).
    private static func runZoneOnly(_ task: any BackgroundRefreshTask, completion: BackgroundTaskCompletion,
                                    startedAt: Int64) -> Task<Void, Never> {
        let zoneBox = TaskCancelBox()
        task.onExpiration {
            // Zuerst abbrechen und das Ende melden, danach neu vormerken (`BackgroundExpiry`)
            BackgroundExpiry(cancel: { zoneBox.cancel() }, complete: { completion.complete(success: false) },
                             afterwards: {
                                 AppLog.background.notice("BG-Aufgabe abgelaufen (nur Fear & Greed)")
                                 scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
                             }).run()
        }
        let zoneWork = Task { @MainActor in
            await runZoneStep(settings: SharedStorage.loadSettings(), deadline: RefreshBudget(start: startedAt).deadline)
            completion.complete(success: !Task.isCancelled)
        }
        zoneBox.set(zoneWork)
        return zoneWork
    }

    /// 2) Portfolio-Widget: eigener Stand, damit es nicht vom Öffnen der App abhängt — gleich nach den
    /// Kursalarmen (braucht nur Kurse und Devisen), vor den Alarmen mit Zusatzdaten (nur mit Restzeit,
    /// höchstens bis zur Endzeit; ohne Ergebnis keine Portfolio-Alarme diesmal).
    @MainActor
    static func portfolioStep(deadline: RefreshDeadline, extraAlarmsWaiting: Bool) async {
        guard !Task.isCancelled && RefreshBudget.shouldRun(.portfolio, remaining: deadline.remaining()) else { return }
        // Warten Alarme mit Zusatzdaten, bleibt ihnen wenn möglich ihre Mindestzeit
        let allowance = RefreshBudget.portfolioAllowance(
            remaining: deadline.remaining(),
            extraAlarmsWaiting: extraAlarmsWaiting)
        // Fällige Adress-Guthaben zuerst (10 min Zwischenspeicher; ohne Adressen keine Anfrage) — mit eigener
        // Grenze (höchstens 8 s, je Anfrage 5 s), die dem Portfolio-Stand seine Mindestzeit lässt; reicht
        // die Zeit nicht, bleiben die Adressen bis zum nächsten Lauf fällig (Kurse und Alarme gehen vor)
        let portfolioEnd = EpochTime.nowMillis + allowance
        let addressMillis = min(WatchedAddressSource.backgroundTotalMillis,
                                allowance - RefreshBudget.portfolioMinMillis)
        if addressMillis >= 1_000 {
            _ = await RefreshDeadline.run(within: addressMillis) { @MainActor in
                await PortfolioModel.shared.refreshWatchedAddresses(force: false, budgetMillis: addressMillis - 500)
            }
        }
        // Tagesschlüsse für «7 T» und «1 J» des Widgets: übers Netz nur, wenn der Zwischenspeicher älter als 12 h ist
        // (`dailyPrices`) — sonst zeigte das Widget «—», bis jemand den Wertverlauf im Portfolio-Tab öffnet. Nur mit
        // Restzeit über der Mindestzeit des Portfolio-Stands. Wie Android `PortfolioSnapshotUpdater.refresh`
        let dailyMillis = min(RefreshBudget.portfolioDailyMaxMillis,
                              portfolioEnd - EpochTime.nowMillis - RefreshBudget.portfolioMinMillis)
        if dailyMillis >= 1_000 {
            _ = await RefreshDeadline.run(within: dailyMillis) { @MainActor in
                await PortfolioHistorySource.refreshWidgetDaily()
            }
        }
        let widget = await RefreshDeadline.run(within: max(0, portfolioEnd - EpochTime.nowMillis)) { @MainActor in
            await PortfolioWidgetStore.refresh(stamp: true)
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

    /// 3) Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) prüfen, speichern und melden;
    /// was nicht rechtzeitig kommt, bleibt diese Runde ungeprüft (Alarm bleibt scharf). `outcome`: Ergebnis der Kurse.
    @MainActor
    static func deferredAlarmsStep(_ outcome: PriceRefresher.Outcome, settings: AppSettings,
                                   deadline: RefreshDeadline, fullCheck: Bool,
                                   seams: BackgroundRunSeams) async {
        let extra = await seams.checkDeferred(outcome.deferredAlarms, settings, deadline)
        // Geprüft und (falls nötig) gespeichert, nicht abgelaufen: erst jetzt zählt der Lauf als Prüfung mit allen Alarmen
        var deferredChecked = false
        defer {
            if BackgroundSchedule.marksAlarmCheck(fullCheck: fullCheck, deferredWaiting: true,
                                                  deferredChecked: deferredChecked) {
                SharedStorage.lastAlarmCheckAt = EpochTime.nowMillis
            }
        }
        if Task.isCancelled {
            extra.releaseAlarmLease()
        } else if extra.alarms.isEmpty && extra.pendingAlarms.isEmpty {
            deferredChecked = true
            extra.releaseAlarmLease()
            // Nichts zu speichern, aber geprüft: fürs Alarm-Protokoll festhalten — abseits des Hauptfadens
            // (verschlüsselte, abgestimmte Datei), abgewartet, damit es vor dem Ende der Aufgabe geschrieben ist
            let log = AlarmLog.withSource(extra.alarmLog, .bgTask)
            await Task.detached(priority: .utility) { AlarmLogStore.record(log, live: false) }.value
        } else if let stored = await offMain({ SharedStorage.updateSnapshot { extra.apply(to: &$0) } }) {
            await seams.afterExtraSave()
            // Während des Speicherns abgelaufen → nicht mehr melden, Lease frei, Alarme scharf
            // (wie der Kurs-Pfad; in der Melde-Schleife prüft `deliverAlarms` je Alarm erneut)
            if Task.isCancelled {
                extra.releaseAlarmLease()
            } else {
                deferredChecked = true
                _ = await extra.keepingCurrentPairs(stored.watches)
                    .deliverAlarms(settings: settings, current: stored.alarms,
                                   save: { PriceRefresher.saveAlarmOnDisk($0, $1) }, notifier: seams.notifier,
                                   recordLog: { AlarmLogStore.record(AlarmLog.withSource($0, .bgTask), live: $1) })
            }
            await AppData.shared.reloadFromDisk()
        } else {
            extra.releaseAlarmLease()
        }
    }

    /// Nebenprüfungen nach Kursen, Portfolio und Alarmen — je nur mit Restzeit und höchstens bis zur Endzeit.
    @MainActor
    static func sideChecks(settings: AppSettings, deadline: RefreshDeadline, zoneDue: Bool,
                           watches: [Watch]) async {
        // USDT/USD messen und Depeg-Alarm prüfen (ein Alarm, darum vor Aktivität und Gas): nur mit Alarm oder
        // Portfolio (Live-Kurs USDT/USD der Bewertung), höchstens alle 5 Minuten, höchstens bis zur Endzeit —
        // gleiche Restzeit-Regel wie Gas
        let hasPortfolio = await MainActor.run { AppData.shared.hasPortfolioData }
        // Fiat-Alarm bzw. ≈ Umrechnung über USDT: Live-Kurs USDT/USD statt still USDT = 1
        let fiat = await MainActor.run { AppData.shared.fiatConversionNeedsUsdt }
        if DepegAlarm.shouldMeasure(depegAlarm: settings.depegAlarm, portfolioEnabled: settings.portfolioEnabled,
                                    hasPortfolioData: hasPortfolio, fiatConversion: fiat)
            && !Task.isCancelled && RefreshBudget.shouldRun(.gas, remaining: deadline.remaining()) {
            _ = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) {
                await DepegAlarmCheck.runIfDue(settings: settings, hasPortfolioData: hasPortfolio, fiatConversion: fiat,
                                               stables: { await StableDepegPlanner.plan() })
            }
        }
        // Fear & Greed und Kennzahlen in derselben Aufgabe: nur wenn fällig, höchstens bis zur Endzeit
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
    }

    /// Datei-Arbeit (`NSFileCoordinator`, AES, JSON) abseits des Hauptfadens und abgewartet: Der Lauf
    /// selbst ist auf dem MainActor, und das Abstimmen kann auf Widget oder App warten (Oberfläche hinge sonst).
    static func offMain<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await Task.detached(priority: .userInitiated) { work() }.value
    }
}
