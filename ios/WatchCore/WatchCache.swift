import Foundation

/// Gespeicherter Kurs eines Coins mit dem «Stand des Abrufs» `time` (Epoch-ms): der Start des Abrufs, der ihn geliefert
/// hat, nicht der Zeitpunkt des Speicherns. Der Kurs ist also mindestens so frisch wie `time`; Alter und «veraltet»
/// (`WatchFreshness`) zählen ab dort, und zwei Abrufe lassen sich danach ordnen, wann sie gestartet sind.
struct WatchCached: Codable, Equatable, Sendable {
    let quote: WatchQuote
    let time: Int64
}

/// Zuletzt geladene Kurse der Uhr, je Coin mit eigener Zeit: ein Abruf ersetzt nur die Coins, die er liefert; die
/// anderen behalten ihren letzten Kurs samt dessen Zeit (Teil-Ausfall, z. B. Zeitüberschreitung bei einem Coin).
/// «Veraltet» (`WatchFreshness`) und «Stand» gelten deshalb je Coin. Wie Android `WatchCache` (`Parity/watch_quotes.json`,
/// Abschnitt «cache»).
enum WatchCache {
    /// `fresh` eines Abrufs, der zur Zeit `startedAt` begann, in `old` übernehmen. Ein Coin wird nur ersetzt, wenn sein
    /// gespeicherter Stand nicht neuer als `startedAt` ist; sonst hat ein später gestarteter Abruf (Uhr-App, Komplikation)
    /// schon geschrieben, und dessen Kurs bleibt. Übernommene Kurse bekommen `startedAt` als Zeit. Behalten werden nur
    /// Coins aus `keep` (beide Listen der Wahl), damit alte Favoriten den Speicher nicht füllen.
    static func merge(_ old: [String: WatchCached], fresh: [String: WatchQuote], startedAt: Int64,
                      keep: [String]) -> [String: WatchCached] {
        let kept = Set(keep)
        var out = old.filter { kept.contains($0.key) }
        for (coin, quote) in fresh where kept.contains(coin) && (out[coin]?.time ?? .min) <= startedAt {
            out[coin] = WatchCached(quote: quote, time: startedAt)
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
