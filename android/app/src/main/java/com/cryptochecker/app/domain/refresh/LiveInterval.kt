package com.cryptochecker.app.domain.refresh

/**
 * Takt des Live-Modus (Vordergrunddienst): Mit sichtbarer App das gewählte Intervall, bei
 * geschlossener App höchstens jede Minute ([HIDDEN_MIN_SECONDS]) — spart Akku und Daten,
 * wenn niemand hinschaut. Wird die App wieder sichtbar, gilt sofort das gewählte Intervall:
 * Ist der letzte Durchlauf schon älter, kommt gleich einer. Reine Logik (getestet in
 * LiveIntervalTest); iOS braucht das nicht (Live läuft dort nur mit offener App).
 */
object LiveInterval {

    /** Kürzester Takt bei geschlossener App. */
    const val HIDDEN_MIN_SECONDS = 60

    /** Abstand in Millisekunden: [chosenSeconds] (mindestens [minSeconds]), unsichtbar mindestens 60 s. */
    fun intervalMillis(chosenSeconds: Int, appVisible: Boolean, minSeconds: Int = 15): Long {
        val chosen = chosenSeconds.coerceAtLeast(minSeconds)
        val seconds = if (appVisible) chosen else maxOf(chosen, HIDDEN_MIN_SECONDS)
        return seconds * 1_000L
    }

    /** Wartezeit bis zum nächsten Durchlauf ab [now] (0 = sofort), gemessen ab [lastRunAt]. */
    fun waitMillis(lastRunAt: Long, now: Long, chosenSeconds: Int, appVisible: Boolean, minSeconds: Int = 15): Long {
        // Uhr zurückgestellt (now < lastRunAt): höchstens ein Intervall warten
        val elapsed = (now - lastRunAt).coerceAtLeast(0L)
        return (intervalMillis(chosenSeconds, appVisible, minSeconds) - elapsed).coerceAtLeast(0L)
    }
}
