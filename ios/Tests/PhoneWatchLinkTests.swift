import XCTest
@testable import CryptoChecker

/// Kurse für die Uhr aus der Merkliste (`PhoneWatchLink.quoteRows` + `WatchPhoneQuotes.pick`): Kürzel nur Spot gegen
/// USDT (keine Futures, keine DEX-Pools mit Kennung), beide Listen der Uhr; Favoriten mit Paar genau dieses Paar (Börse
/// und Kontrakt, z. B. BANK/USDT Perp bei Binance Futures). Wie Android `PhoneWatchFeedTest`.
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
        let quotes = WatchPhoneQuotes.pick(rows, coins: ["BTC", "ETH", "SOL", "ADA", "DOT"])
        XCTAssertEqual(quotes.map(\.symbol), ["BTC", "ADA"])
        XCTAssertEqual(quotes.first?.price, 67_000)
        XCTAssertEqual(quotes.first?.exchange, "Binance")
    }

    /// «Wie iPhone»: die Uhr bekommt das Paar des Favoriten (Binance Futures, Perp) samt dessen Kurs — nicht einen
    /// Spot-Kurs einer anderen Börse.
    @MainActor
    func testSourceFollowsThePhonesPair() {
        var bank = watch(1, "BANK", price: 0.0712, contract: .perpetual)
        bank.exchangeName = "Binance Futures"
        bank.favorite = true
        var spot = watch(2, "BANK", price: 0.07)
        spot.exchangeName = "Bybit"
        var eur = watch(3, "BTC", quote: "EUR", price: 60_000)
        eur.exchangeName = "Kraken"
        eur.favorite = true
        eur.sortOrder = 1
        let (phone, pairs) = PhoneWatchLink.favorites([bank, spot, eur])
        XCTAssertEqual(phone, ["BANK", "BTC"])
        XCTAssertEqual(pairs.map(\.key), ["BANK/USDT|Binance Futures|Perp", "BTC/EUR|Kraken|"])
        XCTAssertEqual(pairs.map(\.label), ["BANK Perp", "BTC/EUR"])
        let settings = WatchSettings(mode: .phone, own: ["BANK"], phone: phone, pairs: pairs)
        let quotes = WatchPhoneQuotes.pick(PhoneWatchLink.quoteRows([bank, spot, eur]), coins: settings.kept)
        XCTAssertEqual(quotes.map(\.symbol), ["BANK", "BANK/USDT|Binance Futures|Perp", "BTC/EUR|Kraken|"])
        XCTAssertEqual(quotes.map(\.exchange), ["Bybit", "Binance Futures", "Kraken"])
        XCTAssertEqual(quotes.map(\.price), [0.07, 0.0712, 60_000])
    }
}
