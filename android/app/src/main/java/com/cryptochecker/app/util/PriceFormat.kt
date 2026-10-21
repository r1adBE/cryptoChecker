package com.cryptochecker.app.util

import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.marketdata.util.FormatUtilsBase
import java.math.BigDecimal
import java.math.RoundingMode
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
        // Vorher kaufmännisch runden ([DecimalText]): 1.005 → «1.01» wie unter iOS; Prozentzeichen der Sprache
        // ([PercentText]: «2,40 %», «%2,40», «٢٫٤٠٪»)
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl («1.20%+»)
        return BidiText.ltr(sign + PercentText.unsigned(value, 2))
    }

    /**
     * Prozent mit Vorzeichen und genau [decimals] Nachkommastellen (z. B. Funding «+0.0100%»,
     * «−0.0050%»): echtes Minus U+2212, kaufmännisch gerundet, Schreibweise der App-Sprache ([PercentText]);
     * gerundet 0 ohne Vorzeichen. Wie `PriceFormat.signedPercent` (iOS).
     */
    fun signedPercent(value: Double, decimals: Int): String = BidiText.ltr(PercentText.signed(value, decimals))

    /**
     * Praktisch keine Änderung: «0.00%» grau, ohne Pfeil — in der Schreibweise der App-Sprache
     * («0,00 %», arabisch «٠٫٠٠٪») wie [changePercent].
     */
    fun zeroPercent(): String = BidiText.ltr(PercentText.unsigned(0.0, 2))

    /**
     * Uhrzeit mit Sekunden — bei Kursen zählt die Sekunde. 12/24 h nach der Systemeinstellung ([ClockText], seit
     * Build 95); [is24] = null: wie die Sprache.
     */
    fun time(millis: Long, is24: Boolean? = ClockText.systemIs24Hour(), locale: Locale = LocaleNumbers.appLocale): String =
        if (millis <= 0) "—"
        else ClockText.format(locale, withSeconds = true, is24 = is24).format(Date(millis))

    /** Uhrzeit ohne Sekunden in der Sprache des Geräts, z. B. «19:41» («Offline · Stand 19:41»); 12/24 h wie [time]. */
    fun shortTime(millis: Long, is24: Boolean? = ClockText.systemIs24Hour(), locale: Locale = LocaleNumbers.appLocale): String =
        if (millis <= 0) "—"
        else ClockText.format(locale, withSeconds = false, is24 = is24).format(Date(millis))

    /**
     * Prozentwert für Benachrichtigungen: mit Vorzeichen (echtes Minus U+2212), drei Nachkommastellen; gerundet 0
     * ohne Vorzeichen («0.000%» statt «+0.000%»/«−0.000%», seit Build 95) wie [PercentText.signed].
     */
    fun changePercentDetailed(value: Double, locale: Locale = LocaleNumbers.appLocale): String =
        BidiText.ltr(PercentText.signed(value, 3, locale))

    /** Einheit von [ageParts]: grösste ganze Einheit des Abstands. */
    enum class AgeUnit { DAYS, HOURS, MINUTES, SECONDS }

    /** Grösste ganze Einheit des Abstands seit [sinceMillis] (7 Tage → 7 [AgeUnit.DAYS]); null ohne bzw. mit künftigem Zeitpunkt. */
    fun ageParts(sinceMillis: Long, now: Long = System.currentTimeMillis()): Pair<Long, AgeUnit>? {
        if (sinceMillis <= 0) return null
        val elapsed = now - sinceMillis
        if (elapsed < 0) return null
        val seconds = elapsed / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> days to AgeUnit.DAYS
            hours > 0 -> hours to AgeUnit.HOURS
            minutes > 0 -> minutes to AgeUnit.MINUTES
            else -> seconds to AgeUnit.SECONDS
        }
    }

    /**
     * Abstand zu einem Zeitpunkt, kompakt und in der App-Sprache: Englisch «45s», «12m», «3h», «7d», Deutsch «7 T.»,
     * «3 Std.» (ICU MeasureFormat, NARROW — wie iOS `DateComponentsFormatter` .abbreviated). Für «seit der letzten
     * Meldung» in der Benachrichtigung. Seit Build 94 nicht mehr fest englisch («vor 7d»).
     */
    fun age(sinceMillis: Long, now: Long = System.currentTimeMillis(), locale: Locale = LocaleNumbers.appLocale): String? {
        val (value, unit) = ageParts(sinceMillis, now) ?: return null
        return runCatching {
            val icuUnit = when (unit) {
                AgeUnit.DAYS -> android.icu.util.MeasureUnit.DAY
                AgeUnit.HOURS -> android.icu.util.MeasureUnit.HOUR
                AgeUnit.MINUTES -> android.icu.util.MeasureUnit.MINUTE
                AgeUnit.SECONDS -> android.icu.util.MeasureUnit.SECOND
            }
            android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.NARROW)
                .format(android.icu.util.Measure(value, icuUnit))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ageFallback(value, unit, locale)
    }

    /** Ersatz ohne ICU (z. B. in JVM-Tests): Ziffern der Sprache, Kürzel wie Englisch NARROW. */
    internal fun ageFallback(value: Long, unit: AgeUnit, locale: Locale = LocaleNumbers.appLocale): String =
        LocaleNumbers.integer(value, locale) + when (unit) {
            AgeUnit.DAYS -> "d"
            AgeUnit.HOURS -> "h"
            AgeUnit.MINUTES -> "m"
            AgeUnit.SECONDS -> "s"
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
        // Kaufmännisch aus der kürzesten Darstellung ([DecimalText.fixed]; Punkt, lateinische Ziffern) — gleich wie iOS:
        // String.format (halb aufwärts) und printf auf iOS (halb gerade) lasen 60000.5 als «60001» bzw. «60000»
        value >= 1000 -> DecimalText.fixed(value, 0)
        value >= 1 -> DecimalText.fixed(value, 2)
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
