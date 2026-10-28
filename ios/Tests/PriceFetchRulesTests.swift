import XCTest
@testable import CryptoChecker

/// Regeln der Kursabfrage: Einzelabfragen nach 429/418 stoppen (`SingleRateGate`), «vollständig» je
/// Teilabfrage (`BulkCompleteness`), inverse Perpetuals (`FuturesContractType.isInversePerpetualId`) und «veraltet» am Abruf,
/// am letzten Handel erst ab der Grenze der Alarmsperre (`Exchange.readTicker`, `PriceFreshness.staleReason`).
/// Wie `PriceFetchRulesTest.kt`.
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

    func testSingleGroupExchangesStayAllOrNothing() {
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
        let bithumb = try XCTUnwrap(ExchangesConfig.exchange("Bithumb"))
        let upbit = try XCTUnwrap(ExchangesConfig.exchange("Upbit"))
        XCTAssertEqual(bithumb.bulkKnownPairsURL(call: 0), "https://api.bithumb.com/v1/market/all?isDetails=false")
        // Upbit hat die ungefilterte Liste
        XCTAssertNil(upbit.bulkKnownPairsURL(call: 0))
        XCTAssertTrue(bithumb.bulkTickersComplete)
    }

    func testKrakenBulkIsComplete() throws {
        // Fehlendes Paar: einzeln nachfragen, «EQuery:Unknown asset pair» → nicht mehr gehandelt (BulkDelisted lernt es)
        XCTAssertTrue(try XCTUnwrap(ExchangesConfig.exchange("Kraken")).bulkTickersComplete)
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "EQuery:Unknown asset pair"))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: "EGeneral:Too many requests"))
    }

    // MARK: inverse Perpetuals

    func testInversePerpetualIds() {
        func inverse(_ exchange: String, _ id: String?) -> Bool {
            FuturesContractType.isInversePerpetualId(exchangeKey: exchange, pairId: id)
        }
        XCTAssertTrue(inverse("BinanceFutures", "2:BTCUSD_PERP"))
        XCTAssertFalse(inverse("BinanceFutures", "BTCUSDT"))
        // COIN-M-Laufzeit ist kein Perpetual
        XCTAssertFalse(inverse("BinanceFutures", "2:BTCUSD_251226"))
        XCTAssertTrue(inverse("OkexFutures", "BTC-USD-SWAP"))
        XCTAssertFalse(inverse("OkexFutures", "BTC-USDT-SWAP"))
        XCTAssertFalse(inverse("OkexFutures", "BTC-USDC-SWAP"))
        XCTAssertTrue(inverse("Deribit", "ETH-PERPETUAL"))
        XCTAssertFalse(inverse("Deribit", "SOL_USDC-PERPETUAL"))
        // Andere Börsen und Spot: nie; ohne Kennung: nie
        XCTAssertFalse(inverse("BybitFutures", "BTCUSD"))
        XCTAssertFalse(inverse("Okex", "BTC-USD"))
        XCTAssertFalse(inverse("OkexFutures", nil))
    }

    // MARK: «veraltet» am Abruf, Zeit der Börse nur zur Anzeige

    /// «veraltet»: Abrufzeit, Zeit der Börse erst ab der Grenze der Alarmsperre — wie Android.
    func testFetchTimeDrivesStalenessExchangeTimeOnlyPastAlarmLimit() throws {
        let upbit = try XCTUnwrap(ExchangesConfig.exchange("Upbit"))
        let body = #"[{"market":"KRW-BTC","trade_timestamp":1728540000000,"trade_price":86000000,"acc_trade_price_24h":1.0,"acc_trade_volume_24h":2.0}]"#
        let before = EpochTime.nowMillis
        var ticker = Ticker()
        try upbit.readTicker(call: 0, body: body, ticker: &ticker, info: PairQuery("BTC", "KRW", "KRW-BTC"))
        let after = EpochTime.nowMillis
        // Letzter Handel nur als exchangeTime; timestamp = Abruf → lastUpdate frisch, am Abruf nicht veraltet
        XCTAssertEqual(ticker.exchangeTime, 1_728_540_000_000)
        XCTAssertTrue((before...after).contains(ticker.timestamp))
        XCTAssertFalse(OutdatedRule.isOutdated(ticker.timestamp, now: after, afterMillis: OutdatedRule.minMillis))
        // Handel länger als die Alarmsperre (2 h) her: Zeile «veraltet» aus demselben Grund wie die Sperre
        XCTAssertEqual(PriceFreshness.staleReason(lastUpdate: ticker.timestamp, exchangeTime: ticker.exchangeTime,
                                                  now: after, staleAfter: OutdatedRule.minMillis), .trade)
        // Ruhiges Paar (letzter Handel vor 1 h): frischer Abruf zählt, nicht veraltet
        XCTAssertNil(PriceFreshness.staleReason(lastUpdate: ticker.timestamp, exchangeTime: after - 3_600_000,
                                                now: after, staleAfter: OutdatedRule.minMillis))

        let bulk = try XCTUnwrap(upbit.parseBulkTickersDetailed(call: 0, body: body).tickers["KRW-BTC"])
        XCTAssertEqual(bulk.exchangeTime, 1_728_540_000_000)
        XCTAssertGreaterThanOrEqual(bulk.timestamp, before)
    }
}
