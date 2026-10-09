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

    func testTradFiNeverFromCoinGecko() throws {
        let t = try XCTUnwrap(try S.fixture("coin_logos")["tradFi"] as? [String: Any])
        let crypto = Set(try XCTUnwrap(t["crypto"] as? [String]))
        let entries = S.list(t["binance"]).map {
            CoinLogos.BinanceEntry(name: $0["name"] as? String ?? "", logo: string($0["logo"]),
                                   tags: $0["tags"] as? [String] ?? [], onlyFutures: $0["onlyFutures"] as? Bool ?? false,
                                   fullName: string($0["fullName"]))
        }
        var names: [String: String] = [:]
        var nameOrder: [String] = []
        CoinLogos.pickTradFiNames(entries, crypto: crypto, into: &names, order: &nameOrder)
        XCTAssertEqual(names, try XCTUnwrap(t["expectedNames"] as? [String: String]))
        var map: [String: String] = [:]
        var order: [String] = []
        CoinLogos.pickTradFi(entries, crypto: crypto, into: &map, order: &order)
        XCTAssertEqual(map, try XCTUnwrap(t["expected"] as? [String: String]))
        XCTAssertEqual(order, try XCTUnwrap(t["order"] as? [String]))
        XCTAssertEqual(CoinLogos.logoKey("nvda", tradFi: true), "TRADFI:NVDA")
        XCTAssertEqual(CoinLogos.logoKey("nvda", tradFi: false), "nvda")
        XCTAssertEqual(CoinLogos.pairKey(marketKey: "BinanceFutures", base: "nvda", quote: "usdt", contractType: "PERPETUAL"),
                       "BinanceFutures|NVDA|USDT|PERPETUAL")
    }

    func testNames() throws {
        let data = try S.fixture("coin_logos")
        for c in S.list(data["cleanName"]) {
            XCTAssertEqual(CoinLogos.cleanName(string(c["raw"]), symbol: string(c["symbol"])), string(c["expected"]), "cleanName: \(c)")
        }
        let n = try XCTUnwrap(data["names"] as? [String: Any])
        var map: [String: String] = [:]
        var order: [String] = []
        CoinLogos.pickNames(S.list(n["ranked"]).map { (symbol: $0["symbol"] as? String ?? "", name: string($0["name"])) },
                            into: &map, order: &order)
        XCTAssertEqual(map, try XCTUnwrap(n["expected"] as? [String: String]))
        let names = ["BTC": "Bitcoin", "TRADFI:NVDA": "NVIDIA"]
        XCTAssertEqual(CoinLogos.decodeNames(CoinLogos.encodeNames(names, order: ["BTC", "TRADFI:NVDA"]) + "\nBROKEN\n\tNoKey"), names)
        XCTAssertEqual(CoinLogos.nameKey("nvda", tradFi: true), "TRADFI:NVDA")
        XCTAssertEqual(CoinLogos.nameKey("1000PEPE", tradFi: false), "PEPE")
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
        // TradFi-Zeilen bleiben erhalten
        var withTradFi = map
        withTradFi["TRADFI:NVDA"] = "https://bin.bnbstatic.com/image/nvdab.png"
        XCTAssertEqual(CoinLogos.decode(CoinLogos.encode(withTradFi, order: ["BTC", "ETH", "TRADFI:NVDA"])), withTradFi)
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
