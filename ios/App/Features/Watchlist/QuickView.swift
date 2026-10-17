import Foundation

/// Vorübergehende Ansichten der Merkliste, zusätzlich zu «Alle», Favoriten und Gruppen
/// (`WatchFilter`): ⚡ (Paare, bei denen gerade etwas passiert; Chip bei den Gruppen), «nur
/// veraltete» (Tipp auf den Status «10 von 30 veraltet») und «nur deaktivierte» (Tipp auf
/// «3 deaktiviert» neben dem Status). Nicht gespeichert — sie zeigen Momentanes und enden von
/// selbst, wenn nichts mehr passt. Wie `QuickView.kt` (Android).
enum QuickView: Equatable {
    case activity, stale, disabled
}

/// Deaktivierte Paare (`Watch.disabled`): keine Abfragen, keine Mitteilungen, keine Alarme. Sie
/// zählen nirgends mit — weder als «veraltet» noch als gescheitert, nicht im Puls und nicht für
/// «Alle Kurse aktuell». Wie `DisabledView` in `QuickView.kt` (Android).
enum DisabledView {
    /// Nur die aktiven Einträge (alles, was abgefragt, gezählt und gemeldet wird).
    static func active<T>(_ items: [T], disabled: (T) -> Bool) -> [T] {
        items.contains(where: disabled) ? items.filter { !disabled($0) } : items
    }

    /// Nur die deaktivierten Einträge (Ansicht `QuickView.disabled`).
    static func only<T>(_ items: [T], disabled: (T) -> Bool) -> [T] {
        items.filter(disabled)
    }

    /// Anzahl deaktivierter Einträge (Hinweis «3 deaktiviert»; 0 = kein Hinweis).
    static func count<T>(_ items: [T], disabled: (T) -> Bool) -> Int {
        items.reduce(0) { $0 + (disabled($1) ? 1 : 0) }
    }

    /// Ohne frischen Kurs («veraltet»)? Nie bei deaktivierten oder nicht mehr gehandelten Paaren —
    /// gleiche Regel für Zeile, Status und die Ansicht «nur veraltete».
    static func isStale(disabled: Bool, notTraded: Bool, lastUpdate: Int64, now: Int64, staleAfter: Int64) -> Bool {
        !disabled && !notTraded && (lastUpdate <= 0 || now - lastUpdate > staleAfter)
    }
}

enum ActivityView {
    /// Paare für den ⚡-Chip aus `hot` (stärkste Signale zuerst): mit `limit` (Empfindlichkeit
    /// «Weniger») nur die Paare der `limit` stärksten Coins — ein Coin an mehreren Börsen zählt
    /// einmal, bleibt aber mit allen seinen Paaren sichtbar.
    static func pick<T>(_ hot: [T], baseAsset: (T) -> String, limit: Int?) -> [T] {
        guard let limit else { return hot }
        guard limit > 0 else { return [] }
        var coins: [String] = []
        for item in hot {
            let coin = baseAsset(item).uppercased()
            if !coins.contains(coin) { coins.append(coin) }
            if coins.count == limit { break }
        }
        let chosen = Set(coins)
        return hot.filter { chosen.contains(baseAsset($0).uppercased()) }
    }
}
