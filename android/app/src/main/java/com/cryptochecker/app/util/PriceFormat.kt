package com.cryptochecker.app.util

import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.marketdata.util.FormatUtilsBase
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object PriceFormat {

    fun price(value: Double?): String =
        if (value == null || value <= 0.0) "—" else FormatUtilsBase.formatDouble(value)

    fun priceWithCurrency(value: Double?, quoteAsset: String): String =
        if (value == null || value <= 0.0) "—" else "${price(value)} $quoteAsset"

    /**
     * Schwellwert eines Kursalarms genau so, wie er gespeichert ist — nie gerundet wie [price]
     * (sonst stünde 1.0005 als «1» da): Tausendertrennung und Dezimalzeichen der Region wie
     * [price], ohne Exponent und ohne Nullen am Ende (1.0005 → «1.0005», 65000 → «65,000»,
     * 0.00000712 → «0.00000712»). Gerechnet mit der kürzesten Dezimaldarstellung des Double
     * (wie [DecimalText]), höchstens [THRESHOLD_MAX_DECIMALS] Nachkommastellen. Nicht positiv
     * oder nicht endlich → «—». Wie `PriceFormat.threshold` (iOS).
     */
    fun threshold(value: Double?, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String {
        if (value == null || !value.isFinite() || value <= 0.0) return "—"
        val exact = BigDecimal.valueOf(value).stripTrailingZeros()
        val format = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
            maximumFractionDigits = THRESHOLD_MAX_DECIMALS
            minimumFractionDigits = 0
        }
        return format.format(exact)
    }

    /** Schwellwert wie [threshold] mit Währung: «1.0005 USDT». */
    fun thresholdWithCurrency(value: Double?, quoteAsset: String): String =
        if (value == null || !value.isFinite() || value <= 0.0) "—" else "${threshold(value)} $quoteAsset"

    /** Höchstens so viele Nachkommastellen zeigt [threshold] (wie die exakte Menge). */
    const val THRESHOLD_MAX_DECIMALS = 18

    /** Gehaltene Menge: Tausendertrennung, bis acht Nachkommastellen, ohne Nullen am Ende. */
    fun amount(value: Double): String = DecimalFormat("#,##0.########").format(value)

    /** Wert eines Bestands mit Währung, immer zwei Nachkommastellen. */
    fun valueWithCurrency(value: Double, quoteAsset: String): String =
        "${DecimalFormat("#,##0.00").format(value)} $quoteAsset"

    /**
     * Menge oder Kurs für ein Eingabefeld: ohne Exponent und Tausendertrennung, mit dem
     * Dezimalzeichen der Region (liest [parseAmount] so eindeutig zurück). 0 → «0», nur
     * null (oder nicht endlich) → leer. Wie `PriceFormat.amountForInput` (iOS).
     */
    fun amountForInput(value: Double?, decimalSeparator: Char = '.'): String =
        value?.let { DecimalText.plain(it) }.orEmpty()
            .replace('.', ThresholdParser.normalized(decimalSeparator))

    /**
     * Freie Eingabe einer Menge oder eines Kurses — nach den Regeln von [ThresholdParser]:
     * Tausendertrennung (auch geschützte/schmale Leerzeichen), Dezimalzeichen der Region,
     * arabische/persische Ziffern; mehrdeutig («60.000») entscheidet [priceHint] (aktueller
     * Kurs), ohne Kurs das Dezimalzeichen. Leer = 0 (kein Bestand), 0 gilt;
     * ungültig, negativ, mit Exponent oder Buchstaben («1.5f») = null.
     */
    fun parseAmount(text: String, decimalSeparator: Char, priceHint: Double? = null): Double? {
        if (text.isBlank()) return 0.0
        return ThresholdParser.parseAllowingZero(text, decimalSeparator, priceHint)
    }

    /** Wie [parseAmount], aber exakt ([BigDecimal], z. B. 0.00000001 BTC = 1 Satoshi). */
    fun parseDecimalAmount(text: String, decimalSeparator: Char, priceHint: Double? = null): BigDecimal? {
        if (text.isBlank()) return BigDecimal.ZERO
        return ThresholdParser.parseDecimalAllowingZero(text, decimalSeparator, priceHint)
    }

    /** Wie [amountForInput] für einen exakten Wert: ohne Exponent, alle Stellen, Dezimalzeichen der Region. */
    fun amountForInput(value: BigDecimal?, decimalSeparator: Char = '.'): String =
        value?.let { DecimalText.plain(it) }.orEmpty()
            .replace('.', ThresholdParser.normalized(decimalSeparator))

    /** Exakte Menge mit Tausendertrennung wie [amount], ohne Rundung (alle Nachkommastellen, höchstens 18). */
    fun amount(value: BigDecimal): String {
        val scaled = value.setScale(minOf(maxOf(value.stripTrailingZeros().scale(), 0), 18), RoundingMode.HALF_UP)
        val format = DecimalFormat("#,##0").apply { maximumFractionDigits = 18; minimumFractionDigits = 0 }
        return format.format(scaled)
    }

    /**
     * Pfeil zur Kursbewegung, überall gleich: «↗» steigend, «↘» fallend (schräg wie iOS
     * `arrow.up.right`/`arrow.down.right`), leer bei praktisch 0. Folgt immer dem Vorzeichen,
     * nie dem Farbtausch. Steht vor jeder farbigen Änderung («↘ −0.26%»), wie in den Widgets.
     */
    fun changeArrow(value: Double?): String = when {
        value == null || abs(value) < 0.005 -> ""
        value > 0 -> ARROW_UP
        else -> ARROW_DOWN
    }

    /** Schräger Pfeil «steigend» (U+2197). */
    const val ARROW_UP = "\u2197"

    /** Schräger Pfeil «fallend» (U+2198). */
    const val ARROW_DOWN = "\u2198"

    fun changePercent(value: Double?): String? {
        if (value == null || abs(value) < 0.005) return null
        val sign = if (value > 0) "+" else "−"
        // Vorher kaufmännisch runden ([DecimalText]): 1.005 → «1.01» wie unter iOS
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl («1.20%+»)
        return BidiText.ltr("$sign%.2f%%".format(DecimalText.rounded(abs(value), 2)))
    }

    /**
     * Prozent mit Vorzeichen und genau [decimals] Nachkommastellen (z. B. Funding «+0.0100%»,
     * «−0.0050%»): echtes Minus U+2212, kaufmännisch gerundet, Schreibweise der App-Sprache;
     * gerundet 0 ohne Vorzeichen. Wie `PriceFormat.signedPercent` (iOS).
     */
    fun signedPercent(value: Double, decimals: Int): String {
        val rounded = DecimalText.rounded(abs(value), decimals)
        val sign = when {
            rounded == 0.0 -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return BidiText.ltr("$sign%.${decimals}f%%".format(rounded))
    }

    /**
     * Praktisch keine Änderung: «0.00%» grau, ohne Pfeil — in der Schreibweise der App-Sprache
     * («0,00%», arabisch «٠٫٠٠%») wie [changePercent].
     */
    fun zeroPercent(): String = BidiText.ltr("%.2f%%".format(0.0))

    /** Uhrzeit mit Sekunden — bei Kursen zählt die Sekunde. */
    fun time(millis: Long): String =
        if (millis <= 0) "—"
        else DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

    /** Uhrzeit ohne Sekunden in der Sprache des Geräts, z. B. «19:41» («Offline · Stand 19:41»). */
    fun shortTime(millis: Long): String =
        if (millis <= 0) "—"
        else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    /** Prozentwert für Benachrichtigungen: mit Vorzeichen (echtes Minus U+2212), drei Nachkommastellen. */
    fun changePercentDetailed(value: Double): String {
        val sign = if (value >= 0) "+" else "−"
        return BidiText.ltr("$sign%.3f%%".format(DecimalText.rounded(abs(value), 3)))
    }

    /**
     * Abstand zu einem Zeitpunkt, kompakt: 45s, 12m, 3h, 7d.
     * Für „seit der letzten Meldung" in der Benachrichtigung.
     */
    fun age(sinceMillis: Long, now: Long = System.currentTimeMillis()): String? {
        if (sinceMillis <= 0) return null
        val elapsed = now - sinceMillis
        if (elapsed < 0) return null

        val seconds = elapsed / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> LocaleNumbers.integer(days) + "d"
            hours > 0 -> LocaleNumbers.integer(hours) + "h"
            minutes > 0 -> LocaleNumbers.integer(minutes) + "m"
            else -> LocaleNumbers.integer(seconds) + "s"
        }
    }

    /** Dauer einer Aktualisierung, kurz gehalten für den Widget-Kopf. */
    fun duration(millis: Long): String = when {
        millis <= 0 -> ""
        millis < 1000 -> LocaleNumbers.integer(millis) + " ms"
        else -> "%.1f s".format(millis / 1000.0)
    }

    /**
     * Kurs so aufbereiten, dass die Sprachausgabe ihn sinnvoll vorliest. Unter 1 vier
     * signifikante Stellen (kaufmännisch gerundet, ohne Exponent und Nullen am Ende), damit
     * kleine Kurse nicht zu «0» werden: 0.00000712 → «0.00000712», 0.123456 → «0.1235».
     * Wie `PriceFormat.spokenPrice` (iOS).
     */
    fun spokenPrice(value: Double): String = when {
        // Locale.ROOT: Punkt und lateinische Ziffern, unabhängig von der Gerätesprache
        value >= 1000 -> String.format(Locale.ROOT, "%.0f", value)
        value >= 1 -> String.format(Locale.ROOT, "%.2f", value)
        else -> DecimalText.plain(value, DecimalText.significantScale(value, 0, SPOKEN_SIGNIFICANT))
    }

    /**
     * Wie [spokenPrice], aber mit dem Dezimalzeichen der Sprache, in der vorgelesen wird ([locale] =
     * Sprache der Stimme bzw. des Satzes): Eine deutsche Stimme liest «1.50» sonst als Datum oder
     * «eins Punkt fünfzig». Ziffern bleiben lateinisch, ohne Tausendertrennung. Wie iOS.
     */
    fun spokenPrice(value: Double, locale: Locale): String {
        val plain = spokenPrice(value)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') plain else plain.replace('.', separator)
    }

    /** Signifikante Stellen eines vorgelesenen Kurses unter 1. */
    private const val SPOKEN_SIGNIFICANT = 4
}
