package com.cryptochecker.app.domain.refresh

/**
 * Ab wann ein Kurs «veraltet» heisst — eine Regel für Merkliste, Portfolio und Widgets:
 * dreimal das Hintergrund-Intervall, mindestens 15 Minuten. Abgefragt wird nur im
 * Hintergrund-Takt oder von Hand; einen eigenen schnelleren Abfrage-Takt gibt es nicht.
 * Paare am Live-Strom haben ihre eigene, kürzere
 * Grenze [LIVE_MILLIS] (siehe LiveStatus).
 * Reine Logik ohne Android (getestet in OutdatedRuleTest und den Parity-Fällen
 * `testdata/parity/outdated.json`, Swift-Spiegel OutdatedRule.swift).
 */
object OutdatedRule {

    /** Untergrenze: nie früher als nach 15 Minuten «veraltet». */
    const val MIN_MILLIS = 15 * 60_000L

    /** Paar am Live-Strom: nach 2 Minuten ohne neuen Kurs «veraltet». */
    const val LIVE_MILLIS = 2 * 60_000L

    /** Alter, ab dem ein abgefragter Kurs veraltet ist: max(3 × Hintergrund-Intervall, 15 Min.). */
    fun afterMillis(backgroundIntervalMinutes: Int): Long =
        maxOf(backgroundIntervalMinutes * 60_000L * 3, MIN_MILLIS)

    /** Veraltet? Ohne Zeitpunkt (≤ 0) nie — dann steht ohnehin «—». */
    fun isOutdated(time: Long, now: Long, afterMillis: Long): Boolean =
        time > 0 && now - time > afterMillis

    /**
     * Nächster Zeitpunkt, an dem einer der [times] veraltet wird (eine Millisekunde nach der
     * Grenze), oder null, wenn keiner mehr wechselt (alle schon veraltet oder ohne Zeit).
     * Für den Wecker, der die Widgets dann neu zeichnet.
     */
    fun nextChangeAt(times: Iterable<Long>, now: Long, afterMillis: Long): Long? =
        times.filter { it > 0 && !isOutdated(it, now, afterMillis) }
            .minOrNull()
            ?.let { it + afterMillis + 1 }
}
