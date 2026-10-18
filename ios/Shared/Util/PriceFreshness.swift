import Foundation

/// Wie frisch ist ein Kurs? Börsen ohne eigene Zeit (Kraken, Gate.io, Hyperliquid, bitFlyer, Binance
/// und MEXC — deren «closeTime» ist nur das Ende des 24-h-Fensters —,
/// Gemini-Sammelabfrage …) bekommen beim Abruf «jetzt» — ein seit Stunden stehender letzter Kurs
/// wirkte sonst frisch. Deshalb:
///  - `freshnessTime`: Zeit laut Börse, sonst der letzte erfolgreiche Abruf (`lastUpdate`).
///  - `staleReason`/`isStale`: «veraltet» nach derselben Regel wie die Alarmsperre — letzter Abruf älter
///    als die Grenze (`.fetch`) ODER die Börse meldet einen Handel älter als
///    `AlarmFreshness.exchangeStaleMillis` (`.trade`). Ruhige Paare und Live-Paare ohne Handel seit ein
///    paar Minuten werden so nicht «veraltet»; Zeile und Alarm sperren gleich.
///  - `unchangedSince`: nur OHNE Zeit der Börse — ist der Kurs länger als `unchangedAfterMillis`
///    gleich geblieben, zeigt die Zeile «Kurs unverändert seit HH:MM» statt frisch zu wirken.
///    Grenze: max(1 Stunde, Veraltet-Grenze aus `OutdatedRule`, also 3 × Intervall).
///  - `nextPriceChangedAt`: neuer `priceChangedAt` nach einem erfolgreichen Abruf.
/// `lastUpdate` bleibt der letzte erfolgreiche Abruf; Alarme nutzen nichts davon.
/// Wie `PriceFreshness.kt` (Parity `price_freshness.json`).
enum PriceFreshness {
    /// Untergrenze für «Kurs unverändert seit»: 1 Stunde.
    static let unchangedMinMillis: Int64 = 60 * 60_000

    /// Zeitpunkt für Alter und «veraltet»: Zeit der Börse, sonst letzter erfolgreicher Abruf.
    static func freshnessTime(lastUpdate: Int64, exchangeTime: Int64?) -> Int64 {
        if let exchangeTime, exchangeTime > 0 { return exchangeTime }
        return lastUpdate
    }

    static func freshnessTime(_ watch: Watch) -> Int64 {
        freshnessTime(lastUpdate: watch.lastUpdate, exchangeTime: watch.exchangeTime)
    }

    /// Warum ein Kurs «veraltet» ist: Abruf zu alt bzw. letzter Handel laut Börse zu alt.
    enum StaleReason: String { case fetch = "FETCH", trade = "TRADE" }

    /// Grund für «veraltet» oder nil: `.fetch`, wenn der letzte erfolgreiche Abruf `lastUpdate` (> 0) länger als
    /// `staleAfter` zurückliegt (geht vor); sonst `.trade`, wenn die Börse eine Handelszeit älter als die Alarmsperre
    /// meldet (`AlarmFreshness.exchangeTimeTooOld`). Ohne Abruf (≤ 0) nil — «noch kein Kurs» entscheidet der Aufrufer.
    static func staleReason(lastUpdate: Int64, exchangeTime: Int64?, now: Int64, staleAfter: Int64) -> StaleReason? {
        guard lastUpdate > 0 else { return nil }
        if now - lastUpdate > staleAfter { return .fetch }
        if AlarmFreshness.exchangeTimeTooOld(exchangeTime, now: now) { return .trade }
        return nil
    }

    static func staleReason(_ watch: Watch, now: Int64, staleAfter: Int64) -> StaleReason? {
        staleReason(lastUpdate: watch.lastUpdate, exchangeTime: watch.exchangeTime, now: now, staleAfter: staleAfter)
    }

    /// Gehandeltes, aktives Paar «veraltet» (Zeile, Filter, Status, Widgets): noch nie abgefragt oder ein
    /// `staleReason`. Nie bei deaktivierten oder nicht mehr gehandelten Paaren.
    static func isStale(_ watch: Watch, now: Int64, staleAfter: Int64) -> Bool {
        !watch.disabled && !watch.isNotTraded
            && (watch.lastUpdate <= 0 || staleReason(watch, now: now, staleAfter: staleAfter) != nil)
    }

    /// Ab diesem Alter der letzten Kursänderung gilt «unverändert»: max(1 h, Veraltet-Grenze).
    static func unchangedAfterMillis(outdatedAfter: Int64) -> Int64 {
        max(unchangedMinMillis, outdatedAfter)
    }

    /// Zeitpunkt der letzten Kursänderung, wenn die Zeile «Kurs unverändert seit …» zeigen soll:
    /// die Börse liefert keine Zeit, Kurs und Änderungszeit sind bekannt und die Änderung liegt
    /// länger als `unchangedAfterMillis` zurück. Sonst nil.
    static func unchangedSince(exchangeTime: Int64?, priceChangedAt: Int64, lastUpdate: Int64,
                               now: Int64, outdatedAfter: Int64) -> Int64? {
        if let exchangeTime, exchangeTime > 0 { return nil }
        guard priceChangedAt > 0, lastUpdate > 0 else { return nil }
        guard now - priceChangedAt > unchangedAfterMillis(outdatedAfter: outdatedAfter) else { return nil }
        return priceChangedAt
    }

    /// Wie oben für ein Paar; deaktiviert, ohne Kurs oder mit Fehler nie (dort steht anderes).
    static func unchangedSince(_ watch: Watch, now: Int64, outdatedAfter: Int64) -> Int64? {
        guard !watch.disabled, watch.lastPrice != nil, watch.lastError == nil else { return nil }
        return unchangedSince(exchangeTime: watch.exchangeTime, priceChangedAt: watch.priceChangedAt,
                              lastUpdate: watch.lastUpdate, now: now, outdatedAfter: outdatedAfter)
    }

    /// `priceChangedAt` nach einem erfolgreichen Abruf zum Zeitpunkt `time`: bleibt bei gleichem Kurs
    /// stehen (sofern bekannt), sonst `time`. Unbekannt (0, z. B. nach dem Update) beginnt bei `time`.
    static func nextPriceChangedAt(previousPrice: Double?, previousChangedAt: Int64, price: Double,
                                   time: Int64) -> Int64 {
        if let previousPrice, previousPrice == price, previousChangedAt > 0 { return previousChangedAt }
        return time
    }
}
