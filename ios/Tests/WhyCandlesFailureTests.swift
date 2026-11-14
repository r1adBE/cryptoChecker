import XCTest
@testable import CryptoChecker

/// V4-02: «Was bewegt sich mit?» ohne Kerzen – offline bzw. Zeitgrenze ist «nicht abrufbar» (mit «Erneut versuchen»),
/// nur sonst «keine Stundenkerzen für dieses Paar». Android: WhyCandlesFailureTest.
final class WhyCandlesFailureTests: XCTestCase {
    func testOfflineGehtVor() {
        XCTAssertEqual(WhyCandlesFailure.of(online: false, timedOut: false), .offline)
        XCTAssertEqual(WhyCandlesFailure.of(online: false, timedOut: true), .offline)
    }

    func testZeitgrenzeIstNichtAbrufbar() {
        XCTAssertEqual(WhyCandlesFailure.of(online: true, timedOut: true), .timeout)
    }

    func testGeantwortetOhneKerzenIstKeinFehler() {
        XCTAssertNil(WhyCandlesFailure.of(online: true, timedOut: false))
        XCTAssertNil(ActivityAnalyzer.notLive(now: 1).candlesFailure)
    }

    /// Leerzustand nennt die Börse des Paars statt «Binance» und bietet bei «nicht abrufbar» «Erneut versuchen»,
    /// das neu fragt (`refresh`) statt den gemerkten Leerstand zu liefern.
    func testLeerzustandMitBoerseUndErneutVersuchen() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let sheet = try String(contentsOf: root.appendingPathComponent("App/Features/Watchlist/WatchWhySheet.swift"),
                               encoding: .utf8)
        XCTAssertTrue(sheet.contains("WatchlistWhyEmpty(failure: r.candlesFailure, exchange: watch.exchangeName,"))
        XCTAssertTrue(sheet.contains("data.explain(watch, refresh: attempt > 0)"))
        XCTAssertFalse(sheet.contains("WatchlistWhyEmpty(onRetry: nil)"))
        let monitor = try String(contentsOf: root.appendingPathComponent("Shared/Activity/ActivityMonitor.swift"),
                                 encoding: .utf8)
        XCTAssertTrue(monitor.contains("limit: count, refresh: refresh)"))
        XCTAssertTrue(monitor.contains("WhyCandlesFailure.of(online: NetworkStatus.shared.isOnlineFresh, timedOut: candlesFailed)"))
    }
}
