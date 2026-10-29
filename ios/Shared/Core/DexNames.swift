import Foundation

/// DEX-Namen in der Coin-Suche von «Paar hinzufügen»: Wer «Uniswap» oder «raydium pepe» tippt, bekommt DexScreener als
/// Treffer (DEX-Pools gibt es nur dort). Reine Logik — wie Android `DexNames` (`testdata/parity/dex_names.json`).
///
/// Regel: Suchtext ohne Gross-/Kleinschreibung, in Wörter zerlegt. Die ersten ein oder zwei Wörter, ohne Leerzeichen
/// zusammengesetzt, müssen der Anfang eines Namens sein (mindestens `minPrefix` Zeichen; «trader joe» = «traderjoe»);
/// zwei Wörter gehen vor einem. Der Rest ist der Token für die DEX-Suche («uniswap pepe» → «pepe»).
enum DexNames {
    /// Dezentrale Börsen in kanonischer Schreibweise; der Hinweis nennt den getroffenen Namen.
    static let named: [String] = [
        "Uniswap", "PancakeSwap", "Raydium", "Aerodrome", "SushiSwap", "Orca", "Meteora", "Curve", "Balancer",
        "Trader Joe", "Jupiter",
    ]

    /// Treffer mit allgemeinem Hinweis («Pools dezentraler Börsen über DexScreener»).
    static let generic: [String] = ["GeckoTerminal", "DexScreener", "DEX"]

    /// So viele Zeichen muss der Anfang mindestens haben («uni» ja, «un» nein).
    static let minPrefix = 3

    /// Höchstens so viele Wörter bilden den Namen («Trader Joe»).
    private static let maxNameWords = 2

    /// Treffer: `dexName` kanonisch oder nil (allgemein), `token` der übrige Suchtext (leer = nichts vorbelegen).
    struct Match: Equatable, Sendable {
        let dexName: String?
        let token: String
    }

    private static let all: [(name: String, generic: Bool)] =
        named.map { ($0, false) } + generic.map { ($0, true) }

    private static func key(_ text: String) -> String {
        text.lowercased().replacingOccurrences(of: " ", with: "")
    }

    /// DEX zum Suchtext; nil = kein DEX-Name.
    static func match(_ query: String) -> Match? {
        // Jedes Leerzeichen trennt (auch geschütztes und ideografisches), wie `Char.isWhitespace` in Kotlin
        let words = query.split(whereSeparator: \.isWhitespace).map(String.init)
        if words.isEmpty { return nil }
        for take in stride(from: min(maxNameWords, words.count), through: 1, by: -1) {
            let head = key(words.prefix(take).joined())
            if head.count < minPrefix { continue }
            guard let hit = all.first(where: { key($0.name).hasPrefix(head) }) else { continue }
            return Match(dexName: hit.generic ? nil : hit.name, token: words.dropFirst(take).joined(separator: " "))
        }
        return nil
    }
}
