package com.cryptochecker.app.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * Zahlen für Text, den Menschen lesen, in den Ziffern der App-Sprache: Arabisch «٧٢»,
 * Persisch «۷۲», sonst «72» — überall gleich (Werte, Zähler, Gebühren, Achsen). Wie
 * `LocaleNumbers` (iOS). `Int.toString()`, `"$n"` und `String.format(Locale.US/ROOT, …)`
 * schreiben dagegen immer lateinische Ziffern.
 *
 * Maschinenformate (Sicherung/Export als JSON, CSV-Export, URLs, API-Parameter,
 * Cache-Schlüssel, Logs, vorbefüllte Eingabefelder) bleiben bei [Locale.ROOT]. Eingaben
 * nehmen beide Schreibweisen an (`ThresholdParser.latinDigits`).
 *
 * [locale] ist standardmässig `Locale.getDefault()` — die App-Sprache (`AppLanguages` bzw.
 * ab Android 13 die Sprache pro App).
 */
object LocaleNumbers {

    /** Ganze Zahl; ohne Tausendertrennung, ausser [grouping] (Werte wie «72», Jahre wie «2024»). */
    fun integer(value: Long, locale: Locale = Locale.getDefault(), grouping: Boolean = false): String =
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = grouping }.format(value)

    fun integer(value: Int, locale: Locale = Locale.getDefault(), grouping: Boolean = false): String =
        integer(value.toLong(), locale, grouping)

    /**
     * Kommazahl mit [minDecimals]…[maxDecimals] Nachkommastellen, kaufmännisch gerundet wie
     * `"%.nf"` (0.25 → «0.3» bei einer Stelle); ohne Tausendertrennung, ausser [grouping].
     */
    fun decimal(
        value: Double,
        maxDecimals: Int,
        minDecimals: Int = maxDecimals,
        locale: Locale = Locale.getDefault(),
        grouping: Boolean = false,
    ): String {
        val format = NumberFormat.getNumberInstance(locale)
        if (format is DecimalFormat) format.roundingMode = RoundingMode.HALF_UP
        format.isGroupingUsed = grouping
        format.minimumFractionDigits = minDecimals
        format.maximumFractionDigits = maxDecimals
        return format.format(value)
    }

    /**
     * [formatter] (java.time) mit den Ziffern von [locale]: java.time schreibt sonst immer
     * lateinische Ziffern ([DecimalStyle.STANDARD]), auch in einer arabischen Sprache.
     */
    fun dates(formatter: DateTimeFormatter, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        formatter.withLocale(locale).withDecimalStyle(DecimalStyle.of(locale))

    /**
     * Locale für Zahleneingaben: Sprache der App ([app]), Region des Geräts ([region], z. B. «CH»)
     * — wie `Locale.current` unter iOS. Ist in der App eine Sprache gewählt, hat
     * `Locale.getDefault()` keine Region («de»); ohne Region gälte das deutsche Komma, in der
     * Schweiz (de_CH) ist aber der Punkt das Dezimalzeichen.
     */
    fun inputLocale(app: Locale, region: String): Locale =
        if (region.isEmpty() || region == app.country) app
        else runCatching { Locale.Builder().setLocale(app).setRegion(region).build() }.getOrDefault(app)

    /** Dezimalzeichen für Eingaben ([inputLocale]), auf Punkt oder Komma abgebildet (`ThresholdParser`). */
    fun inputDecimalSeparator(app: Locale, region: String): Char =
        com.cryptochecker.app.domain.alarm.ThresholdParser.normalized(
            DecimalFormatSymbols.getInstance(inputLocale(app, region)).decimalSeparator
        )

    /** Null-Ziffer der Sprache: '0', '٠' (Arabisch) oder '۰' (Persisch). */
    fun zeroDigit(locale: Locale = Locale.getDefault()): Char = DecimalFormatSymbols.getInstance(locale).zeroDigit

    /**
     * Lateinische Ziffern 0–9 eines fertigen Textes durch die der Sprache ersetzen — nur für
     * Ziffern ohne Dezimal- oder Tausendertrennzeichen (Zähler in festen Vorlagen).
     */
    fun digits(text: String, locale: Locale = Locale.getDefault()): String {
        val zero = zeroDigit(locale)
        if (zero == '0') return text
        return buildString(text.length) {
            for (c in text) append(if (c in '0'..'9') zero + (c - '0') else c)
        }
    }
}
