package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.util.BidiText
import com.cryptochecker.app.domain.util.LocaleNumbers
import java.math.BigDecimal
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
 *   Mit [indianGrouping] (Region mit Zweier-Nebengruppen wie hi-IN) auch indisch: «1,00,000».
 * - Einmal ein Zeichen mit genau drei Ziffern danach («60,000», «60.000») ist mehrdeutig:
 *   Mit [priceHint] (aktueller Kurs in der Währung des Schwellwerts) gewinnt die Lesart, die
 *   näher am Kurs liegt (Verhältnis am nächsten bei 1) — aber nur, wenn diese Lesart höchstens
 *   Faktor 10 vom Kurs entfernt ist ([HINT_MAX_FACTOR]). Sonst (z. B. «60.000» bei einem Coin um
 *   2 000: beide Lesarten weit weg) und ohne Kurs gilt: Ist das Zeichen das Dezimalzeichen der Region
 *   ([decimalSeparator]), ist es eine Dezimalzahl («1,234» in DE = 1,234); sonst wird nicht still als
 *   Tausendertrennung geraten (eingefügt «1.234» in DE kann 1,234 ETH heissen) — ungültig, [ambiguity]
 *   nennt beide Schreibweisen für den Fehlertext. Gilt für jedes Zahlenfeld der App.
 * - Arabisch-indische und persische Ziffern sowie «٫»/«٬» gelten wie 0–9, «.» und «’» ([latinDigits]).
 * - Alles andere (Buchstaben wie «60k», Vorzeichen, Exponent) ist ungültig → null.
 *
 * Ergebnis immer > 0 und endlich, sonst null.
 */
object ThresholdParser {

    /** Zeichen, die nur der Tausendertrennung dienen. */
    private val GROUPING = setOf('’', '\'', '‘', '`', ' ', ' ', ' ', ' ')

    /**
     * Gruppiert die Zahlen-Locale ([LocaleNumbers.appLocale], auch für Eingaben) indisch?
     * Standard für [indianGrouping]; wie iOS `ThresholdParser.regionIndianGrouping`.
     */
    val regionIndianGrouping: Boolean
        get() = LocaleNumbers.usesIndianGrouping(LocaleNumbers.appLocale)

    fun parse(
        text: String,
        decimalSeparator: Char,
        priceHint: Double? = null,
        indianGrouping: Boolean = regionIndianGrouping,
    ): Double? =
        canonical(text, decimalSeparator, priceHint, indianGrouping)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    /**
     * Wie [parse], aber exakt als [BigDecimal] (z. B. 0.00000001 BTC = 1 Satoshi ohne
     * Gleitkomma-Rundung) — für Mengen, Kurse und Gebühren im Portfolio.
     */
    fun parseDecimal(
        text: String,
        decimalSeparator: Char,
        priceHint: Double? = null,
        indianGrouping: Boolean = regionIndianGrouping,
    ): BigDecimal? =
        // Wie iOS über PortfolioDecimal.parse (gleiche Grössengrenzen)
        canonical(text, decimalSeparator, priceHint, indianGrouping)?.let { PortfolioDecimal.parse(it) }
            ?.takeIf { it.signum() > 0 }

    /**
     * Gelesene Zahl als Dezimaltext mit Punkt ohne Tausendertrennung («1234.56»), nach den Regeln
     * oben; null = ungültig. Der Wert kann 0 sein (prüfen die Aufrufer).
     */
    private fun canonical(text: String, decimalSeparator: Char, priceHint: Double?, indianGrouping: Boolean): String? {
        val cleaned = digitsAndSeparators(text) ?: return null

        val dots = cleaned.count { it == '.' }
        val commas = cleaned.count { it == ',' }

        return when {
            dots == 0 && commas == 0 -> cleaned
            dots > 0 && commas > 0 -> {
                // Das letzte Zeichen ist der Dezimaltrenner, das andere die Tausendertrennung davor
                val decimal = if (cleaned.lastIndexOf('.') > cleaned.lastIndexOf(',')) '.' else ','
                val grouping = if (decimal == '.') ',' else '.'
                val decimalIndex = cleaned.lastIndexOf(decimal)
                if (cleaned.count { it == decimal } != 1) null
                else {
                    val intPart = cleaned.substring(0, decimalIndex)
                    if (!validGrouping(intPart, grouping, indianGrouping)) null
                    else number(intPart.replace(grouping.toString(), ""), cleaned.substring(decimalIndex + 1))
                }
            }
            else -> {
                val sep = if (dots > 0) '.' else ','
                val count = if (dots > 0) dots else commas
                if (count > 1) {
                    // Mehrfach: nur Tausendertrennung
                    if (validGrouping(cleaned, sep, indianGrouping)) cleaned.replace(sep.toString(), "") else null
                } else {
                    single(cleaned, sep, decimalSeparator, priceHint).value
                }
            }
        }
    }

    /**
     * Mehrdeutige Eingabe (siehe oben, nur ein Zeichen mit genau drei Ziffern danach, das nicht das Dezimalzeichen der
     * Region ist, und kein Kurs entscheidet): die zwei eindeutigen Schreibweisen — [grouped] ohne Trennung («1234»),
     * [decimal] mit dem Dezimalzeichen der Region («1,234» in DE). Für den Fehlertext «… oder …?».
     */
    data class Ambiguity(val grouped: String, val decimal: String)

    /** Ist [text] mehrdeutig (dann liest [parse] null)? Sonst null. Wie iOS `ThresholdParser.ambiguity`. */
    fun ambiguity(text: String, decimalSeparator: Char, priceHint: Double? = null): Ambiguity? {
        val cleaned = digitsAndSeparators(text) ?: return null
        if (cleaned.count { it == '.' || it == ',' } != 1) return null
        return single(cleaned, if ('.' in cleaned) '.' else ',', decimalSeparator, priceHint).ambiguity
    }

    /** Ziffern, Punkt und Komma ohne Tausendertrennung; null = andere Zeichen oder keine Ziffer. */
    private fun digitsAndSeparators(text: String): String? {
        val cleaned = buildString {
            for (c in latinDigits(text).trim()) {
                when {
                    c in GROUPING -> Unit
                    c in '0'..'9' || c == '.' || c == ',' -> append(c)
                    else -> return null
                }
            }
        }
        return cleaned.takeUnless { it.isEmpty() || it.none { c -> c in '0'..'9' } }
    }

    /** Lesart bei genau einem Trennzeichen [sep]: Wert ([value], null = ungültig) bzw. mehrdeutig ([ambiguity]). */
    private class Single(val value: String?, val ambiguity: Ambiguity? = null)

    private fun single(cleaned: String, sep: Char, decimalSeparator: Char, priceHint: Double?): Single {
        val index = cleaned.indexOf(sep)
        val intPart = cleaned.substring(0, index)
        val fracPart = cleaned.substring(index + 1)
        val asDecimal = number(intPart, fracPart)
        val asGrouping = if (validGrouping(cleaned, sep)) intPart + fracPart else null
        val decimalValue = asDecimal?.toDoubleOrNull()
        val groupingValue = asGrouping?.toDoubleOrNull()
        return when {
            asGrouping == null || groupingValue == null -> Single(asDecimal)
            asDecimal == null || decimalValue == null || decimalValue <= 0.0 -> Single(asGrouping)
            priceHint != null && priceHint.isFinite() && priceHint > 0.0 &&
                minOf(distance(groupingValue, priceHint), distance(decimalValue, priceHint)) < ln(HINT_MAX_FACTOR) ->
                Single(if (distance(groupingValue, priceHint) < distance(decimalValue, priceHint)) asGrouping else asDecimal)
            normalized(decimalSeparator) == sep -> Single(asDecimal)
            // Die Region läse das Zeichen als Tausendertrennung, der Kurs entscheidet nicht: nicht still raten
            else -> Single(null, Ambiguity(grouped = intPart + fracPart, decimal = intPart + normalized(decimalSeparator) + fracPart))
        }
    }

    /**
     * Wie [parse], aber auch 0 gilt («0», «0,00», «.0», «0’000») — für Menge und Kurs einer
     * Transaktion (Kurs 0 = geschenkt). Leer bleibt ungültig (null); negativ, Exponent oder
     * Buchstaben («1.5f», «2d») ebenso.
     */
    fun parseAllowingZero(
        text: String,
        decimalSeparator: Char,
        priceHint: Double? = null,
        indianGrouping: Boolean = regionIndianGrouping,
    ): Double? {
        parse(text, decimalSeparator, priceHint, indianGrouping)?.let { return it }
        return if (isZero(text)) 0.0 else null
    }

    /** Wie [parseAllowingZero], aber exakt als [BigDecimal]. */
    fun parseDecimalAllowingZero(
        text: String,
        decimalSeparator: Char,
        priceHint: Double? = null,
        indianGrouping: Boolean = regionIndianGrouping,
    ): BigDecimal? {
        parseDecimal(text, decimalSeparator, priceHint, indianGrouping)?.let { return it }
        return if (isZero(text)) BigDecimal.ZERO else null
    }

    private fun isZero(text: String): Boolean {
        val cleaned = latinDigits(text).trim().filterNot { it in GROUPING }
        return cleaned.any { it == '0' } && cleaned.all { it == '0' || it == '.' || it == ',' } &&
            cleaned.count { it == '.' || it == ',' } <= 1
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

    /** «12» + «5» → «12.5»; leere Teile zählen als 0 («.5», «60.»). */
    private fun number(intPart: String, fracPart: String): String? {
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        return "${intPart.ifEmpty { "0" }}.${fracPart.ifEmpty { "0" }}"
    }

    /**
     * Tausendergruppen: erste Gruppe 1–3 Ziffern ohne führende 0, danach je genau drei. Mit
     * [indian] zusätzlich indisch: erste Gruppe 1–2 Ziffern, dann Zweiergruppen, zuletzt genau drei («12,34,567»).
     */
    private fun validGrouping(text: String, sep: Char, indian: Boolean = false): Boolean {
        val groups = text.split(sep)
        if (groups.size < 2) return true
        val first = groups.first()
        if (first.isEmpty() || first.length > 3 || first.startsWith('0')) return false
        if (groups.drop(1).all { it.length == 3 }) return true
        return indian && first.length <= 2 && groups.last().length == 3 &&
            groups.subList(1, groups.size - 1).all { it.length == 2 }
    }

    /** Abstand zweier positiver Werte auf der log. Skala (Verhältnis zu 1). */
    private fun distance(value: Double, hint: Double): Double = abs(ln(value / hint))

    /** Der Kurs entscheidet nur, wenn eine Lesart höchstens so weit (Faktor) von ihm entfernt ist. */
    const val HINT_MAX_FACTOR = 10.0
}
