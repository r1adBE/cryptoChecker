package com.cryptochecker.app.domain.refresh

/**
 * Sperre für die vom Nutzer erzwungene Gesamt-Aktualisierung (nach unten ziehen,
 * Menü): Läuft schon eine, oder ist die letzte vollständige erst [WINDOW_MILLIS]
 * her, startet keine neue — die Anzeige zeigt dann einfach den bestehenden Stand
 * («Alles aktuell · vor 8 s»), «Aktualisieren» im Menü ist so lange grau und zählt die
 * Sekunden herunter ([remainingSeconds]); nach unten ziehen wird trotzdem angenommen: Der Kreisel dreht kurz
 * ([PULL_ECHO_MILLIS]), danach «Gerade aktualisiert · vor N s» ([pullEchoSeconds]) — ohne neuen Durchlauf.
 * Ein einzelnes Paar (Aktionsblatt) ist davon ausgenommen.
 *
 * Reine Logik ohne Android (getestet in RefreshDebounceTest, Swift-Spiegel RefreshDebounce.swift).
 */
object RefreshDebounce {

    /** So lange nach dem Ende einer vollständigen Aktualisierung startet keine erzwungene neue. */
    const val WINDOW_MILLIS = 15_000L

    /** Ziehen im Fenster: so lange dreht der Kreisel trotzdem (Rückmeldung «angenommen»), ohne Abfrage. */
    const val PULL_ECHO_MILLIS = 400L

    enum class Decision {
        /** Jetzt aktualisieren. */
        START,

        /** Läuft schon — nichts tun, der Kreisel dreht bereits. */
        RUNNING,

        /** Eben erst fertig — kein neuer Durchlauf (Menüpunkt grau mit Countdown). */
        RECENT,
    }

    /**
     * @param running läuft gerade eine vollständige Aktualisierung (gleich welcher Herkunft)?
     * @param lastFinishedAt Ende der letzten vollständigen Aktualisierung mit mindestens einem
     *   Kurs; 0 = noch keine. Liegt sie in der Zukunft (Uhr verstellt), wird aktualisiert.
     */
    fun decide(running: Boolean, lastFinishedAt: Long, now: Long, windowMillis: Long = WINDOW_MILLIS): Decision {
        if (running) return Decision.RUNNING
        if (lastFinishedAt <= 0) return Decision.START
        val age = now - lastFinishedAt
        return if (age in 0 until windowMillis) Decision.RECENT else Decision.START
    }

    /**
     * Ziehen nach unten mit Ergebnis [decision]: Sekunden seit der letzten vollständigen Aktualisierung für «Gerade
     * aktualisiert · vor N s» (abgerundet, mindestens 1) — nur bei [Decision.RECENT]; sonst null (es läuft schon bzw.
     * ein neuer Durchlauf startet, dann zeigt der Kreisel genug).
     */
    fun pullEchoSeconds(decision: Decision, lastFinishedAt: Long, now: Long): Int? {
        if (decision != Decision.RECENT || lastFinishedAt <= 0) return null
        val age = now - lastFinishedAt
        if (age < 0) return null
        return (age / 1000).toInt().coerceAtLeast(1)
    }

    /**
     * Ganze Sekunden, bis «Aktualisieren» wieder geht (aufgerundet: 14,2 s → 15), für den Countdown
     * im Menü und die Meldung beim Ziehen. 0 = frei — gleiche Grenzen wie [decide] (noch keine,
     * Fenster vorbei oder Zeitpunkt in der Zukunft). Ob gerade eine läuft, prüft der Aufrufer.
     */
    fun remainingSeconds(lastFinishedAt: Long, now: Long, windowMillis: Long = WINDOW_MILLIS): Int {
        val rest = remainingMillis(lastFinishedAt, now, windowMillis)
        return ((rest + 999) / 1000).toInt()
    }

    /**
     * Wie lange warten, bis [remainingSeconds] die nächste Zahl zeigt (1..1000 ms); 0 = frei.
     * So springt der Countdown genau auf der Sekundengrenze, statt bis zu 1 s nachzuhinken.
     */
    fun millisUntilNextChange(lastFinishedAt: Long, now: Long, windowMillis: Long = WINDOW_MILLIS): Long {
        val rest = remainingMillis(lastFinishedAt, now, windowMillis)
        return if (rest <= 0) 0 else (rest - 1) % 1000 + 1
    }

    private fun remainingMillis(lastFinishedAt: Long, now: Long, windowMillis: Long): Long {
        if (lastFinishedAt <= 0) return 0
        val age = now - lastFinishedAt
        return if (age in 0 until windowMillis) windowMillis - age else 0
    }
}
