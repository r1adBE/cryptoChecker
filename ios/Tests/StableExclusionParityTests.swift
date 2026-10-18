import XCTest
@testable import CryptoChecker

/// Stablecoins und Doppelgänger aus `stable_exclusion.json` (Android: `StableExclusionParityTest.kt`).
final class StableExclusionParityTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("stable_exclusion") }

    /// Beide Plattformen führen genau dieselben Listen.
    func testListsMatchFixture() throws {
        let d = try data()
        XCTAssertEqual(Set(d["stablecoins"] as? [String] ?? []), CoinExclusion.stablecoins)
        XCTAssertEqual(Set(d["wrapped"] as? [String] ?? []), CoinExclusion.wrapped)
    }

    /// Jeder Eintrag der Listen gilt als ausgeschlossen, auch klein geschrieben und ohne Namen.
    func testEveryListedSymbolIsExcluded() {
        for symbol in CoinExclusion.stablecoins.union(CoinExclusion.wrapped) {
            XCTAssertTrue(CoinExclusion.isExcluded(symbol: symbol, name: ""), symbol)
            XCTAssertTrue(CoinExclusion.isExcluded(symbol: symbol.lowercased(), name: ""), symbol)
        }
    }

    func testCases() throws {
        let coins = S.list(try data()["coins"])
        XCTAssertGreaterThanOrEqual(coins.count, 40)
        for c in coins {
            let symbol = c["symbol"] as? String ?? ""
            let name = c["name"] as? String ?? ""
            let expected = try XCTUnwrap(c["excluded"] as? Bool)
            XCTAssertEqual(CoinExclusion.isExcluded(symbol: symbol, name: name), expected,
                           "\(c["id"] ?? "") (\(symbol) / \(name))")
        }
    }

    /// Gewinner und Verlierer: ausgeschlossene Coins tauchen nie auf.
    func testMoversHonourTheSameRule() throws {
        let raw = S.list(try data()["coins"])
            .filter { !($0["symbol"] as? String ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
        let coins = raw.enumerated().map { i, c in
            MoverCoin(symbol: c["symbol"] as? String ?? "", name: c["name"] as? String ?? "", rank: i + 1,
                      change24h: i % 2 == 0 ? Double(i + 1) : -Double(i + 1))
        }
        let result = MarketMovers.select(coins)
        for coin in result.gainers + result.losers {
            XCTAssertFalse(CoinExclusion.isExcluded(symbol: coin.symbol, name: coin.name), coin.symbol)
        }
    }
}
