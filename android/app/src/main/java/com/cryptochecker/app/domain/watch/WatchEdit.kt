package com.cryptochecker.app.domain.watch

/**
 * «Paar bearbeiten» (Stift im Aktionsblatt): Börse, Paar und Kontrakt eines bestehenden
 * Eintrags ändern. Der Eintrag bleibt derselbe (Id, Gruppe, Favorit, Notiz, Platz, Alarme);
 * nur die Marktdaten des alten Paars werden verworfen. Reines Kotlin (testbar, siehe
 * WatchEditTest), gespiegelt in `WatchEdit.swift`.
 */
object WatchEdit {
    /** Wie der eindeutige Schlüssel der Merkliste: Börse, Basis, Quote, Kontrakt (Name). */
    data class Key(val marketKey: String, val base: String, val quote: String, val contract: String)

    enum class Outcome {
        /** Gleiche Auswahl wie bisher: nichts zu tun. */
        UNCHANGED,

        /** Diese Börse/dieses Paar steht schon als anderer Eintrag in der Merkliste. */
        DUPLICATE,

        /** Speichern: derselbe Eintrag mit neuem Paar. */
        CHANGED,
    }

    /**
     * [existingId] = Id des Eintrags, der [target] schon führt (null = keiner). Findet die
     * Suche den Eintrag selbst ([selfId]), ist das kein Doppel.
     */
    fun decide(selfId: Long, current: Key, target: Key, existingId: Long?): Outcome = when {
        current == target -> Outcome.UNCHANGED
        existingId != null && existingId != selfId -> Outcome.DUPLICATE
        else -> Outcome.CHANGED
    }

    /**
     * Hinweis «Alarme bleiben bestehen – Schwellen prüfen»: nur, wenn eine andere Auswahl
     * getroffen ist und das Paar Alarme hat (Schwellen gelten für den alten Kurs).
     */
    fun warnAlarms(current: Key, target: Key?, alarmCount: Int): Boolean =
        target != null && target != current && alarmCount > 0
}
