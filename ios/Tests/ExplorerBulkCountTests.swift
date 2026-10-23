import XCTest
@testable import CryptoChecker

/// «312 neu, 434 schon in der Merkliste»: Zählung vor dem Hinzufügen — wie Android `BulkAddProgressTest`.
final class ExplorerBulkCountTests: XCTestCase {
    private func pair(_ base: String) -> MarketPair {
        MarketPair(base, "USDT", base + "USDT")
    }

    private func watch(_ id: Int64, _ base: String, market: String = "Binance") -> Watch {
        Watch(id: id, marketKey: market, marketName: market, baseAsset: base, quoteAsset: "USDT",
              pairId: base + "USDT")
    }

    func testCountsOnlyPairsNotInWatchlist() {
        let pairs = [pair("BTC"), pair("ETH"), pair("SOL")]
        XCTAssertEqual(ExplorerBulkCount.newCount(marketKey: "Binance", pairs: pairs, watches: [watch(1, "ETH")]), 2)
        XCTAssertEqual(ExplorerBulkCount.newCount(marketKey: "Binance", pairs: pairs, watches: []), 3)
        // Andere Börse zählt nicht als vorhanden
        XCTAssertEqual(ExplorerBulkCount.newCount(marketKey: "Kraken", pairs: pairs, watches: [watch(1, "ETH")]), 3)
        let all = [watch(1, "BTC"), watch(2, "ETH"), watch(3, "SOL")]
        XCTAssertEqual(ExplorerBulkCount.newCount(marketKey: "Binance", pairs: pairs, watches: all), 0)
        XCTAssertEqual(ExplorerBulkCount.newCount(marketKey: nil, pairs: pairs, watches: []), 0)
    }
}
