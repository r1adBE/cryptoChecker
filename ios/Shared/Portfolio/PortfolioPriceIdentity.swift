import Foundation

/// Gleicher Token auch beim Binance-Kurs des Portfolios (K-8, wie Merkliste und Kerzen-Kette): Der Sammel-Ticker
/// `SYMBOLUSDT` nennt nur das Kürzel. Heisst der Coin laut den Paaren der Merkliste klar anders als bei Binance (LUNA
/// Terra Classic gegen Terra 2.0, TON Tokamak gegen Toncoin, GMT GoMining gegen STEPN, HOT Hydro gegen Holo), gilt
/// Binances Kurs nicht — dann Merklisten-Paar bzw. Kerzen-Kette (die prüft den Namen ebenso). Unbekannte Namen:
/// Binance wie bisher. Reine Logik — wie `PortfolioPriceIdentity.kt`.
enum PortfolioPriceIdentity {
    /// Ein Paar der Merkliste für `watchNames`.
    struct WatchName: Equatable {
        let base: String
        let name: String?
        var disabled: Bool = false
    }

    /// Name des Coins je Kürzel laut den aktiven Paaren der Merkliste (`watches`: Kürzel, Name aus der Paarliste der
    /// Börse, abgeschaltet); widersprechen sich die Paare, nil (`TokenIdentity.consensus`).
    static func watchNames(_ coins: [String], watches: [WatchName]) -> [String: String?] {
        var out: [String: String?] = [:]
        for coin in coins {
            let key = TokenIdentity.key(coin)
            let names = watches.filter { !$0.disabled && TokenIdentity.key($0.base) == key }.map(\.name)
            out[coin] = .some(TokenIdentity.consensus(symbol: key, names))
        }
        return out
    }

    /// Darf Binances Ticker für `coin` gelten? Nein nur bei klar anderem Namen (`TokenIdentity.compatible`).
    static func binanceTickerAllowed(_ coin: String, watchName: String?, binanceName: String?) -> Bool {
        guard let watchName else { return true }
        return TokenIdentity.compatible(symbol: coin, watchName, binanceName)
    }

    /// Die Coins aus `coins`, deren Binance-Ticker gelten darf (`binanceTickerAllowed`).
    static func binanceTickerCoins(_ coins: [String], watchNames: [String: String?],
                                   binanceName: (String) -> String?) -> [String] {
        coins.filter { coin in
            let name: String? = watchNames[coin] ?? nil
            return binanceTickerAllowed(coin, watchName: name, binanceName: binanceName(coin))
        }
    }
}
