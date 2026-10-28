import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `spot_market.json` (Android: `SpotMarketParityTest.kt`).
final class SpotMarketTests: XCTestCase {

    private typealias S = TestSupport
    private let us = Locale(identifier: "en_US")

    private func assertNum(_ actual: Double?, _ expected: Any?, _ message: String) {
        let e = S.number(expected)
        S.assertNumber(actual, e, accuracy: 1e-9 * Swift.max(1, abs(e ?? 0)), message)
    }

    func testConstants() throws {
        let data = try S.fixture("spot_market")
        XCTAssertEqual(SpotMarket.topURL, data["topUrl"] as? String)
        XCTAssertEqual(SpotMarket.cacheTTLMillis, (data["cacheTtlMillis"] as? NSNumber)?.int64Value)
        // CoinGecko-Daten nie über den Spiegel (API-Bedingungen): direkt beim Anbieter
        XCTAssertNil(DataMirror.entry(for: SpotMarket.topURL))
    }

    func testParse() throws {
        let data = try S.fixture("spot_market")
        let parsed = SpotMarket.parse(data["body"] as? String)
        let expected = S.list(data["parsed"])
        XCTAssertEqual(parsed.count, expected.count)
        for (e, a) in zip(expected, parsed) {
            let m = "entry \(e["id"] ?? "")"
            XCTAssertEqual(a.id, e["id"] as? String, m)
            XCTAssertEqual(a.symbol, e["symbol"] as? String, m)
            XCTAssertEqual(a.name, e["name"] as? String, m)
            assertNum(a.priceUsd, e["priceUsd"], "\(m) price")
            assertNum(a.marketCap, e["marketCap"], "\(m) mcap")
            assertNum(a.marketCapChange24h, e["marketCapChange24h"], "\(m) change")
            assertNum(a.volume24h, e["volume24h"], "\(m) volume")
            assertNum(a.fdv, e["fdv"], "\(m) fdv")
            assertNum(a.circulatingSupply, e["circulatingSupply"], "\(m) circ")
            assertNum(a.totalSupply, e["totalSupply"], "\(m) total")
            assertNum(a.maxSupply, e["maxSupply"], "\(m) max")
            XCTAssertEqual(a.lastUpdated, (e["lastUpdated"] as? NSNumber)?.int64Value, m)
        }
        for body in (data["parseInvalid"] as? [Any]) ?? [] {
            XCTAssertTrue(SpotMarket.parse(body as? String).isEmpty, "invalid: \(body)")
        }
    }

    func testPick() throws {
        let data = try S.fixture("spot_market")
        let parsed = SpotMarket.parse(data["body"] as? String)
        for c in S.list(data["pick"]) {
            XCTAssertEqual(SpotMarket.pick(parsed, symbol: c["symbol"] as? String ?? "")?.id,
                           c["expected"] as? String, "pick: \(c)")
        }
    }

    func testRatios() throws {
        let data = try S.fixture("spot_market")
        for c in S.list(data["volumeToMarketCap"]) {
            assertNum(SpotMarket.volumeToMarketCap(volume: S.number(c["volume"]), marketCap: S.number(c["marketCap"])),
                      c["expected"], "vol/mcap: \(c)")
        }
        for c in S.list(data["supplyRatio"]) {
            assertNum(SpotMarket.supplyRatio(circulating: S.number(c["circulating"]), max: S.number(c["max"])),
                      c["expected"], "supply: \(c)")
        }
    }

    func testFormatting() throws {
        let data = try S.fixture("spot_market")
        for c in S.list(data["compact"]) {
            XCTAssertEqual(SpotMarket.compact(S.number(c["value"]) ?? .nan, locale: us), c["expected"] as? String, "compact: \(c)")
        }
        for c in S.list(data["usd"]) {
            XCTAssertEqual(SpotMarket.usd(S.number(c["value"]), locale: us), c["expected"] as? String, "usd: \(c)")
        }
        for c in S.list(data["supply"]) {
            XCTAssertEqual(SpotMarket.supply(S.number(c["value"]), symbol: c["symbol"] as? String ?? "", locale: us),
                           c["expected"] as? String, "supply: \(c)")
        }
        for c in S.list(data["percent"]) {
            XCTAssertEqual(SpotMarket.percent(S.number(c["value"]), locale: us), c["expected"] as? String, "percent: \(c)")
        }
    }

    func testSymbolsAndEligibility() throws {
        let data = try S.fixture("spot_market")
        for c in S.list(data["lookupSymbol"]) {
            XCTAssertEqual(SpotMarket.lookupSymbol(c["base"] as? String ?? ""), c["expected"] as? String, "lookup: \(c)")
        }
        for c in S.list(data["url"]) {
            XCTAssertEqual(SpotMarket.url(c["symbol"] as? String ?? ""), c["expected"] as? String)
        }
        for c in S.list(data["eligible"]) {
            XCTAssertEqual(SpotMarket.eligible(exchangeKey: c["exchangeKey"] as? String, tradFi: c["tradFi"] as? Bool ?? false),
                           c["expected"] as? Bool, "eligible: \(c)")
        }
    }

    func testIsoMillis() {
        XCTAssertEqual(SpotMarket.isoMillis("2026-10-11T12:34:56.789Z"), 1_791_722_096_789)
        XCTAssertEqual(SpotMarket.isoMillis("2026-10-11T12:30:00Z"), 1_791_721_800_000)
        XCTAssertNil(SpotMarket.isoMillis("garbage"))
        XCTAssertNil(SpotMarket.isoMillis(nil))
    }
}
