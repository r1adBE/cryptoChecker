import XCTest
@testable import CryptoChecker

/// Binance Alpha (`Parity/binance_alpha.json`): Paarliste Spot + Alpha, Kurse aus der Token-Liste und vom
/// Ticker, Kerzen, Einordnung «α» auf anderen Börsen und Filter «inkl. Alpha-Tokens» — wie
/// `BinanceAlphaParityTest.kt`.
final class BinanceAlphaParityTests: XCTestCase {

    private var data: [String: Any] = [:]
    private var entries: [CoinLogos.AlphaEntry] = []
    private var tokens: [BinanceAlpha.Token] = []
    private var alphaSymbols: Set<String> = []
    private var alphaNames: [String: String] = [:]

    override func setUpWithError() throws {
        data = try TestSupport.fixture("binance_alpha")
        entries = try XCTUnwrap(CoinLogos.parseAlpha(data["tokenList"] as? String))
        tokens = CoinLogos.alphaTokens(entries)
        alphaSymbols = CoinLogos.alphaSymbols(entries)
        alphaNames = CoinLogos.alphaNames(tokens)
    }

    private func near(_ label: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTFail("\(label): expected value missing") }
        guard let actual else { return XCTFail("\(label): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), max(abs(expected) * 1e-9, 1e-12), label)
    }

    private func triple(_ raw: Any) -> ExchangePair {
        let l = raw as? [Any] ?? []
        return ExchangePair(l[0] as? String ?? "", l[1] as? String ?? "", l[2] as? String)
    }

    func testPairListMergesSpotAndAlpha() {
        let spot = (data["spotPairs"] as? [Any] ?? []).map(triple)
        let alpha = BinanceAlpha.pairs(tokens: tokens, spotPairs: spot)
        let expected: [[String]] = (data["pairs"] as? [[String]]) ?? []
        let actual: [[String]] = alpha.sorted { $0.base < $1.base }.map { [$0.base, $0.quote, $0.pairId ?? ""] }
        XCTAssertEqual(actual, expected)
        // Spot-Paare bleiben normal (kein «α»), Alpha-Paare tragen die Kennung
        XCTAssertTrue(alpha.allSatisfy { BinanceAlpha.isPairId($0.pairId) && $0.quote == BinanceAlpha.quote })
        XCTAssertFalse(spot.contains { BinanceAlpha.isPairId($0.pairId) })
        // Zweite Zusammenführung mit schon enthaltenen Alpha-Paaren: keine Doppel
        XCTAssertEqual(alpha.map(\.pairId), BinanceAlpha.pairs(tokens: tokens, spotPairs: spot + alpha).map(\.pairId))
        XCTAssertEqual(alphaSymbols.sorted(), (data["alphaSymbols"] as? [String]) ?? [])
    }

    func testIds() {
        XCTAssertTrue(BinanceAlpha.isPairId("ALPHA_373USDT"))
        XCTAssertFalse(BinanceAlpha.isPairId("ALPHA_USDT"))
        XCTAssertFalse(BinanceAlpha.isPairId("ALPHA_373"))
        XCTAssertFalse(BinanceAlpha.isPairId("FROGGIEUSDT"))
        XCTAssertFalse(BinanceAlpha.isPairId("ALPHA_1234567890USDT"))
        XCTAssertEqual(BinanceAlpha.pairId(alphaId: " alpha_175 "), "ALPHA_175USDT")
        XCTAssertNil(BinanceAlpha.pairId(alphaId: ""))
        XCTAssertTrue(BinanceAlpha.isAlphaPair(exchangeKey: "Binance", pairId: "ALPHA_1USDT"))
        XCTAssertFalse(BinanceAlpha.isAlphaPair(exchangeKey: "BinanceUs", pairId: "ALPHA_1USDT"))
        // Kennung bleibt beim Speichern der Listen erhalten
        XCTAssertEqual(entries.first { $0.symbol == "FROGGIE" }?.alphaId, "ALPHA_373")
    }

    func testBulkTickersFromTokenList() throws {
        let exchange = Binance()
        let bulk = data["bulk"] as? [String: Any] ?? [:]
        for c in TestSupport.list(bulk["cases"]) {
            let ids = c["ids"] as? [String] ?? []
            let count = exchange.bulkTickersRequestCount(pairIds: ids)
            XCTAssertEqual(count, Int(TestSupport.number(c["count"]) ?? -1), "count \(ids)")
            let expectedUrls: [String?] = (c["urls"] as? [Any] ?? []).map { $0 as? String }
            let urls: [String?] = (0..<count).map { exchange.bulkTickersURL(call: $0, pairIds: ids) }
            XCTAssertEqual(urls, expectedUrls, "urls \(ids)")
        }
        // Ab Anfrage 1 keine ungefilterte Ersatz-Anfrage (ein gescheiterter Ticker holt nicht die ganze Liste)
        XCTAssertNil(exchange.bulkTickersURL(call: 1))
        XCTAssertNil(exchange.bulkTickersURL(call: 2))
        let parsed = try exchange.parseBulkTickersDetailed(call: 1, body: data["tokenList"] as? String ?? "")
        let expected = bulk["tickers"] as? [String: Any] ?? [:]
        XCTAssertEqual(Set(parsed.tickers.keys), Set(expected.keys))
        for (id, raw) in expected {
            let e = raw as? [String: Any] ?? [:]
            guard let t = parsed.tickers[id] else { XCTFail("\(id) missing"); continue }
            near("\(id) last", TestSupport.number(e["last"]), t.last)
            if let high = TestSupport.number(e["high"]) { near("\(id) high", high, t.high) } else { XCTAssertEqual(t.high, Ticker.missing) }
            if let low = TestSupport.number(e["low"]) { near("\(id) low", low, t.low) } else { XCTAssertEqual(t.low, Ticker.missing) }
            near("\(id) volQuote", TestSupport.number(e["volQuote"]), t.volQuote)
            near("\(id) change", TestSupport.number(e["change"]), t.change24hPercent)
            // Keine Handelszeit in der Liste: Zeit der Börse unbekannt
            XCTAssertNil(t.exchangeTime, "\(id) exchangeTime")
        }
        XCTAssertEqual(parsed.failed, Set((bulk["failed"] as? [String]) ?? []))
    }

    func testBulkSwitchOver() throws {
        // Bis maxSingleTickers Alpha-Paare je Paar ein Ticker, darüber einmal die Token-Liste
        let ids: [String] = (1...(BinanceAlpha.maxSingleTickers + 1)).map { "ALPHA_\($0)USDT" }
        XCTAssertEqual(BinanceAlpha.bulkRequestURLs(pairIds: ["BTCUSDT"]), [])
        let ten: [String?] = BinanceAlpha.bulkRequestURLs(pairIds: Array(ids.prefix(BinanceAlpha.maxSingleTickers)) + ["BTCUSDT"])
        XCTAssertEqual(ten.count, BinanceAlpha.maxSingleTickers + 1)
        XCTAssertNil(ten[0])
        XCTAssertTrue(ten.dropFirst().allSatisfy { ($0 ?? "").hasPrefix(BinanceAlpha.tickerURL) })
        XCTAssertEqual(BinanceAlpha.bulkRequestURLs(pairIds: ids), [BinanceAlpha.tokenListURL])
        // Doppelte Kennungen zählen einmal
        XCTAssertEqual(BinanceAlpha.bulkRequestURLs(pairIds: ["ALPHA_1USDT", "ALPHA_1USDT", "ALPHA_2USDT"]).count, 3)

        // Einzel-Ticker als Folgeanfrage: Kennung aus `data.symbol`; Fehler-Code → keine Zeile
        let exchange = Binance()
        let b = data["bulkTicker"] as? [String: Any] ?? [:]
        let requestId = Int(TestSupport.number(b["requestId"]) ?? -1)
        let id = b["id"] as? String ?? ""
        let tickerBody = (data["ticker"] as? [String: Any])?["body"] as? String ?? ""
        let parsed = try exchange.parseBulkTickersDetailed(call: requestId, body: tickerBody)
        XCTAssertEqual(Set(parsed.tickers.keys), [id])
        near("bulk ticker last", TestSupport.number(b["last"]), parsed.tickers[id]?.last)
        let errorBody = (data["tickerError"] as? [String: Any])?["body"] as? String ?? ""
        let none = try exchange.parseBulkTickersDetailed(call: requestId, body: errorBody)
        XCTAssertTrue(none.tickers.isEmpty && none.failed.isEmpty)
    }

    func testListCacheKeepsSixtySeconds() {
        let cache = BinanceAlpha.ListCache()
        XCTAssertNil(cache.get(now: 1_000))
        cache.put("list", now: 1_000)
        XCTAssertEqual(cache.get(now: 1_000), "list")
        XCTAssertEqual(cache.get(now: 1_000 + BinanceAlpha.listCacheMillis - 1), "list")
        XCTAssertNil(cache.get(now: 1_000 + BinanceAlpha.listCacheMillis))
        // Uhr zurückgestellt: nicht weiterverwenden
        XCTAssertNil(cache.get(now: 999))
    }

    func testSingleTicker() throws {
        let exchange = Binance()
        let t = data["ticker"] as? [String: Any] ?? [:]
        let id = t["id"] as? String ?? ""
        let info = PairQuery("FROGGIE", "USDT", id)
        XCTAssertEqual(exchange.tickerAddress(call: 0, info: info), t["url"] as? String)
        var ticker = Ticker()
        try exchange.readTicker(call: 0, body: t["body"] as? String ?? "", ticker: &ticker, info: info)
        let e = t["expected"] as? [String: Any] ?? [:]
        near("last", TestSupport.number(e["last"]), ticker.last)
        near("high", TestSupport.number(e["high"]), ticker.high)
        near("low", TestSupport.number(e["low"]), ticker.low)
        near("vol", TestSupport.number(e["vol"]), ticker.vol)
        near("volQuote", TestSupport.number(e["volQuote"]), ticker.volQuote)
        near("change", TestSupport.number(e["change"]), ticker.change24hPercent)
        XCTAssertNil(ticker.exchangeTime)
        let err = data["tickerError"] as? [String: Any] ?? [:]
        let errBody = err["body"] as? String ?? ""
        var broken = Ticker()
        XCTAssertThrowsError(try exchange.readTicker(call: 0, body: errBody, ticker: &broken, info: info))
        XCTAssertEqual(try exchange.readError(call: 0, body: errBody, info: info), err["message"] as? String)
        // Spot-Paare unverändert
        XCTAssertEqual(exchange.tickerAddress(call: 0, info: PairQuery("BTC", "USDT", "BTCUSDT")),
                       "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT")
    }

    private func candlePair(_ raw: Any?) -> CandlePair {
        let p = raw as? [String: Any] ?? [:]
        return CandlePair(exchangeKey: p["exchangeKey"] as? String ?? "",
                          exchangeName: p["exchangeName"] as? String ?? "",
                          base: p["base"] as? String ?? "",
                          quote: p["quote"] as? String ?? "",
                          pairId: p["pairId"] as? String,
                          contractType: FuturesContractType(backupName: p["contract"] as? String ?? "NONE"))
    }

    /// Aufgezeichnete Anfragen (die Antwort-Funktion ist `@Sendable`).
    private final class Recorder: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
        private let lock = NSLock()
        private var urls: [String] = []
        func add(_ url: String) { lock.lock(); urls.append(url); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return urls }
    }

    func testCandles() async throws {
        let k = data["klines"] as? [String: Any] ?? [:]
        let pair = candlePair(k["pair"])
        let expectedURL = k["url"] as? String ?? ""
        let body = k["body"] as? String ?? ""
        let recorder = Recorder()
        let http = CandleHTTP(get: { url in
            recorder.add(url)
            return url == expectedURL ? body : nil
        })
        XCTAssertTrue(ExchangeCandleRegistry.supports(pair, interval: .h1))
        XCTAssertFalse(ExchangeCandleRegistry.supports(pair, interval: .w1))
        let source = try XCTUnwrap(ExchangeCandleRegistry.source(for: pair))
        XCTAssertTrue(source is BinanceAlphaCandles)
        let limit = Int(TestSupport.number(k["limit"]) ?? 0)
        let fetched = await source.fetch(pair, interval: .h1, limit: limit, http: http)
        let result = try XCTUnwrap(fetched)
        XCTAssertEqual(recorder.all, [expectedURL])
        let rows = k["expected"] as? [[Any]] ?? []
        XCTAssertEqual(result.count, rows.count)
        for (i, r) in rows.enumerated() where i < result.count {
            let c = result[i]
            XCTAssertEqual(c.openTime, Int64(TestSupport.number(r[0]) ?? 0))
            near("open \(i)", TestSupport.number(r[1]), c.open)
            near("high \(i)", TestSupport.number(r[2]), c.high)
            near("low \(i)", TestSupport.number(r[3]), c.low)
            near("close \(i)", TestSupport.number(r[4]), c.close)
            near("volume \(i)", TestSupport.number(r[5]), c.volume)
            near("quoteVolume \(i)", TestSupport.number(r[6]), c.quoteVolume)
        }
        // Herkunft «Binance Alpha», keine Ausweich-Kette (gleiches Kürzel anderswo = anderer Token)
        XCTAssertEqual(OwnCandlePolicy.provider(pair), k["provider"] as? String)
        XCTAssertFalse(OwnCandlePolicy.allowsFallback(pair))
        // Spot-Paar von Binance bleibt bei den Spot-Kerzen
        let spot = candlePair(k["spotPair"])
        XCTAssertEqual(OwnCandlePolicy.provider(spot), "Binance")
        XCTAssertTrue(OwnCandlePolicy.allowsFallback(spot))
        XCTAssertTrue(ExchangeCandleRegistry.source(for: spot) is BinanceCandles)
    }

    func testKinds() {
        for c in TestSupport.list(data["kinds"]) {
            let kind = AssetKind.of(exchangeKey: c["exchangeKey"] as? String ?? "",
                                    base: c["base"] as? String ?? "",
                                    tradFi: c["tradFi"] as? Bool ?? false,
                                    alphaSymbols: alphaSymbols,
                                    pairId: c["pairId"] as? String,
                                    pairName: c["pairName"] as? String,
                                    alphaNames: alphaNames)
            XCTAssertEqual(kind.rawValue, c["expected"] as? String, c["name"] as? String ?? "")
        }
    }

    func testAllowsOnOff() {
        for c in TestSupport.list(data["allows"]) {
            let name = c["name"] as? String ?? ""
            let exchangeKey = c["exchangeKey"] as? String ?? ""
            let base = c["base"] as? String ?? ""
            let pair = ExchangePair(base, "USDT", c["pairId"] as? String)
            let symbols: Set<String> = (c["emptyList"] as? Bool ?? false) ? [] : alphaSymbols
            let hidden = AssetScope.hiddenAlphaSymbols(includeAlpha: c["includeAlpha"] as? Bool ?? false, alphaSymbols: symbols)
            var pairNames: [String: String] = [:]
            if let n = c["pairName"] as? String { pairNames[TokenIdentity.key(base)] = n }
            let expected = c["expected"] as? Bool ?? false
            XCTAssertEqual(AssetScope.TOKENS.allows(pair, exchangeKey: exchangeKey, hiddenAlpha: hidden,
                                                    pairNames: pairNames, alphaNames: alphaNames), expected, name)
            // Gleiches Ergebnis über die ganze Liste
            let info = ExchangePairsInfo(lastSyncDate: 1, pairs: [pair])
            let scoped = info.scoped(.TOKENS, exchangeKey: exchangeKey, hiddenAlpha: hidden,
                                     pairNames: pairNames, alphaNames: alphaNames)
            XCTAssertEqual(!scoped.pairs.isEmpty, expected, name)
        }
    }
}
