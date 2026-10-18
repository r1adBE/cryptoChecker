import Foundation

/// Ein Coin der CoinGecko-Rangliste für «Gewinner und Verlierer 24 h». Wie `MoverCoin` (Kotlin).
struct MoverCoin: Equatable, Sendable, Codable, Identifiable {
    /// Kürzel in Grossbuchstaben (Logo und Tipp gehen über das Kürzel, wie bei «Heute auffällig»).
    let symbol: String
    let name: String
    /// Rang nach Marktkapitalisierung; nil = unbekannt.
    let rank: Int?
    /// 24-h-Veränderung in %; nil = fehlt.
    let change24h: Double?

    var id: String { symbol }
}

/// Die stärksten Gewinner (absteigend) und Verlierer (die stärksten Verluste zuerst).
struct MarketMoversResult: Equatable, Sendable, Codable {
    let gainers: [MoverCoin]
    let losers: [MoverCoin]

    var isEmpty: Bool { gainers.isEmpty && losers.isEmpty }
}

/// «Gewinner und Verlierer 24 h» unter dem Pulse im Markt-Tab — reine Auswahl, gleich wie `MarketMovers.kt`.
/// Daten: dieselbe CoinGecko-Rangliste wie die Marktbreite des Pulse (eine Anfrage, gleicher Zwischenspeicher).
///
/// Regeln:
///  1. Grundmenge: die ersten `universe` Coins nach Rang (ohne Rang hinten, sonst in der gelieferten Reihenfolge) —
///     «Top 100 nach Marktkapitalisierung».
///  2. Davon weg: Stablecoins und Doppelgänger (dieselbe Regel wie Marktbreite und Start-Coins, `CoinExclusion`),
///     Einträge ohne gültige 24-h-Veränderung und doppelte Kürzel (der besser platzierte bleibt: Logo und Tipp hängen
///     am Kürzel).
///  3. Weniger als `minCoins` gültige: nichts (eine Rangliste aus einer Handvoll Coins sagt nichts).
///  4. Gewinner: Veränderung > 0, grösste zuerst; Verlierer: < 0, grösster Verlust zuerst; je höchstens `count`.
///     Genau 0 ist weder noch. Gleichstand: besserer Rang zuerst, dann Kürzel (stabil auf beiden Plattformen).
enum MarketMovers {
    /// So viele der grössten Coins bilden die Grundmenge.
    static let universe = 100
    /// Je Liste höchstens so viele.
    static let count = 5
    /// Weniger gültige Coins: keine Liste.
    static let minCoins = 10

    static func select(_ coins: [MoverCoin]) -> MarketMoversResult {
        let ranked = coins.enumerated()
            .sorted { a, b in
                let ra = a.element.rank ?? Int.max
                let rb = b.element.rank ?? Int.max
                return ra != rb ? ra < rb : a.offset < b.offset
            }
            .map(\.element)
            .prefix(universe)
        var seen = Set<String>()
        var valid: [MoverCoin] = []
        for c in ranked {
            let symbol = c.symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            let name = c.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let change = c.change24h, change.isFinite, !symbol.isEmpty,
                  !CoinExclusion.isExcluded(symbol: symbol, name: name),
                  seen.insert(symbol).inserted else { continue }
            valid.append(MoverCoin(symbol: symbol, name: name, rank: c.rank, change24h: change))
        }
        guard valid.count >= minCoins else { return MarketMoversResult(gainers: [], losers: []) }
        func tie(_ a: MoverCoin, _ b: MoverCoin) -> Bool {
            let ra = a.rank ?? Int.max
            let rb = b.rank ?? Int.max
            return ra != rb ? ra < rb : a.symbol < b.symbol
        }
        let gainers = valid.filter { ($0.change24h ?? 0) > 0 }
            .sorted { a, b in
                let ca = a.change24h ?? 0
                let cb = b.change24h ?? 0
                return ca != cb ? ca > cb : tie(a, b)
            }
        let losers = valid.filter { ($0.change24h ?? 0) < 0 }
            .sorted { a, b in
                let ca = a.change24h ?? 0
                let cb = b.change24h ?? 0
                return ca != cb ? ca < cb : tie(a, b)
            }
        return MarketMoversResult(gainers: Array(gainers.prefix(count)), losers: Array(losers.prefix(count)))
    }
}
