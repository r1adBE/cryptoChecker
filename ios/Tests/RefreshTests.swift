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
