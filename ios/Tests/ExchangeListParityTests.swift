import XCTest
@testable import CryptoChecker

/// Börsenliste: Abschnitte und Volumen-Reihenfolge (`picker_order.json`), Netzwerke von GeckoTerminal
/// (`gecko_terminal_network.json`), EZB-Referenzkurse (Gruppen, Adresse, Lesen) und der Ausweg der DEX-Kurse über
/// GeckoTerminal. Wie Android `ExchangeOrderParityTest.kt` (auch GeckoTerminal-Netzwerke), `DexGeckoTerminalFallbackTest.kt`, `NewExchangesTest.kt`.
final class ExchangeListParityTests: XCTestCase {

    private typealias S = TestSupport

    // MARK: Abschnitte und Volumen

    func testExchangeOrderSections() throws {
        let data = try S.fixture("picker_order")
        let cases = S.list(data["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 3, "cases")
        for c in cases {
            let name = c["name"] as? String ?? ""
            let exchanges = S.list(c["exchanges"]).map { spec -> ExchangeOrder.Entry in
                ExchangeOrder.Entry(key: spec["key"] as? String ?? "",
                                    category: ExchangeCategory(rawValue: spec["category"] as? String ?? "") ?? .spot)
            }
            let volumes = (c["volumes"] as? [String: Any]).map { raw in
                raw.compactMapValues { S.number($0) }
            }
            let actual = ExchangeOrder.sections(exchanges, volumes: volumes).map { "\($0.category.rawValue): \($0.keys)" }
            let expected = S.list(c["expected"]).map { "\($0["category"] as? String ?? ""): \($0["keys"] as? [String] ?? [])" }
            XCTAssertEqual(actual, expected, name)
        }
    }

    func testExchangeOrderParseVolumes() throws {
        let data = try S.fixture("picker_order")
        let parse = try XCTUnwrap(data["parse"] as? [String: Any])
        let spot = try JSONSerialization.data(withJSONObject: parse["spot"] ?? [])
        let derivatives = try JSONSerialization.data(withJSONObject: parse["derivatives"] ?? [])
        let actual = try ExchangeOrder.parseVolumes(spotBody: String(decoding: spot, as: UTF8.self),
                                                    derivativesBody: String(decoding: derivatives, as: UTF8.self))
        let expected = (parse["expected"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) }
        XCTAssertEqual(actual, expected)
        XCTAssertThrowsError(try ExchangeOrder.parseVolumes(spotBody: "{}", derivativesBody: "[]"), "kein Array")
    }

    func testExchangeRanks() {
        XCTAssertEqual(ExchangeOrder.ranks(nil), [:])
        XCTAssertEqual(ExchangeOrder.ranks(["Kraken": 5, "Binance": 9, "Coinbase": 5]),
                       ["Binance": 0, "Coinbase": 1, "Kraken": 2])
    }

    /// Jede registrierte Börse hat einen Abschnitt; Futures-Schlüssel stehen nur in der Derivate-Tabelle.
    func testCategoriesOfTheRegistry() {
        XCTAssertEqual(ExchangesConfig.exchange("BitunixFutures")?.category, .futures)
        XCTAssertEqual(ExchangesConfig.exchange("Hyperliquid")?.category, .futures)
        XCTAssertEqual(ExchangesConfig.exchange("Bybit")?.category, .spot)
        XCTAssertEqual(ExchangesConfig.exchange("BybitFutures")?.category, .futures)
        XCTAssertEqual(ExchangesConfig.exchange("DexScreener")?.category, .dex)
        XCTAssertEqual(ExchangesConfig.exchange("EcbReference")?.category, .forex)
        XCTAssertEqual(ExchangesConfig.exchange("EcbReference")?.allowsAlarms, false)
        XCTAssertEqual(ExchangesConfig.exchange("Binance")?.allowsAlarms, true)
        for key in ExchangeOrder.derivativesIds.keys {
            XCTAssertEqual(ExchangesConfig.exchange(key)?.category, .futures, key)
        }
        for key in ExchangeOrder.spotIds.keys {
            XCTAssertEqual(ExchangesConfig.exchange(key)?.category, .spot, key)
        }
    }

    // MARK: GeckoTerminal

    func testGeckoTerminalNetworks() throws {
        let data = try S.fixture("gecko_terminal_network")
        let cases = S.list(data["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5, "cases")
        for c in cases {
            let chain = c["chain"] as? String ?? ""
            XCTAssertEqual(GeckoTerminalNetworks.network(chain), c["network"] as? String, chain)
        }
        let url = try XCTUnwrap(data["url"] as? [String: Any])
        XCTAssertEqual(DexScreener.geckoTerminalUrl(chainId: url["chain"] as? String ?? "",
                                                    address: url["address"] as? String ?? ""),
                       url["expected"] as? String)
    }

    private let geckoPool = """
    {"data":{"id":"eth_0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640","type":"pool","attributes":{\
    "base_token_price_usd":"2323.61","address":"0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640",\
    "price_change_percentage":{"h1":"0.259","h24":"-1.41"},"volume_usd":{"h1":"1953003.9","h24":"43684163.3595396"},\
    "reserve_in_usd":"81184541.9406"}}}
    """

    func testGeckoTerminalFallbackReadsThePool() throws {
        let exchange = DexScreener()
        let info = ExchangePair("WETH", "USDC", "ethereum/0x88E6A0c2dDD26FEEb64F039a2c41296fCb3f5640")
        XCTAssertEqual(exchange.tickerFallbackAddress(call: 0, info: info),
                       "https://api.geckoterminal.com/api/v2/networks/eth/pools/0x88E6A0c2dDD26FEEb64F039a2c41296fCb3f5640")
        var ticker = Ticker()
        try exchange.readTickerFallback(call: 0, body: geckoPool, ticker: &ticker, info: info)
        XCTAssertEqual(ticker.last, 2323.61, accuracy: 1e-9)
        XCTAssertEqual(ticker.change24hPercent ?? .nan, -1.41, accuracy: 1e-9)
        XCTAssertEqual(ticker.volQuote, 43684163.3595396, accuracy: 1e-6)
        XCTAssertEqual(ticker.liquidityUsd ?? .nan, 81184541.9406, accuracy: 1e-6)
        XCTAssertNil(ticker.exchangeTime)
    }

    func testGeckoTerminalFallbackRefusesOtherPoolsAndEmptyAnswers() {
        let exchange = DexScreener()
        let other = ExchangePair("WETH", "USDT", "ethereum/0x11b815efb8f581194ae79006d24e0d814b7697f6")
        var ticker = Ticker()
        XCTAssertThrowsError(try exchange.readTickerFallback(call: 0, body: geckoPool, ticker: &ticker, info: other)) {
            XCTAssertEqual(($0 as? JSONError)?.message, DexScreener.poolNotFound)
        }
        let rateLimited = #"{"status":{"error_code":429,"error_message":"Rate Limit"}}"#
        let pool = ExchangePair("WETH", "USDC", "ethereum/0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640")
        XCTAssertThrowsError(try exchange.readTickerFallback(call: 0, body: rateLimited, ticker: &ticker, info: pool)) {
            XCTAssertEqual(($0 as? JSONError)?.message, DexScreener.poolNotFound)
        }
        // Unzulässige Kennung: kein Ausweg
        XCTAssertNil(exchange.tickerFallbackAddress(call: 0, info: ExchangePair("A", "B", "eth/..")))
    }

    func testOnlyPoolNotFoundOpensTheFallback() {
        let exchange = DexScreener()
        XCTAssertTrue(exchange.tickerFallbackOnParseError(call: 0, error: JSONError(message: DexScreener.poolNotFound)))
        XCTAssertFalse(exchange.tickerFallbackOnParseError(call: 0, error: JSONError(message: "Kein JSON-Objekt")))
        XCTAssertFalse(Kraken().tickerFallbackOnParseError(call: 0, error: JSONError(message: DexScreener.poolNotFound)))
    }

    func testRateLimitOfTheGeckoTerminalFallbackKeepsTheOriginalError() async {
        let info = ExchangePair("WETH", "USDC", "ethereum/0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640")
        // 5xx bei DexScreener, Bremse bei GeckoTerminal (anderer Host): der Serverfehler bleibt
        do {
            _ = try await SingleTickerFallback.load(exchange: DexScreener(), call: 0, info: info, base: Ticker(),
                                                    serverError: HttpExchangeError(httpCode: 503, body: "")) { _ in
                throw HttpExchangeError(httpCode: 429, body: "")
            }
            XCTFail("Fehler erwartet")
        } catch {
            XCTAssertEqual((error as? HttpExchangeError)?.httpCode, 503)
        }
        // «Pool not found», Bremse bei GeckoTerminal: der Lesefehler bleibt
        do {
            _ = try await SingleTickerFallback.loadAfterMissing(exchange: DexScreener(), call: 0, info: info,
                                                                base: Ticker(),
                                                                missing: JSONError(message: DexScreener.poolNotFound)) { _ in
                throw HttpExchangeError(httpCode: 429, body: "")
            }
            XCTFail("Fehler erwartet")
        } catch {
            XCTAssertEqual((error as? JSONError)?.message, DexScreener.poolNotFound)
        }
    }

    func testLoadAfterMissingDeliversThePoolPrice() async throws {
        let info = ExchangePair("WETH", "USDC", "ethereum/0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640")
        let body = geckoPool
        let ticker = try await SingleTickerFallback.loadAfterMissing(
            exchange: DexScreener(), call: 0, info: info, base: Ticker(),
            missing: JSONError(message: DexScreener.poolNotFound)) { address in
            XCTAssertTrue(address.hasPrefix("https://api.geckoterminal.com/api/v2/networks/eth/pools/"))
            return body
        }
        XCTAssertEqual(ticker.last, 2323.61, accuracy: 1e-9)
    }

    func testSameHost() {
        XCTAssertTrue(SingleTickerFallback.sameHost("https://API.onetrading.com/a", "https://api.onetrading.com/b"))
        XCTAssertFalse(SingleTickerFallback.sameHost("https://api.geckoterminal.com/x", "https://api.dexscreener.com/y"))
        XCTAssertTrue(SingleTickerFallback.sameHost("kein url", "https://api.dexscreener.com/y"))
    }

    // MARK: EZB-Referenzkurse

    func testEcbReferenceAddressesAndKind() {
        let exchange = EcbReference()
        let pair = ExchangePair("EUR", "USD", "EURUSD")
        XCTAssertEqual(exchange.tickerAddress(call: 0, info: pair),
                       "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml")
        XCTAssertEqual(exchange.bulkTickersURL(call: 0, pairIds: ["EURUSD", "USDJPY"]), EcbRates.hist90dURL)
        XCTAssertNil(exchange.bulkTickersURL(call: 1, pairIds: ["EURUSD"]))
        // Eine Datei für alle Paare
        XCTAssertEqual(exchange.bulkTickersRequestCount(pairIds: ["EURUSD", "USDJPY", "CHFEUR"]), 1)
        XCTAssertEqual(exchange.pairListAddress(call: 0), "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml")
        XCTAssertNotNil(exchange.tickerQueryError(call: 0, info: ExchangePair("EUR", "US", "EURUS")))
        XCTAssertNil(exchange.tickerQueryError(call: 0, info: pair))
        XCTAssertEqual(exchange.name, "ECB")
    }

    func testEcbReferenceReadsLatestAndPreviousDay() throws {
        let body = "<Cube><Cube time='2026-10-26'><Cube currency='USD' rate='1.1188'/><Cube currency='JPY' rate='177.31'/>"
            + "</Cube><Cube time='2026-10-23'><Cube currency='USD' rate='1.1193'/><Cube currency='JPY' rate='176.95'/>"
            + "</Cube><Cube time='2026-10-12'><Cube currency='USD' rate='1.0'/></Cube></Cube>"
        let window = EcbRates.recent(try XCTUnwrap(EcbRates.parseXML(body)), windowDays: EcbReference.windowDays)
        // 12. Oktober liegt mehr als 10 Tage vor dem jüngsten Tag
        XCTAssertEqual(window.count, 2)
        var ticker = Ticker()
        try EcbReference().readTicker(call: 0, body: body, ticker: &ticker, info: ExchangePair("USD", "JPY", "USDJPY"))
        XCTAssertEqual(ticker.last, EcbRates.round5(177.31 / 1.1188))
        let previous = EcbRates.round5(176.95 / 1.1193)
        XCTAssertEqual(ticker.change24hPercent ?? .nan, (ticker.last - previous) / previous * 100, accuracy: 1e-12)
        XCTAssertNil(ticker.exchangeTime)
        var failed = Set<String>()
        let bulk = try EcbReference().parseBulkTickers(call: 0, body: body, failed: &failed)
        XCTAssertEqual(Set(bulk.keys), ["EURJPY", "EURUSD", "JPYEUR", "JPYUSD", "USDEUR", "USDJPY"])
        XCTAssertTrue(failed.isEmpty)
        var empty = Ticker()
        XCTAssertThrowsError(try EcbReference().readTicker(call: 0, body: body, ticker: &empty,
                                                           info: ExchangePair("USD", "CHF", "USDCHF")))
    }

    func testEcbRatesRoundCrossRatesToFiveSignificantDigits() {
        // Gleiche Werte wie bisher beim Spiegel (USD-Basis, 4.11.2026). Wie Android.
        let day = ["USD": 1.1267, "CHF": 0.9188, "JPY": 178.3, "IDR": 20425.83]
        XCTAssertEqual(EcbRates.cross(day, base: "USD", quote: "CHF"), 0.81548)
        XCTAssertEqual(EcbRates.cross(day, base: "USD", quote: "JPY"), 158.25)
        XCTAssertEqual(EcbRates.cross(day, base: "USD", quote: "IDR"), 18129)
        XCTAssertEqual(EcbRates.cross(day, base: "USD", quote: "EUR"), 0.88755)
        XCTAssertEqual(EcbRates.cross(day, base: "EUR", quote: "IDR"), 20425.83)
        XCTAssertNil(EcbRates.cross(day, base: "USD", quote: "USD"))
        XCTAssertNil(EcbRates.cross(day, base: "USD", quote: "GBP"))
        XCTAssertEqual(EcbRates.round5(123456.7), 123460)
        XCTAssertEqual(EcbRates.round5(0.000123456), 0.00012346)
        XCTAssertEqual(Set(EcbRates.rebase(day, base: "USD").keys), ["CHF", "JPY", "IDR", "EUR"])
    }

    func testEcbDataPortalAddressAndCoverage() throws {
        XCTAssertEqual(EcbRates.seriesURL(codes: ["USD", "EUR", "CHF", "USD"], from: LocalDay(2024, 1, 1),
                                          to: LocalDay(2024, 1, 10)),
                       "https://data-api.ecb.europa.eu/service/data/EXR/D.CHF+USD.EUR.SP00.A"
                           + "?startPeriod=2024-01-01&endPeriod=2024-01-10&format=jsondata&detail=dataonly")
        let today = LocalDay(2026, 11, 5)
        XCTAssertTrue(EcbRates.coveredByHist90d(from: today.plusDays(-80), today: today))
        XCTAssertFalse(EcbRates.coveredByHist90d(from: today.plusDays(-81), today: today))
        // DOCTYPE wird nie gelesen
        XCTAssertNil(EcbRates.parseXML("<!DOCTYPE x><Cube time='2026-10-26'/>"))
        XCTAssertNil(EcbRates.parseXML("<html>maintenance</html>"))
    }

    func testEcbReferencePairList() throws {
        let body = "<Cube><Cube time='2026-10-26'><Cube currency='USD' rate='1.1188'/><Cube currency='CHF' rate='0.9243'/>"
            + "<Cube currency='x1' rate='2'/></Cube></Cube>"
        let pairs = try EcbReference().readPairList(call: 0, body: body)
        XCTAssertEqual(pairs.count, 6)
        XCTAssertEqual(pairs.first?.pairId, "CHFEUR")
        XCTAssertTrue(pairs.allSatisfy { $0.base != $0.quote && $0.contractType == .none })
    }

    // MARK: Bitunix

    func testBitunixBaseKeepsTheMultiplier() {
        XCTAssertEqual(BitunixFutures.base(symbol: "1000PEPEUSDT", base: "PEPE", quote: "USDT"), "1000PEPE")
        XCTAssertEqual(BitunixFutures.base(symbol: "BTCUSDT", base: "BTC", quote: "USDT"), "BTC")
        XCTAssertEqual(BitunixFutures.base(symbol: "USDT", base: "X", quote: "USDT"), "X")
        XCTAssertEqual(BitunixFutures.base(symbol: "BTC-PERP", base: "BTC", quote: "USDT"), "BTC")
    }
}
