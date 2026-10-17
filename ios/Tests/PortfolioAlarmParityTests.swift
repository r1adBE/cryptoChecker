import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/portfolio_alarm.json` — wie `PortfolioAlarmParityTest.kt`.
final class PortfolioAlarmParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("portfolio_alarm") }

    /// Alarm in einer anderen Währung als der Stand: USDT zum Marktkurs wie der Portfolio-Kopf.
    func testTotalIn() throws {
        for c in TestSupport.list(try data()["totalIn"]) {
            let r = c["reading"] as? [String: Any] ?? [:]
            var usdTo: [String: Double] = [:]
            for (code, rate) in r["usdTo"] as? [String: Any] ?? [:] {
                if let v = TestSupport.number(rate) { usdTo[code] = v }
            }
            let reading = PortfolioReading(total: TestSupport.number(r["total"]) ?? 0,
                                           currency: r["currency"] as? String ?? "",
                                           totalUsdt: TestSupport.number(r["totalUsdt"]), changePercent: nil, empty: false,
                                           usdTo: usdTo, usdtUsdLive: TestSupport.number(r["usdtUsdLive"]))
            let actual = PortfolioAlarmLogic.total(in: c["currency"] as? String, reading)
            let name = c["name"] as? String ?? ""
            if let expected = TestSupport.number(c["expected"]) {
                XCTAssertEqual(actual ?? .nan, expected, accuracy: 1e-6, name)
            } else {
                XCTAssertNil(actual, name)
            }
        }
    }
}
