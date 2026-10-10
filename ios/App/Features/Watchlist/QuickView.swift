import Foundation

/// Vorübergehende Ansichten der Merkliste, zusätzlich zu «Alle», Favoriten und Gruppen
/// (`WatchFilter`): ⚡ (Paare, bei denen gerade etwas passiert; Chip bei den Gruppen) und «nur
/// veraltete» (Tipp auf den Status «10 von 30 veraltet»). Nicht gespeichert — sie zeigen
/// Momentanes und enden von selbst, wenn nichts mehr passt. Wie `QuickView.kt` (Android).
enum QuickView: Equatable {
    case activity, stale
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
