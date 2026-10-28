import XCTest
@testable import CryptoChecker

/// Wie `StarterPairsTest.kt`: Binance überall ausser den USA (auch CH/LI), in den USA Binance.US — beide …/USDT.
final class StarterPairsTests: XCTestCase {

    func testExchangePerRegion() {
        XCTAssertEqual(StarterPairs.exchangeKey("CH"), "Binance")
        XCTAssertEqual(StarterPairs.exchangeKey(" li "), "Binance")
        XCTAssertEqual(StarterPairs.exchangeKey("us"), "BinanceUs")
        XCTAssertEqual(StarterPairs.exchangeKey("DE"), "Binance")
        XCTAssertEqual(StarterPairs.exchangeKey(nil), "Binance")
        XCTAssertEqual(StarterPairs.startQuote("CH"), "USDT")
        XCTAssertEqual(StarterPairs.startQuote("US"), "USDT")
        XCTAssertEqual(StarterPairs.startQuote(nil), "USDT")
    }

    func testBinanceAndBinanceUs() {
        let binance = StarterPairs.pair(for: "btc", region: "CH")
        XCTAssertEqual(binance.exchangeKey, "Binance")
        XCTAssertEqual(binance.pair.quote, "USDT")
        XCTAssertEqual(binance.pair.pairId, "BTCUSDT")
        let us = StarterPairs.pair(for: "SOL", region: "US")
        XCTAssertEqual(us.exchangeKey, "BinanceUs")
        XCTAssertEqual(us.pair.pairId, "SOLUSDT")
    }

    /// Binance sperrt den Standort (451/403) → Startpaare auf Binance.US, wie `StarterPairsTest.kt`.
    func testGeoBlockSwitchesToBinanceUs() {
        XCTAssertTrue(StarterPairs.isGeoBlocked(httpCode: 451))
        XCTAssertTrue(StarterPairs.isGeoBlocked(httpCode: 403))
        XCTAssertFalse(StarterPairs.isGeoBlocked(httpCode: 429))
        XCTAssertFalse(StarterPairs.isGeoBlocked(httpCode: nil))
        XCTAssertEqual(StarterPairs.effectiveRegion("CH", binanceBlocked: true), "US")
        XCTAssertEqual(StarterPairs.effectiveRegion(nil, binanceBlocked: true), "US")
        XCTAssertEqual(StarterPairs.effectiveRegion("CH", binanceBlocked: false), "CH")
        XCTAssertEqual(StarterPairs.effectiveRegion("US", binanceBlocked: true), "US")
        let blocked = StarterPairs.pair(for: "btc", region: StarterPairs.effectiveRegion("DE", binanceBlocked: true))
        XCTAssertEqual(blocked.exchangeKey, "BinanceUs")
        XCTAssertEqual(blocked.pair.pairId, "BTCUSDT")
        XCTAssertEqual(StarterCoins.fallback(exchangeKey: StarterPairs.exchangeKey(
            StarterPairs.effectiveRegion("CH", binanceBlocked: true))).map(\.symbol), ["BTC", "ETH", "BNB", "SOL", "DOGE"])
        XCTAssertFalse(StarterPairs.shouldProbe(deviceRegion: "us"))
        XCTAssertTrue(StarterPairs.shouldProbe(deviceRegion: "CH"))
    }

    func testBlockedAfterProbe() {
        XCTAssertTrue(StarterPairs.blockedAfterProbe(previous: false, httpCode: 451))
        XCTAssertTrue(StarterPairs.blockedAfterProbe(previous: false, httpCode: 403))
        XCTAssertFalse(StarterPairs.blockedAfterProbe(previous: true, httpCode: 200))
        // Kein Netz oder anderer Fehler: Stand bleibt
        XCTAssertTrue(StarterPairs.blockedAfterProbe(previous: true, httpCode: nil))
        XCTAssertFalse(StarterPairs.blockedAfterProbe(previous: false, httpCode: nil))
        XCTAssertTrue(StarterPairs.blockedAfterProbe(previous: true, httpCode: 500))
        XCTAssertFalse(StarterPairs.blockedAfterProbe(previous: false, httpCode: 429))
    }

    func testExchangeNameForStarterText() {
        XCTAssertEqual(StarterPairs.exchangeName("CH"), "Binance")
        XCTAssertEqual(StarterPairs.exchangeName("US"), "Binance.US")
        // Standort-Sperre: die Start-Auswahl nennt Binance.US
        XCTAssertEqual(StarterPairs.exchangeName(StarterPairs.effectiveRegion("DE", binanceBlocked: true)), "Binance.US")
        // Ohne Registry-Namen: Schlüssel (wie Android)
        XCTAssertEqual(StarterPairs.exchangeName("US", name: { _ in nil }), "BinanceUs")
        XCTAssertEqual(StarterPairs.startQuote("US"), "USDT")
    }

    func testFallbackCoins() {
        XCTAssertEqual(StarterCoins.fallback(exchangeKey: "Binance").map(\.symbol), ["BTC", "ETH", "XRP", "BNB", "SOL"])
        // Binance.US: ohne XRP
        XCTAssertEqual(StarterCoins.fallback(exchangeKey: "BinanceUs").map(\.symbol), ["BTC", "ETH", "BNB", "SOL", "DOGE"])
    }

    func testParseBinance24h() throws {
        let text = """
        [{"symbol":"BTCUSDT","lastPrice":"81000.50","priceChangePercent":"1.25"},\
        {"symbol":"SOLUSDT","lastPrice":"150.25"},{"symbol":"ETHBTC","lastPrice":"0.05"}]
        """
        let prices = try StarterPriceSource.parseBinance24h(text)
        XCTAssertEqual(prices["BTC"], StarterPrice(price: 81000.5, change: 1.25))
        XCTAssertEqual(prices["SOL"], StarterPrice(price: 150.25, change: nil))
        XCTAssertNil(prices["ETH"])
    }
}
