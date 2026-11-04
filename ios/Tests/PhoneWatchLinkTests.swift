import XCTest
@testable import CryptoChecker

/// Kurse für die Uhr aus der Merkliste (`PhoneWatchLink.quoteRows` + `WatchPhoneQuotes.pick`): Kürzel nur Spot gegen
/// USDT (keine Futures, keine DEX-Pools), beide Listen der Uhr; Favoriten mit Paar genau dieses Paar (Börse und Kontrakt,
/// z. B. BANK/USDT Perp bei Binance Futures). Die Kennung einer Börse («BTCUSDT», «XBTEUR») macht kein Pool — Pool nur
/// bei DexScreener, mit der Kennung im Schlüssel. Wie Android `PhoneWatchFeedTest`.
final class PhoneWatchLinkTests: XCTestCase {

    /// Wie die Merkliste speichert: Börsen-Paare mit ihrer Kennung (`pairId` leer = Basis + Quote, z. B. «BTCUSDT»),
    /// DexScreener «chain/address».
    private func watch(_ id: Int64, _ base: String, quote: String = "USDT", price: Double = 10,
                       contract: FuturesContractType = .none, exchange: String = "Binance", pairId: String? = "",
                       time: Int64 = 1_000) -> Watch {
        var w = Watch(id: id, exchangeKey: exchange, exchangeName: exchange, baseAsset: base, quoteAsset: quote)
        w.contractType = contract
        w.pairId = pairId == "" ? base + quote : pairId
        w.lastPrice = price
        w.lastUpdate = time
        w.change24h = 1.5
        return w
    }

    @MainActor
    func testSpotUsdtOfBothLists() {
        let watches = [watch(1, "BTC", price: 67_000), watch(2, "ETH", price: 3_000, contract: .perpetual),
                       watch(3, "SOL", price: 150, exchange: "DexScreener",
                             pairId: "solana/8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj"),
                       watch(4, "ADA", price: 0.5), watch(5, "DOT", quote: "EUR")]
        let rows = PhoneWatchLink.quoteRows(watches)
        let quotes = WatchPhoneQuotes.pick(rows, coins: ["BTC", "ETH", "SOL", "ADA", "DOT"])
        XCTAssertEqual(quotes.map(\.symbol), ["BTC", "ADA"])
        XCTAssertEqual(quotes.first?.price, 67_000)
        XCTAssertEqual(quotes.first?.exchange, "Binance")
    }

    /// Binance-Spot mit Kennung «BTCUSDT»: Spot, Schlüssel ohne «Pool», die Uhr darf ihn selbst abfragen.
    @MainActor
    func testExchangePairIdStaysSpot() {
        var btc = watch(1, "BTC", price: 67_000)
        btc.favorite = true
        XCTAssertEqual(PhoneWatchLink.watchContract(btc), "")
        let (_, pairs) = PhoneWatchLink.favorites([btc])
        XCTAssertEqual(pairs.map(\.key), ["BTC/USDT|Binance|"])
        XCTAssertEqual(pairs.map(\.label), ["BTC"])
        XCTAssertEqual(pairs.first?.selfFetchable, true)
    }

    /// Zwei DexScreener-Pools mit gleicher Basis/Quote: eigene Schlüssel, die Uhr bekommt den Kurs des markierten.
    @MainActor
    func testTwoPoolsStayApart() {
        var marked = watch(1, "PEPE", price: 4e-9, exchange: "DexScreener",
                           pairId: "ethereum/0xa43fe16908251ee70ef74718545e4fe6c5ccec9f")
        marked.favorite = true
        let other = watch(2, "PEPE", price: 5e-9, exchange: "DexScreener",
                          pairId: "ethereum/0x11950d141ecb863f01007add7d1a342041227b58", time: 2_000)
        let (phone, pairs) = PhoneWatchLink.favorites([marked, other])
        XCTAssertEqual(pairs.map(\.key), ["PEPE/USDT|DexScreener|Pool995b79ee"])
        XCTAssertEqual(PhoneWatchLink.watchContract(other), "Pool0275864c")
        let settings = WatchSettings(mode: .phone, own: [], phone: phone, pairs: pairs)
        let quotes = WatchPhoneQuotes.pick(PhoneWatchLink.quoteRows([marked, other]), coins: settings.kept)
        XCTAssertEqual(quotes.map(\.symbol), ["PEPE/USDT|DexScreener|Pool995b79ee"])
        XCTAssertEqual(quotes.map(\.price), [4e-9])
    }

    /// «Wie iPhone»: die Uhr bekommt das Paar des Favoriten (Binance Futures, Perp) samt dessen Kurs — nicht einen
    /// Spot-Kurs einer anderen Börse.
    @MainActor
    func testSourceFollowsThePhonesPair() {
        var bank = watch(1, "BANK", price: 0.0712, contract: .perpetual, exchange: "Binance Futures")
        bank.favorite = true
        let spot = watch(2, "BANK", price: 0.07, exchange: "Bybit")
        var eur = watch(3, "BTC", quote: "EUR", price: 60_000, exchange: "Kraken", pairId: "XBTEUR")
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
