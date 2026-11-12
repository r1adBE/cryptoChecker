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
            let endedAt = EpochTime.nowMillis
            let result = priceBox.get()
            let logs = logGate.claim()
            BackgroundExpiry(cancel: { workBox.cancel() }, complete: { completion.complete(success: false) },
                             afterwards: {
                                 AppLog.background.notice("BG-Aufgabe abgelaufen nach \(endedAt - startedAt, privacy: .public) ms")
                                 // Dauer bis zum Ablauf, nicht bis zum (vielleicht späteren) Schreiben
                                 if logs {
                                     CheckRunStore.add(CheckRun(at: endedAt, trigger: .bgTask, result: result,
                                                                durationMillis: max(endedAt - startedAt, 0)))
                                 }
                                 scheduleRefresh(settings: SharedStorage.loadSettings(), fromNow: true)
                             }, detach: seams.detachAfterExpiry).run()
        }
        AppLog.background.info("BG-Aufgabe gestartet")
        let work = Task { @MainActor in
            let settings = SharedStorage.loadSettings()
            // Datei-Arbeit (Abstimmung, AES, JSON) abseits des Hauptfadens (`offMain`)
            let snapshot = await offMain { SharedStorage.loadSnapshot() }
            // Zeitbudget: iOS gibt der Aufgabe nur rund 30 s. Eine Endzeit gilt für ALLE Schritte (Kurse,
            // Tages-Bezüge, Lease, Alarm-Daten, Nebenprüfungen). Reihenfolge nach Wichtigkeit
            // (`RefreshBudget.Step`): Kurse und Kursalarme speichern und melden, dann Portfolio (braucht nur
            // Kurse und Devisen), dann Alarme mit Zusatzdaten, Fear & Greed (wenn fällig), Aktivität, Gas — jeweils nur, solange Zeit
            // bleibt. Sonst endet alles im Ablauf, nichts ist gespeichert, und iOS gibt künftig weniger Hintergrundzeit.
            let budget = RefreshBudget(start: EpochTime.nowMillis)
            let deadline = budget.deadline
            let outcome = await seams.refresh(snapshot, settings, deadline)
            // Speichern der Reihe nach mit den Schreibern der App (`PriceWriteGate`: Aktualisierung, Live-Kurse).
            // Von iOS abgebrochen (auch während des Wartens aufs Gate): abgebrochene Anfragen nicht als Fehler speichern.
            let gate = PriceWriteGate.shared
            let entered = await gate.acquire()
            if Task.isCancelled || !entered {
                if entered { gate.release() }
                // Nicht gespeichert → auch nichts melden; die Alarme bleiben scharf.
                outcome.releaseAlarmLease()
                if logGate.claim() { await CheckRunStore.logDetached(.bgTask, .failed, startedAt: startedAt).value }
                completion.complete(success: false)
                return
            }
            // 1) Kurse und Kursalarme ZUERST speichern. Frisch lesen und nur Kurse/Alarm-Zustand je Id
            // übertragen — als ein abgestimmter Schritt mit Widget und App (der Nutzer könnte inzwischen
            // etwas geändert haben). Unlesbare oder gerade nicht zugreifbare Datei: nichts schreiben.
            let saved = await offMain { SharedStorage.updateSnapshot { outcome.apply(to: &$0) } }
            gate.release()
            let current = saved ?? snapshot
            // Ergebnis für «Letzte Prüfungen»: Kurse geholt, aber nicht gespeichert zählt als fehlgeschlagen
            let priceResult: CheckResult = (saved == nil && outcome.checked > 0)
                ? .failed : CheckRunLog.result(checked: outcome.checked, failed: outcome.failed)
            priceBox.set(priceResult)
            // Speichern bzw. Kursabruf komplett gescheitert — nur gezählt (wie Android BackgroundCheck)
            if saved == nil && outcome.checked > 0 {
                BackgroundErrorCounter.record(BackgroundErrors.storage, kind: BackgroundErrors.saveFailedKind)
            } else if outcome.checked > 0 && outcome.failed >= outcome.checked {
                BackgroundErrorCounter.record(BackgroundErrors.refresh, kind: BackgroundErrors.allFailedKind)
            }
            await seams.afterPriceSave()
            // Während oder nach dem Speichern abgelaufen (Ende schon gemeldet) → nicht mehr melden; die
            // Kurse bleiben gespeichert, die Alarme scharf (wie Android: abgebrochener Lauf meldet nicht). Einmal gelesen,
            // damit «Alarme geprüft» und Melden zusammenpassen
            let expired = Task.isCancelled
            var fullCheck = false
            // Uhrzeit nur mit gespeichertem Stand: Ohne (unlesbar, nicht zugreifbar) hielten die Widgets die alten Kurse
            // auf der Platte sonst für frisch (`WidgetDataFreshness`)
            if saved != nil && outcome.failed < outcome.checked {
                SharedStorage.markFullRefresh()
                SharedStorage.lastRefreshDuration = outcome.durationMillis
                // Alarme ausgewertet, gemeldet gleich danach (nur mit voller Prüfung — die meisten Paare mit Kurs,
                // wie `AppData.apply` und Android `lastFullCheck`); abgelaufen: nicht gemeldet, zählt nicht.
                // Warten Alarme mit Zusatzdaten (Schritt 3), zählt der Lauf erst, wenn auch sie geprüft sind —
                // sonst bleibt der alte Wert (`BackgroundSchedule.marksAlarmCheck`)
                fullCheck = !expired && BackgroundSchedule.isFullCheck(attempted: outcome.checked, failed: outcome.failed)
                if BackgroundSchedule.marksAlarmCheck(fullCheck: fullCheck, deferredWaiting: !outcome.deferredAlarms.isEmpty,
                                                      deferredChecked: false) {
                    SharedStorage.lastAlarmCheckAt = EpochTime.nowMillis
                }
            }
            if let report = outcome.report { SharedStorage.lastRefreshReport = report }
            // Mit dieser %-Basis gerechnet (Änderung, Widgets und Live-Aktivität prüfen das)
            if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
            if expired {
                outcome.releaseAlarmLease()
                if logGate.claim() { await CheckRunStore.logDetached(.bgTask, priceResult, startedAt: startedAt).value }
                completion.complete(success: false)
                return
            }
            if let saved {
                // Erst jetzt Alarme melden, je bestätigter Mitteilung den Alarm speichern und die Lease freigeben —
                // nach der Uhrzeit, damit sie zur eben geschriebenen Datei passt (`WidgetDataFreshness`)
                // Paar seit der Abfrage geändert (Bearbeiten, Wiederherstellung mit gleicher Id): nicht melden
                _ = await outcome.keepingCurrentPairs(saved.watches)
                    .deliverAlarms(settings: settings, current: saved.alarms,
                                   save: { PriceRefresher.saveAlarmOnDisk($0, $1) }, notifier: seams.notifier,
                                   recordLog: { AlarmLogStore.record(AlarmLog.withSource($0, .bgTask), live: $1) })
            } else {
                // Nicht gespeichert (keine Merkliste, unlesbar, nicht zugreifbar) → nichts melden; die
                // Alarme bleiben scharf. Portfolio-Widget, Portfolio- und Gas-Alarm laufen trotzdem.
                outcome.releaseAlarmLease()
            }
            await AppData.shared.reloadFromDisk()
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
            await portfolioStep(deadline: deadline,
                                extraAlarmsWaiting: saved != nil && !outcome.deferredAlarms.isEmpty)
            // 3) Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) — nur mit Restzeit;
            // was nicht rechtzeitig kommt, bleibt diese Runde ungeprüft (Alarm bleibt scharf).
            if saved != nil && !outcome.deferredAlarms.isEmpty && !Task.isCancelled
                && RefreshBudget.shouldRun(.extraAlarms, remaining: deadline.remaining()) {
                await deferredAlarmsStep(outcome, settings: settings, deadline: deadline, fullCheck: fullCheck,
                                         seams: seams)
            }
            // Depeg, Fear & Greed (wenn fällig), Aktivität und Gas — je nur mit Restzeit
            await sideChecks(settings: settings, deadline: deadline, zoneDue: zoneDue, watches: current.watches)
            // Alles Geschriebene auf die Platte, bevor iOS die App wieder anhält — abgewartet, ohne den Hauptfaden zu
            // blockieren
            await SharedStorage.snapshotWritesSettled()
            if logGate.claim() { await CheckRunStore.logDetached(.bgTask, priceResult, startedAt: startedAt).value }
            AppLog.background.info("BG-Aufgabe beendet nach \(EpochTime.nowMillis - startedAt, privacy: .public) ms, geprüft \(outcome.checked, privacy: .public), fehlgeschlagen \(outcome.failed, privacy: .public)")
            completion.complete(success: !Task.isCancelled)
        }
        workBox.set(work)
        return work
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
    private static func portfolioStep(deadline: RefreshDeadline, extraAlarmsWaiting: Bool) async {
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
        let widget = await RefreshDeadline.run(within: max(0, portfolioEnd - EpochTime.nowMillis)) { @MainActor in
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

    /// 3) Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) prüfen, speichern und melden;
    /// was nicht rechtzeitig kommt, bleibt diese Runde ungeprüft (Alarm bleibt scharf). `outcome`: Ergebnis der Kurse.
    @MainActor
    private static func deferredAlarmsStep(_ outcome: PriceRefresher.Outcome, settings: AppSettings,
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
    private static func sideChecks(settings: AppSettings, deadline: RefreshDeadline, zoneDue: Bool,
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
    private static func offMain<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await Task.detached(priority: .userInitiated) { work() }.value
    }
}
