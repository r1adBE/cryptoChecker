package com.cryptochecker.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/**
 * Zahlen als Dezimaltext ohne Exponent und Tausendertrennung, mit Punkt — für Export,
 * vorbefüllte Eingabefelder und Rundung vor einem `"%.nf"`. Reines Kotlin, als Unit-Test
 * prüfbar (Spiegel: `DecimalText` in Shared/Util/PriceFormat.swift).
 *
 * Gerechnet wird mit der kürzesten Dezimaldarstellung des Double ([BigDecimal.valueOf]),
 * nicht mit dem exakten Binärwert: 1.005 ist «1.005» und rundet kaufmännisch
 * ([RoundingMode.HALF_UP], bei der Hälfte weg von 0) auf «1.01» — auf beiden Plattformen
 * gleich (`"%.2f"` in C/Swift ergäbe «1.00», in Java «1.01»).
 *
 * Nicht endliche Werte (NaN, ±∞) ergeben einen leeren Text.
 */
object DecimalText {

    private fun decimal(value: Double): BigDecimal? = if (value.isFinite()) BigDecimal.valueOf(value) else null

    /** Ohne Nullen am Ende; mit [scale] vorher auf so viele Nachkommastellen gerundet. «-0» → «0». */
    fun plain(value: Double, scale: Int? = null): String {
        var d = decimal(value) ?: return ""
        if (scale != null) d = d.setScale(scale, RoundingMode.HALF_UP)
        if (d.signum() == 0) return "0"
        return d.stripTrailingZeros().toPlainString()
    }

    /** Genau [scale] Nachkommastellen, kaufmännisch gerundet; «-0.00» → «0.00». */
    fun fixed(value: Double, scale: Int): String {
        val d = decimal(value)?.setScale(scale, RoundingMode.HALF_UP) ?: return ""
        return (if (d.signum() == 0) d.abs() else d).toPlainString()
    }

    /** Kaufmännisch auf [scale] Nachkommastellen gerundet (für ein folgendes `"%.nf"`). */
    fun rounded(value: Double, scale: Int): Double =
        decimal(value)?.setScale(scale, RoundingMode.HALF_UP)?.toDouble() ?: value

    /**
     * Stellen vor dem Komma (ohne führende Nullen): 123.4 → 3, 0.5 → 0, 0.000123 → −3; 0 → 0.
     */
    fun integerDigits(value: Double): Int {
        val d = decimal(value)?.stripTrailingZeros() ?: return 0
        if (d.signum() == 0) return 0
        return d.precision() - d.scale()
    }

    /**
     * Nachkommastellen für mindestens [minDecimals] Stellen und mindestens [significant]
     * gültige Stellen: 123.456 → 10, 3e-11 → 20 (bei 10/10) — kleinste Kurse bleiben so lesbar.
     */
    fun significantScale(value: Double, minDecimals: Int, significant: Int): Int =
        max(minDecimals, significant - integerDigits(value))
}
