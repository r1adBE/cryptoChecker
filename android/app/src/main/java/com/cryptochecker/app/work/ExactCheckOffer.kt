package com.cryptochecker.app.work

/**
 * «Pünktlich prüfen» nach dem allerersten Alarm einmal anbieten (Review 10): ohne exakte Prüfung
 * können Alarme im Doze-Modus Stunden zu spät kommen. Nie wiederholt (lokaler Merker, nicht gesichert).
 */
object ExactCheckOffer {
    enum class Kind {
        /** Aus, Berechtigung da: nur einschalten. */
        TURN_ON,

        /** Berechtigung fehlt: einschalten und zur Einstellung «Wecker und Erinnerungen». */
        GRANT,
    }

    /** null = nichts anbieten (schon aktiv oder schon einmal angeboten). */
    fun decide(exactOn: Boolean, permitted: Boolean, offeredBefore: Boolean): Kind? = when {
        offeredBefore -> null
        exactOn && permitted -> null
        !permitted -> Kind.GRANT
        else -> Kind.TURN_ON
    }
}
