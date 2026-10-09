package com.cryptochecker.app.domain.refresh

/**
 * Sperre für die vom Nutzer erzwungene Gesamt-Aktualisierung (nach unten ziehen,
 * Knopf oben): Läuft schon eine, oder ist die letzte vollständige erst [WINDOW_MILLIS]
 * her, startet keine neue — die Anzeige zeigt dann einfach den bestehenden Stand
 * («Alles aktuell · vor 8 s») bzw. kurz «Gerade aktualisiert», ohne Fehlermeldung.
 * Ein einzelnes Paar (Aktionsblatt) ist davon ausgenommen.
 *
 * Reine Logik ohne Android (getestet in RefreshDebounceTest, Swift-Spiegel RefreshDebounce.swift).
 */
object RefreshDebounce {

    /** So lange nach dem Ende einer vollständigen Aktualisierung startet keine erzwungene neue. */
    const val WINDOW_MILLIS = 15_000L

    enum class Decision {
        /** Jetzt aktualisieren. */
        START,

        /** Läuft schon — nichts tun, der Kreisel dreht bereits. */
        RUNNING,

        /** Eben erst fertig — kurzer Hinweis «Gerade aktualisiert», kein neuer Durchlauf. */
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
}
