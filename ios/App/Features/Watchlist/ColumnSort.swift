import Foundation

/// Spalte zum Sortieren der Merkliste — wie `SortKey` (Android).
enum SortKey: String, CaseIterable {
    case NAME, PRICE, CHANGE
}

/// Sortieren nach Spalten wie an der Börse («Name ⇅ · Kurs ⇅ · 24h ⇅» über der Merkliste). Nur eine
/// Ansicht: die eigene Reihenfolge bleibt gespeichert und kommt mit dem dritten Tipp zurück.
/// Favoriten bleiben oben und werden unter sich sortiert. Kurse werden über einen gemeinsamen Wert
/// verglichen (im Hintergrund in CHF umgerechnet). Paare ohne Wert stehen immer am Ende.
/// Wie `ColumnSort.kt` (Android).
struct ColumnSort: Equatable {
    let key: SortKey
    let descending: Bool

    /// Gespeicherte Form, z. B. «PRICE_DESC».
    var encoded: String { "\(key.rawValue)_\(descending ? "DESC" : "ASC")" }

    /// Gespeicherte Form lesen; unbekannt oder leer = eigene Reihenfolge (nil).
    static func decode(_ value: String?) -> ColumnSort? {
        guard let parts = value?.split(separator: "_").map(String.init), parts.count == 2,
              let key = SortKey(rawValue: parts[0]) else { return nil }
        switch parts[1] {
        case "DESC": return ColumnSort(key: key, descending: true)
        case "ASC": return ColumnSort(key: key, descending: false)
        default: return nil
        }
    }

    /// Feld der Spaltensortierung in der eigenen Sicherung (Einstellungen; gleich unter Android).
    static let backupField = "watchlistColumnSort"
    /// Schlüssel der Einstellung (`@AppStorage` in der Merkliste; "" = eigene Reihenfolge).
    static let storageKey = "watchlist_column_sort"

    /// Wert für bzw. aus der Sicherung: gespeicherte Form («PRICE_DESC») oder nil = eigene
    /// Reihenfolge (auch bei leerem oder unbekanntem Wert) — wie `ColumnSort.backupValue` (Android).
    static func backupValue(_ raw: String?) -> String? { decode(raw)?.encoded }

    /// Tipp auf eine Spalte: neue Spalte → erste Richtung (Name A–Z, Kurs und 24h gross zuerst),
    /// gleiche Spalte → andere Richtung, dann wieder die eigene Reihenfolge (nil).
    static func next(_ current: ColumnSort?, tapped: SortKey) -> ColumnSort? {
        let first = tapped != .NAME
        guard let current, current.key == tapped else { return ColumnSort(key: tapped, descending: first) }
        return current.descending == first ? ColumnSort(key: tapped, descending: !first) : nil
    }

    /// `items` in der eigenen Reihenfolge sortieren: Favoriten zuerst (unter sich sortiert), dann
    /// die übrigen. Ohne `sort` unverändert; gleiche Werte behalten ihre Reihenfolge.
    static func apply<T>(_ items: [T], _ sort: ColumnSort?, favorite: (T) -> Bool, name: (T) -> String,
                         value: (T) -> Double?, change: (T) -> Double?) -> [T] {
        guard let sort else { return items }
        let favorites = items.filter(favorite)
        let others = items.filter { !favorite($0) }
        return part(favorites, sort, name: name, value: value, change: change)
            + part(others, sort, name: name, value: value, change: change)
    }

    private static func part<T>(_ items: [T], _ sort: ColumnSort, name: (T) -> String,
                                value: (T) -> Double?, change: (T) -> Double?) -> [T] {
        let indexed = Array(items.enumerated())
        switch sort.key {
        case .NAME:
            return indexed.sorted { a, b in
                let r = name(a.element).caseInsensitiveCompare(name(b.element))
                if r == .orderedSame { return a.offset < b.offset }
                return sort.descending ? r == .orderedDescending : r == .orderedAscending
            }.map(\.element)
        case .PRICE, .CHANGE:
            let number = sort.key == .PRICE ? value : change
            // Wert je Paar einmal berechnen und mitführen (keine zweite Abfrage, kein !) — wie Android
            let keyed: [(offset: Int, element: T, value: Double?)] = indexed.map { pair in
                let v = number(pair.element)
                return (pair.offset, pair.element, (v?.isNaN ?? true) ? nil : v)
            }
            let known = keyed.compactMap { k in k.value.map { (offset: k.offset, element: k.element, value: $0) } }
            let unknown = keyed.filter { $0.value == nil }.map(\.element)
            let sorted = known.sorted { a, b in
                if a.value == b.value { return a.offset < b.offset }
                return sort.descending ? a.value > b.value : a.value < b.value
            }
            return sorted.map(\.element) + unknown
        }
    }
}
