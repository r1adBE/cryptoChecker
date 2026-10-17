package com.cryptochecker.app.util

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Gehaltene Menge klein vor dem Kürzel in den Portfolio-Widgets («1.3 BTC», «12.45 ETH»).
 * Reines Kotlin (Spiegel: `WidgetQuantityFormat.swift`, Testfälle `widget_quantity.json`).
 *
 * Regeln:
 *  - höchstens 2 Nachkommastellen, Nullen am Ende weg (1.30 → «1.3», 3 → «3»),
 *  - unter 0.01: 2 gültige Stellen («0.0042», «0.00051») — nie «0.00»,
 *  - immer abgeschnitten (ROUND_DOWN), nie gerundet: das Widget zeigt nie mehr als gehalten,
 *  - immer mit Null vor dem Komma, Tausendertrennung und Dezimalzeichen der Sprache.
 *
 * Gerechnet wird auf dem Dezimaltext (aus [DecimalText.plain]), nicht auf dem Double —
 * so schneidet 1.13 nicht wegen 1.1299999… auf «1.12» ab.
 */
object WidgetQuantityFormat {

    /** Anzeige im Widget: null bei verborgenen Beträgen ([hidden]) oder ohne Menge (ältere Aufnahme). */
    fun visible(quantity: String?, hidden: Boolean, locale: Locale = LocaleNumbers.appLocale): String? =
        if (hidden) null else format(quantity, locale)

    /** Kürzel für den Screenreader: «1.3 BTC» mit Menge, sonst nur das Kürzel. */
    fun spokenName(symbol: String, quantity: String?): String =
        if (quantity.isNullOrEmpty()) symbol else "$quantity $symbol"

    /** Mit Dezimalzeichen und Tausendertrennung der App-Sprache. */
    fun format(text: String?, locale: Locale = LocaleNumbers.appLocale): String? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        return format(text, symbols.decimalSeparator.toString(), symbols.groupingSeparator.toString())
    }

    /**
     * @param text Menge als Dezimaltext («12.4567», auch «1.2E-5»); Punkt als Dezimalzeichen
     * @return null bei leerem, ungültigem, negativem oder null-Wert (Widget zeigt dann keine Menge)
     */
    fun format(text: String?, decimal: String, grouping: String): String? {
        val (intPart, fracPart) = split(text?.trim() ?: return null) ?: return null
        if (intPart == "0" && fracPart.all { it == '0' }) return null
        val frac = if (intPart != "0" || fracPart.take(2).any { it != '0' }) {
            fracPart.take(2)
        } else {
            // Unter 0.01: bis zur zweiten gültigen Stelle
            val first = fracPart.indexOfFirst { it != '0' }
            fracPart.take(first + 2)
        }.trimEnd('0')
        val grouped = group(intPart, grouping)
        return if (frac.isEmpty()) grouped else grouped + decimal + frac
    }

    /** Ganz- und Nachkommastellen (ohne führende Nullen, mindestens «0»); null = ungültig/negativ. */
    private fun split(raw: String): Pair<String, String>? {
        if (raw.isEmpty() || raw.startsWith("-")) return null
        var mantissa = raw.removePrefix("+")
        var exponent = 0
        val e = mantissa.indexOfFirst { it == 'e' || it == 'E' }
        if (e >= 0) {
            exponent = mantissa.substring(e + 1).removePrefix("+").toIntOrNull() ?: return null
            mantissa = mantissa.substring(0, e)
        }
        val dot = mantissa.indexOf('.')
        var intDigits = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        var fracDigits = if (dot >= 0) mantissa.substring(dot + 1) else ""
        if (intDigits.isEmpty() && fracDigits.isEmpty()) return null
        if (!(intDigits + fracDigits).all { it in '0'..'9' }) return null
        // Komma um den Exponenten verschieben
        if (exponent > 0) {
            val padded = fracDigits.padEnd(exponent, '0')
            intDigits += padded.substring(0, exponent)
            fracDigits = padded.substring(exponent)
        } else if (exponent < 0) {
            val padded = intDigits.padStart(-exponent, '0')
            fracDigits = padded.substring(padded.length + exponent) + fracDigits
            intDigits = padded.substring(0, padded.length + exponent)
        }
        return intDigits.trimStart('0').ifEmpty { "0" } to fracDigits
    }

    /** Dreiergruppen von rechts. */
    private fun group(digits: String, grouping: String): String {
        if (digits.length <= 3 || grouping.isEmpty()) return digits
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(grouping)
            out.append(c)
        }
        return out.toString()
    }
}
