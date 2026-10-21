package com.cryptochecker.app.util

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Monat und Jahr («Apr. 2028», «2028年4月», «أبريل ٢٠٢٨») in der Reihenfolge und Schreibweise der Sprache — wie iOS
 * `setLocalizedDateFormatFromTemplate("MMMyyyy")`. Ein festes «MMM yyyy» stellte etwa Japanisch falsch dar
 * («4月 2028»). Ziffern nach der Regel der App ([LocaleNumbers.dates]). Seit Build 98.
 */
object MonthYearFormat {
    /** Vorlage (Skeleton) wie auf iOS. */
    const val SKELETON = "MMMyyyy"

    /** Ersatz, falls ICU kein für java.time lesbares Muster liefert. */
    internal const val FALLBACK_PATTERN = "MMM yyyy"

    /**
     * Formatter für [locale]. [bestPattern] liefert das Muster zur Vorlage (Standard: ICU des Systems; JVM-Tests geben
     * ein festes Muster mit, android.icu gibt es dort nicht).
     */
    fun formatter(
        locale: Locale = LocaleNumbers.appLocale,
        bestPattern: (Locale) -> String = ::icuPattern,
    ): DateTimeFormatter {
        val base = try {
            DateTimeFormatter.ofPattern(bestPattern(locale), locale)
        } catch (_: IllegalArgumentException) {
            // ICU-Muster mit Zeichen, die java.time nicht kennt: lieber festes Muster als gar kein Datum
            DateTimeFormatter.ofPattern(FALLBACK_PATTERN, locale)
        }
        return LocaleNumbers.dates(base, locale)
    }

    /** Bestes Muster des Systems (ICU) zur Vorlage [SKELETON]. */
    private fun icuPattern(locale: Locale): String =
        android.icu.text.DateTimePatternGenerator.getInstance(locale).getBestPattern(SKELETON)
}
