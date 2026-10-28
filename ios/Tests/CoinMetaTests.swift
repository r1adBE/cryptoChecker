import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `coin_meta.json` (Android: `CoinMetaParityTest.kt`): ein ganzer Abgleich mit
/// Antworten wie vom Server (CoinGecko-Seite, Binance-Symbolliste, Alpha-Liste) und der Ersatz aus
/// `coin_meta.json` — geprüft wird, was die Oberfläche danach liest (Name über `CoinLogos.nameKey`,
/// Logo-Adresse über `CoinLogos.normalize`, Alpha über `AssetKind.of`).
final class CoinMetaTests: XCTestCase {

    private typealias S = TestSupport

    /// Teil der Datei als Rohtext (wie vom Server), damit die echten Leser laufen.
    static func raw(_ fixture: [String: Any], _ key: String) throws -> String {
        let value = try XCTUnwrap(fixture[key])
        let data = try JSONSerialization.data(withJSONObject: value)
        return String(decoding: data, as: UTF8.self)
    }

    private func assertUiReads(_ lists: CoinMeta.Lists, _ expected: [String: Any]) throws {
        let names = try XCTUnwrap(expected["names"] as? [String: String])
        for (key, name) in names {
            let tradFi = key.hasPrefix(CoinLogos.tradFiPrefix)
            let symbol = CoinLogos.displaySymbol(key)
            XCTAssertEqual(lists.names[CoinLogos.nameKey(symbol, tradFi: tradFi)], name, "Name \(key)")
        }
        let map = try XCTUnwrap(expected["map"] as? [String: String])
        for (key, url) in map {
            XCTAssertEqual(lists.map[key], url, "Logo \(key)")
            XCTAssertNotNil(CoinLogos.fileName(key), "Dateiname \(key)")
        }
        XCTAssertEqual(lists.alpha, Set(try XCTUnwrap(expected["alpha"] as? [String])))
    }

    func testFullSyncNamesLogosAlphaReachUi() throws {
        let data = try S.fixture("coin_meta")
        let gecko = try XCTUnwrap(CoinMeta.parseMarketsPage(try Self.raw(data, "geckoPage")))
        let binance = try XCTUnwrap(CoinMeta.parseBinanceList(try Self.raw(data, "binanceList")))
        let alpha = try XCTUnwrap(CoinLogos.parseAlpha(try Self.raw(data, "alphaList")))
        let lists = CoinMeta.merge(geckoTop: gecko, binance: binance, alpha: alpha)
        let expected = try XCTUnwrap(data["fullSync"] as? [String: Any])
        try assertUiReads(lists, expected)
        let alphaSymbols = lists.alpha ?? []
        for c in S.list(expected["kinds"]) {
            let kind = AssetKind.of(exchangeKey: c["exchangeKey"] as? String ?? "", base: c["base"] as? String ?? "",
                                    tradFi: c["tradFi"] as? Bool ?? false, alphaSymbols: alphaSymbols)
            XCTAssertEqual(kind.rawValue, c["expected"] as? String, "kind \(c)")
        }
        // Binance-Namen für «gleicher Token»: Spot vor Futures, Kürzel gross
        XCTAssertEqual(CoinMeta.binanceFullNames(binance)["WLD"], "Worldcoin")
    }

    func testMetaOnlyWhenDirectListsFail() throws {
        let data = try S.fixture("coin_meta")
        let meta = try XCTUnwrap(CoinMeta.parse(try Self.raw(data, "coinMeta")))
        let expected = try XCTUnwrap(data["metaOnly"] as? [String: Any])
        XCTAssertEqual(meta.generatedAt, S.millis(expected["generated"]))
        // CoinGecko und beide Binance-Listen fehlen: nur die Ersatzdatei
        try assertUiReads(CoinMeta.merge(geckoTop: [], binance: meta.binance, alpha: meta.alpha), expected)
    }

    func testInvalidMeta() throws {
        let data = try S.fixture("coin_meta")
        for item in try XCTUnwrap(data["invalidMeta"] as? [Any]) {
            XCTAssertNil(CoinMeta.parse(item as? String), "invalid: \(item)")
        }
        XCTAssertNil(CoinMeta.parseBinanceList("{\"data\":1}"))
        XCTAssertNil(CoinMeta.parseMarketsPage("{}"))
    }

    func testUsableAndNeeded() throws {
        let data = try S.fixture("coin_meta")
        for c in S.list(data["usable"]) {
            let meta = CoinMeta.Meta(generatedAt: S.millis(c["generated"]), binance: [], alpha: [])
            XCTAssertEqual(CoinMeta.isUsable(meta, now: S.millis(c["now"])), c["expected"] as? Bool, "usable \(c)")
        }
        for c in S.list(data["needed"]) {
            let needed = CoinMeta.needed(directOk: c["directOk"] as? Bool ?? false,
                                         lastDirectAt: S.millis(c["lastDirectAt"]), now: S.millis(c["now"]))
            XCTAssertEqual(needed, c["expected"] as? Bool, "needed \(c)")
        }
    }

    /// Nur die Binance-Listen (Namen sofort, vor CoinGecko): keine TradFi-Namen für Spot-Kürzel.
    func testListsOnlyNames() throws {
        let data = try S.fixture("coin_meta")
        let binance = try XCTUnwrap(CoinMeta.parseBinanceList(try Self.raw(data, "binanceList")))
        let alpha = try XCTUnwrap(CoinLogos.parseAlpha(try Self.raw(data, "alphaList")))
        let lists = CoinMeta.listNames(binance: binance, alpha: alpha)
        let expected = try XCTUnwrap(data["listsOnly"] as? [String: Any])
        for (key, name) in try XCTUnwrap(expected["names"] as? [String: String]) {
            let tradFi = key.hasPrefix(CoinLogos.tradFiPrefix)
            XCTAssertEqual(lists.names[CoinLogos.nameKey(CoinLogos.displaySymbol(key), tradFi: tradFi)], name, "Name \(key)")
        }
        for key in try XCTUnwrap(expected["absent"] as? [String]) {
            XCTAssertNil(lists.names[key], "kein Name für \(key)")
        }
        XCTAssertEqual(lists.alpha, Set(try XCTUnwrap(expected["alpha"] as? [String])))
    }

    /// Gespeicherte Listen (gleiches Format wie coin_meta.json) lesen sich wieder gleich.
    func testEncodeRoundTrip() throws {
        let data = try S.fixture("coin_meta")
        let binance = try XCTUnwrap(CoinMeta.parseBinanceList(try Self.raw(data, "binanceList")))
        let alpha = try XCTUnwrap(CoinLogos.parseAlpha(try Self.raw(data, "alphaList")))
        let meta = CoinMeta.Meta(generatedAt: 1_760_400_000_000, binance: binance, alpha: alpha)
        let back = try XCTUnwrap(CoinMeta.parse(CoinMeta.encode(meta)))
        XCTAssertEqual(back.generatedAt, meta.generatedAt)
        XCTAssertEqual(back.alpha, meta.alpha)
        XCTAssertEqual(back.binance.map(\.name), meta.binance.map(\.name))
        XCTAssertEqual(back.binance.map(\.fullName), meta.binance.map(\.fullName))
        XCTAssertEqual(back.binance.map(\.logo), meta.binance.map(\.logo))
        XCTAssertEqual(back.binance.map(\.tags), meta.binance.map(\.tags))
        XCTAssertEqual(back.binance.map(\.onlyFutures), meta.binance.map(\.onlyFutures))
    }

    /// Der mitgelieferte Auszug liegt in der App und liefert Namen.
    func testBundledSnapshot() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: CoinMeta.snapshotName, withExtension: "json"))
        let meta = try XCTUnwrap(CoinMeta.parse(try String(contentsOf: url, encoding: .utf8)))
        let lists = CoinMeta.listNames(binance: meta.binance, alpha: meta.alpha)
        XCTAssertGreaterThan(lists.names.count, 100)
        XCTAssertNotNil(lists.names["BTC"])
    }
}
