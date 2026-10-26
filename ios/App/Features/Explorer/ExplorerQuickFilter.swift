import Foundation

// Schnellfilter der Paarliste einer Börse («Gewinner», «Verlierer», «Volumen») mit den Kennzahlen je Paar aus
// der Sammelabfrage. Eigene Datei wie `ExplorerQuickFilter.kt`.

// MARK: - Schnellfilter

/// Schnellfilter der Paarliste einer Börse: «Gewinner», «Verlierer», «Volumen» — wie `QuickFilter` in `ExplorerQuickFilter.kt`.
enum ExplorerQuickFilter: String, CaseIterable, Sendable {
    case gainers, losers, volume

    var labelKey: String {
        switch self {
        case .gainers: return "explorer_filter_gainers"
        case .losers: return "explorer_filter_losers"
        case .volume: return "explorer_filter_volume"
        }
    }
}

/// Kennzahlen eines Paars aus der Sammelabfrage der Börse; nil = nicht geliefert.
struct ExplorerPairStats: Equatable, Sendable {
    var change24h: Double?
    var quoteVolume: Double?
}

/// Sortierhilfen der Schnellfilter — wie `QuickFilters` in `ExplorerQuickFilter.kt`.
/// Ohne Filter bleibt die Reihenfolge unverändert; Paare ohne Wert stehen hinten (in ihrer
/// bisherigen Reihenfolge).
enum ExplorerQuickFilters {

    /// Kennzahlen aus einem Ticker: 24-h-Veränderung; Volumen in der Gegenwährung (sonst Menge × Kurs).
    static func stats(of ticker: Ticker) -> ExplorerPairStats {
        let change = ticker.change24hPercent.flatMap { $0.isFinite ? $0 : nil }
        var volume: Double? = nil
        if ticker.volQuote.isFinite && ticker.volQuote > 0 {
            volume = ticker.volQuote
        } else if ticker.vol.isFinite && ticker.vol > 0 && ticker.last.isFinite && ticker.last > 0 {
            volume = ticker.vol * ticker.last
        }
        return ExplorerPairStats(change24h: change, quoteVolume: volume)
    }

    /// Welche Filter die Daten tragen: «Gewinner»/«Verlierer» nur mit Veränderung, «Volumen» nur mit Volumen.
    static func available(_ stats: [ExplorerPairStats?]) -> [ExplorerQuickFilter] {
        var result: [ExplorerQuickFilter] = []
        if stats.contains(where: { $0?.change24h != nil }) { result += [.gainers, .losers] }
        if stats.contains(where: { $0?.quoteVolume != nil }) { result.append(.volume) }
        return result
    }

    /// Wert, nach dem `filter` sortiert.
    static func value(_ stats: ExplorerPairStats?, _ filter: ExplorerQuickFilter) -> Double? {
        switch filter {
        case .gainers, .losers: return stats?.change24h
        case .volume: return stats?.quoteVolume
        }
    }

    /// Sortiert stabil; `filter` nil = unveränderte Reihenfolge.
    static func sort<T>(_ items: [T], _ filter: ExplorerQuickFilter?, stats: (T) -> ExplorerPairStats?) -> [T] {
        guard let filter else { return items }
        var known: [(offset: Int, value: Double)] = []
        var missing: [Int] = []
        for (offset, item) in items.enumerated() {
            if let v = Self.value(stats(item), filter) {
                known.append((offset: offset, value: v))
            } else {
                missing.append(offset)
            }
        }
        let ascending = filter == .losers
        // Gleiche Werte in der bisherigen Reihenfolge (Swift sortiert nicht garantiert stabil)
        known.sort { a, b in
            if a.value != b.value { return ascending ? a.value < b.value : a.value > b.value }
            return a.offset < b.offset
        }
        return known.map { items[$0.offset] } + missing.map { items[$0] }
    }

    /// Antippen: anderer Filter wird gewählt, derselbe noch einmal hebt ihn auf.
    static func toggle(_ current: ExplorerQuickFilter?, _ tapped: ExplorerQuickFilter) -> ExplorerQuickFilter? {
        current == tapped ? nil : tapped
    }
}
