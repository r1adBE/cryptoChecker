import Foundation

/// Registry of all exchanges (counterpart of `config/ExchangesConfig.kt` and `util/ExchangesConfigUtils.kt`).
/// Same order as on Android, shown in that order; the first entry is the default exchange. A new adapter is
/// added here and on Android (DEVELOPMENT.md § 8).
enum ExchangesConfig {
    /// All adapters in display order.
    static let all: [Exchange] = [
        // Standardbörse
        Coinbase(),
        // Grosse Spot- und Futures-Börsen
        Kraken(), GateIo(), Binance(), BinanceUs(), Kucoin(),
        Okex(), OkexFutures(), BinanceFutures(),
        Bybit(), BybitFutures(), Bitget(), BitgetFutures(),
        Mexc(), MexcFutures(), Htx(), HtxFutures(),
        // Weitere internationale Börsen
        Bitfinex(), Bitstamp(), Bitvavo(), CryptoCom(), Gemini(), Hyperliquid(), Aster(), BingX(), BingXFutures(), Lighter(),
        Woo(), WooFutures(), Deribit(), Phemex(), PhemexFutures(), BitunixFutures(), Poloniex(), OneTrading(),
        // Regionale Börsen
        Upbit(), Bithumb(), BitFlyer(), Bitbank(), Coincheck(), Btcturk(), Paribu(), Icrypex(), Bitso(),
        MercadoBitcoin(), Indodax(), ZebPay(), CoinDcx(), HashKey(), IndependentReserve(), BtcMarkets(), Luno(),
        // Kleinere Börsen und DEX-Daten
        Latoken(), NonKyc(), DexScreener(),
        // Devisen-Referenzkurse der EZB (Abschnitt «Forex», keine Alarme)
        EcbReference(),
    ]

    /// Suche per Schlüssel; bei doppeltem Schlüssel gewinnt der spätere Eintrag.
    static let byKey: [String: Exchange] = {
        var table: [String: Exchange] = [:]
        for exchange in all { table[exchange.key] = exchange }
        return table
    }()

    static func exchange(_ key: String) -> Exchange? { byKey[key] }

    // MARK: Hilfen wie ExchangesConfigUtils

    /// Die Standardbörse — der erste Eintrag von `all`.
    static var defaultExchange: Exchange { all[0] }

    /// Platzhalter für Schlüssel, die es nicht (mehr) gibt.
    static let unknownExchange: Exchange = UnknownExchange()

    /// Wie `ExchangesConfigUtils.getExchangeByKey`: Fehlt der Schlüssel oder ist er unbekannt, kommt `UnknownExchange`.
    static func exchangeOrUnknown(_ key: String?) -> Exchange {
        key.flatMap { byKey[$0] } ?? unknownExchange
    }
}
