package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmFreshness

/**
 * Wie frisch ist ein Kurs? Börsen ohne eigene Zeit (Kraken, Gate.io, Hyperliquid, bitFlyer, Binance
 * und MEXC — deren «closeTime» ist nur das Ende des 24-h-Fensters —,
 * Gemini-Sammelabfrage …) bekommen beim Abruf «jetzt» — ein seit Stunden stehender letzter Kurs
 * wirkte sonst frisch. Deshalb:
 *  - [freshnessTime]: Zeit laut Börse, sonst der letzte erfolgreiche Abruf (`lastUpdate`).
 *  - [staleReason]/[isStale]: «veraltet» nach derselben Regel wie die Alarmsperre — letzter Abruf älter
 *    als die Grenze ([StaleReason.FETCH]) ODER die Börse meldet einen Handel älter als
 *    [AlarmFreshness.EXCHANGE_STALE_MILLIS] ([StaleReason.TRADE]). Ruhige Paare und Live-Paare ohne
 *    Handel seit ein paar Minuten werden so nicht «veraltet»; Zeile und Alarm sperren gleich.
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

    /** Warum ein Kurs «veraltet» ist: Abruf zu alt bzw. letzter Handel laut Börse zu alt. */
    enum class StaleReason { FETCH, TRADE }

    /**
     * Grund für «veraltet» oder null: [StaleReason.FETCH], wenn der letzte erfolgreiche Abruf
     * [lastUpdate] (> 0) länger als [staleAfterMillis] zurückliegt (geht vor); sonst [StaleReason.TRADE],
     * wenn die Börse eine Handelszeit älter als die Alarmsperre meldet ([AlarmFreshness.exchangeTimeTooOld]).
     * Ohne Abruf (≤ 0) null — «noch kein Kurs» entscheidet der Aufrufer.
     */
    fun staleReason(lastUpdate: Long, exchangeTime: Long?, now: Long, staleAfterMillis: Long): StaleReason? = when {
        lastUpdate <= 0 -> null
        now - lastUpdate > staleAfterMillis -> StaleReason.FETCH
        AlarmFreshness.exchangeTimeTooOld(exchangeTime, now) -> StaleReason.TRADE
        else -> null
    }

    fun staleReason(watch: WatchEntity, now: Long, staleAfterMillis: Long): StaleReason? =
        staleReason(watch.lastUpdate, watch.exchangeTime, now, staleAfterMillis)

    /**
     * Gehandeltes, aktives Paar «veraltet» (Zeile, Filter, Status, Widgets): noch nie abgefragt oder ein
     * [staleReason]. Nie bei deaktivierten oder nicht mehr gehandelten Paaren.
     */
    fun isStale(watch: WatchEntity, now: Long, staleAfterMillis: Long): Boolean =
        !watch.disabled && !watch.isNotTraded &&
            (watch.lastUpdate <= 0 || staleReason(watch, now, staleAfterMillis) != null)

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
     * stehen (sofern bekannt), sonst [time]. Unbekannt (0) beginnt bei [time].
     */
    fun nextPriceChangedAt(previousPrice: Double?, previousChangedAt: Long, price: Double, time: Long): Long =
        if (previousPrice != null && previousPrice == price && previousChangedAt > 0) previousChangedAt else time
}
