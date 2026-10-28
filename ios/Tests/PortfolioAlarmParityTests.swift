import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/portfolio_alarm.json` — wie `PortfolioAlarmParityTest.kt`.
final class PortfolioAlarmParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("portfolio_alarm") }

    /// «fällt um» nur unter 100 %; aus einer Sicherung ≥ 100 % übernommen, aber aus; die
    /// einmalige Bereinigung schaltet genau diese aus.
    func testThreshold() throws {
        let block = try XCTUnwrap(try data()["threshold"] as? [String: Any])
        XCTAssertEqual(PortfolioAlarmLogic.maxFallPercent, TestSupport.number(block["maxFallPercent"]))
        let cases = TestSupport.list(block["cases"])
        XCTAssertEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let kind = try XCTUnwrap(PortfolioAlarmKind(rawValue: c["kind"] as? String ?? ""), name)
            let threshold = TestSupport.number(c["threshold"]) ?? .nan
            XCTAssertEqual(PortfolioAlarmLogic.isValidThreshold(kind, threshold), c["valid"] as? Bool, "\(name): gültig")
            XCTAssertEqual(PortfolioAlarmLogic.exceedsFall(kind, threshold), c["exceedsFall"] as? Bool, "\(name): fällt ≥ 100")
            let json: [String: Any] = ["id": 1, "kind": kind.rawValue, "threshold": threshold,
                                       "currency": kind.isValue ? "CHF" : NSNull(), "enabled": true, "repeating": false]
            let restored = try BackupManager.jsonToPortfolioAlarm(json)
            XCTAssertEqual(restored != nil, c["restored"] as? Bool, "\(name): übernommen")
            XCTAssertEqual(restored?.enabled ?? false, c["restoredEnabled"] as? Bool, "\(name): danach an")
        }
    }

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

    /// Unvollständiger Stand: Adressen, Wesentlichkeit alter Kurse, Hinweis «pausiert».
    func testIncomplete() throws {
        let block = try XCTUnwrap(try data()["incomplete"] as? [String: Any])
        XCTAssertEqual(PortfolioAlarmLogic.immaterialShare, TestSupport.number(block["immaterialShare"]))
        func int(_ v: Any?) -> Int { Int(TestSupport.number(v) ?? 0) }
        for c in TestSupport.list(block["cases"]) {
            let r = c["reading"] as? [String: Any] ?? [:]
            var reading = PortfolioReading(total: 0, currency: "USDT", totalUsdt: TestSupport.number(r["totalUsdt"]),
                                           changePercent: 1, empty: false)
            reading.missingPrices = int(r["missingPrices"])
            reading.todaySkipped = int(r["todaySkipped"])
            reading.stalePrices = int(r["stalePrices"])
            reading.staleValueUsdt = TestSupport.number(r["staleValueUsdt"])
            reading.addressesPending = int(r["addressesPending"])
            reading.addressesFailing = r["addressesFailing"] as? [String] ?? []
            reading.staleTokenAddresses = r["staleTokenAddresses"] as? [String] ?? []
            reading.staleTokenValueUsdt = TestSupport.number(r["staleTokenValueUsdt"]) ?? 0
            reading.unverifiedCoins = r["unverifiedCoins"] as? [String] ?? []
            reading.unverifiedValueUsdt = TestSupport.number(r["unverifiedValueUsdt"]) ?? 0
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(PortfolioAlarmLogic.incomplete(.VALUE_BELOW, reading), c["value"] as? Bool, "Wert: \(name)")
            XCTAssertEqual(PortfolioAlarmLogic.incomplete(.VALUE_ABOVE, reading), c["value"] as? Bool, "Wert darüber: \(name)")
            XCTAssertEqual(PortfolioAlarmLogic.incomplete(.CHANGE_UP, reading), c["change"] as? Bool, "Veränderung: \(name)")
            let kinds = (c["kinds"] as? [String] ?? []).compactMap { PortfolioAlarmKind(rawValue: $0) }
            let pause = PortfolioAlarmLogic.pause(kinds, reading, missingCoins: c["missingCoins"] as? [String] ?? [],
                                                  staleCoins: c["staleCoins"] as? [String] ?? [])
            let expected: PortfolioAlarmPause?
            if c["pause"] as? String == "addresses" {
                expected = .addressesPending(int(r["addressesPending"]))
            } else if let coins = c["pause"] as? [String] {
                expected = .noPrice(coins)
            } else if let p = c["pause"] as? [String: Any] {
                // {"tokens":[…]} bzw. {"failing":[…]} bzw. {"unverified":[…]}
                if let tokens = p["tokens"] as? [String] { expected = .tokensStale(tokens) }
                else if let coins = p["unverified"] as? [String] { expected = .priceUnverified(coins) }
                else { expected = .addressesFailing(p["failing"] as? [String] ?? []) }
            } else {
                expected = nil
            }
            XCTAssertEqual(pause, expected, "pause: \(name)")
        }
    }
}
