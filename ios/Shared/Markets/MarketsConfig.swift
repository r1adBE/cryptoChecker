import Foundation

/// Entspricht `config/MarketsConfig.kt` (plus `util/MarketsConfigUtils.kt`).
/// Reihenfolge wie in Android; die erste Börse ist die Standardbörse.
enum MarketsConfig {
    static let all: [Market] = [
        Coinbase(), // Standardbörse
        Kraken(),
        GateIo(),
        Binance(),
        BinanceUs(),
        Kucoin(),
        Okex(),
        OkexFutures(),
        BinanceFutures(),
        Bybit(),
        BybitFutures(),
        Bitget(),
        BitgetFutures(),
        Mexc(),
        MexcFutures(),
        Htx(),
        HtxFutures(),
        Bitfinex(),
        Bitstamp(),
        Bitvavo(),
        CryptoCom(),
        Gemini(),
        Hyperliquid(),
        Woo(),
        WooFutures(),
        Deribit(),
        Phemex(),
        PhemexFutures(),
        Poloniex(),
        OneTrading(),
        Upbit(),
        Bithumb(),
        BitFlyer(),
        Btcturk(),
        Bitso(),
        Indodax(),
        ZebPay(),
        IndependentReserve(),
        Latoken(),
        NonKyc(),
        DexScreener(),
    ]

    static let byKey: [String: Market] =
        Dictionary(all.map { ($0.key, $0) }, uniquingKeysWith: { _, last in last })

    static func market(_ key: String) -> Market? { byKey[key] }

    // MARK: Wie MarketsConfigUtils

    /// Standardbörse (erste Börse der Liste).
    static var defaultMarket: Market { all[0] }

    static let unknownMarket: Market = UnknownMarket()

    /// Wie `MarketsConfigUtils.getMarketByKey`: unbekannte Schlüssel → `UnknownMarket`.
    static func marketOrUnknown(_ key: String?) -> Market {
        guard let key, let market = byKey[key] else { return unknownMarket }
        return market
    }
}
