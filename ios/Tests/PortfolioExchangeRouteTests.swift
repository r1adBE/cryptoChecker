import XCTest
@testable import CryptoChecker

/// Kurs von der eingetragenen Börse — wie `PortfolioExchangeRouteTest.kt`.
final class PortfolioExchangeRouteTests: XCTestCase {
    private let exchanges = ["Binance": "Binance", "Kraken": "Kraken", "BinanceFutures": "Binance Futures",
                           "Bitstamp": "Bitstamp", "DexScreener": "DEX"]

    func testOneExchangePerCoinMixedFallsBack() {
        let r = PortfolioExchangeRoute.exchangeByCoin([
            (coin: "btc", exchange: "Kraken"), (coin: "BTC", exchange: "kraken"),
            (coin: "ETH", exchange: "Kraken"), (coin: "ETH", exchange: "Bitstamp"),
            (coin: "SOL", exchange: nil), (coin: "SOL", exchange: " "),
        ])
        XCTAssertEqual(r.single, ["BTC": "Kraken"])
        XCTAssertEqual(r.mixed, ["ETH"])
    }

    func testOnlySpotMarkets() {
        XCTAssertEqual(PortfolioExchangeRoute.exchange("kraken", exchanges: exchanges)?.key, "Kraken")
        XCTAssertNil(PortfolioExchangeRoute.exchange("Binance", exchanges: exchanges))
        XCTAssertNil(PortfolioExchangeRoute.exchange("Binance Futures", exchanges: exchanges))
        XCTAssertNil(PortfolioExchangeRoute.exchange("DEX", exchanges: exchanges))
        XCTAssertNil(PortfolioExchangeRoute.exchange("Meine Bank", exchanges: exchanges))
    }

    func testPairPreference() {
        let pairs = [ExchangePair("BTC", "EUR", "XXBTZEUR"), ExchangePair("BTC", "USD", "XXBTZUSD"),
                     ExchangePair("ETH", "USDT", "ETHUSDT"), ExchangePair("BTC", "USDC", "XBTUSDC")]
        XCTAssertEqual(PortfolioExchangeRoute.pickPair("btc", pairs: pairs)?.quote, "USD")
        XCTAssertEqual(PortfolioExchangeRoute.pickPair("ETH", pairs: pairs)?.quote, "USDT")
        XCTAssertNil(PortfolioExchangeRoute.pickPair("SOL", pairs: pairs))
        XCTAssertNil(PortfolioExchangeRoute.pickPair("BTC", pairs: [ExchangePair("BTC", "USDC", nil)]))
    }

    func testConversion() {
        XCTAssertEqual(PortfolioExchangeRoute.toUsdt(100, quote: "USD") { _ in nil } ?? .nan, 100, accuracy: 1e-12)
        XCTAssertEqual(PortfolioExchangeRoute.toUsdt(80, quote: "CHF") { $0 == "CHF" ? 0.8 : nil } ?? .nan, 100, accuracy: 1e-9)
        XCTAssertNil(PortfolioExchangeRoute.toUsdt(80, quote: "CHF") { _ in nil })
        XCTAssertNil(PortfolioExchangeRoute.toUsdt(80, quote: "XYZ") { _ in 1 })
    }

    func testScaleAndSourcesText() {
        let scaled = PortfolioExchangeRoute.scaleCloses(["BTC": [1: 100, 2: 110], "ETH": [1: 10]],
                                                        prices: ["BTC": 101, "ETH": 10], binance: ["BTC": 100])
        XCTAssertEqual(scaled["BTC"]?[2] ?? .nan, 111.1, accuracy: 1e-9)
        XCTAssertEqual(scaled["ETH"]?[1] ?? .nan, 10, accuracy: 1e-9)
        XCTAssertEqual(PortfolioExchangeRoute.sourcesText(["ETH": "Kraken", "BTC": "Kraken", "SOL": "Bitstamp"]),
                       "Bitstamp (SOL); Kraken (BTC, ETH)")
    }

    func testHistoryRangesFollowTheirCloses() {
        let closes: [String: [Int: Double]] = ["BTC": [1: 100], "SOL": [1: 20], "ETH": [1: 10]]
        let ranges: [String: [Int: PortfolioDayRange]] = [
            "BTC": [1: PortfolioDayRange(open: 98, high: 104, low: 96)],
            "SOL": [1: PortfolioDayRange(open: 19, high: 21, low: 18)],
            "ETH": [1: PortfolioDayRange(open: 9, high: 11, low: 8)],
        ]
        // BTC Ausweich mit Bezug, SOL Ausweich ohne Binance-Kurs (Neustart), ETH ohne eigene Börse
        let result = PortfolioExchangeRoute.historyCloses(closes, ranges: ranges, own: [], routed: ["BTC", "SOL"], failed: [],
                                                          prices: ["BTC": 102, "SOL": 21, "ETH": 10], binance: ["BTC": 100])
        XCTAssertEqual(Set(result.closes.keys), ["BTC", "ETH"])
        XCTAssertEqual(Set(result.ranges.keys), ["BTC", "ETH"])
        XCTAssertEqual(result.ranges["BTC"]?[1]?.high ?? .nan, 106.08, accuracy: 1e-9)
        XCTAssertEqual(result.ranges["ETH"]?[1]?.open ?? .nan, 9, accuracy: 1e-12)
        XCTAssertEqual(result.scaled, ["BTC"])
    }

    func testOwnDailyUsdtConvertsRanges() {
        let daily = PortfolioCoinDaily(closes: [10: 90], ranges: [10: PortfolioDayRange(open: 88, high: 99, low: 81)])
        let usdt = PortfolioExchangeRoute.ownDailyUsdt(daily, quote: "EUR", usdToByDay: [10: 0.9])
        XCTAssertEqual(usdt?.closes[10] ?? .nan, 100, accuracy: 1e-9)
        XCTAssertEqual(usdt?.ranges[10]?.high ?? .nan, 110, accuracy: 1e-9)
        XCTAssertEqual(usdt?.ranges[10]?.low ?? .nan, 90, accuracy: 1e-9)
    }

    func testStitchedWicks() {
        let own = PortfolioCoinDaily(closes: [5: 200], ranges: [5: PortfolioDayRange(open: 190, high: 210, low: 180)])
        let binance = PortfolioCoinDaily(closes: [4: 95, 5: 100],
                                         ranges: [4: PortfolioDayRange(open: 90, high: 99, low: 89),
                                                  5: PortfolioDayRange(open: 1, high: 1, low: 1)])
        let stitched = PortfolioExchangeRoute.stitch(own, binance: binance)
        XCTAssertEqual(stitched.binanceBefore, 5)
        XCTAssertEqual(stitched.daily.ranges[4]?.high ?? .nan, 198, accuracy: 1e-9)
        XCTAssertEqual(stitched.daily.ranges[5]?.high ?? .nan, 210, accuracy: 1e-12)
        XCTAssertNil(PortfolioExchangeRoute.stitch(own, binance: nil).binanceBefore)
    }

    /// Wie `usdtParityShownWhenValuesDependOnIt` (Android).
    func testUsdtParityShownWhenValuesDependOnIt() {
        XCTAssertTrue(PortfolioExchangeRoute.usdtParityShown(displayCurrency: "CHF", converted: true, sources: [:]))
        XCTAssertTrue(PortfolioExchangeRoute.usdtParityShown(displayCurrency: "usd", converted: false, sources: [:]))
        XCTAssertTrue(PortfolioExchangeRoute.usdtParityShown(displayCurrency: "USDT", converted: false, sources: ["BTC": "Kraken"]))
        XCTAssertFalse(PortfolioExchangeRoute.usdtParityShown(displayCurrency: "USDT", converted: false, sources: [:]))
        XCTAssertFalse(PortfolioExchangeRoute.usdtParityShown(displayCurrency: "CHF", converted: false, sources: [:]))
    }
}
