import XCTest
@testable import CryptoChecker

/// Wie `DayChangeSourceTest.kt`: Hinweis «Veränderung aus …-Kerzen» nur bei Kerzen einer anderen Börse.
final class DayChangeSourceTests: XCTestCase {

    func testForeignOnlyForOtherExchange() {
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Kraken", "Kraken"], provider: "Binance"), "Binance")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Bitstamp"], provider: "Coinbase"), "Coinbase")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Binance"], provider: "Binance.US"), "Binance.US")
        // eigene Börse oder ihr Futures-Markt: kein Hinweis
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Binance"], provider: "Binance"))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["BinanceFutures", "Binance Futures"], provider: "Binance"))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["BinanceUS", "Binance.US"], provider: "Binance.US"))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["coinbase"], provider: "Coinbase"))
        // keine Kerzen (Ticker) oder unbekannter Anbieter
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Kraken"], provider: nil))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Kraken"], provider: " "))
    }
}
