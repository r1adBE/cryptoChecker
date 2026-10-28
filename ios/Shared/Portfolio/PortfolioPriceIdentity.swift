import Foundation

/// Gleicher Token auch beim Binance-Kurs des Portfolios (wie Merkliste und Kerzen-Kette): Der Sammel-Ticker
/// `SYMBOLUSDT` nennt nur das Kürzel. Heisst der Coin laut den Paaren der Merkliste klar anders als bei Binance (LUNA
/// Terra Classic gegen Terra 2.0, TON Tokamak gegen Toncoin, GMT GoMining gegen STEPN, HOT Hydro gegen Holo), gilt
/// Binances Kurs nicht — dann Merklisten-Paar bzw. Kerzen-Kette (die prüft den Namen ebenso). Ist kein Name bekannt,
/// gilt Binance nur, wenn Binance dem Kürzel einen Namen gibt und es kein bekanntes Doppel-Kürzel ist (`binanceCheck`);
/// sonst bleibt der Kurs «ungeprüft». Führen die Paarlisten anderer Börsen unter dem Kürzel klar verschiedene Token,
/// zählt Binances Kurs weiter, aber als «Kurs ungeprüft» (`BinanceCheck.unverified`).
/// Reine Logik — wie `PortfolioPriceIdentity.kt`.
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

    /// Wie Binances Ticker für einen Coin gilt (`binanceCheck`).
    enum BinanceCheck: Equatable {
        /// Gleicher Token oder (ohne bekannten Namen) eindeutiges Kürzel: Binances Kurs gilt.
        case use
        /// Klar anderer Name: Binance nicht, sondern Merklisten-Paar bzw. Kerzen-Kette.
        case skip
        /// Kein Name bekannt und Kürzel bei Binance ohne Namen oder mehrdeutig: nicht Binance, sonst «ungeprüft».
        case unchecked
        /// Kein Name bekannt, Binance nennt das Kürzel, aber andere Listen führen darunter klar verschiedene Token
        /// (`ambiguous`): Binances Kurs zählt (lieber ein markierter Wert als 0), ist aber nicht bestätigt — die Zeile
        /// zeigt «Kurs ungeprüft», Portfolio-Alarme behandeln ihn wie einen alten Kurs (wesentlich → Pause).
        case unverified
    }

    /// Kürzel, unter dem mehrere Token bekannt sind (`TokenIdentity.known` mit mehr als einer Gruppe: LUNA, LUNC, TON,
    /// GMT, HOT, CAT, NVDA) — ohne Namen ist dort nicht zu sagen, welchen Token Binance meint.
    static func knownCollision(_ coin: String) -> Bool { (TokenIdentity.known[TokenIdentity.key(coin)]?.count ?? 0) > 1 }

    /// Mehrere Token unter dem Kürzel? Ja, wenn sich Binances Name `binanceName` und die Namen anderer Paarlisten
    /// `otherNames` (je Börse einer) klar widersprechen (`TokenIdentity.conflicting`) — etwa ein Kürzel, das an einer
    /// anderen Börse für einen ganz anderen Token steht. Ein Bestand ohne Namen kann dann jeder davon sein.
    static func ambiguous(_ coin: String, binanceName: String?, otherNames: [String?]) -> Bool {
        TokenIdentity.conflicting(symbol: TokenIdentity.key(coin), [binanceName] + otherNames)
    }

    /// Prüfung für Binances Ticker: Mit bekanntem Namen (`knownName`, aus der Merkliste) zählt nur, ob er zu Binances
    /// Namen passt (`binanceTickerAllowed`). Ohne bekannten Namen gilt Binance nur, wenn Binances Paarliste dem Kürzel
    /// einen Namen gibt (`binanceName`) und es keine bekannte Kollision ist (`knownCollision`); sonst `.unchecked`.
    /// Kennen andere Listen (`otherNames`) unter dem Kürzel einen klar anderen Token (`ambiguous`): `.unverified` —
    /// eindeutiges Kürzel = bestätigt, mehrdeutiges = ungeprüft.
    static func binanceCheck(_ coin: String, knownName: String?, binanceName: String?,
                             otherNames: [String?] = []) -> BinanceCheck {
        if let knownName {
            return binanceTickerAllowed(coin, watchName: knownName, binanceName: binanceName) ? .use : .skip
        }
        let named = !(binanceName ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        guard named && !knownCollision(coin) else { return .unchecked }
        return ambiguous(coin, binanceName: binanceName, otherNames: otherNames) ? .unverified : .use
    }

    /// Aufteilung von `binanceSplit`: `use` für die Binance-Abfrage (samt `unverified`), `unchecked` ohne anderen Kurs
    /// «ungeprüft», `unverified` mit Binance-Kurs, aber «Kurs ungeprüft» (`BinanceCheck.unverified`).
    struct BinanceSplit: Equatable {
        let use: [String]
        let unchecked: Set<String>
        var unverified: Set<String> = []
    }

    /// `coins` nach `binanceCheck` aufgeteilt; `knownNames` wie `watchNames`, `otherNames` je Coin wie bei `ambiguous`.
    static func binanceSplit(_ coins: [String], knownNames: [String: String?],
                             binanceName: (String) -> String?,
                             otherNames: (String) -> [String?] = { _ in [] }) -> BinanceSplit {
        var use: [String] = []
        var unchecked = Set<String>()
        var unverified = Set<String>()
        for coin in coins {
            let known: String? = knownNames[coin] ?? nil
            switch binanceCheck(coin, knownName: known, binanceName: binanceName(coin), otherNames: otherNames(coin)) {
            case .use: use.append(coin)
            case .unverified:
                use.append(coin)
                unverified.insert(coin)
            case .unchecked: unchecked.insert(coin)
            case .skip: break
            }
        }
        return BinanceSplit(use: use, unchecked: unchecked, unverified: unverified)
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
