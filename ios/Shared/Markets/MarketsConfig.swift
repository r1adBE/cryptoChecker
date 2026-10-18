import Foundation

/// Registry of all exchanges (counterpart of `config/MarketsConfig.kt` and `util/MarketsConfigUtils.kt`).
/// Same order as on Android, shown in that order; the first entry is the default exchange. A new adapter is
/// added here and on Android (DEVELOPMENT.md § 8).
enum MarketsConfig {
    /// All adapters in display order.
    static let all: [Market] = [
        // Standardbörse
        Coinbase(),
        // Grosse Spot- und Futures-Börsen
        Kraken(), GateIo(), Binance(), BinanceUs(), Kucoin(),
        Okex(), OkexFutures(), BinanceFutures(),
        Bybit(), BybitFutures(), Bitget(), BitgetFutures(),
        Mexc(), MexcFutures(), Htx(), HtxFutures(),
        // Weitere internationale Börsen
        Bitfinex(), Bitstamp(), Bitvavo(), CryptoCom(), Gemini(), Hyperliquid(),
        Woo(), WooFutures(), Deribit(), Phemex(), PhemexFutures(), Poloniex(), OneTrading(),
        // Regionale Börsen
        Upbit(), Bithumb(), BitFlyer(), Btcturk(), Bitso(), Indodax(), ZebPay(), IndependentReserve(),
        // Kleinere Börsen und DEX-Daten
        Latoken(), NonKyc(), DexScreener(),
    ]

    /// Suche per Schlüssel; bei doppeltem Schlüssel gewinnt der spätere Eintrag.
    static let byKey: [String: Market] = {
        var table: [String: Market] = [:]
        for market in all { table[market.key] = market }
        return table
    }()

    static func market(_ key: String) -> Market? { byKey[key] }

    // MARK: Hilfen wie MarketsConfigUtils

    /// Die Standardbörse — der erste Eintrag von `all`.
    static var defaultMarket: Market { all[0] }

    /// Platzhalter für Schlüssel, die es nicht (mehr) gibt.
    static let unknownMarket: Market = UnknownMarket()

    /// Wie `MarketsConfigUtils.getMarketByKey`: Fehlt der Schlüssel oder ist er unbekannt, kommt `UnknownMarket`.
    static func marketOrUnknown(_ key: String?) -> Market {
        key.flatMap { byKey[$0] } ?? unknownMarket
    }
}
