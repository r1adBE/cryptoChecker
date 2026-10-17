import XCTest
@testable import CryptoChecker

/// Ende-zu-Ende mit Antworten wie vom Server (`coin_meta.json`; Android: `CoinListsTest.kt`): Namen und
/// Alpha-Kennzeichen stehen nach den beiden Binance-Listen bereit — ohne dass CoinGecko oder ein
/// Bild-Server gefragt wurde. CoinGecko- und Bild-Abrufe des Testabrufers schlagen sofort fehl und
/// werden notiert; der Test verlangt, dass es keine gibt.
final class CoinListsTests: XCTestCase {

    private typealias S = TestSupport
    private let generated: Int64 = 1_760_400_000_000

    /// Notierte Abrufe (threadsicher, Abrufe laufen gleichzeitig).
    private final class Calls: @unchecked Sendable {
        private let lock = NSLock()
        private var urls: [String] = []
        func add(_ url: String) { lock.lock(); urls.append(url); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return urls }
    }

    private struct ServerError: Error {}

    /// Abrufer mit festen Antworten (`nil` = Server-Fehler, `URLError` = kein Netz).
    private func server(_ responses: [String: String?], offline: Set<String> = [], calls: Calls) -> CoinLists.Fetcher {
        { url in
            calls.add(url)
            if offline.contains(url) { throw URLError(.notConnectedToInternet) }
            if let entry = responses[url] {
                guard let text = entry else { throw ServerError() }
                return text
            }
            if url.contains("coingecko") || url.hasSuffix(".png") { throw ServerError() }
            throw URLError(.notConnectedToInternet)
        }
    }

    private func request(binance: Bool = true, alpha: Bool = true, lastBinance: Int64 = 0, lastAlpha: Int64 = 0) -> CoinLists.Request {
        CoinLists.Request(binanceDue: binance, alphaDue: alpha, lastBinanceAt: lastBinance, lastAlphaAt: lastAlpha,
                          now: generated + 60_000)
    }

    private func fetch(_ fetcher: @escaping CoinLists.Fetcher, _ request: CoinLists.Request) async -> CoinLists.Result {
        await CoinLists.fetch(fetcher, request: request, isNetworkError: { $0 is URLError })
    }

    func testNamesAndAlphaBeforeCoinGeckoAndImages() async throws {
        let data = try S.fixture("coin_meta")
        let calls = Calls()
        let binanceText = try CoinMetaTests.raw(data, "binanceList")
        let alphaText = try CoinMetaTests.raw(data, "alphaList")
        let fetcher = server([CoinLogos.binanceListURL: binanceText, CoinLogos.alphaListURL: alphaText], calls: calls)
        let result = await fetch(fetcher, request())
        XCTAssertEqual(result.binanceSource, .direct)
        XCTAssertEqual(result.alphaSource, .direct)
        XCTAssertFalse(result.networkError)
        // Nur die beiden Listen — weder CoinGecko noch Bilder noch die Ersatzdatei
        XCTAssertEqual(Set(calls.all), [CoinLogos.binanceListURL, CoinLogos.alphaListURL])

        // Was die App sofort veröffentlicht (CoinLogoStore.refreshLists)
        let binance = try XCTUnwrap(result.binance)
        let alpha = try XCTUnwrap(result.alpha)
        let lists = CoinMeta.listNames(binance: binance, alpha: alpha)
        let published = CoinLists.publishNames(known: [:], knownIsSnapshot: false,
                                               listNames: lists.names, listOrder: lists.nameOrder)
        let expected = try XCTUnwrap(data["listsOnly"] as? [String: Any])
        for (key, name) in try XCTUnwrap(expected["names"] as? [String: String]) {
            let tradFi = key.hasPrefix(CoinLogos.tradFiPrefix)
            XCTAssertEqual(published.names[CoinLogos.nameKey(CoinLogos.displaySymbol(key), tradFi: tradFi)], name, "Name \(key)")
        }
        let alphaSymbols = CoinLogos.alphaSymbols(alpha)
        XCTAssertEqual(alphaSymbols, Set(try XCTUnwrap(expected["alpha"] as? [String])))
        XCTAssertEqual(AssetKind.of(marketKey: "BinanceFutures", base: "FROGGIE", tradFi: false, alphaSymbols: alphaSymbols), .ALPHA)
        XCTAssertEqual(CoinMeta.binanceFullNames(binance)["WLD"], "Worldcoin")
    }

    func testOneSourceFailsOtherStillArrives() async throws {
        let data = try S.fixture("coin_meta")
        let calls = Calls()
        let alphaText = try CoinMetaTests.raw(data, "alphaList")
        let fetcher = server([CoinLogos.binanceListURL: nil, CoinLogos.alphaListURL: alphaText, CoinMeta.url: nil],
                             calls: calls)
        let result = await fetch(fetcher, request())
        XCTAssertNil(result.binance)
        XCTAssertEqual(result.binanceSource, .absent)
        XCTAssertEqual(result.alphaSource, .direct)
        XCTAssertTrue(result.mirrorFailed)
        XCTAssertFalse(result.networkError)
        XCTAssertTrue(result.missing(request()))
        XCTAssertEqual(CoinLogos.alphaSymbols(result.alpha ?? []), ["AIA", "FROGGIE"])
    }

    func testDirectListsFailMirrorFills() async throws {
        let data = try S.fixture("coin_meta")
        let calls = Calls()
        let metaText = try CoinMetaTests.raw(data, "coinMeta")
        let fetcher = server([CoinLogos.alphaListURL: nil, CoinMeta.url: metaText],
                             offline: [CoinLogos.binanceListURL], calls: calls)
        let result = await fetch(fetcher, request())
        XCTAssertEqual(result.binanceSource, .mirror)
        XCTAssertEqual(result.alphaSource, .mirror)
        XCTAssertTrue(result.networkError)
        XCTAssertFalse(result.missing(request()))
        let names = CoinMeta.listNames(binance: result.binance, alpha: result.alpha).names
        let expected = try XCTUnwrap(data["metaOnly"] as? [String: Any])
        for (key, name) in try XCTUnwrap(expected["names"] as? [String: String]) {
            let tradFi = key.hasPrefix(CoinLogos.tradFiPrefix)
            XCTAssertEqual(names[CoinLogos.nameKey(CoinLogos.displaySymbol(key), tradFi: tradFi)], name, "Name \(key)")
        }
        XCTAssertEqual(CoinLogos.alphaSymbols(result.alpha ?? []), Set(try XCTUnwrap(expected["alpha"] as? [String])))
        XCTAssertFalse(calls.all.contains { $0.contains("coingecko") })
    }

    func testMirrorOlderThanDirectIsIgnored() async throws {
        let data = try S.fixture("coin_meta")
        let calls = Calls()
        let binanceText = try CoinMetaTests.raw(data, "binanceList")
        let metaText = try CoinMetaTests.raw(data, "coinMeta")
        let fetcher = server([CoinLogos.binanceListURL: binanceText, CoinLogos.alphaListURL: nil, CoinMeta.url: metaText],
                             calls: calls)
        let result = await fetch(fetcher, request(lastAlpha: generated + 1))
        XCTAssertEqual(result.binanceSource, .direct)
        XCTAssertNil(result.alpha)
        XCTAssertEqual(result.alphaSource, .absent)
    }

    func testAlphaOnlyAndNothingDue() async throws {
        let data = try S.fixture("coin_meta")
        let calls = Calls()
        let alphaText = try CoinMetaTests.raw(data, "alphaList")
        let fetcher = server([CoinLogos.alphaListURL: alphaText], calls: calls)
        let result = await fetch(fetcher, request(binance: false))
        XCTAssertEqual(calls.all, [CoinLogos.alphaListURL])
        XCTAssertNil(result.binance)
        XCTAssertFalse(result.missing(request(binance: false)))
        let idle = Calls()
        _ = await fetch(server([:], calls: idle), request(binance: false, alpha: false))
        XCTAssertTrue(idle.all.isEmpty)
    }

    func testOfflineEverythingMissing() async {
        let result = await fetch(server([:], calls: Calls()), request())
        XCTAssertTrue(result.networkError)
        XCTAssertTrue(result.mirrorFailed)
        XCTAssertTrue(result.missing(request()))
    }

    func testPublishNamesPrecedence() throws {
        let data = try S.fixture("coin_meta")
        for c in S.list(data["publishNames"]) {
            let known = c["known"] as? [String: String] ?? [:]
            let lists = c["lists"] as? [String: String] ?? [:]
            let out = CoinLists.publishNames(known: known, knownIsSnapshot: c["snapshot"] as? Bool ?? false,
                                             listNames: lists, listOrder: lists.keys.sorted())
            XCTAssertEqual(out.names, c["expected"] as? [String: String], "publish \(c)")
        }
    }
}
