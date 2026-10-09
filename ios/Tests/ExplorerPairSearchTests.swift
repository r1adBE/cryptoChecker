import XCTest
@testable import CryptoChecker

/// Suche wie an der Börse — wie `PairSearchTest.kt`: «BTCUSDT», «BTCUSDT Qtly 1225», «BTCUSD_PERP».
final class ExplorerPairSearchTests: XCTestCase {

    private var markets: [Market] { [MarketsConfig.market("Binance")!, MarketsConfig.market("BinanceFutures")!] }

    private let cache: [String: MarketPairsInfo] = [
        "Binance": MarketPairsInfo(lastSyncDate: 1, pairs: [
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT"),
            CurrencyPairInfo("BTC", "EUR", "BTCEUR"),
            CurrencyPairInfo("ETH", "USDT", "ETHUSDT"),
        ]),
        "BinanceFutures": MarketPairsInfo(lastSyncDate: 1, pairs: [
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT_261225", .quarterly),
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT_270326", .biquarterly),
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT", .perpetual),
            CurrencyPairInfo("BTC", "USD", "2:BTCUSD_PERP", .inversePerpetual),
        ]),
    ]

    private func find(_ q: String) -> [String] {
        ExplorerPairSearch.find(q, markets: markets, cache: cache).map {
            "\($0.market.key) \($0.pair.base)/\($0.pair.quote) \($0.pair.contractType)"
        }
    }

    func testJoinedSymbolFindsSpotPerpAndQuarters() {
        XCTAssertEqual(find("BTCUSDT"), [
            "Binance BTC/USDT none",
            "BinanceFutures BTC/USDT perpetual",
            "BinanceFutures BTC/USDT quarterly",
            "BinanceFutures BTC/USDT biquarterly",
        ])
    }

    func testQuarterWordAndDateNarrowDown() {
        XCTAssertEqual(find("BTCUSDT Qtly 1225"), ["BinanceFutures BTC/USDT quarterly"])
        XCTAssertEqual(find("BTCUSDT_261225"), ["BinanceFutures BTC/USDT quarterly"])
        XCTAssertEqual(find("btc usdt 0326"), ["BinanceFutures BTC/USDT biquarterly"])
        XCTAssertEqual(find("BTC USDT Quartal"), ["BinanceFutures BTC/USDT quarterly", "BinanceFutures BTC/USDT biquarterly"])
    }

    func testPerpWord() {
        XCTAssertEqual(find("BTCUSD_PERP"), ["BinanceFutures BTC/USD inversePerpetual"])
        XCTAssertEqual(find("BTC PERP"), ["BinanceFutures BTC/USDT perpetual", "BinanceFutures BTC/USD inversePerpetual"])
    }

    func testClassicQueriesUnchanged() {
        XCTAssertEqual(find("BTC").first, "Binance BTC/USDT none")
        XCTAssertEqual(find("eth/usdt"), ["Binance ETH/USDT none"])
        XCTAssertEqual(find("XYZ"), [])
    }

    func testParseAndDeliveryCode() throws {
        let q = try XCTUnwrap(ExplorerPairSearch.parse("BTCUSDT Qtly 1225"))
        XCTAssertEqual(q.base, "BTCUSDT")
        XCTAssertNil(q.quote)
        XCTAssertEqual(q.date, "1225")
        XCTAssertEqual(ExplorerPairSearch.parse("1000")?.base, "1000")
        XCTAssertEqual(ExplorerPairSearch.deliveryCode(CurrencyPairInfo("BTC", "USDT", "BTCUSDT_261225", .quarterly)), "261225")
        XCTAssertNil(ExplorerPairSearch.deliveryCode(CurrencyPairInfo("BTC", "USDT", "BTCUSDT", .perpetual)))
    }
}
