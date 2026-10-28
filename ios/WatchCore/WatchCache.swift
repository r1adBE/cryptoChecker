import Foundation

/// Gespeicherter Kurs eines Coins mit der Zeit seines Abrufs (Epoch-ms, «Stand des Abrufs»).
struct WatchCached: Codable, Equatable, Sendable {
    let quote: WatchQuote
    let time: Int64
}

/// Zuletzt geladene Kurse der Uhr, je Coin mit eigener Zeit: ein Abruf ersetzt nur die Coins, die er liefert; die
/// anderen behalten ihren letzten Kurs samt dessen Zeit (Teil-Ausfall, z. B. Zeitüberschreitung bei einem Coin).
/// «Veraltet» (`WatchFreshness`) und «Stand» gelten deshalb je Coin. Wie Android `WatchCache` (`Parity/watch_quotes.json`,
/// Abschnitt «cache»).
enum WatchCache {
    /// `fresh` (Abruf zur Zeit `time`) in `old` übernehmen. Behalten werden nur Coins aus `keep` (beide Listen der Wahl),
    /// damit alte Favoriten den Speicher nicht füllen.
    static func merge(_ old: [String: WatchCached], fresh: [String: WatchQuote], time: Int64,
                      keep: [String]) -> [String: WatchCached] {
        let kept = Set(keep)
        var out = old.filter { kept.contains($0.key) }
        for (coin, quote) in fresh where kept.contains(coin) {
            out[coin] = WatchCached(quote: quote, time: time)
        }
        return out
    }

    /// Stand der Liste `coins`: Zeit des ältesten gezeigten Kurses (0 = keiner geladen).
    static func listTime(_ cache: [String: WatchCached], coins: [String]) -> Int64 {
        coins.compactMap { cache[$0]?.time }.min() ?? 0
    }
}

/// Laufnummer der Abrufe: `start` macht jeden früheren Abruf ungültig; ein Ergebnis gilt nur, wenn seine Nummer noch die
/// neueste ist und die gezeigte Liste noch die abgefragte. Wie Android `WatchFetchGate`.
struct WatchFetchGate: Sendable {
    private var generation = 0

    mutating func start() -> Int {
        generation += 1
        return generation
    }

    func accepts(_ run: Int, asked: [String], shown: [String]) -> Bool {
        run == generation && asked == shown
    }
}
