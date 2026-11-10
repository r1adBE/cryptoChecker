package com.cryptochecker.wear

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Zahlen auf der Uhr: Kurs (ab 1000 ohne Nachkommastellen-Flut), Kurs knapp für kleine Komplikationen ([compactPrice]),
 * Zeile der langen Komplikation ([priceLine]) und Änderung mit Vorzeichen.
 * Prozent in derselben Schreibweise wie die Phone-App (`PercentText`, `BidiText` im Modul :domain, das die Uhr nicht
 * einbindet): gleiche Tabelle, geprüft mit denselben Testfällen (`testdata/parity/percent_text.json`).
 * Spiegel: iOS `WatchCore/WatchPercent.swift`, Kurs `WatchCore/WatchPriceFormat.swift` (`testdata/parity/watch_price.json`).
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

    /**
     * Kurs knapp für kleine Komplikationen (etwa 7 Zeichen), Ziffern, Dezimalzeichen und Tausendertrennung der Sprache:
     * ab 1 Mio. drei Stellen mit M/B/T («1.23M»), ab 10’000 ganz («84’450»), ab 1000 eine Nachkommastelle
     * («2’234.6»), ab 10 zwei («123.46»), darunter vier signifikante Stellen wie die Phone-App («1.234», «0.02468»), unter
     * 0.01 drei («0.00988»), unter 0.001 mit Exponent («1.23e-5»). Kaufmännisch über die kürzeste Dezimaldarstellung
     * gerundet; rundet ein Wert in die nächste Stufe, gilt deren Regel (9’999.96 → «10’000», 999’999.6 → «1M»). Nicht
     * positiv oder nicht endlich «—».
     */
    fun compactPrice(value: Double, locale: Locale = Locale.getDefault()): String {
        if (!value.isFinite() || value <= 0) return "—"
        var step = step(BigDecimal.valueOf(value))
        var rounded = round(BigDecimal.valueOf(value), step)
        // Höchstens bis zur nächsten Stufe: 9.99996 → 10.000 (zwei Nachkommastellen) → 10.00
        var rounds = 0
        while (rounds < STEP_ROUNDS) {
            val next = step(rounded)
            if (next == step) break
            step = next
            rounded = round(rounded, step)
            rounds++
        }
        return render(rounded, step, locale)
    }

    /**
     * Text der langen Komplikation: «BTC • 84’450.00 USDT  +1.20%», dahinter [detail] («  ·  Binance» bzw. das Alter);
     * ohne Kurs «BTC • —». [quote] = Gegenwährung des Paars (USDT, USD, EUR …).
     */
    fun priceLine(
        coin: String,
        value: Double?,
        quote: String,
        changePercent: Double?,
        detail: String?,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (value == null || !value.isFinite()) return "$coin • —"
        val line = "$coin • ${price(value, locale)} $quote  ${change(changePercent, locale)}"
        return detail?.takeIf { it.isNotBlank() }?.let { "$line  ·  $it" } ?: line
    }

    /** Stufen von [compactPrice]: Einheit (Zehnerexponent) bzw. Nachkommastellen oder signifikante Stellen. */
    private enum class Step { TRILLION, BILLION, MILLION, WHOLE, ONE_DECIMAL, TWO_DECIMALS, FOUR_DIGITS, THREE_DIGITS, EXPONENT }

    private fun step(x: BigDecimal): Step = when {
        x >= BigDecimal.TEN.pow(12) -> Step.TRILLION
        x >= BigDecimal.TEN.pow(9) -> Step.BILLION
        x >= BigDecimal.TEN.pow(6) -> Step.MILLION
        x >= BigDecimal(10_000) -> Step.WHOLE
        x >= BigDecimal(1000) -> Step.ONE_DECIMAL
        x >= BigDecimal.TEN -> Step.TWO_DECIMALS
        x >= BigDecimal("0.01") -> Step.FOUR_DIGITS
        x >= BigDecimal("0.001") -> Step.THREE_DIGITS
        else -> Step.EXPONENT
    }

    private fun unitExponent(step: Step): Int? = when (step) {
        Step.TRILLION -> 12
        Step.BILLION -> 9
        Step.MILLION -> 6
        else -> null
    }

    private fun unitSuffix(step: Step): String = when (step) {
        Step.TRILLION -> "T"
        Step.BILLION -> "B"
        else -> "M"
    }

    /** Zehnerexponent der ersten Ziffer (84’450 → 4, 0.02468 → −2). */
    private fun magnitude(x: BigDecimal): Int = x.precision() - x.scale() - 1

    /** Nachkommastellen für [digits] signifikante Stellen; bei Einheiten nie weniger als 0 (1’235T). */
    private fun significantScale(x: BigDecimal, digits: Int, clamp: Boolean = false): Int =
        (digits - 1 - magnitude(x)).let { if (clamp) maxOf(0, it) else it }

    private fun round(x: BigDecimal, step: Step): BigDecimal {
        unitExponent(step)?.let { e ->
            val mantissa = x.movePointLeft(e)
            return mantissa.setScale(significantScale(mantissa, 3, clamp = true), RoundingMode.HALF_UP).movePointRight(e)
        }
        val scale = when (step) {
            Step.WHOLE -> 0
            Step.ONE_DECIMAL -> 1
            Step.TWO_DECIMALS -> 2
            Step.FOUR_DIGITS -> significantScale(x, 4)
            else -> significantScale(x, 3)
        }
        return x.setScale(scale, RoundingMode.HALF_UP)
    }

    private fun render(r: BigDecimal, step: Step, locale: Locale): String {
        unitExponent(step)?.let { e ->
            val mantissa = r.movePointLeft(e)
            return number(mantissa, 0, significantScale(mantissa, 3, clamp = true), locale) + unitSuffix(step)
        }
        return when (step) {
            Step.WHOLE -> number(r, 0, 0, locale)
            Step.ONE_DECIMAL -> number(r, 1, 1, locale)
            Step.TWO_DECIMALS -> number(r, 2, 2, locale)
            Step.FOUR_DIGITS -> number(r, 0, significantScale(r, 4), locale)
            Step.THREE_DIGITS -> number(r, 0, significantScale(r, 3), locale)
            else -> {
                val e = magnitude(r)
                number(r.movePointLeft(e), 0, 2, locale) + "e" + e
            }
        }
    }

    /** Zahl in der Schreibweise der Sprache (mit Tausendertrennung), [min]…[max] Nachkommastellen, ohne Nullen am Ende. */
    private fun number(x: BigDecimal, min: Int, max: Int, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale)
        if (format is DecimalFormat) format.roundingMode = RoundingMode.HALF_UP
        format.isGroupingUsed = true
        format.maximumFractionDigits = max
        format.minimumFractionDigits = min
        return format.format(x)
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

    private const val STEP_ROUNDS = 4
    private const val NBSP = " "
    private const val ARABIC_PERCENT = "٪"
    private const val LRI = '⁦'
    private const val PDI = '⁩'
    private val SPACED = setOf("de", "fr", "cs", "da", "es", "fi", "nb", "no", "ro", "ru", "sv")
    private val UNSPACED_REGIONS = mapOf("de" to setOf("CH", "LI"), "fr" to setOf("CH", "LI"), "es" to setOf("MX"))
    private val SPACED_REGIONS = mapOf("en" to setOf("AT", "DE", "DK", "FI", "SE"))
    private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "ps", "yi", "ji", "ckb", "sd", "ug", "dv")
}
