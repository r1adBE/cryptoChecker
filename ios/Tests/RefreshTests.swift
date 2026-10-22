import UserNotifications
import XCTest
@testable import CryptoChecker

/// Wie `RefreshDebounceTest.kt` und `RefreshReportLogicTest.kt`.
final class RefreshDebounceTests: XCTestCase {

    private let now: Int64 = 1_000_000_000

    func testRunningWins() {
        XCTAssertEqual(RefreshDebounce.decide(running: true, lastFinishedAt: 0, now: now), .running)
        XCTAssertEqual(RefreshDebounce.decide(running: true, lastFinishedAt: now - 60_000, now: now), .running)
    }

    func testNeverRefreshedStarts() {
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: 0, now: now), .start)
    }

    func testWithinWindowIsRecent() {
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: now, now: now), .recent)
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: now - 8_000, now: now), .recent)
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: now - RefreshDebounce.windowMillis + 1, now: now), .recent)
    }

    func testAfterWindowStarts() {
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: now - RefreshDebounce.windowMillis, now: now), .start)
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: now - 60_000, now: now), .start)
    }

    func testFutureTimestampStarts() {
        // Uhr zurückgestellt: nicht für immer sperren
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: now + 5_000, now: now), .start)
    }

    func testRemainingSecondsRoundsUp() {
        let w = RefreshDebounce.windowMillis
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now, now: now), 15)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now - 1, now: now), 15)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now - 1_000, now: now), 14)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now - 9_800, now: now), 6)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now - w + 1, now: now), 1)
    }

    func testRemainingSecondsZeroWhenFree() {
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: 0, now: now), 0)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now - RefreshDebounce.windowMillis, now: now), 0)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now - 60_000, now: now), 0)
        // Uhr zurückgestellt: frei wie bei decide
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: now + 5_000, now: now), 0)
    }

    func testRemainingSecondsMatchesDecide() {
        for age in stride(from: Int64(-2_000), through: 20_000, by: 250) {
            let recent = RefreshDebounce.decide(running: false, lastFinishedAt: now - age, now: now) == .recent
            XCTAssertEqual(recent, RefreshDebounce.remainingSeconds(lastFinishedAt: now - age, now: now) > 0)
        }
    }

    func testNextChangeLandsOnSecondBoundary() {
        XCTAssertEqual(RefreshDebounce.millisUntilNextChange(lastFinishedAt: now, now: now), 1_000)
        XCTAssertEqual(RefreshDebounce.millisUntilNextChange(lastFinishedAt: now - 200, now: now), 800)
        XCTAssertEqual(RefreshDebounce.millisUntilNextChange(lastFinishedAt: now - 9_800, now: now), 200)
        XCTAssertEqual(RefreshDebounce.millisUntilNextChange(lastFinishedAt: now - RefreshDebounce.windowMillis + 1, now: now), 1)
        XCTAssertEqual(RefreshDebounce.millisUntilNextChange(lastFinishedAt: now - RefreshDebounce.windowMillis, now: now), 0)
        XCTAssertEqual(RefreshDebounce.millisUntilNextChange(lastFinishedAt: 0, now: now), 0)
        // Nach dem Warten zeigt der Countdown genau eine Zahl weniger
        let last = now - 9_800
        let wait = RefreshDebounce.millisUntilNextChange(lastFinishedAt: last, now: now)
        XCTAssertEqual(RefreshDebounce.remainingSeconds(lastFinishedAt: last, now: now + wait),
                       RefreshDebounce.remainingSeconds(lastFinishedAt: last, now: now) - 1)
    }

    /// Ziehen im Fenster: kein Durchlauf, aber «Gerade aktualisiert · vor N s» (abgerundet, mindestens 1).
    func testPullInsideWindowEchoesSecondsSinceLastRefresh() {
        let last = now - 8_400
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: last, now: now), .recent)
        XCTAssertEqual(RefreshDebounce.pullEchoSeconds(.recent, lastFinishedAt: last, now: now), 8)
        XCTAssertEqual(RefreshDebounce.pullEchoSeconds(.recent, lastFinishedAt: now - 200, now: now), 1)
        XCTAssertEqual(RefreshDebounce.pullEchoSeconds(.recent, lastFinishedAt: now, now: now), 1)
        XCTAssertEqual(RefreshDebounce.pullEchoMillis, 400)
    }

    func testPullOutsideWindowHasNoEcho() {
        XCTAssertNil(RefreshDebounce.pullEchoSeconds(.start, lastFinishedAt: now - 8_000, now: now))
        XCTAssertNil(RefreshDebounce.pullEchoSeconds(.running, lastFinishedAt: now - 8_000, now: now))
        XCTAssertNil(RefreshDebounce.pullEchoSeconds(.recent, lastFinishedAt: 0, now: now))
        // Uhr zurückgestellt
        XCTAssertNil(RefreshDebounce.pullEchoSeconds(.recent, lastFinishedAt: now + 5_000, now: now))
        // Gleiche Grenze wie decide: jede Entscheidung `.recent` hat eine Rückmeldung
        for age in stride(from: Int64(0), to: RefreshDebounce.windowMillis, by: 250) {
            let decision = RefreshDebounce.decide(running: false, lastFinishedAt: now - age, now: now)
            XCTAssertEqual(decision == .recent,
                           RefreshDebounce.pullEchoSeconds(decision, lastFinishedAt: now - age, now: now) != nil)
        }
    }
}

final class RefreshReportLogicTests: XCTestCase {

    private func market(_ name: String, _ pairs: Int, _ updated: Int, notTraded: Int = 0, failed: Int? = nil,
                        millis: Int64 = 500) -> MarketRefresh {
        MarketRefresh(name: name, millis: millis, pairs: pairs, updated: updated, notTraded: notTraded,
                      failed: failed ?? (pairs - updated - notTraded))
    }

    private func report(_ markets: [MarketRefresh], aborted: String? = nil) -> RefreshReport {
        RefreshReport(at: 0, totalMillis: 1_800, pairs: markets.reduce(0) { $0 + $1.pairs }, networkMillis: 1_700,
                      markets: markets, aborted: aborted)
    }

    func testStatusGreenOrangeRed() {
        XCTAssertEqual(RefreshReportLogic.status(market("A", 5, 5)), .ok)
        XCTAssertEqual(RefreshReportLogic.status(market("A", 525, 522)), .partial)
        XCTAssertEqual(RefreshReportLogic.status(market("A", 5, 0)), .failed)
        // Nur nicht gehandelte Paare: orange, nicht rot
        XCTAssertEqual(RefreshReportLogic.status(market("A", 3, 0, notTraded: 3)), .partial)
        XCTAssertEqual(RefreshReportLogic.status(market("A", 4, 0, notTraded: 1)), .failed)
        XCTAssertEqual(RefreshReportLogic.status(market("A", 0, 0)), .ok)
    }

    func testSortedRedOrangeGreenThenSlowestFirst() {
        let sorted = RefreshReportLogic.sorted([
            market("Green fast", 5, 5, millis: 100),
            market("Orange", 10, 9, millis: 50),
            market("Green slow", 5, 5, millis: 900),
            market("Red", 2, 0, millis: 10),
            market("Orange slow", 10, 8, millis: 700),
        ])
        XCTAssertEqual(sorted.map(\.name), ["Red", "Orange slow", "Orange", "Green slow", "Green fast"])
    }

    func testOverallIsWorst() {
        XCTAssertEqual(RefreshReportLogic.overall(report([])), .ok)
        XCTAssertEqual(RefreshReportLogic.overall(report([market("A", 5, 5)])), .ok)
        XCTAssertEqual(RefreshReportLogic.overall(report([market("A", 5, 5), market("B", 5, 4)])), .partial)
        XCTAssertEqual(RefreshReportLogic.overall(report([market("A", 5, 4), market("B", 5, 0)])), .failed)
        XCTAssertEqual(RefreshReportLogic.overall(report([market("A", 5, 5)], aborted: "IOException")), .failed)
    }

    func testHeadlineChoice() {
        XCTAssertEqual(RefreshReportLogic.headline(report([market("A", 5, 5)])), .allUpdated)
        XCTAssertEqual(RefreshReportLogic.headline(report([market("A", 5, 5), market("B", 525, 522)])), .pairsNotUpdated(3))
        XCTAssertEqual(RefreshReportLogic.headline(report([market("A", 5, 4), market("B", 3, 0, notTraded: 3)])),
                       .pairsNotUpdated(4))
        XCTAssertEqual(RefreshReportLogic.headline(report([market("Binance", 5, 0), market("B", 525, 522)])),
                       .marketUnreachable("Binance"))
        XCTAssertEqual(RefreshReportLogic.headline(report([market("A", 5, 0), market("B", 2, 0)])), .marketsUnreachable(2))
        XCTAssertEqual(RefreshReportLogic.headline(report([market("A", 5, 5)], aborted: "x")), .aborted)
    }

    func testClassifyErrors() {
        XCTAssertEqual(RefreshReportLogic.classify("java.net.SocketTimeoutException: timeout"), .TIMEOUT)
        XCTAssertEqual(RefreshReportLogic.classify("Request timed out"), .TIMEOUT)
        XCTAssertEqual(RefreshReportLogic.classify("java.net.UnknownHostException: Unable to resolve host"), .OFFLINE)
        XCTAssertEqual(RefreshReportLogic.classify(ConnectionErrors.offlineMarker), .OFFLINE)
        XCTAssertEqual(RefreshReportLogic.classify("HttpCode: 429"), .RATE_LIMIT)
        XCTAssertEqual(RefreshReportLogic.classify("HTTP 418: banned"), .RATE_LIMIT)
        XCTAssertEqual(RefreshReportLogic.classify("HTTP 503"), .SERVER)
        XCTAssertEqual(RefreshReportLogic.classify("Parsed ticker has no data"), .NO_DATA)
        XCTAssertEqual(RefreshReportLogic.classify("Market unavailable"), .UNAVAILABLE)
        XCTAssertEqual(RefreshReportLogic.classify("Something odd"), .OTHER)
        XCTAssertEqual(RefreshReportLogic.classify(nil), .OTHER)
    }

    func testReasonIsMostFrequent() {
        XCTAssertNil(RefreshReportLogic.reason([]))
        XCTAssertEqual(RefreshReportLogic.reason(["timeout", "HTTP 500", "timed out"]), .TIMEOUT)
        // Gleichstand: frühere Art (Zeitüberschreitung vor Serverfehler)
        XCTAssertEqual(RefreshReportLogic.reason(["HTTP 500", "timeout"]), .TIMEOUT)
    }
}

/// Zeitbudget der Hintergrund-Aktualisierung (nur iOS): Endzeit, Stufen der Alarm-Daten,
/// Reihenfolge der Schritte nach dem Speichern, Sichtbarkeit von Mitteilungen.
final class RefreshDeadlineTests: XCTestCase {

    private let start: Int64 = 1_000_000_000

    func testAllowanceKeepsReserveAndCapsAtWanted() {
        let d = RefreshDeadline(endMillis: start + 25_000)
        XCTAssertEqual(d.remaining(now: start), 25_000)
        // Reserve fürs Speichern bleibt immer
        XCTAssertEqual(d.allowance(wanted: Int64.max, now: start), 25_000 - RefreshDeadline.saveReserveMillis)
        XCTAssertEqual(d.allowance(wanted: 15_000, now: start), 15_000)
        // Kurz vor dem Ende: nur noch der Rest, nie negativ
        XCTAssertEqual(d.allowance(wanted: 5_000, now: start + 21_000), 2_000)
        XCTAssertEqual(d.allowance(wanted: 5_000, now: start + 24_000), 0)
        XCTAssertEqual(d.allowance(wanted: 5_000, now: start + 40_000), 0)
    }

    func testAllows() {
        let d = RefreshDeadline(endMillis: start + 10_000)
        XCTAssertTrue(d.allows(8_000, now: start))
        XCTAssertFalse(d.allows(8_001, now: start))
        XCTAssertFalse(d.allows(1, now: start + 9_000))
    }

    func testNanosWithoutDeadlineIsUnchanged() {
        XCTAssertEqual(RefreshDeadline.nanos(wanted: 5_000, deadline: nil, now: start), 5_000_000_000)
        let d = RefreshDeadline(endMillis: start + 4_000)
        XCTAssertEqual(RefreshDeadline.nanos(wanted: 5_000, deadline: d, now: start), 2_000_000_000)
    }

    func testLeaseWaitIsCappedByDeadline() {
        XCTAssertEqual(PriceRefresher.leaseWaitSeconds(nil), PriceRefresher.alarmLeaseWaitSeconds)
        let d = RefreshDeadline(endMillis: start + 5_000)
        XCTAssertEqual(PriceRefresher.leaseWaitSeconds(d, now: start), 3, accuracy: 0.001)
        XCTAssertEqual(PriceRefresher.leaseWaitSeconds(d, now: start + 6_000), 0, accuracy: 0.001)
    }

    private func alarm(_ condition: AlarmCondition, currency: String? = nil) -> Alarm {
        Alarm(id: 1, watchId: 1, condition: condition, threshold: 1, currency: currency)
    }

    func testStageOfAlarm() {
        XCTAssertNil(AlarmDataStage.needed(for: alarm(.PRICE_ABOVE)))
        XCTAssertNil(AlarmDataStage.needed(for: alarm(.CHANGE_PERCENT_UP)))
        XCTAssertNil(AlarmDataStage.needed(for: alarm(.MOVE_PERCENT_WINDOW)))
        // Alarmwährung zählt nur bei Kursmarken
        XCTAssertEqual(AlarmDataStage.needed(for: alarm(.PRICE_BELOW, currency: "CHF")), .convert)
        XCTAssertNil(AlarmDataStage.needed(for: alarm(.CHANGE_PERCENT_DOWN, currency: "CHF")))
        XCTAssertEqual(AlarmDataStage.needed(for: alarm(.VOLUME_SPIKE)), .volume)
        XCTAssertEqual(AlarmDataStage.needed(for: alarm(.NEAR_HIGH)), .nearExtreme)
        XCTAssertEqual(AlarmDataStage.needed(for: alarm(.FUNDING_ABOVE)), .derivatives)
        XCTAssertEqual(AlarmDataStage.needed(for: alarm(.OI_DOWN)), .derivatives)
        XCTAssertTrue(AlarmDataStage.needsExtraData(alarm(.NEAR_LOW)))
        XCTAssertFalse(AlarmDataStage.needsExtraData(alarm(.PRICE_ABOVE)))
    }

    func testStagePlanSkipsStagesThatCannotStart() {
        let all = Set(AlarmDataStage.allCases)
        // Ohne Endzeit: alle
        XCTAssertEqual(AlarmDataStage.plan(all, allowanceMillis: nil), all)
        XCTAssertEqual(AlarmDataStage.plan(all, allowanceMillis: 10_000), all)
        // Knapp: nur die Umrechnung (kürzeste Mindestzeit)
        XCTAssertEqual(AlarmDataStage.plan(all, allowanceMillis: 2_500), [.convert])
        XCTAssertEqual(AlarmDataStage.plan(all, allowanceMillis: 0), [])
        // Nur benötigte Stufen
        XCTAssertEqual(AlarmDataStage.plan([.volume], allowanceMillis: 10_000), [.volume])
    }

    func testStepsAfterSavingRunOnlyWithTimeLeft() {
        let reserve = RefreshBudget.reserveMillis
        XCTAssertTrue(RefreshBudget.shouldRun(.extraAlarms, remaining: RefreshBudget.extraAlarmsMinMillis + reserve))
        XCTAssertFalse(RefreshBudget.shouldRun(.extraAlarms, remaining: RefreshBudget.extraAlarmsMinMillis + reserve - 1))
        XCTAssertTrue(RefreshBudget.shouldRun(.portfolio, remaining: RefreshBudget.portfolioMinMillis + reserve))
        XCTAssertFalse(RefreshBudget.shouldRun(.gas, remaining: RefreshBudget.sideCheckMinMillis + reserve - 1))
        // Portfolio (nur Kurse und Devisen) gleich nach den Kursalarmen, vor den Alarmen mit Zusatzdaten,
        // Aktivität und Gas; Alarme mit Zusatzdaten brauchen am wenigsten Zeit
        XCTAssertEqual(RefreshBudget.Step.allCases, [.portfolio, .extraAlarms, .zone, .activity, .gas])
        XCTAssertEqual(RefreshBudget.Step.zone.minMillis, RefreshBudget.sideCheckMinMillis)
        for step in RefreshBudget.Step.allCases {
            XCTAssertLessThanOrEqual(RefreshBudget.Step.extraAlarms.minMillis, step.minMillis)
        }
        // Kurse höchstens `networkMillis`, und danach bleibt Zeit für Speichern und Kursalarme
        XCTAssertLessThan(RefreshBudget.networkMillis + reserve, RefreshBudget.totalMillis)
        XCTAssertLessThan(RefreshBudget.totalMillis, 30_000)
    }

    func testPortfolioLeavesTimeForWaitingExtraAlarms() {
        let reserve = RefreshBudget.reserveMillis
        let extra = RefreshBudget.extraAlarmsMinMillis
        let minimum = RefreshBudget.portfolioMinMillis
        // Keine wartenden Zusatzdaten-Alarme: alles bis zur Reserve
        XCTAssertEqual(RefreshBudget.portfolioAllowance(remaining: 20_000, extraAlarmsWaiting: false), 20_000 - reserve)
        // Wartende: deren Mindestzeit bleibt übrig, danach beginnen sie noch
        let shared = RefreshBudget.portfolioAllowance(remaining: 20_000, extraAlarmsWaiting: true)
        XCTAssertEqual(shared, 20_000 - reserve - extra)
        XCTAssertTrue(RefreshBudget.shouldRun(.extraAlarms, remaining: 20_000 - shared))
        // Zu knapp für beide: das Portfolio (früherer Schritt) behält seine Zeit
        let tight = reserve + minimum + 1_000
        XCTAssertEqual(RefreshBudget.portfolioAllowance(remaining: tight, extraAlarmsWaiting: true), tight - reserve)
        XCTAssertEqual(RefreshBudget.portfolioAllowance(remaining: 0, extraAlarmsWaiting: true), 0)
    }

    func testPortfolioFxIsBoundedByStepEndAndDeadline() {
        let deadline = RefreshDeadline(endMillis: start + 20_000)
        // Viel Zeit: höchstens `portfolioFxMaxMillis`
        XCTAssertEqual(RefreshBudget.portfolioFxAllowance(portfolioEndMillis: start + 18_000, deadline: deadline, now: start),
                       RefreshBudget.portfolioFxMaxMillis)
        // Ende des Portfolio-Schritts kommt zuerst
        XCTAssertEqual(RefreshBudget.portfolioFxAllowance(portfolioEndMillis: start + 2_000, deadline: deadline, now: start),
                       2_000)
        // Endzeit samt Reserve kommt zuerst
        let late = start + 20_000 - RefreshDeadline.saveReserveMillis - 1_500
        XCTAssertEqual(RefreshBudget.portfolioFxAllowance(portfolioEndMillis: start + 30_000, deadline: deadline, now: late),
                       1_500)
        // Zu knapp oder vorbei: gar nicht abfragen (Zwischenspeicher)
        XCTAssertEqual(RefreshBudget.portfolioFxAllowance(portfolioEndMillis: start + 100, deadline: deadline, now: start), 0)
        XCTAssertEqual(RefreshBudget.portfolioFxAllowance(portfolioEndMillis: start - 1_000, deadline: deadline, now: start), 0)
    }

    func testBudgetDeadline() {
        let budget = RefreshBudget(start: start)
        XCTAssertEqual(budget.deadline.endMillis, start + RefreshBudget.totalMillis)
        XCTAssertEqual(budget.remaining(now: start + 1_000), RefreshBudget.totalMillis - 1_000)
    }

    func testRunWithinReturnsResultOrNilAfterTimeout() async {
        let fast = await RefreshDeadline.run(within: 1_000) { 42 }
        XCTAssertEqual(fast, 42)
        let slow = await RefreshDeadline.run(within: 50) { () async -> Int in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            return 7
        }
        XCTAssertNil(slow)
    }

    func testDeliverableNeedsVisibleSetting() {
        // Erlaubt und Banner an
        XCTAssertTrue(Notifier.isDeliverable(status: .authorized, alert: .enabled, lockScreen: .disabled, notificationCenter: .disabled))
        // Nur Mitteilungszentrale (z. B. vorläufig erlaubt)
        XCTAssertTrue(Notifier.isDeliverable(status: .provisional, alert: .disabled, lockScreen: .disabled, notificationCenter: .enabled))
        XCTAssertTrue(Notifier.isDeliverable(status: .authorized, alert: .disabled, lockScreen: .enabled, notificationCenter: .disabled))
        // Erlaubt, aber nichts Sichtbares (nur Ton): nicht zugestellt
        XCTAssertFalse(Notifier.isDeliverable(status: .authorized, alert: .disabled, lockScreen: .disabled, notificationCenter: .disabled))
        XCTAssertFalse(Notifier.isDeliverable(status: .authorized, alert: .notSupported, lockScreen: .disabled, notificationCenter: .disabled))
        // Nicht erlaubt
        XCTAssertFalse(Notifier.isDeliverable(status: .denied, alert: .enabled, lockScreen: .enabled, notificationCenter: .enabled))
        XCTAssertFalse(Notifier.isDeliverable(status: .notDetermined, alert: .enabled, lockScreen: .enabled, notificationCenter: .enabled))
    }
}

/// Ergebnis einer Abfrage nur auf dasselbe Paar übertragen: «Paar bearbeiten» oder eine Wiederherstellung mit
/// gleicher Id während der Abfrage → weder Kurs noch Alarm-Bezug des alten Paars in die Zeile.
final class OutcomePairIdentityTests: XCTestCase {
    private func watch(base: String) -> Watch {
        Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: base, quoteAsset: "USDT")
    }

    func testPriceOfOldPairIsDropped() {
        let old = watch(base: "BTC")
        var outcome = PriceRefresher.Outcome()
        outcome.prices[1] = PriceRefresher.PriceUpdate(price: 60_000, time: 1_000, error: nil, pair: old.identity)
        var alarm = Alarm(id: 7, watchId: 1, condition: .CHANGE_PERCENT_UP, threshold: 5)
        var after = alarm
        after.referencePrice = 60_000
        after.referenceAt = 1_000
        outcome.alarms[7] = PriceRefresher.AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)

        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = [watch(base: "ETH")]
        alarm.referencePrice = nil
        snapshot.alarms = [alarm]
        outcome.apply(to: &snapshot)
        XCTAssertNil(snapshot.watches[0].lastPrice)
        XCTAssertNil(snapshot.alarms[0].referencePrice)

        // Gleiches Paar: wie bisher übernommen
        var same = SharedStorage.Snapshot()
        same.watches = [old]
        same.alarms = [alarm]
        outcome.apply(to: &same)
        XCTAssertEqual(same.watches[0].lastPrice, 60_000)
        XCTAssertEqual(same.alarms[0].referencePrice, 60_000)
    }
}

/// Seit Build 102: Änderungen eines Durchlaufs nur bei gleicher Alarm-Währung übertragen (wie Android
/// `currency IS :currency`) — eine inzwischen auf USD gestellte EUR-Marke wird nicht wieder scharf.
final class OutcomeAlarmCurrencyTests: XCTestCase {
    func testRearmSkippedWhenCurrencyChanged() {
        var alarm = Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 50_000)
        alarm.currency = "EUR"
        alarm.referenceAt = 1_000
        var after = alarm
        after.referenceAt = 0
        let update = PriceRefresher.AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
        XCTAssertTrue(update.matches(alarm))
        var changed = alarm
        changed.currency = "USD"
        XCTAssertFalse(update.matches(changed))
        changed.currency = nil
        XCTAssertFalse(update.matches(changed))
    }
}
