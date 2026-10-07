package com.cryptochecker.app.domain.watch

import kotlin.math.abs

/**
 * Puls-Zeile über der Merkliste: «▲ 7 steigen · ▼ 3 fallen · Ø ▲ +1.80%».
 *
 * Grundlage ist derselbe Wert wie in der Prozent-Pille jeder Zeile
 * (`WatchEntity.change24h`: Veränderung über 24 Stunden, siehe [DayChange]) — so
 * passen Zeile und Puls immer zusammen. Ø ist der einfache Durchschnitt dieser
 * Werte (jedes Paar zählt gleich). Paare ohne 24-h-Wert (Pille «—») fallen weg.
 * Praktisch unveränderte Paare (Pille «0.00%») zählen weder als steigend noch als
 * fallend, fliessen aber in den Durchschnitt ein.
 */
data class WatchPulse(val up: Int, val down: Int, val average: Double) {

    /** Durchschnitt praktisch 0 (wird wie die Pille grau «0.00%» gezeigt). */
    val flat: Boolean get() = abs(average) < FLAT_BELOW

    companion object {
        /** Weniger Paare mit 24-h-Veränderung: keine Puls-Zeile. */
        const val MIN_VALUES = 2

        /** Wie `PriceFormat.changePercent`: darunter gilt eine Änderung als 0.00 %. */
        const val FLAT_BELOW = 0.005

        /** Puls der gezeigten Paare aus ihren 24-h-Veränderungen; null bei weniger als [MIN_VALUES] Werten. */
        fun of(changes: List<Double?>): WatchPulse? {
            val values = changes.filterNotNull().filter { it.isFinite() }
            if (values.size < MIN_VALUES) return null
            return WatchPulse(
                up = values.count { it >= FLAT_BELOW },
                down = values.count { it <= -FLAT_BELOW },
                average = values.average(),
            )
        }
    }
}
