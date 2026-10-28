import Foundation

/// Frische eines Kurses in den Widgets — gleiche Regeln wie die Merkliste, wie
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

    /// Zeit für «veraltet · 06:42» oder nil (nicht veraltet) — Regel der Zeile und der Alarmsperre
    /// (`PriceFreshness.staleReason`): Abruf zu alt → letzter Abruf; letzter Handel laut Börse älter als 2 h →
    /// dieser Handel. Deaktivierte und nicht mehr gehandelte Paare nie. Wie Android.
    static func outdatedTime(_ watch: Watch, now: Int64, afterMillis: Int64) -> Int64? {
        guard !watch.disabled, !watch.isNotTraded else { return nil }
        switch PriceFreshness.staleReason(watch, now: now, staleAfter: afterMillis) {
        case .fetch: return watch.lastUpdate
        case .trade: return watch.exchangeTime
        case nil: return nil
        }
    }

    /// Zeitpunkte für den nächsten Eintrag «wird veraltet» (`OutdatedRule.nextChangeAt` mit `afterMillis`): letzter
    /// Abruf und der Handel laut Börse, so verschoben, dass er mit derselben Grenze genau nach 2 h wechselt.
    static func outdatedTimes(_ watch: Watch, afterMillis: Int64) -> [Int64] {
        var times = [watch.lastUpdate]
        if let exchange = watch.exchangeTime, exchange > 0 {
            times.append(exchange + AlarmFreshness.exchangeStaleMillis - afterMillis)
        }
        return times
    }

    /// Zeit neben dem Kurs (kompakte Zeilen ohne Platz für den Hinweis): siehe oben.
    static func priceTime(_ watch: Watch, now: Int64, afterMillis: Int64) -> Int64 {
        unchangedSince(watch, now: now, afterMillis: afterMillis) ?? PriceFreshness.freshnessTime(watch)
    }

    /// Paar des Einzel-Widgets kurz vor dem Zeichnen neu lesen? Ja nach eigener Aktualisierung (`refreshed`) oder wenn
    /// `watchlist.json` seit dem ersten Lesen geschrieben wurde (`writtenBefore` → `writtenNow`, ms; z. B. hat die App
    /// während des Chart-Ladens frische Kurse gespeichert). Sonst bleibt das schon gelesene Paar — kein zweites Lesen
    /// der ganzen Merkliste.
    static func rereadWatch(refreshed: Bool, writtenBefore: Int64, writtenNow: Int64) -> Bool {
        refreshed || writtenNow != writtenBefore
    }
}
