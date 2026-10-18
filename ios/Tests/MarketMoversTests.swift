import XCTest
@testable import CryptoChecker

/// «Gewinner und Verlierer 24 h» aus `market_movers.json` (Android: `MarketMoversParityTest.kt`).
final class MarketMoversTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("market_movers") }

    private func coins(_ raw: Any?) -> [MoverCoin] {
        S.list(raw).map {
            MoverCoin(symbol: $0["symbol"] as? String ?? "", name: $0["name"] as? String ?? "",
                      rank: ($0["rank"] as? NSNumber)?.intValue, change24h: S.number($0["change"]))
        }
    }

    private func expected(_ raw: Any?) -> [String] {
        S.list(raw).map { "\($0["symbol"] as? String ?? "")|\($0["name"] as? String ?? "")|\(S.number($0["change"]) ?? .nan)" }
    }

    private func actual(_ list: [MoverCoin]) -> [String] {
        list.map { "\($0.symbol)|\($0.name)|\($0.change24h ?? .nan)" }
    }

    func testConstants() throws {
        let d = try data()
        XCTAssertEqual((d["universe"] as? NSNumber)?.intValue, MarketMovers.universe)
        XCTAssertEqual((d["count"] as? NSNumber)?.intValue, MarketMovers.count)
        XCTAssertEqual((d["minCoins"] as? NSNumber)?.intValue, MarketMovers.minCoins)
    }

    func testCases() throws {
        for c in S.list(try data()["cases"]) {
            let note = c["note"] as? String ?? ""
            let result = MarketMovers.select(coins(c["coins"]))
            XCTAssertEqual(actual(result.gainers), expected(c["gainers"]), "gainers: \(note)")
            XCTAssertEqual(actual(result.losers), expected(c["losers"]), "losers: \(note)")
            // Nie derselbe Coin in beiden Listen, nie mehr als `count`
            XCTAssertTrue(result.gainers.allSatisfy { g in !result.losers.contains { $0.symbol == g.symbol } }, note)
            XCTAssertTrue(result.gainers.count <= MarketMovers.count && result.losers.count <= MarketMovers.count, note)
        }
    }

    func testEmptyInput() {
        XCTAssertTrue(MarketMovers.select([]).isEmpty)
    }

    /// Gemeinsamer Stand (App Group): hin und zurück gleich; ältere Stände ohne «movers» = nil.
    func testStoredRoundTrip() throws {
        let first = try XCTUnwrap(S.list(try data()["cases"]).first)
        let movers = MarketMovers.select(coins(first["coins"]))
        let market = PulseMarketData(btc: 1, eth: -2, sol: 3, volumeRatio: nil, fundingPercent: nil, time: 5,
                                     topChanges: [1, -1], movers: movers)
        let raw = try JSONEncoder().encode(market)
        XCTAssertEqual(try JSONDecoder().decode(PulseMarketData.self, from: raw), market)
        let old = PulseMarketData(btc: 1, eth: -2, sol: 3, volumeRatio: nil, fundingPercent: nil, time: 5)
        var dict = try XCTUnwrap(try JSONSerialization.jsonObject(with: try JSONEncoder().encode(old)) as? [String: Any])
        dict.removeValue(forKey: "movers")
        let decoded = try JSONDecoder().decode(PulseMarketData.self, from: try JSONSerialization.data(withJSONObject: dict))
        XCTAssertNil(decoded.movers)
    }
}
