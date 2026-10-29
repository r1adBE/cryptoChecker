import Foundation

/// Ruhige Fusszeile am Ende der Merkliste: «6 Paare» bzw. «6 von 42 Paaren», sobald eine Ansicht (Gruppe, Filter-Chip,
/// ⚡, «nur veraltete/deaktivierte», Suche) Paare ausblendet; nahe der Obergrenze (`WatchlistLimits.maxPairs`)
/// zusätzlich «· noch 5 frei» bzw. «· Merkliste voll». Reine Regel — wie Android `WatchlistFooter.kt`
/// (gleiche Testfälle: `Parity/watchlist_footer.json`).
struct WatchlistFooter: Equatable {
    /// Gezeigte Zeilen
    let shown: Int
    /// Paare der ganzen Merkliste
    let total: Int
    /// «x von y» statt «x Paare» (die Ansicht blendet Paare aus)
    let filtered: Bool
    /// Freie Plätze, nur nahe der Grenze (`isNearLimit`); 0 = voll, nil = kein Hinweis
    let remaining: Int?

    /// «Nahe» heisst höchstens so viele freie Plätze: 10 % der Grenze bzw. 10, was kleiner ist.
    static let nearLimitSlots = 10
    static let nearLimitPercent = 10

    /// Fusszeile zu `shown` gezeigten von `total` Paaren; nil = keine (leere Merkliste mit Start-Auswahl, leere Ansicht
    /// mit Hinweis, Mehrfachauswahl — dort steht unten die Leiste).
    static func of(shown: Int, total: Int, selecting: Bool, max: Int = WatchlistLimits.maxPairs) -> WatchlistFooter? {
        guard !selecting, shown > 0, total > 0 else { return nil }
        let free = Swift.max(0, max - total)
        return WatchlistFooter(shown: shown, total: total, filtered: shown < total,
                               remaining: isNearLimit(free: free, max: max) ? free : nil)
    }

    /// Höchstens `nearLimitPercent` % von `max` bzw. `nearLimitSlots` Plätze frei, was kleiner ist?
    static func isNearLimit(free: Int, max: Int) -> Bool {
        free <= min(max * nearLimitPercent / 100, nearLimitSlots)
    }
}
