package com.cryptochecker.wear

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Zahlen auf der Uhr: Kurs kurz (ab 1000 ohne Nachkommastellen-Flut) und Änderung mit Vorzeichen.
 * Prozent in derselben Schreibweise wie die Phone-App (`PercentText`, `BidiText` im Modul :domain, das die Uhr nicht
 * einbindet): gleiche Tabelle, geprüft mit denselben Testfällen (`testdata/parity/percent_text.json`).
 * Spiegel: iOS `WatchCore/WatchPercent.swift`.
 */
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

    /** Änderung «+1.23%», Deutsch «+1,23 %», Türkisch «+%1,23»; in RTL-Sprachen als links-nach-rechts-Insel. */
    fun change(value: Double?, locale: Locale = Locale.getDefault()): String {
        if (value == null || !value.isFinite()) return "—"
        val text = signedPercent(value, 2, locale)
        return if (locale.language in RTL_LANGUAGES) "$LRI$text$PDI" else text
    }

    /** Mit Vorzeichen vor dem Ganzen, echtes Minus U+2212; gerundet 0 ohne Vorzeichen (wie `PercentText.signed`). */
    fun signedPercent(value: Double, decimals: Int, locale: Locale): String {
        val sign = when {
            rounded(abs(value), decimals) == 0.0 -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return sign + unsignedPercent(value, decimals, locale)
    }

    /** Betrag mit genau [decimals] Nachkommastellen (kaufmännisch) und Prozentzeichen der Sprache (`PercentText.unsigned`). */
    fun unsignedPercent(value: Double, decimals: Int, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale)
        if (format is DecimalFormat) format.roundingMode = RoundingMode.HALF_UP
        format.isGroupingUsed = false
        format.minimumFractionDigits = decimals
        format.maximumFractionDigits = decimals
        val number = format.format(rounded(abs(value), decimals))
        val language = locale.language
        val latin = DecimalFormatSymbols.getInstance(locale).zeroDigit == '0'
        return when {
            language == "tr" -> "%$number"
            (language == "ar" || language == "fa") && !latin -> number + ARABIC_PERCENT
            spaced(language, locale.country.uppercase()) -> number + NBSP + "%"
            else -> "$number%"
        }
    }

    /** Abstand vor «%» nach CLDR — dieselbe Tabelle wie `PercentText.isSpaced`. */
    private fun spaced(language: String, region: String): Boolean = if (language in SPACED) {
        region !in UNSPACED_REGIONS[language].orEmpty()
    } else {
        region in SPACED_REGIONS[language].orEmpty()
    }

    /** Kaufmännisch gerundet über die kürzeste Dezimaldarstellung (1.005 → 1.01), wie `DecimalText.rounded`. */
    private fun rounded(value: Double, scale: Int): Double =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toDouble()

    private const val NBSP = " "
    private const val ARABIC_PERCENT = "٪"
    private const val LRI = '⁦'
    private const val PDI = '⁩'
    private val SPACED = setOf("de", "fr", "cs", "da", "es", "fi", "nb", "no", "ro", "ru", "sv")
    private val UNSPACED_REGIONS = mapOf("de" to setOf("CH", "LI"), "fr" to setOf("CH", "LI"), "es" to setOf("MX"))
    private val SPACED_REGIONS = mapOf("en" to setOf("AT", "DE", "DK", "FI", "SE"))
    private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "ps", "yi", "ji", "ckb", "sd", "ug", "dv")
}
