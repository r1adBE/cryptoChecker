package com.cryptochecker.wear

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** Zahlen auf der Uhr: Kurs kurz (ab 1000 ohne Nachkommastellen-Flut) und Änderung mit Vorzeichen. */
object WatchFormat {
    fun price(value: Double, locale: Locale = Locale.getDefault()): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val pattern = when {
            value >= 1000 -> "#,##0.00"
            value >= 1 -> "#,##0.00##"
            value >= 0.01 -> "0.0000##"
            else -> "0.00000000##"
        }
        return DecimalFormat(pattern, symbols).format(value)
    }

    fun change(value: Double?, locale: Locale = Locale.getDefault()): String {
        if (value == null || !value.isFinite()) return "—"
        val sign = when {
            abs(value) < 0.005 -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return sign + DecimalFormat("0.00", DecimalFormatSymbols.getInstance(locale)).format(abs(value)) + "%"
    }
}
