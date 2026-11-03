import XCTest
@testable import CryptoChecker

/// Kurse für die Uhr aus der Merkliste (`PhoneWatchLink.quoteRows` + `WatchPhoneQuotes.pick`): nur Spot gegen USDT
/// (keine Futures, keine DEX-Pools mit Kennung), beide Listen der Uhr. Wie Android `PhoneWatchFeedTest`.
final class PhoneWatchLinkTests: XCTestCase {

    private func watch(_ id: Int64, _ base: String, quote: String = "USDT", price: Double = 10,
                       contract: FuturesContractType = .none, pairId: String? = nil) -> Watch {
        var w = Watch(id: id, exchangeKey: "binance", exchangeName: "Binance", baseAsset: base, quoteAsset: quote)
        w.contractType = contract
        w.pairId = pairId
        w.lastPrice = price
        w.lastUpdate = 1_000
        w.change24h = 1.5
        return w
    }

    @MainActor
    func testSpotUsdtOfBothLists() {
        let watches = [watch(1, "BTC", price: 67_000), watch(2, "ETH", price: 3_000, contract: .perpetual),
                       watch(3, "SOL", price: 150, pairId: "solana:pool"), watch(4, "ADA", price: 0.5),
                       watch(5, "DOT", quote: "EUR")]
        let rows = PhoneWatchLink.quoteRows(watches)
        XCTAssertFalse(rows.contains { $0.base == "DOT" })
        let quotes = WatchPhoneQuotes.pick(rows, coins: ["BTC", "ETH", "SOL", "ADA", "DOT"])
        XCTAssertEqual(quotes.map(\.symbol), ["BTC", "ADA"])
        XCTAssertEqual(quotes.first?.price, 67_000)
        XCTAssertEqual(quotes.first?.exchange, "Binance")
    }
}
