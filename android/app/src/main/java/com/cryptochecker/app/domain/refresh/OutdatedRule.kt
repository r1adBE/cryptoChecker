package com.cryptochecker.app.domain.refresh

/**
 * Ab wann ein Kurs «veraltet» heisst — eine Regel für Merkliste und Widgets:
 * dreimal das eingestellte Intervall (Live-Dienst: Sekunden, sonst Hintergrund-Minuten),
 * mindestens 15 Minuten. Reine Logik ohne Android (getestet in OutdatedRuleTest,
 * Swift-Spiegel OutdatedRule.swift).
 */
object OutdatedRule {

    /** Untergrenze: nie früher als nach 15 Minuten «veraltet». */
    const val MIN_MILLIS = 15 * 60_000L

    /** Eingestelltes Abfrage-Intervall in Millisekunden. */
    fun intervalMillis(liveService: Boolean, liveIntervalSeconds: Int, backgroundIntervalMinutes: Int): Long =
        if (liveService) liveIntervalSeconds * 1_000L else backgroundIntervalMinutes * 60_000L

    /** Alter, ab dem ein Kurs veraltet ist: max(3 × Intervall, 15 Min.). */
    fun afterMillis(liveService: Boolean, liveIntervalSeconds: Int, backgroundIntervalMinutes: Int): Long =
        maxOf(intervalMillis(liveService, liveIntervalSeconds, backgroundIntervalMinutes) * 3, MIN_MILLIS)

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
