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

    func testStockLogos() throws {
        let data = try S.fixture("coin_logos")
        for c in S.list(data["stockLogo"]) {
            XCTAssertEqual(CoinLogos.stockLogoURL(string(c["symbol"]) ?? ""), string(c["expected"]), "stockLogo: \(c)")
        }
        let w = try XCTUnwrap(data["withStockLogos"] as? [String: Any])
        let map = try XCTUnwrap(w["map"] as? [String: String])
        let bases = Set(try XCTUnwrap(w["bases"] as? [String]))
        XCTAssertEqual(CoinLogos.withStockLogos(map, tradFiBases: bases), try XCTUnwrap(w["expected"] as? [String: String]))
    }

    func testStockNames() throws {
        let data = try S.fixture("coin_logos")
        for c in S.list(data["cleanStockName"]) {
            XCTAssertEqual(CoinLogos.cleanStockName(string(c["raw"])), string(c["expected"]), "cleanStockName: \(c)")
        }
        let d = try XCTUnwrap(data["symbolDirectory"] as? [String: Any])
        var parsed: [String: String] = [:]
        CoinLogos.parseSymbolDirectory(string(d["other"]), into: &parsed)
        CoinLogos.parseSymbolDirectory(string(d["nasdaq"]), into: &parsed)
        XCTAssertEqual(parsed, try XCTUnwrap(d["expectedOtherFirst"] as? [String: String]))
        let w = try XCTUnwrap(data["withStockNames"] as? [String: Any])
        XCTAssertEqual(CoinLogos.withStockNames(try XCTUnwrap(w["names"] as? [String: String]),
                                                stockNames: try XCTUnwrap(w["stockNames"] as? [String: String]),
                                                tradFiBases: Set(try XCTUnwrap(w["bases"] as? [String]))),
                       try XCTUnwrap(w["expected"] as? [String: String]))
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
    func testAlphaList() throws {
        let a = try XCTUnwrap(try S.fixture("coin_logos")["alpha"] as? [String: Any])
        let entries = S.list(a["entries"]).map {
            CoinLogos.AlphaEntry(symbol: $0["symbol"] as? String ?? "", name: string($0["name"]),
                                 icon: string($0["iconUrl"]),
                                 marketCap: string($0["marketCap"]).flatMap { Double($0) },
                                 offline: $0["offline"] as? Bool ?? false)
        }
        let ranked = CoinLogos.rankAlpha(entries)
        XCTAssertEqual(ranked.map(\.symbol), try XCTUnwrap(a["expectedOrder"] as? [String]))
        var map = try XCTUnwrap(a["known"] as? [String: String])
        var order = Array(map.keys)
        CoinLogos.pick(ranked.map { (symbol: $0.symbol, image: $0.icon) }, into: &map, order: &order)
        XCTAssertEqual(map, try XCTUnwrap(a["expected"] as? [String: String]))
        var names: [String: String] = [:]
        var nameOrder: [String] = []
        CoinLogos.pickNames(ranked.map { (symbol: $0.symbol, name: $0.name) }, into: &names, order: &nameOrder)
        XCTAssertEqual(names, try XCTUnwrap(a["expectedNames"] as? [String: String]))
    }

    func testPartialList() throws {
        let p = try XCTUnwrap(try S.fixture("coin_logos")["partial"] as? [String: Any])
        var map = try XCTUnwrap(p["fresh"] as? [String: String])
        var order = map.keys.sorted()
        CoinLogos.withKnown(&map, order: &order, known: try XCTUnwrap(p["known"] as? [String: String]))
        XCTAssertEqual(map, try XCTUnwrap(p["expected"] as? [String: String]))
        XCTAssertEqual(Set(order), Set(map.keys))
        for c in S.list(p["rateLimit"]) {
            let retry = (c["retryAfter"] as? NSNumber)?.int64Value
            XCTAssertEqual(CoinLogos.rateLimitWaitMillis(retryAfterSeconds: retry), (c["expected"] as? NSNumber)?.int64Value, "rateLimit: \(c)")
        }
        let now: Int64 = 10 * CoinLogos.mapTTLMillis
        XCTAssertEqual(CoinLogos.savedAt(now: now, complete: true), now)
        let partial = CoinLogos.savedAt(now: now, complete: false)
        XCTAssertTrue(CoinLogos.isFresh(savedAt: partial, now: now + CoinLogos.partialTTLMillis - 1))
        XCTAssertFalse(CoinLogos.isFresh(savedAt: partial, now: now + CoinLogos.partialTTLMillis))
    }
    func testGithubIndex() throws {
        let x = try XCTUnwrap(try S.fixture("coin_logos")["index"] as? [String: Any])
        let index = try XCTUnwrap(CoinLogos.parseIndex(x["text"] as? String))
        XCTAssertEqual(index.logos, try XCTUnwrap(x["expectedLogos"] as? [String: String]))
        XCTAssertEqual(index.names, try XCTUnwrap(x["expectedNames"] as? [String: String]))
        for text in try XCTUnwrap(x["invalid"] as? [String]) {
            XCTAssertNil(CoinLogos.parseIndex(text), "invalid: \(text)")
        }
        XCTAssertNil(CoinLogos.parseIndex(nil))
        XCTAssertTrue(CoinLogos.isFromIndex(index.logos))
        XCTAssertFalse(CoinLogos.isFromIndex(["BTC": "https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png"]))
        let c = try XCTUnwrap(x["changed"] as? [String: Any])
        XCTAssertEqual(CoinLogos.changedKeys(old: try XCTUnwrap(c["old"] as? [String: String]),
                                             new: try XCTUnwrap(c["new"] as? [String: String])),
                       Set(try XCTUnwrap(c["expected"] as? [String])))
    }
    func testPack() throws {
        let x = try XCTUnwrap(try S.fixture("coin_logos")["pack"] as? [String: Any])
        let data = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(x["base64"] as? String)))
        let pack = try XCTUnwrap(CoinLogos.parsePack(data))
        let expected = try XCTUnwrap(x["expected"] as? [String: String]).mapValues { Data(base64Encoded: $0) ?? Data() }
        XCTAssertEqual(pack, expected)
        for text in try XCTUnwrap(x["invalidBase64"] as? [String]) {
            XCTAssertNil(CoinLogos.parsePack(Data(base64Encoded: text) ?? Data()))
        }
    }
}
