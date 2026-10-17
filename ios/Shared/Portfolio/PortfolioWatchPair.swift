import Foundation

/// Kurs aus einem Merklisten-Paar für einen Coin, den Binance-Spot nicht führt — wie
/// `PortfolioWatchPair.kt` (Android).
///
/// Folgt der Nutzer dem Coin in der Merkliste an einer anderen Spot-Börse (z. B. Kraken XYZ/EUR), gilt
/// der Kurs dieses Paars — es ist der Token, den der Nutzer selbst gewählt hat — statt der ungeprüften
/// letzten Stundenkerze der Ausweich-Kette (gleiches Kürzel kann dort ein anderer Token sein). Gegenwährung
/// wie beim Weg über die eingetragene Börse (`PortfolioExchangeRoute.quotes`: USDT, USD, Fiat).
enum PortfolioWatchPair {

    /// Ein Merklisten-Paar (nur die Felder, die die Wahl braucht).
    struct Candidate: Equatable, Sendable {
        let marketKey: String
        let base: String
        let quote: String
        let pairId: String?
        /// Spot (kein Futures-Kontrakt).
        let spot: Bool
        let disabled: Bool
        /// Letzte Aktualisierung (ms) — bei gleicher Gegenwährung gewinnt das frischere Paar.
        let lastUpdate: Int64
        /// Voller Name des Coins laut Paarliste der Börse (TokenIdentity, K-8); nil = unbekannt.
        var name: String? = nil
    }

    /// Bestes Paar für `coin`: aktiv, Spot, Spot-Börse der App ausser Binance (`PortfolioExchangeRoute.isSpot`,
    /// Binance führt den Coin ja nicht), Gegenwährung aus `PortfolioExchangeRoute.quotes` (in dieser Reihenfolge);
    /// bei Gleichstand das zuletzt aktualisierte, dann nach Börse. nil = keines.
    /// Gleicher Token (K-8): Nennen die Börsen der passenden Paare den Coin klar verschieden
    /// (`TokenIdentity.conflicting`, z. B. «Simon's Cat» und «Catcoin»), ist unklar, welcher Token im
    /// Portfolio steht — dann keines (kein geratener Kurs).
    static func pick(_ coin: String, watches: [Candidate]) -> Candidate? {
        let base = PortfolioCalculator.normalizeCoin(coin)
        let quotes = PortfolioExchangeRoute.quotes
        let usable = watches.filter { w in
            !w.disabled && w.spot
                && PortfolioExchangeRoute.isSpot(w.marketKey)
                && w.marketKey != PortfolioExchangeRoute.defaultMarket
                && PortfolioCalculator.normalizeCoin(w.base) == base
                && quotes.contains(w.quote.uppercased())
        }
        if TokenIdentity.conflicting(symbol: base, usable.map(\.name)) { return nil }
        return usable.min { a, b in
            let qa = quotes.firstIndex(of: a.quote.uppercased()) ?? Int.max
            let qb = quotes.firstIndex(of: b.quote.uppercased()) ?? Int.max
            if qa != qb { return qa < qb }
            if a.lastUpdate != b.lastUpdate { return a.lastUpdate > b.lastUpdate }
            return a.marketKey < b.marketKey
        }
    }
}
