package com.cryptochecker.app.work

/**
 * Nach dem allerersten Alarm einmal fragen, wie oft geprüft wird, wenn das Gerät ruht: zwei ausdrückliche
 * Wahlen «Sparsam» (ohne «Pünktlich prüfen»; im Doze-Modus können Alarme Stunden zu spät kommen) oder
 * «Etwa alle 15 Minuten» (schaltet «Pünktlich prüfen» ein, samt Berechtigung). Nie wiederholt (lokaler
 * Merker, nicht gesichert); später änderbar über den Schalter in den Einstellungen.
 */
object ExactCheckOffer {
    enum class Kind {
        /** Aus, Berechtigung da: «Etwa alle 15 Minuten» schaltet nur ein. */
        TURN_ON,

        /** Berechtigung fehlt: «Etwa alle 15 Minuten» schaltet ein und führt zu «Wecker und Erinnerungen». */
        GRANT,
    }

    /** Die zwei Wahlen im Dialog nach dem ersten Alarm. */
    enum class Choice {
        /** «Sparsam»: «Pünktlich prüfen» aus. */
        SAVING,

        /** «Etwa alle 15 Minuten»: «Pünktlich prüfen» ein. */
        EVERY_15_MIN,
    }

    /** null = nicht fragen (schon aktiv oder schon einmal gefragt). */
    fun decide(exactOn: Boolean, permitted: Boolean, offeredBefore: Boolean): Kind? = when {
        offeredBefore -> null
        exactOn && permitted -> null
        !permitted -> Kind.GRANT
        else -> Kind.TURN_ON
    }

    /** Wert des Schalters «Pünktlich prüfen» nach der Wahl. */
    fun exactChecksFor(choice: Choice): Boolean = choice == Choice.EVERY_15_MIN

    /** Nach der Wahl zur Systemeinstellung «Wecker und Erinnerungen»? Nur bei «Etwa alle 15 Minuten» ohne Berechtigung. */
    fun opensPermission(kind: Kind, choice: Choice): Boolean = kind == Kind.GRANT && choice == Choice.EVERY_15_MIN
}
