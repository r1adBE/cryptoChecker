import XCTest
@testable import CryptoChecker

/// WOO X Spot: zweite Anfrage «letzter Abschluss» (echte Antwort vom 20.10.2026) ersetzt den Schluss der
/// Stundenkerze, behält die Eröffnung der Veränderung und setzt die Zeit der Börse — wie `WooLastTradeTest.kt`.
final class WooLastTradeTests: XCTestCase {

    private let market = Woo()
    private let info = PairQuery("BTC", "USDT", "SPOT_BTC_USDT")
    private let realTrades = """
        {"success":true,"rows":[{"symbol":"SPOT_BTC_USDT","side":"SELL","executed_price":83081.96,"executed_quantity":0.174010,"executed_timestamp":"1792534030.272","source":0,"rpi":false}]}
        """

    func testSecondRequestAsksForTheLastTrade() {
        XCTAssertEqual(market.tickerCallCount(info), 2)
        XCTAssertEqual(market.tickerAddress(call: 1, info: info),
                       "https://api.woox.io/v1/public/market_trades?symbol=SPOT_BTC_USDT&limit=1")
        XCTAssertFalse(market.priceIsHourlyClose)
    }

    func testLastTradeReplacesTheCandleCloseAndKeepsTheOpening() throws {
        // Nach den Kerzen: Kurs 80 000, +10 % → Eröffnung 72 727,27
        var ticker = Ticker()
        ticker.last = 80_000
        ticker.change24hPercent = 10
        ticker.exchangeTime = 1_792_533_600_000
        try market.readTicker(call: 1, body: realTrades, ticker: &ticker, info: info)
        XCTAssertEqual(ticker.last, 83_081.96)
        XCTAssertEqual(ticker.exchangeTime, 1_792_534_030_272)
        let opening = 80_000.0 / 1.1
        XCTAssertEqual(try XCTUnwrap(ticker.change24hPercent), (83_081.96 - opening) / opening * 100, accuracy: 1e-9)
    }

    /// «inf» oder riesige Zeit: kein Absturz, die Zeit der Kerzen bleibt (der Kurs gilt trotzdem).
    func testUnreadableTradeTimeKeepsTheCandleTime() throws {
        for raw in ["inf", "1e300"] {
            var ticker = Ticker()
            ticker.last = 80_000
            ticker.exchangeTime = 1_792_533_600_000
            let body = #"{"success":true,"rows":[{"executed_price":83081.96,"executed_timestamp":"\#(raw)"}]}"#
            try market.readTicker(call: 1, body: body, ticker: &ticker, info: info)
            XCTAssertEqual(ticker.last, 83_081.96, raw)
            XCTAssertEqual(ticker.exchangeTime, 1_792_533_600_000, raw)
        }
    }

    func testEmptyTradesLeaveTheCandlePrice() {
        var ticker = Ticker()
        ticker.last = 80_000
        ticker.change24hPercent = 10
        XCTAssertThrowsError(try market.readTicker(call: 1, body: #"{"success":true,"rows":[]}"#, ticker: &ticker, info: info))
        XCTAssertEqual(ticker.last, 80_000)
        XCTAssertEqual(ticker.change24hPercent, 10)
    }
}
