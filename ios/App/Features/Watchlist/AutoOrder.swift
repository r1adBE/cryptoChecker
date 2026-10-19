import Foundation

/// «Nach Coin ordnen» bzw. «Nach Börse ordnen» im Sortiermodus (⋯ › Sortieren): ordnet die gezeigten
/// Paare einmal und wird danach wie nach dem Ziehen als eigene Reihenfolge gespeichert — von Hand
/// bleibt alles verschiebbar. Favoriten zuerst und unter sich gleich geordnet (wie `ColumnSort`);
/// Gross-/Kleinschreibung egal wie `SortKey.NAME`. Bei Gleichstand bleibt die bisherige Reihenfolge.
/// Wie `AutoOrder.kt` (Android).
enum AutoOrder: CaseIterable {
    /// Anzeigename A–Z, dann Börse A–Z: gleiche Coins verschiedener Börsen stehen untereinander.
    case coin
    /// Nach Börse A–Z gruppiert, innerhalb einer Börse Anzeigename A–Z.
    case exchange

    /// `items` neu geordnet: Favoriten zuerst, dann die übrigen. `name`: Anzeigename wie in der Zeile
    /// (z. B. «BTC/USDT Perp»); `exchange`: Börsenname (z. B. «Binance»).
    func apply<T>(_ items: [T], favorite: (T) -> Bool, name: (T) -> String, exchange: (T) -> String) -> [T] {
        let favorites = items.filter(favorite)
        let others = items.filter { !favorite($0) }
        return sorted(favorites, name: name, exchange: exchange) + sorted(others, name: name, exchange: exchange)
    }

    private func sorted<T>(_ items: [T], name: (T) -> String, exchange: (T) -> String) -> [T] {
        // Swift sortiert nicht stabil: bei Gleichstand entscheidet der bisherige Platz
        Array(items.enumerated()).sorted { a, b in
            let byName = name(a.element).caseInsensitiveCompare(name(b.element))
            let byExchange = exchange(a.element).caseInsensitiveCompare(exchange(b.element))
            let first = self == .coin ? byName : byExchange
            let second = self == .coin ? byExchange : byName
            if first != .orderedSame { return first == .orderedAscending }
            if second != .orderedSame { return second == .orderedAscending }
            return a.offset < b.offset
        }.map(\.element)
    }
}
