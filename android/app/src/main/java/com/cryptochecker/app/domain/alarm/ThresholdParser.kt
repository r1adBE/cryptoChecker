package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.BidiText
import kotlin.math.abs
import kotlin.math.ln

/**
 * Liest einen getippten oder eingefügten Schwellwert — robust gegen Tausendertrennung und
 * Dezimalzeichen der Region. Reines Kotlin, als Unit-Test prüfbar
 * (Spiegel: Shared/Services/ThresholdParser.swift).
 *
 * - Tausendertrenner ’ ' ‘ ` Leerzeichen, geschütztes und schmales Leerzeichen werden ignoriert.
 * - Kommen Punkt und Komma vor, ist das letzte der beiden das Dezimalzeichen: «1.234,56», «1,234.56».
 * - Mehrfach dasselbe Zeichen = Tausendertrennung in Dreiergruppen: «1.234.567», «1,234,567».
 * - Einmal ein Zeichen mit genau drei Ziffern danach («60,000», «60.000») ist mehrdeutig:
 *   Mit [priceHint] (aktueller Kurs in der Währung des Schwellwerts) gewinnt die Lesart, die
 *   näher am Kurs liegt (Verhältnis am nächsten bei 1); ohne Kurs entscheidet das
 *   Dezimalzeichen der Region ([decimalSeparator]).
 * - Arabisch-indische und persische Ziffern sowie «٫»/«٬» gelten wie 0–9, «.» und «’» ([latinDigits]).
 * - Alles andere (Buchstaben wie «60k», Vorzeichen, Exponent) ist ungültig → null.
 *
 * Ergebnis immer > 0 und endlich, sonst null.
 */
object ThresholdParser {

    /** Zeichen, die nur der Tausendertrennung dienen. */
    private val GROUPING = setOf('’', '\'', '‘', '`', ' ', ' ', ' ', ' ')

    fun parse(text: String, decimalSeparator: Char, priceHint: Double? = null): Double? {
        val cleaned = buildString {
            for (c in latinDigits(text).trim()) {
                when {
                    c in GROUPING -> Unit
                    c in '0'..'9' || c == '.' || c == ',' -> append(c)
                    else -> return null
                }
            }
        }
        if (cleaned.isEmpty() || cleaned.none { it in '0'..'9' }) return null

        val dots = cleaned.count { it == '.' }
        val commas = cleaned.count { it == ',' }

        val value: Double? = when {
            dots == 0 && commas == 0 -> cleaned.toDoubleOrNull()
            dots > 0 && commas > 0 -> {
                // Das letzte Zeichen ist der Dezimaltrenner, das andere die Tausendertrennung davor
                val decimal = if (cleaned.lastIndexOf('.') > cleaned.lastIndexOf(',')) '.' else ','
                val grouping = if (decimal == '.') ',' else '.'
                val decimalIndex = cleaned.lastIndexOf(decimal)
                if (cleaned.count { it == decimal } != 1) null
                else {
                    val intPart = cleaned.substring(0, decimalIndex)
                    if (!validGrouping(intPart, grouping)) null
                    else number(intPart.replace(grouping.toString(), ""), cleaned.substring(decimalIndex + 1))
                }
            }
            else -> {
                val sep = if (dots > 0) '.' else ','
                val count = if (dots > 0) dots else commas
                if (count > 1) {
                    // Mehrfach: nur Tausendertrennung
                    if (validGrouping(cleaned, sep)) cleaned.replace(sep.toString(), "").toDoubleOrNull() else null
                } else {
                    val index = cleaned.indexOf(sep)
                    val intPart = cleaned.substring(0, index)
                    val fracPart = cleaned.substring(index + 1)
                    val asDecimal = number(intPart, fracPart)
                    val asGrouping = if (validGrouping(cleaned, sep)) (intPart + fracPart).toDoubleOrNull() else null
                    when {
                        asGrouping == null -> asDecimal
                        asDecimal == null || asDecimal <= 0.0 -> asGrouping
                        priceHint != null && priceHint.isFinite() && priceHint > 0.0 ->
                            if (distance(asGrouping, priceHint) < distance(asDecimal, priceHint)) asGrouping else asDecimal
                        normalized(decimalSeparator) == sep -> asDecimal
                        else -> asGrouping
                    }
                }
            }
        }
        return value?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Wie [parse], aber auch 0 gilt («0», «0,00», «.0», «0’000») — für Menge und Kurs einer
     * Transaktion (Kurs 0 = geschenkt). Leer bleibt ungültig (null); negativ, Exponent oder
     * Buchstaben («1.5f», «2d») ebenso.
     */
    fun parseAllowingZero(text: String, decimalSeparator: Char, priceHint: Double? = null): Double? {
        parse(text, decimalSeparator, priceHint)?.let { return it }
        val cleaned = latinDigits(text).trim().filterNot { it in GROUPING }
        val zero = cleaned.any { it == '0' } && cleaned.all { it == '0' || it == '.' || it == ',' } &&
            cleaned.count { it == '.' || it == ',' } <= 1
        return if (zero) 0.0 else null
    }

    /**
     * Eingabe in lateinische Ziffern: arabisch-indische (٠–٩), persische (۰–۹) und andere
     * Unicode-Ziffern → 0–9, arabisches Dezimalzeichen «٫» → «.», arabische Tausendertrennung
     * «٬» → «’»; Richtungszeichen (z. B. LRM aus eingefügtem Text) fallen weg.
     * So liest sich auch, was eine arabische oder persische Zifferntastatur tippt.
     */
    fun latinDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c in '0'..'9' -> append(c)
                c == '\u066B' -> append('.')
                c == '\u066C' -> append('’')
                BidiText.isMark(c) -> Unit
                Character.isDigit(c) -> append('0' + Character.digit(c, 10))
                else -> append(c)
            }
        }
    }

    /** Dezimalzeichen der Region auf Punkt oder Komma abbilden (anderes → Punkt). */
    fun normalized(decimalSeparator: Char): Char = if (decimalSeparator == ',') ',' else '.'

    /** «12» + «5» → 12.5; leere Teile zählen als 0 («.5», «60.»). */
    private fun number(intPart: String, fracPart: String): Double? {
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        return "${intPart.ifEmpty { "0" }}.${fracPart.ifEmpty { "0" }}".toDoubleOrNull()
    }

    /** Tausendergruppen: erste Gruppe 1–3 Ziffern ohne führende 0, danach je genau drei. */
    private fun validGrouping(text: String, sep: Char): Boolean {
        val groups = text.split(sep)
        if (groups.size < 2) return true
        val first = groups.first()
        if (first.isEmpty() || first.length > 3 || first.startsWith('0')) return false
        return groups.drop(1).all { it.length == 3 }
    }

    /** Abstand zweier positiver Werte auf der log. Skala (Verhältnis zu 1). */
    private fun distance(value: Double, hint: Double): Double = abs(ln(value / hint))
}
