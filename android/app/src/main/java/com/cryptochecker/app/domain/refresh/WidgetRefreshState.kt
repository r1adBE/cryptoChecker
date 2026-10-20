package com.cryptochecker.app.domain.refresh

/**
 * Aktualisieren-Knopf im Merkliste-Widget mit derselben 15-s-Sperre wie die App ([RefreshDebounce]):
 * Ein Tipp, während eine vollständige Aktualisierung läuft oder die letzte keine 15 s her ist, startet
 * nichts. Solange die Sperre gilt — gleich nach dem Tipp, der die Aktualisierung ausgelöst hat, wie nach
 * jeder anderen vollständigen — zeigt das Widget statt des Symbols einen Countdown ([blockedUntil]),
 * vom Launcher selbst heruntergezählt (Android: Chronometer, iOS: Text mit Zeitspanne), also ohne
 * Neuzeichnen je Sekunde; einmal neu gezeichnet wird erst am Ende, dann kommt das Symbol zurück.
 *
 * Reine Logik ohne Android (getestet in WidgetRefreshStateTest, Swift-Spiegel WidgetRefreshState.swift).
 */
object WidgetRefreshState {

    /** Was ein Tipp auf den Knopf auslöst. */
    sealed interface Tap {
        /** Jetzt aktualisieren. */
        data object Start : Tap

        /** Läuft schon — nichts tun. */
        data object Running : Tap

        /** Gesperrt bis [until] (Wanduhr, ms): nichts holen, Countdown zeigen. */
        data class Blocked(val until: Long) : Tap
    }

    /** Gleiche Grenzen wie [RefreshDebounce.decide]; gesperrt bis zum Ende des Fensters. */
    fun onTap(running: Boolean, lastFinishedAt: Long, now: Long, windowMillis: Long = RefreshDebounce.WINDOW_MILLIS): Tap =
        when (RefreshDebounce.decide(running, lastFinishedAt, now, windowMillis)) {
            RefreshDebounce.Decision.START -> Tap.Start
            RefreshDebounce.Decision.RUNNING -> Tap.Running
            RefreshDebounce.Decision.RECENT -> Tap.Blocked(lastFinishedAt + windowMillis)
        }

    /**
     * Ende der Sperre, das ein Zeichnen jetzt zeigt (Wanduhr, ms); 0 = Knopf zeigen. Das spätere von
     * gespeichertem Ende (Tipp, [Tap.Blocked.until]) und Ende des Fensters nach der letzten vollständigen
     * Aktualisierung — soweit es noch gilt ([remainingMillis]).
     * Läuft eine Aktualisierung, die nach der letzten fertigen begonnen hat, kein Countdown (der Knopf bleibt,
     * ein Tipp tut nichts). Gezeichnet am Ende einer Aktualisierung (noch unter ihrer Sperre, aber mit
     * schon gesetzter Zeit) gilt sie als fertig: dort beginnt der Countdown.
     * @param runningSince Beginn der laufenden vollständigen Aktualisierung; 0 = keine
     */
    fun blockedUntil(
        storedUntil: Long,
        lastFinishedAt: Long,
        runningSince: Long,
        now: Long,
        windowMillis: Long = RefreshDebounce.WINDOW_MILLIS,
    ): Long {
        if (runningSince > 0 && runningSince >= lastFinishedAt) return 0
        val afterRefresh = if (lastFinishedAt > 0) lastFinishedAt + windowMillis else 0
        return listOf(storedUntil, afterRefresh)
            .filter { remainingMillis(it, now, windowMillis) > 0 }
            .maxOrNull() ?: 0
    }

    /**
     * Restzeit der Sperre beim Zeichnen (ms); 0 = Knopf zeigen. Nie länger als ein Fenster: Wurde die Uhr
     * zurückgestellt, stünde der Countdown sonst beliebig lange da.
     * @param blockedUntil Ende der Sperre ([blockedUntil], [Tap.Blocked.until]); 0 = keine Sperre
     */
    fun remainingMillis(blockedUntil: Long, now: Long, windowMillis: Long = RefreshDebounce.WINDOW_MILLIS): Long {
        if (blockedUntil <= 0) return 0
        val rest = blockedUntil - now
        return if (rest in 1..windowMillis) rest else 0
    }

    /** Ganze Sekunden für den Screenreader (aufgerundet wie [RefreshDebounce.remainingSeconds]); 0 = frei. */
    fun remainingSeconds(blockedUntil: Long, now: Long, windowMillis: Long = RefreshDebounce.WINDOW_MILLIS): Int =
        ((remainingMillis(blockedUntil, now, windowMillis) + 999) / 1000).toInt()

    /**
     * Basis für den Chronometer im Countdown-Modus (Zeitbasis elapsedRealtime). Er zeigt die abgeschnittenen
     * Sekunden bis zur Basis; 999 ms dazu runden auf wie [remainingSeconds] — «15» … «1», «0» erst am Ende.
     */
    fun chronometerBase(elapsedNow: Long, remainingMillis: Long): Long = elapsedNow + remainingMillis + 999

    /**
     * Countdown nur bei eingeschaltetem Bildschirm (Android): Gezeichnet bei ausgeschaltetem Bildschirm
     * (Hintergrund-Aktualisierung) zeigt das Widget gleich das Symbol – ein Tipp in der Sperre tut trotzdem
     * nichts ([onTap]). @return Restzeit für den Countdown; 0 = Symbol
     */
    fun countdownMillis(remainingMillis: Long, screenOn: Boolean): Long = if (screenOn) remainingMillis else 0

    /** Neuzeichnen am Ende der Sperre etwas danach, damit sicher das Symbol kommt (nicht «0»). */
    const val UNLOCK_SLACK_MILLIS = 300L
}
