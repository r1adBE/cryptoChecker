import Foundation

/// Frische eines Kurses in den Widgets — gleiche Regeln wie die Merkliste (K-1, K-4), wie
/// `WidgetFreshness.kt`:
///  - «veraltet»: Widgets sind nie am Live-Strom, also immer die Abfrage-Schwelle
///    (`LiveStatus.pollingStaleAfterMillis`: 3 × Hintergrund-Intervall, mind. 15 Min.).
///  - «Kurs unverändert seit 14:05» (`PriceFreshness.unchangedSince`) bei Börsen ohne eigene Zeit,
///    aber nur, wenn das Paar nicht ohnehin «veraltet» ist (wie die Zeile der App).
///  - `priceTime`: Zeit, die das Widget neben dem Kurs zeigt — Beginn des unveränderten Kurses,
///    sonst Zeit laut Börse, sonst letzter Abruf. So wirkt ein stehender Kurs nicht frisch.
enum WidgetFreshness {
    /// Grenze «veraltet» für Widgets (nie live).
    static func afterMillis(backgroundIntervalMinutes: Int) -> Int64 {
        LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: backgroundIntervalMinutes)
    }

    /// Zeitpunkt für «Kurs unverändert seit …» oder nil: nicht bei deaktivierten, fehlerhaften oder
    /// veralteten Paaren (dort steht anderes) und nie mit Zeit der Börse.
    static func unchangedSince(_ watch: Watch, now: Int64, afterMillis: Int64) -> Int64? {
        if OutdatedRule.isOutdated(watch.lastUpdate, now: now, afterMillis: afterMillis) { return nil }
        return PriceFreshness.unchangedSince(watch, now: now, outdatedAfter: afterMillis)
    }

    /// Zeit neben dem Kurs (kompakte Zeilen ohne Platz für den Hinweis): siehe oben.
    static func priceTime(_ watch: Watch, now: Int64, afterMillis: Int64) -> Int64 {
        unchangedSince(watch, now: now, afterMillis: afterMillis) ?? PriceFreshness.freshnessTime(watch)
    }
}
