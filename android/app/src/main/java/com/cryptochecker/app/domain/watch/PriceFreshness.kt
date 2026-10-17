package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.data.local.model.WatchEntity

/**
 * Wie frisch ist ein Kurs? Börsen ohne eigene Zeit (Kraken, Gate.io, Hyperliquid, bitFlyer, Binance
 * und MEXC — deren «closeTime» ist nur das Ende des 24-h-Fensters —,
 * Gemini-Sammelabfrage …) bekommen beim Abruf «jetzt» — ein seit Stunden stehender letzter Kurs
 * wirkte sonst frisch. Deshalb:
 *  - [freshnessTime]: Zeit laut Börse, sonst der letzte erfolgreiche Abruf (`lastUpdate`).
 *  - [unchangedSince]: nur OHNE Zeit der Börse — ist der Kurs länger als [unchangedAfterMillis]
 *    gleich geblieben, zeigt die Zeile «Kurs unverändert seit HH:MM» statt frisch zu wirken.
 *    Grenze: max(1 Stunde, Veraltet-Grenze aus `OutdatedRule`, also 3 × Intervall).
 *  - [nextPriceChangedAt]: neuer `priceChangedAt` nach einem erfolgreichen Abruf.
 * `lastUpdate` bleibt der letzte erfolgreiche Abruf; Alarme nutzen nichts davon.
 * Reine Logik ohne Android (PriceFreshnessTest, Parity `testdata/parity/price_freshness.json`,
 * Swift-Spiegel PriceFreshness.swift).
 */
object PriceFreshness {

    /** Untergrenze für «Kurs unverändert seit»: 1 Stunde. */
    const val UNCHANGED_MIN_MILLIS = 60 * 60_000L

    /** Zeitpunkt für Alter und «veraltet»: Zeit der Börse, sonst letzter erfolgreicher Abruf. */
    fun freshnessTime(lastUpdate: Long, exchangeTime: Long?): Long =
        exchangeTime?.takeIf { it > 0 } ?: lastUpdate

    fun freshnessTime(watch: WatchEntity): Long = freshnessTime(watch.lastUpdate, watch.exchangeTime)

    /** Ab diesem Alter der letzten Kursänderung gilt «unverändert»: max(1 h, Veraltet-Grenze). */
    fun unchangedAfterMillis(outdatedAfterMillis: Long): Long = maxOf(UNCHANGED_MIN_MILLIS, outdatedAfterMillis)

    /**
     * Zeitpunkt der letzten Kursänderung, wenn die Zeile «Kurs unverändert seit …» zeigen soll:
     * die Börse liefert keine Zeit, Kurs und Änderungszeit sind bekannt und die Änderung liegt
     * länger als [unchangedAfterMillis] zurück. Sonst null.
     */
    fun unchangedSince(
        exchangeTime: Long?,
        priceChangedAt: Long,
        lastUpdate: Long,
        now: Long,
        outdatedAfterMillis: Long,
    ): Long? {
        if (exchangeTime != null && exchangeTime > 0) return null
        if (priceChangedAt <= 0 || lastUpdate <= 0) return null
        if (now - priceChangedAt <= unchangedAfterMillis(outdatedAfterMillis)) return null
        return priceChangedAt
    }

    /** Wie oben für ein Paar; deaktiviert, ohne Kurs oder mit Fehler nie (dort steht anderes). */
    fun unchangedSince(watch: WatchEntity, now: Long, outdatedAfterMillis: Long): Long? {
        if (watch.disabled || watch.lastPrice == null || watch.lastError != null) return null
        return unchangedSince(watch.exchangeTime, watch.priceChangedAt, watch.lastUpdate, now, outdatedAfterMillis)
    }

    /**
     * `priceChangedAt` nach einem erfolgreichen Abruf zum Zeitpunkt [time]: bleibt bei gleichem Kurs
     * stehen (sofern bekannt), sonst [time]. Unbekannt (0, z. B. nach dem Update) beginnt bei [time].
     */
    fun nextPriceChangedAt(previousPrice: Double?, previousChangedAt: Long, price: Double, time: Long): Long =
        if (previousPrice != null && previousPrice == price && previousChangedAt > 0) previousChangedAt else time
}
