package com.cryptochecker.app.service

/**
 * Ob der Live-Dienst gerade läuft — damit die periodische Hintergrund-Aktualisierung
 * nicht dieselbe Arbeit parallel macht. Der Dienst meldet jeden Durchlauf ([beat]);
 * gilt als laufend, solange die letzte Meldung jünger als [MAX_AGE_MILLIS] ist
 * (hängt die Schleife oder wurde der Prozess neu gestartet, übernimmt wieder der Job).
 *
 * Reines Kotlin ohne Android, damit es ohne Gerät testbar ist.
 */
object LiveServiceGate {

    /**
     * Längstes Live-Intervall (300 s) zweimal plus Reserve für einen langsamen Durchlauf.
     */
    const val MAX_AGE_MILLIS = 12 * 60_000L

    /** Zeitpunkt des letzten Durchlaufs des Live-Dienstes in diesem Prozess; 0 = läuft nicht. */
    @Volatile
    var lastBeatAt: Long = 0L
        private set

    fun beat(now: Long = System.currentTimeMillis()) {
        lastBeatAt = now
    }

    fun stopped() {
        lastBeatAt = 0L
    }

    fun isRunning(now: Long = System.currentTimeMillis()): Boolean = isAlive(lastBeatAt, now)

    /** Reine Regel: Meldung vorhanden, nicht aus der Zukunft und jünger als [maxAgeMillis]. */
    fun isAlive(lastBeatAt: Long, now: Long, maxAgeMillis: Long = MAX_AGE_MILLIS): Boolean =
        lastBeatAt > 0L && now >= lastBeatAt && now - lastBeatAt < maxAgeMillis

    /**
     * Soll der Hintergrund-Job diesen Durchlauf auslassen? Nur der periodische —
     * ein Knopfdruck (manuell) läuft immer.
     */
    fun skipWorker(manual: Boolean, lastBeatAt: Long, now: Long): Boolean =
        !manual && isAlive(lastBeatAt, now)
}
