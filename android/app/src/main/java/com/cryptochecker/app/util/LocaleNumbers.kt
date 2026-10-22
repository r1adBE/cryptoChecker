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
 * [locale] ist standardmässig [appLocale]: die App-Sprache (`AppLanguages` bzw. ab Android 13
 * die Sprache pro App) mit der Region des Geräts ([formatLocale]) — dieselbe Locale für Anzeige
 * und Eingabe (Deutsch auf einem Schweizer Gerät: «90’000.00», Eingabe mit Punkt).
 */
object LocaleNumbers {

    /**
     * Locale für Zahlen (Anzeige und Eingabe): Kategorie FORMAT, gesetzt von [applyFormatLocale];
     * `DecimalFormat("#,##0.00")` und `String.format` ohne Locale folgen ihr ebenfalls.
     */
    val appLocale: Locale get() = Locale.getDefault(Locale.Category.FORMAT)

    /**
     * Zahlen-Locale: Sprache der App ([app]) mit der Region des Geräts ([region], z. B. «CH») —
     * wie `Locale.current` unter iOS. Ist in der App eine Sprache gewählt, hat
     * `Locale.getDefault()` keine Region («de»); ohne Region gälte das deutsche Komma und
     * «90.000,00», in der Schweiz (de_CH) aber «90’000.00». Passt die Region nicht zur Sprache
     * (z. B. de + US), nimmt der Formatierer die Sprach-Daten («de»).
     */
    fun formatLocale(app: Locale, region: String): Locale {
        val r = region.trim().uppercase()
        if (r.isEmpty() || r == app.country) return app
        return runCatching { Locale.Builder().setLocale(app).setRegion(r).build() }.getOrDefault(app)
    }

    /**
     * Zahlen-Locale ([formatLocale]) als Standard der Kategorie FORMAT setzen — beim Start und
     * nach jedem Sprach-/Konfigurationswechsel (Android setzt dann `Locale.setDefault` neu).
     */
    fun applyFormatLocale(app: Locale, region: String) {
        val wanted = formatLocale(app, region)
        if (Locale.getDefault(Locale.Category.FORMAT) != wanted) Locale.setDefault(Locale.Category.FORMAT, wanted)
    }

    /** Ganze Zahl; ohne Tausendertrennung, ausser [grouping] (Werte wie «72», Jahre wie «2024»). */
    fun integer(value: Long, locale: Locale = appLocale, grouping: Boolean = false): String =
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = grouping }.format(value)

    fun integer(value: Int, locale: Locale = appLocale, grouping: Boolean = false): String =
        integer(value.toLong(), locale, grouping)

    /**
     * Kommazahl mit [minDecimals]…[maxDecimals] Nachkommastellen, kaufmännisch gerundet wie
     * `"%.nf"` (0.25 → «0.3» bei einer Stelle); ohne Tausendertrennung, ausser [grouping].
     */
    fun decimal(
        value: Double,
        maxDecimals: Int,
        minDecimals: Int = maxDecimals,
        locale: Locale = appLocale,
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
    fun dates(formatter: DateTimeFormatter, locale: Locale = appLocale): DateTimeFormatter =
        formatter.withLocale(locale).withDecimalStyle(DecimalStyle.of(locale))

    /** Locale für Zahleneingaben — dieselbe wie für die Anzeige ([formatLocale]). */
    fun inputLocale(app: Locale, region: String): Locale = formatLocale(app, region)

    /** Dezimalzeichen für Eingaben ([inputLocale]), auf Punkt oder Komma abgebildet (`ThresholdParser`). */
    fun inputDecimalSeparator(app: Locale, region: String): Char =
        com.cryptochecker.app.domain.alarm.ThresholdParser.normalized(
            DecimalFormatSymbols.getInstance(inputLocale(app, region)).decimalSeparator
        )

    /** Seit Build 103: Ergebnis von [usesIndianGrouping] je Locale (ICU-Formatierer sind teuer). */
    private val indianGroupingCache = java.util.concurrent.ConcurrentHashMap<Locale, Boolean>()

    /**
     * Seit Build 103: Gruppiert [locale] Zahlen indisch, mit Zweier-Nebengruppen («1,00,000», z. B. hi-IN, en-IN)?
     * Aus den CLDR-Daten von ICU (`java.text` kennt keine Nebengruppen); ohne ICU (JVM-Tests) nein. Wie iOS
     * `NumberFormatter.secondaryGroupingSize`.
     */
    fun usesIndianGrouping(locale: Locale): Boolean = indianGroupingCache.getOrPut(locale) {
        runCatching {
            (android.icu.text.NumberFormat.getNumberInstance(locale) as? android.icu.text.DecimalFormat)
                ?.secondaryGroupingSize == 2
        }.getOrDefault(false)
    }

    /** Null-Ziffer der Sprache: '0', '٠' (Arabisch) oder '۰' (Persisch). */
    fun zeroDigit(locale: Locale = appLocale): Char = DecimalFormatSymbols.getInstance(locale).zeroDigit

    /** Schreibt [locale] Zahlen mit 0–9 (auch Arabisch mit `nu-latn`)? Sonst eigene Ziffern wie «٧٢» oder «۷۲». */
    fun hasLatinDigits(locale: Locale = appLocale): Boolean = zeroDigit(locale) == '0'

    /**
     * Lateinische Ziffern 0–9 eines fertigen Textes durch die der Sprache ersetzen — nur für
     * Ziffern ohne Dezimal- oder Tausendertrennzeichen (Zähler in festen Vorlagen).
     */
    fun digits(text: String, locale: Locale = appLocale): String {
        val zero = zeroDigit(locale)
        if (zero == '0') return text
        return buildString(text.length) {
            for (c in text) append(if (c in '0'..'9') zero + (c - '0') else c)
        }
    }
}
