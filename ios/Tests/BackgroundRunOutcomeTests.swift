import XCTest
@testable import CryptoChecker

/// «Letzte Prüfungen» ehrlich (Audit A-01, früher Quelltext-Wächter): pausiert, offline und Budget-Lücken als Verhalten;
/// dazu, wann die Kurs-Mitteilung eines Paars verschwindet (`PriceRefresher.evaluate`).
/// Gegenstück Android: `BackgroundRunLogTest.kt`, `ExactFiredStepTest.kt` (Gerät), `PriceFetcherOrderTest.kt`.
final class BackgroundRunOutcomeTests: XCTestCase {
    private typealias Fetched = PriceRefresher.Fetched

    private func watch(_ id: Int64) -> Watch {
        Watch(id: id, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "C\(id)", quoteAsset: "USDT")
    }

    private func priced(_ price: Double) -> Fetched {
        var ticker = Ticker()
        ticker.last = price
        return Fetched(ticker: ticker, error: nil)
    }

    private var run: PriceRefresher.Evaluation {
        PriceRefresher.Evaluation(now: 1_700_000_000_000, settings: AppSettings(), speechAllowed: false, live: false,
                                  basis: .ROLLING_24H, dayStart: nil, dayReferences: [:], deferExtra: false,
                                  alarmsByWatch: [:], logAlarmsByWatch: [:], inputs: PriceRefresher.AlarmInputs())
    }

    private func outcome(_ results: [(Watch, Fetched?)]) -> PriceRefresher.Outcome { evaluated(results).outcome }

    private func evaluated(_ results: [(Watch, Fetched?)]) -> (outcome: PriceRefresher.Outcome, cancelIds: [Int64]) {
        var outcome = PriceRefresher.Outcome()
        var cancel: [Int64] = []
        var posts: [Watch] = []
        let evaluation = run
        for (w, result) in results {
            PriceRefresher.evaluate(w, result: result, run: evaluation, outcome: &outcome, cancelIds: &cancel, pricePosts: &posts)
        }
        return (outcome, cancel)
    }

    /// N3-01: Kurs-Mitteilung nur bei «nicht gehandelt»/stillgelegt entfernen, nicht bei jedem Fehler (Zeit, 5xx, Netz).
    func testPriceNotificationStaysOnOrdinaryFailure() {
        let failed = evaluated([(watch(1), Fetched(ticker: nil, error: "HTTP 503")), (watch(2), PriceRefresher.budgetSpent(
            [:], partial: [:], groups: ["Binance": [watch(2)]])[2])])
        XCTAssertEqual(failed.cancelIds, [])
        XCTAssertEqual(failed.outcome.failed, 2)
        let gone = evaluated([(watch(3), Fetched(ticker: nil, error: NotTraded.marker, notTraded: true))])
        XCTAssertEqual(gone.cancelIds, [3])
        XCTAssertEqual(gone.outcome.notTraded, 1)
        XCTAssertEqual(gone.outcome.failed, 0)
    }

    func testPricesAreOkAndAFullCheck() {
        let o = outcome([(watch(1), priced(100)), (watch(2), priced(200))])
        XCTAssertEqual(o.checkResult, .ok)
        XCTAssertTrue(o.isFullCheck)
        XCTAssertTrue(o.isFullCheck(expired: false))
        XCTAssertFalse(o.isFullCheck(expired: true), "abgelaufen zählt nie als volle Prüfung")
    }

    func testBudgetCutCountsAndIsNoFullCheck() {
        let spent = PriceRefresher.budgetSpent([:], partial: [:], groups: ["Binance": [watch(1), watch(2)]])
        let o = outcome([(watch(1), priced(100)), (watch(2), spent[2])])
        XCTAssertEqual(o.cutByBudget, 1)
        XCTAssertFalse(o.isFullCheck, "Budget-Lücke: keine volle Prüfung")
    }

    func testBudgetKeepsFinishedAndPartialPrices() {
        let groups = ["Binance": [watch(1), watch(2)], "Kraken": [watch(3)]]
        let spent = PriceRefresher.budgetSpent([1: priced(1)], partial: [2: priced(2), 1: priced(9)], groups: groups)
        XCTAssertEqual(spent[1]?.ticker?.last, 1, "fertige Börse bleibt, das Teilergebnis überschreibt sie nicht")
        XCTAssertEqual(spent[2]?.ticker?.last, 2, "schon geholter Kurs einer laufenden Börse bleibt")
        XCTAssertEqual(spent[3]?.budgetCut, true)
        XCTAssertNil(spent[3]?.ticker)
        XCTAssertEqual(spent.count, 3)
    }

    func testPausedAndOfflineAreLoggedHonestly() {
        var paused = outcome([(watch(1), Fetched(ticker: nil, error: nil, skipped: true))])
        XCTAssertEqual(paused.checkResult, .paused)
        XCTAssertFalse(paused.isFullCheck)
        paused.alarmsUnchecked = true
        XCTAssertFalse(paused.isFullCheck)
        var offline = PriceRefresher.Outcome()
        offline.skippedOffline = true
        XCTAssertEqual(offline.checkResult, .skipped)
        var noLease = outcome([(watch(1), priced(100))])
        noLease.alarmsUnchecked = true
        XCTAssertFalse(noLease.isFullCheck, "Alarme ohne Lease: keine volle Prüfung")
    }
}
