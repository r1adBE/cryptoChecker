package com.cryptochecker.app.util

import kotlin.math.abs

/** Richtung einer Änderung für Screenreader-Texte (a11y_change_up/down/flat). */
enum class ChangeDirection { UP, DOWN, FLAT }

/**
 * Kennzahlen einer Kursreihe für die Screenreader-Beschreibung eines Charts:
 * Start, Ende, Hoch, Tief und die Änderung in Prozent.
 */
data class ChartSummary(
    val start: Double,
    val end: Double,
    val high: Double,
    val low: Double,
) {
    /** Änderung Start → Ende in Prozent; null, wenn der Start 0 ist. */
    val changePercent: Double?
        get() = if (start == 0.0) null else (end - start) / abs(start) * 100.0

    companion object {
        /** null bei weniger als zwei Werten. */
        fun of(values: List<Double>): ChartSummary? {
            if (values.size < 2) return null
            return ChartSummary(
                start = values.first(),
                end = values.last(),
                high = values.max(),
                low = values.min(),
            )
        }

        /** Unter einem halben Hundertstel-Prozent gilt «unverändert» (wie [PriceFormat.changePercent]). */
        fun direction(percent: Double?): ChangeDirection = when {
            percent == null || abs(percent) < 0.005 -> ChangeDirection.FLAT
            percent > 0 -> ChangeDirection.UP
            else -> ChangeDirection.DOWN
        }

        /**
         * Prozent ohne Vorzeichen für Screenreader («2.35%»): Die Richtung steht als
         * Wort davor, das Zeichen «−» lesen Screenreader uneinheitlich.
         */
        fun unsignedPercent(percent: Double, decimals: Int = 2): String =
            "%.${decimals}f%%".format(abs(percent))
    }
}
