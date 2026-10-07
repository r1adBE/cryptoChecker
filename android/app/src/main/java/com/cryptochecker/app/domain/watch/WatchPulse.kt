package com.cryptochecker.app.domain.watch

import kotlin.math.abs

/**
 * Puls-Zeile über der Merkliste: «▲ 7 steigen · ▼ 3 fallen · Ø ▲ +1.80%», dazu klein
 * «· 510 ohne 24h-Wert», wenn gezeigte Paare (mit Kurs) keinen 24-h-Wert haben ([missing]).
 *
 * Grundlage ist derselbe Wert wie in der Prozent-Pille jeder Zeile
 * (`WatchEntity.change24h`: Veränderung über 24 Stunden, siehe [DayChange]) — so
 * passen Zeile und Puls immer zusammen. Ø ist der einfache Durchschnitt dieser
 * Werte (jedes Paar zählt gleich). Paare ohne 24-h-Wert (Pille «—») fallen weg, werden
 * aber ehrlich als [missing] gezählt. Praktisch unveränderte Paare (Pille «0.00%») zählen
 * weder als steigend noch als fallend, fliessen aber in den Durchschnitt ein.
 */
data class WatchPulse(val up: Int, val down: Int, val average: Double, val missing: Int = 0) {

    /** Durchschnitt praktisch 0 (wird wie die Pille grau «0.00%» gezeigt). */
    val flat: Boolean get() = abs(average) < FLAT_BELOW

    companion object {
        /** Weniger Paare mit 24-h-Veränderung: keine Puls-Zeile. */
        const val MIN_VALUES = 2

        /** Wie `PriceFormat.changePercent`: darunter gilt eine Änderung als 0.00 %. */
        const val FLAT_BELOW = 0.005

        /**
         * Puls der gezeigten Paare aus ihren 24-h-Veränderungen; null bei weniger als [MIN_VALUES] Werten.
         * [missing]: Anzahl gezeigter Paare ohne 24-h-Wert — Standard: alle null/ungültigen in [changes].
         */
        fun of(
            changes: List<Double?>,
            missing: Int = changes.count { it == null || !it.isFinite() },
        ): WatchPulse? {
            val values = changes.filterNotNull().filter { it.isFinite() }
            if (values.size < MIN_VALUES) return null
            return WatchPulse(
                up = values.count { it >= FLAT_BELOW },
                down = values.count { it <= -FLAT_BELOW },
                average = values.average(),
                missing = missing.coerceAtLeast(0),
            )
        }

        /**
         * Für die Merkliste: [changes] aller gezeigten Paare; [hasPrice] je Paar, ob es überhaupt
         * einen Kurs hat (und gehandelt wird) — nur solche zählen als «ohne 24h-Wert».
         * Paare ohne Kurs zeigen keine Pille, gehören also nicht dazu.
         */
        fun of(changes: List<Double?>, hasPrice: List<Boolean>): WatchPulse? {
            require(changes.size == hasPrice.size) { "changes and hasPrice differ in size" }
            var missing = 0
            for (i in changes.indices) {
                val c = changes[i]
                if (hasPrice[i] && (c == null || !c.isFinite())) missing++
            }
            return of(changes, missing)
        }
    }
}
