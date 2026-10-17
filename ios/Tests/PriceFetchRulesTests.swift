import XCTest
@testable import CryptoChecker

/// Regeln der Kursabfrage (Build 47): Einzelabfragen nach 429/418 stoppen (`SingleRateGate`), «vollständig» je
/// Teilabfrage (`BulkCompleteness`), inverse Perpetuals (`FuturesContractType.normalized`) und «veraltet» am Abruf
/// statt am letzten Handel (`Market.readTicker`). Wie `PriceFetchRulesTest.kt`.
final class PriceFetchRulesTests: XCTestCase {
    private typealias Fetched = PriceRefresher.Fetched

    // MARK: SingleRateGate

    func testGateBlocksRemainingSinglesAfterRateLimit() {
        var gate = SingleRateGate()
        gate.note(Fetched(ticker: nil, error: "HTTP 429 (retry-after 30 s)", fromSingle: true))
        XCTAssertEqual(gate.blocked, "HTTP 429 (retry-after 30 s)")
        // Derselbe Text für die übrigen Paare: retry-after bleibt für ExchangeBackoff erhalten
        XCTAssertEqual(ExchangeBackoff.retryAfterMillis([gate.blocked]), 30_000)
    }

    func testGateBlocksAfterBan() {
        var gate = SingleRateGate()
        gate.note(Fetched(ticker: nil, error: "HTTP 418"))
        XCTAssertEqual(gate.blocked, "HTTP 418")
        XCTAssertTrue(ExchangeBackoff.banned([gate.blocked]))
    }

    func testGateIgnoresOtherErrors() {
        var gate = SingleRateGate()
        gate.note(Fetched(ticker: nil, error: "HTTP 404"))
        gate.note(Fetched(ticker: nil, error: "timeout"))
        var t = Ticker()
        t.last = 2
        gate.note(Fetched(ticker: t, error: nil))
        XCTAssertNil(gate.blocked)
    }

    func testGateKeepsFirstRateLimitText() {
        var gate = SingleRateGate()
        gate.note(Fetched(ticker: nil, error: "HTTP 429 (retry-after 60 s)"))
        gate.note(Fetched(ticker: nil, error: "HTTP 418"))
        XCTAssertEqual(gate.blocked, "HTTP 429 (retry-after 60 s)")
    }

    // MARK: BulkCompleteness

    func testAlphaFailureKeepsSpotComplete() {
        let binance = Binance()
        let ids = ["BTCUSDT", "ETHUSDT", "ALPHA_175USDT"]
        let failedGroups: Set<Int> = [binance.bulkTickersRequestGroup(call: 1)]
        let incomplete = BulkCompleteness.incomplete(ids, failedGroups: failedGroups) { binance.bulkTickersPairGroup(pairId: $0) }
        XCTAssertEqual(incomplete, ["ALPHA_175USDT"])
        XCTAssertTrue(BulkCompleteness.anyComplete(ids, failedGroups: failedGroups, incomplete: incomplete))
        // Spot gescheitert (Gruppe 0) → Spot-Paare unvollständig
        XCTAssertEqual(BulkCompleteness.incomplete(ids, failedGroups: [0]) { binance.bulkTickersPairGroup(pairId: $0) },
                       ["BTCUSDT", "ETHUSDT"])
        // Alle Alpha-Ticker (ab Anfrage 2) gehören zur Alpha-Gruppe
        XCTAssertEqual(binance.bulkTickersRequestGroup(call: 1), binance.bulkTickersRequestGroup(call: 3))
        XCTAssertEqual(binance.bulkTickersRequestGroup(call: 0), 0)
    }

    func testSingleGroupMarketsStayAllOrNothing() {
        let ids = ["A", "B"]
        let incomplete = BulkCompleteness.incomplete(ids, failedGroups: [0]) { _ in 0 }
        XCTAssertEqual(incomplete, ["A", "B"])
        XCTAssertFalse(BulkCompleteness.anyComplete(ids, failedGroups: [0], incomplete: incomplete))
        XCTAssertTrue(BulkCompleteness.anyComplete(ids, failedGroups: [], incomplete: []))
        XCTAssertFalse(BulkCompleteness.anyComplete([], failedGroups: [0], incomplete: []))
    }

    func testCoinMFailureKeepsUsdMComplete() {
        let futures = BinanceFutures()
        let ids = ["BTCUSDT", "2:BTCUSD_PERP"]
        let incomplete = BulkCompleteness.incomplete(ids, failedGroups: [futures.bulkTickersRequestGroup(call: 1)]) {
            futures.bulkTickersPairGroup(pairId: $0)
        }
        XCTAssertEqual(incomplete, ["2:BTCUSD_PERP"])
    }

    func testUnknownPairsFromPairList() {
        XCTAssertEqual(BulkCompleteness.unknownPairs(["KRW-BTC", "KRW-OLD"], known: ["KRW-BTC", "KRW-ETH"]), ["KRW-OLD"])
        XCTAssertNil(BulkCompleteness.unknownPairs(["KRW-BTC"], known: ["KRW-BTC"]))
        // Leere Paarliste sagt nichts
        XCTAssertNil(BulkCompleteness.unknownPairs(["KRW-BTC"], known: []))
    }

    func testBithumbFindsUnknownPairsViaPairList() throws {
        let bithumb = try XCTUnwrap(MarketsConfig.market("Bithumb"))
        let upbit = try XCTUnwrap(MarketsConfig.market("Upbit"))
        XCTAssertEqual(bithumb.bulkKnownPairsURL(call: 0), "https://api.bithumb.com/v1/market/all?isDetails=false")
        // Upbit hat die ungefilterte Liste
        XCTAssertNil(upbit.bulkKnownPairsURL(call: 0))
        XCTAssertTrue(bithumb.bulkTickersComplete)
    }

    func testKrakenBulkIsComplete() throws {
        // Fehlendes Paar: einzeln nachfragen, «EQuery:Unknown asset pair» → nicht mehr gehandelt (BulkDelisted lernt es)
        XCTAssertTrue(try XCTUnwrap(MarketsConfig.market("Kraken")).bulkTickersComplete)
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "EQuery:Unknown asset pair"))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: "EGeneral:Too many requests"))
    }

    // MARK: inverse Perpetuals

    func testInversePerpetualIds() {
        func n(_ market: String, _ id: String?, _ type: FuturesContractType) -> FuturesContractType {
            FuturesContractType.normalized(marketKey: market, pairId: id, type)
        }
        XCTAssertEqual(n("BinanceFutures", "2:BTCUSD_PERP", .perpetual), .inversePerpetual)
        XCTAssertEqual(n("BinanceFutures", "BTCUSDT", .perpetual), .perpetual)
        // COIN-M-Laufzeit bleibt
        XCTAssertEqual(n("BinanceFutures", "2:BTCUSD_251226", .quarterly), .quarterly)
        XCTAssertEqual(n("OkexFutures", "BTC-USD-SWAP", .perpetual), .inversePerpetual)
        XCTAssertEqual(n("OkexFutures", "BTC-USDT-SWAP", .perpetual), .perpetual)
        XCTAssertEqual(n("OkexFutures", "BTC-USDC-SWAP", .perpetual), .perpetual)
        XCTAssertEqual(n("Deribit", "ETH-PERPETUAL", .perpetual), .inversePerpetual)
        XCTAssertEqual(n("Deribit", "SOL_USDC-PERPETUAL", .perpetual), .perpetual)
        // Andere Börsen und Spot unverändert; schon invers bleibt invers
        XCTAssertEqual(n("BybitFutures", "BTCUSD", .perpetual), .perpetual)
        XCTAssertEqual(n("Okex", "BTC-USD", .none), FuturesContractType.none)
        XCTAssertEqual(n("Deribit", "BTC-PERPETUAL", .inversePerpetual), .inversePerpetual)
        XCTAssertEqual(n("OkexFutures", nil, .perpetual), .perpetual)
    }

    func testSavedInversePerpetualIsNormalizedOnLoad() throws {
        let json = #"{"id":7,"marketKey":"BinanceFutures","marketName":"Binance Futures","baseAsset":"BTC","quoteAsset":"USD","contractType":1,"pairId":"2:BTCUSD_PERP"}"#
        let watch = try JSONDecoder().decode(Watch.self, from: Data(json.utf8))
        XCTAssertEqual(watch.contractType, .inversePerpetual)
        XCTAssertTrue(watch.contractType.isPerpetual)
    }

    // MARK: «veraltet» am Abruf, Zeit der Börse nur zur Anzeige

    func testExchangeTimeIsDisplayOnlyFetchTimeDrivesStaleness() throws {
        let upbit = try XCTUnwrap(MarketsConfig.market("Upbit"))
        let body = #"[{"market":"KRW-BTC","trade_timestamp":1728540000000,"trade_price":86000000,"acc_trade_price_24h":1.0,"acc_trade_volume_24h":2.0}]"#
        let before = TimeUtils.nowMillis
        var ticker = Ticker()
        try upbit.readTicker(call: 0, body: body, ticker: &ticker, info: PairQuery("BTC", "KRW", "KRW-BTC"))
        let after = TimeUtils.nowMillis
        // Letzter Handel (ruhiges Paar) nur als exchangeTime; timestamp = Abruf → lastUpdate frisch, nicht veraltet
        XCTAssertEqual(ticker.exchangeTime, 1_728_540_000_000)
        XCTAssertTrue((before...after).contains(ticker.timestamp))
        XCTAssertFalse(OutdatedRule.isOutdated(ticker.timestamp, now: after, afterMillis: OutdatedRule.minMillis))

        let bulk = try XCTUnwrap(upbit.parseBulkTickersDetailed(call: 0, body: body).tickers["KRW-BTC"])
        XCTAssertEqual(bulk.exchangeTime, 1_728_540_000_000)
        XCTAssertGreaterThanOrEqual(bulk.timestamp, before)
    }
}
