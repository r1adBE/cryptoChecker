import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `coin_logos.json` (Android: `CoinLogosParityTest.kt`) plus Zwischenspeicher.
final class CoinLogosTests: XCTestCase {

    private typealias S = TestSupport

    private func string(_ value: Any?) -> String? {
        value as? String
    }

    func testNormalize() throws {
        for c in S.list(try S.fixture("coin_logos")["normalize"]) {
            XCTAssertEqual(CoinLogos.normalize(c["symbol"] as? String ?? ""), string(c["expected"]), "normalize: \(c)")
        }
    }

    func testInitials() throws {
        for c in S.list(try S.fixture("coin_logos")["initials"]) {
            XCTAssertEqual(CoinLogos.initials(c["symbol"] as? String ?? ""), string(c["expected"]), "initials: \(c)")
        }
    }

    func testAllowedURL() throws {
        for c in S.list(try S.fixture("coin_logos")["allowedUrl"]) {
            XCTAssertEqual(CoinLogos.isAllowedURL(string(c["url"])), c["expected"] as? Bool, "allowed: \(c)")
        }
    }

    func testSmallURL() throws {
        for c in S.list(try S.fixture("coin_logos")["smallUrl"]) {
            XCTAssertEqual(CoinLogos.smallURL(c["url"] as? String ?? ""), string(c["expected"]), "small: \(c)")
        }
    }

    func testMarket() throws {
        for c in S.list(try S.fixture("coin_logos")["market"]) {
            XCTAssertEqual(CoinLogos.allowed(forMarket: string(c["marketKey"])), c["expected"] as? Bool, "market: \(c)")
        }
    }

    func testFileName() throws {
        for c in S.list(try S.fixture("coin_logos")["fileName"]) {
            XCTAssertEqual(CoinLogos.fileName(c["symbol"] as? String ?? ""), string(c["expected"]), "fileName: \(c)")
        }
    }

    func testPickFirstOccurrenceWins() throws {
        let pick = try XCTUnwrap(try S.fixture("coin_logos")["pick"] as? [String: Any])
        let ranked = S.list(pick["ranked"]).map { (symbol: $0["symbol"] as? String ?? "", image: string($0["image"])) }
        let expected = try XCTUnwrap(pick["expected"] as? [String: String])
        var map: [String: String] = [:]
        var order: [String] = []
        CoinLogos.pick(ranked, into: &map, order: &order)
        XCTAssertEqual(map, expected)
        // Reihenfolge = Rangliste
        XCTAssertEqual(order, ["BTC", "ETH", "UNI", "BAD"])
    }

    func testBinanceFillsOnlyGaps() throws {
        let data = try S.fixture("coin_logos")
        let pick = try XCTUnwrap(data["pick"] as? [String: Any])
        let fill = try XCTUnwrap(data["fill"] as? [String: Any])
        var map: [String: String] = [:]
        var order: [String] = []
        CoinLogos.pick(S.list(pick["ranked"]).map { (symbol: $0["symbol"] as? String ?? "", image: string($0["image"])) },
                       into: &map, order: &order)
        CoinLogos.pick(S.list(fill["binance"]).map { (symbol: $0["symbol"] as? String ?? "", image: string($0["image"])) },
                       into: &map, order: &order)
        XCTAssertEqual(map, try XCTUnwrap(fill["expected"] as? [String: String]))
        XCTAssertEqual(order, ["BTC", "ETH", "UNI", "BAD", "XAU", "TSLA"])
    }

    func testEncodeDecodeRoundTripDropsInvalidLines() {
        let map = [
            "BTC": "https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png",
            "ETH": "https://coin-images.coingecko.com/coins/images/279/large/ethereum.png",
        ]
        let text = CoinLogos.encode(map, order: ["BTC", "ETH"])
        XCTAssertEqual(CoinLogos.decode(text), map)
        let broken = text + "\nXRP\thttp://insecure.example/x.png\n\tno-symbol\nDOGE"
        XCTAssertEqual(CoinLogos.decode(broken), map)
        XCTAssertTrue(CoinLogos.decode(nil).isEmpty)
        XCTAssertTrue(CoinLogos.decode("  ").isEmpty)
    }

    func testFreshness() {
        let now: Int64 = 10 * CoinLogos.mapTTLMillis
        XCTAssertTrue(CoinLogos.isFresh(savedAt: now - 1_000, now: now))
        XCTAssertFalse(CoinLogos.isFresh(savedAt: now - CoinLogos.mapTTLMillis, now: now))
        XCTAssertFalse(CoinLogos.isFresh(savedAt: 0, now: now))
        // Uhr zurückgestellt: nicht frisch, neu holen
        XCTAssertFalse(CoinLogos.isFresh(savedAt: now + 1_000, now: now))
    }
}
