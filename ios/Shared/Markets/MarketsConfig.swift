import Foundation

/// Verzeichnis aller Börsen (Gegenstück zu `config/MarketsConfig.kt` samt `util/MarketsConfigUtils.kt`).
/// Die Reihenfolge ist dieselbe wie auf Android und wird so angezeigt; Platz 1 ist die Standardbörse.
enum MarketsConfig {
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
