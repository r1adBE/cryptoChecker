import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// Kurse aktualisieren in AppData: alle bzw. ein Paar, Entprellung, Ergebnis übernehmen, Alarme zustellen,
// späte Tagesänderungen und die Auswertung ungewöhnlicher Aktivität.
// Eigene Datei, weil der Abruf-Ablauf für sich lesbar sein soll.

extension AppData {
    // MARK: Aktualisieren

    /// - Returns: das Ergebnis (für «Letzte Prüfungen»); nil = es lief schon eine.
    @discardableResult
    func refreshAll() async -> PriceRefresher.Outcome? {
        guard !refreshing else { return nil }
        refreshing = true
        // Geht die App währenddessen in den Hintergrund: zu Ende abfragen und speichern
        let backgroundTask = BackgroundTaskGuard.begin("refreshAll")
        defer {
            refreshing = false
            backgroundTask.end()
        }
        await waitForLiveApply()
        let liveIds = liveCoveredIds()
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, liveIds: liveIds)
        // Alles kommt live: Kurse sind aktuell — Zeit (Widget-Kopfzeile) wie nach einer Aktualisierung
        // (deaktivierte Paare zählen nicht: sie werden nie abgefragt)
        // `lastAlarmCheckAt` (Sperre der Hintergrund-Aufgabe) bleibt: kein voller Lauf (wie Android)
        if outcome.checked == 0 && !liveIds.isEmpty && liveIds.count == snapshot.watches.filter({ !$0.disabled }).count {
            SharedStorage.lastRefreshAt = TimeUtils.nowMillis
        }
        apply(outcome, full: true)
        analyzeActivity()
        return outcome
    }

    /// Nach unten ziehen bzw. Knopf oben: gesperrt, solange eine Aktualisierung läuft oder die
    /// letzte vollständige keine 15 s her ist (`RefreshDebounce`). Dann kein neuer Durchlauf —
    /// der Menüpunkt ist grau mit Countdown (`WatchlistMenuHead`). Live-Takt und neue Paare laufen ohne Sperre.
    @discardableResult
    func refreshAllByUser() async -> RefreshDebounce.Decision {
        let decision = RefreshDebounce.decide(running: refreshing, lastFinishedAt: SharedStorage.lastRefreshAt,
                                              now: TimeUtils.nowMillis)
        if decision == .start {
            // Von Hand aktualisiert: Charts im Aktionsblatt nicht mehr aus dem Zwischenspeicher
            SheetChartStore.shared.invalidateAll()
            let startedAt = TimeUtils.nowMillis
            let outcome: PriceRefresher.Outcome? = await refreshAll()
            // «Letzte Prüfungen» (Build 46): auch der Knopf in der App zählt (Auslöser «App»)
            let result: CheckResult = outcome.map { CheckRunLog.result(checked: $0.checked, failed: $0.failed) } ?? .skipped
            CheckRunStore.log(.foreground, result, startedAt: startedAt)
        }
        return decision
    }

    // MARK: Ungewöhnliche Aktivität

    /// Nach einer kompletten Aktualisierung: eigener Hintergrund-Task, damit die
    /// Aktualisierung (und ihr Kreisel) nicht auf die Auswertung wartet, die bis
    /// zu 20 s dauern kann. Prüft jedes Paar höchstens alle 10 Minuten.
    func analyzeActivity() {
        let watches = snapshot.watches
        let settings = self.settings
        // Nur mit Abnehmer: Mitteilung eingeschaltet oder App im Vordergrund (Karte, ⚡, «Was bewegt sich mit?»)
        if ActivityAnalysisGate.shouldRun(alertsEnabled: settings.activityAlerts, appVisible: appActive) {
            Task.detached(priority: .utility) { [weak self] in
                guard await ActivityMonitor.analyzeAll(watches: watches, settings: settings) else { return }
                await self?.reloadActivity()
            }
        }
        // Gas-Alarm (#167): eigener Task, höchstens alle 10 Minuten, nur wenn eingestellt
        Task.detached(priority: .utility) {
            await GasAlertCheck.runIfDue(settings: settings)
        }
        // USDT/USD messen und Depeg-Alarm prüfen: eigener Task, nur mit Alarm oder Portfolio, höchstens alle 5 Minuten
        let hasPortfolio = hasPortfolioData
        // Fiat-Alarm bzw. ≈ Umrechnung über USDT: Live-Kurs USDT/USD statt still USDT = 1
        let fiat = fiatConversionNeedsUsdt
        if DepegAlarm.shouldMeasure(depegAlarm: settings.depegAlarm, portfolioEnabled: settings.portfolioEnabled,
                                    hasPortfolioData: hasPortfolio, fiatConversion: fiat) {
            Task.detached(priority: .utility) {
                await DepegAlarmCheck.runIfDue(settings: settings, hasPortfolioData: hasPortfolio, fiatConversion: fiat)
            }
        }
    }

    /// Hängt eine Fiat-Umrechnung an USDT (`FiatAlarmGuard.needsUsdtMeasurement`): ≈ Umrechnung der Merkliste oder ein
    /// aktiver Alarm in anderer Währung? Dann misst `DepegAlarmCheck` USDT/USD auch ohne Alarm und Portfolio.
    var fiatConversionNeedsUsdt: Bool {
        let active = snapshot.watches.filter { !$0.disabled }
        let quotes = Dictionary(active.map { ($0.id, WatchlistConversion.quote($0)) }, uniquingKeysWith: { first, _ in first })
        let conversions: [(quote: String, currency: String)] = snapshot.alarms.compactMap { alarm in
            guard alarm.enabled, let currency = alarm.convertCurrency, let quote = quotes[alarm.watchId] else { return nil }
            return (quote: quote, currency: currency)
        }
        return FiatAlarmGuard.needsUsdtMeasurement(showConverted: settings.showConverted,
                                                   displayCurrency: settings.portfolioCurrency,
                                                   watchQuotes: active.map(WatchlistConversion.quote),
                                                   alarmConversions: conversions)
    }

    /// Gespeicherte Ergebnisse neu übernehmen (z. B. nach der Hintergrund-Aktualisierung).
    func reloadActivity() {
        // Nicht mehr gehandelte und deaktivierte Paare haben keine Signale (siehe `NotTraded`)
        let ids = Set(snapshot.watches.filter { !$0.isNotTraded && !$0.disabled }.map(\.id))
        activityReports = ActivityRepository.reports().filter { ids.contains($0.key) }
    }

    /// Daten für «Was bewegt sich mit?»; nil, wenn abgebrochen.
    nonisolated func explain(_ watch: Watch) async -> WhyReport? {
        await ActivityMonitor.explain(watch)
    }

    /// Pausen je Börse neu lesen (gemeinsam mit Hintergrund und Widget gespeichert); nur bei Änderung veröffentlichen.
    func reloadExchangePauses() {
        let pauses = RowPause.pauses(SharedStorage.exchangeBackoff)
        if pauses != exchangePauses { exchangePauses = pauses }
    }

    func refreshOne(_ watchId: Int64) async {
        guard !refreshingWatchIds.contains(watchId) else { return }
        refreshingWatchIds.insert(watchId)
        defer { refreshingWatchIds.remove(watchId) }
        await waitForLiveApply()
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, onlyWatchId: watchId)
        apply(outcome, full: false)
    }

    /// - Parameter live: gespeicherte Live-Kurse (`applyLive`): Ändert das Ergebnis nur Kurs,
    ///   Änderung und Zeit (kein Alarm, keine Mitteilung, kein Fehler), wird der Stand still
    ///   übernommen (`mutate(…, quiet: true)`) — siehe `snapshot`.
    func apply(_ outcome: PriceRefresher.Outcome, full: Bool, reloadWidgets: Bool = true, live: Bool = false) {
        // Uhrzeit und Dauer zuerst speichern, dann die Widgets neu laden —
        // sonst zeigt die Widget-Kopfzeile neue Kurse mit der alten Uhrzeit.
        // Ohne einen einzigen Kurs (z. B. offline) bleibt die Zeit der letzten
        // erfolgreichen Aktualisierung stehen.
        if full && outcome.failed < outcome.checked {
            SharedStorage.lastRefreshAt = TimeUtils.nowMillis
            // Sperre der Hintergrund-Aufgabe: nur nach einer vollen Prüfung (die meisten Paare mit Kurs)
            if BackgroundSchedule.isFullCheck(attempted: outcome.checked, failed: outcome.failed) {
                SharedStorage.lastAlarmCheckAt = TimeUtils.nowMillis
            }
            SharedStorage.lastRefreshDuration = outcome.durationMillis
            lastRefreshMillis = outcome.durationMillis
        }
        // Bericht auch ohne einen einzigen Kurs (wie Android): dann zeigt er, welche Börse scheiterte
        if full, let report = outcome.report {
            SharedStorage.lastRefreshReport = report
            lastRefreshReport = report
        }
        // Pausen je Börse hat der Durchlauf eben gespeichert (Live-Kurse ändern sie nicht)
        if !live { reloadExchangePauses() }
        // Mit dieser %-Basis gerechnet (Anzeige prüft das, siehe `changeView`)
        if full, let stamp = outcome.changeStamp {
            SharedStorage.changeStamp = stamp
            changeStamp = stamp
        }
        // Ein einzelnes Paar: nur Merkliste- und Einzel-Widgets neu laden. Das Portfolio-Widget
        // lädt sich selbst, wenn sich seine Momentaufnahme ändert; «Was gerade auffällt» hängt
        // nicht an einem Paar.
        let kinds: [String]? = full ? nil : [SharedStorage.watchlistWidgetKind, SharedStorage.singleWidgetKind]
        // Fehlerzustand einer Zeile verschwindet (z. B. «nicht erreichbar» → Kurs): das ist mehr als ein Kurs
        let clearsError = live && snapshot.watches.contains { outcome.prices[$0.id] != nil && $0.lastError != nil }
        let pricesOnly = live && !clearsError && outcome.alarms.isEmpty && outcome.pendingAlarms.isEmpty
            && outcome.notified.isEmpty && outcome.prices.values.allSatisfy { $0.price != nil }
        mutate({ s in outcome.apply(to: &s) }, reloadWidgets: reloadWidgets, widgetKinds: kinds, quiet: pricesOnly)
        deliverAlarms(outcome)
        // Portfolio-Alarme: nach jedem vollen Durchlauf mit frischen Portfolio-Kursen prüfen
        // (nur, wenn einer scharf ist — sonst holt nur der Portfolio-Tab die Kurse)
        if full && hasActivePortfolioAlarms {
            Task { [weak self] in
                let widget = await PortfolioWidgetStore.refresh()
                await self?.prefetchPortfolioAlarmRates(widget)
                await self?.evaluatePortfolioAlarms(widget)
            }
        }
        // Live-Aktivität (Sperrbildschirm) mit dem neuen Kurs
        let watches = snapshot.watches
        Task { await LiveActivityController.update(watches: watches) }
        // Tages-Basen: Bezüge, die nach dem Warten noch kamen, nachtragen statt «—»
        if let late = outcome.lateDayChanges, let stamp = outcome.changeStamp {
            let times = outcome.missingDayChange.mapValues { $0.time }
            Task { [weak self] in
                let changes = await late()
                guard !changes.isEmpty else { return }
                self?.fillLateDayChanges(changes, times: times, stamp: stamp)
            }
        }
        // Kursansagen je Paar; Alarm-Ansagen folgen nach der bestätigten Mitteilung (`deliverAlarms`)
        // und gehen beim Sprecher trotzdem vor (nie verworfen, vor wartenden Kursansagen)
        for item in outcome.priceSpeech {
            Speaker.shared.enqueue(item.text, rate: settings.ttsSpeechRate, alarm: false, key: item.watchId)
        }
    }

    /// Ausgelöste Alarme des Durchlaufs: zuerst melden, je bestätigter Mitteilung den Alarm speichern,
    /// dann ansagen (`Outcome.deliverAlarms`, wie Android `AlarmDelivery`); danach ist die Alarm-Lease frei.
    private func deliverAlarms(_ outcome: PriceRefresher.Outcome) {
        // Paar seit der Abfrage geändert (Bearbeiten, Wiederherstellung mit gleicher Id): nicht melden
        var outcome = outcome
        let identities = Dictionary(snapshot.watches.map { ($0.id, $0.identity) }, uniquingKeysWith: { first, _ in first })
        outcome.pendingAlarms.removeAll { identities[$0.watch.id] != $0.watch.identity }
        guard !outcome.pendingAlarms.isEmpty || outcome.alarmLease != nil else {
            AppData.recordAlarmLog(outcome.alarmLog, live: outcome.alarmLogLive)
            return
        }
        let settings = self.settings
        let current = snapshot.alarms
        let pending = outcome
        Task { @MainActor in
            // Melden und Speichern zu Ende führen, auch wenn die App gerade in den Hintergrund geht
            let backgroundTask = BackgroundTaskGuard.begin("deliverAlarms")
            defer { backgroundTask.end() }
            // `mutate` schreibt im Hintergrund: Kurse und Alarm-Änderungen erst auf die Platte, bevor die Lease
            // frei wird — sonst wertet der nächste Prozess den alten Stand aus. Abgewartet ohne den Haupt-Thread zu
            // blockieren (das Schreiben kann über `NSFileCoordinator` auf das Widget warten)
            if pending.alarmLease != nil { await SharedStorage.snapshotWritesSettled() }
            guard !pending.pendingAlarms.isEmpty else {
                pending.releaseAlarmLease()
                AppData.recordAlarmLog(pending.alarmLog, live: pending.alarmLogLive)
                return
            }
            let delivered = await pending.deliverAlarms(settings: settings, current: current,
                                                        save: { id, update in await self.saveDeliveredAlarm(id, update) },
                                                        recordLog: { AppData.recordAlarmLog($0, live: $1) })
            for item in delivered {
                if let text = item.speech { Speaker.shared.enqueue(text, rate: settings.ttsSpeechRate, alarm: true) }
            }
        }
    }

    /// Alarm-Protokoll abseits des Hauptfadens schreiben (verschlüsselte Datei, abgestimmt mit Widget und Hintergrund).
    /// Herkunft «in der App» (Ziehen, Knopf, Live-Kurse) — Hintergrund-Aufgabe und Widget setzen ihre eigene.
    nonisolated static func recordAlarmLog(_ entries: [AlarmLogEntry], live: Bool) {
        guard !entries.isEmpty else { return }
        let stamped = AlarmLog.withSource(entries, .foreground)
        Task.detached(priority: .utility) { AlarmLogStore.record(stamped, live: live) }
    }

    /// `save` für `Outcome.deliverAlarms`: in den Stand im Speicher und abgewartet auf die Platte, bevor der
    /// nächste Alarm gemeldet bzw. die Lease frei wird (ohne den Haupt-Thread zu blockieren).
    /// false = Alarm inzwischen bearbeitet oder gelöscht.
    private func saveDeliveredAlarm(_ id: Int64, _ update: PriceRefresher.AlarmUpdate) async -> Bool {
        var applied = false
        mutate({ s in applied = update.apply(toAlarm: id, in: &s) }, reloadWidgets: false)
        guard applied else { return false }
        await SharedStorage.snapshotWritesSettled()
        return true
    }

    /// Späte Tages-Bezüge übernehmen — nur, solange Basis und Tag gleich sind und das Paar noch
    /// denselben Kurs ohne Veränderung hat; danach Widgets und Live-Aktivität neu.
    private func fillLateDayChanges(_ changes: [Int64: Double], times: [Int64: Int64], stamp: ChangeStamp) {
        // Basis und Tag unverändert, und kein neuerer Durchlauf hat inzwischen einen anderen Stempel gesetzt
        guard ChangeBasisMath.stamp(settings.changeBasis, now: TimeUtils.nowMillis) == stamp,
              changeStamp == stamp else { return }
        let fillable = snapshot.watches.contains { w in
            changes[w.id] != nil && w.change24h == nil && w.lastError == nil && times[w.id] == w.lastUpdate
        }
        guard fillable else { return }
        mutate({ s in
            for i in s.watches.indices {
                let w = s.watches[i]
                guard let change = changes[w.id], w.change24h == nil, w.lastError == nil,
                      times[w.id] == w.lastUpdate else { continue }
                s.watches[i].change24h = change
            }
        }, widgetKinds: [SharedStorage.watchlistWidgetKind, SharedStorage.singleWidgetKind])
        let watches = snapshot.watches
        Task { await LiveActivityController.update(watches: watches) }
    }
}
