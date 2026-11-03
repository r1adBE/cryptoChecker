import Foundation

// Übersicht von «Paar hinzufügen» ohne Suchbegriff: Markt-Chips (Alle · Spot · Futures · DEX · Devisen), «Beliebt»,
// «Zuletzt genutzte Börsen» und das Zeichen der Börsen. Reine Logik (testbar); gleich in Android `ExplorerBrowse.kt`,
// gemeinsame Fälle in `Parity/explorer_browse.json`.

/// Markt-Chip über den Börsen und Treffern; `.all` = alles (Standard), `.forex` = EZB-Referenzkurse.
/// Rohwerte wie die Namen in Android (`MarketFilter`).
enum ExplorerMarketFilter: String, CaseIterable, Sendable {
    case all = "ALL"
    case spot = "SPOT"
    case futures = "FUTURES"
    case dex = "DEX"
    case forex = "FOREX"

    /// Zeigt dieser Chip Börsen der Art `category`?
    func allows(_ category: ExchangeCategory) -> Bool {
        switch self {
        case .all: return true
        case .spot: return category == .spot
        case .futures: return category == .futures
        case .dex: return category == .dex
        case .forex: return category == .forex
        }
    }
}

/// «Zuletzt genutzte Börsen»: Schlüssel der zuletzt geöffneten Börsen, neueste zuerst, jede einmal, höchstens `max`.
/// Nur auf dem Gerät (`UserDefaults.standard`, nicht in der App-Sicherung). Wie Android `RecentExchanges`.
enum ExplorerRecentExchanges {
    static let max = 4
    static let defaultsKey = "explorer_recent_exchanges"

    /// `key` nach vorne; leer bleibt die Liste, wie sie ist (höchstens `max`).
    static func add(_ list: [String], _ key: String) -> [String] {
        let k = key.trimmingCharacters(in: .whitespaces)
        guard !k.isEmpty else { return Array(list.prefix(max)) }
        return Array(([k] + list.filter { $0 != k }).prefix(max))
    }

    /// Gespeicherter Text (ein Schlüssel pro Zeile) → Liste; leer/doppelt weg (wie Android `decode`).
    static func decode(_ text: String?) -> [String] {
        sanitize((text ?? "").components(separatedBy: "\n"))
    }

    static func encode(_ list: [String]) -> String { list.joined(separator: "\n") }

    static func sanitize(_ list: [String]) -> [String] {
        var seen = Set<String>()
        let cleaned = list.map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        return Array(cleaned.prefix(max))
    }

    static func load(_ defaults: UserDefaults = .standard) -> [String] {
        sanitize(defaults.stringArray(forKey: defaultsKey) ?? [])
    }

    static func save(_ list: [String], _ defaults: UserDefaults = .standard) {
        defaults.set(list, forKey: defaultsKey)
    }
}

/// «Beliebt»: rund `count` Coins der Start-Börse (Binance, in den USA Binance.US) als …/USDT-Paare — zuerst die
/// gespeicherte Start-Auswahl, dann die Ausweich-Liste, dann `extra`. Mit Paarliste der Start-Börse auf dem Gerät
/// (`available` = Basis-Kürzel mit Spot-Paar in USDT) nur Coins mit Paar; nil = ungeprüft. Kein Abruf.
/// Wie Android `PopularPairs`.
enum ExplorerPopularPairs {
    static let count = 6
    static let extra = ["DOGE", "ADA", "TRX", "LTC"]

    static func pick(starter: [String], fallback: [String], available: Set<String>?) -> [String] {
        var seen = Set<String>()
        let all = (starter + fallback + extra).map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
        let picked = all.filter { symbol in
            !symbol.isEmpty && (available.map { $0.contains(symbol) } ?? true) && seen.insert(symbol).inserted
        }
        return Array(picked.prefix(count))
    }
}

/// Zeichen einer Börse in den Listen (keine Börsen-Logos auf dem Gerät, kein Abruf dafür): farbiger Kreis mit dem
/// Anfangsbuchstaben, bei zwei Wörtern beide («Bybit Futures» → «BF»); Farbe aus dem ersten Wort (`colorKey`).
/// Wie Android `ExchangeMark`.
enum ExplorerExchangeMark {
    private static func words(_ name: String) -> [Substring] {
        name.split(whereSeparator: { $0.isWhitespace }).filter { $0.first.map { $0.isLetter || $0.isNumber } ?? false }
    }

    static func initials(_ name: String) -> String {
        let letters = words(name).prefix(2).compactMap { $0.first.map { String($0).uppercased() } }.joined()
        return letters.isEmpty ? "?" : letters
    }

    /// Kürzel für die Farbe (`CoinLogoPlaceholder`).
    static func colorKey(_ name: String) -> String {
        words(name).first.map { $0.uppercased() } ?? "?"
    }
}

/// Börsen zum Suchtext: ein Wort (ab 3 Zeichen) nennt den Namen (`ExplorerNameMatch.matchesExchange`: «kraken»,
/// «btc kraken», «cryptocom»). Reihenfolge wie `items`, höchstens `limit`. Wie Android `ExchangeSearch`.
enum ExplorerExchangeSearch {
    static let limit = 5

    static func find<T>(_ query: String, _ items: [T], name: (T) -> String) -> [T] {
        let words = query.split(whereSeparator: { " /-:_".contains($0) })
            .map { ExplorerNameMatch.fold(String($0)) }
            .filter { !$0.isEmpty }
        guard !words.isEmpty else { return [] }
        let found = items.filter { item in
            let folded = ExplorerNameMatch.fold(name(item))
            return words.contains { ExplorerNameMatch.matchesExchange(folded, $0) }
        }
        return Array(found.prefix(limit))
    }
}
