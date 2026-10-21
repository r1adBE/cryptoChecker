package com.cryptochecker.app.util

import java.util.Locale
import kotlin.math.abs

/**
 * Prozentangaben überall in derselben Schreibweise der App-Sprache (Muster nach CLDR, wie
 * `NumberFormat.getPercentInstance`): «2.4%», Deutsch «2,4 %» (Schweiz und Liechtenstein «2.4%»),
 * Französisch «2,4 %», Türkisch «%2,4», Arabisch «٢٫٤٪», Persisch «۲٫۴٪». Eine eigene kleine Tabelle statt
 * `getPercentInstance`, damit Android, die JVM-Tests und iOS (`PercentText.swift`) Zeichen für Zeichen dasselbe
 * liefern (die ICU-Stände der Systeme unterscheiden sich, z. B. beim Abstand vor «%»).
 *
 * Seit Build 95 zählt für den Abstand die ganze Locale (Sprache + Region) wie CLDR, nicht nur die Sprache:
 * Spanisch (Mexiko) «2.4%», Englisch in Deutschland, Österreich, Schweden, Finnland, Dänemark «2,4 %».
 *
 * Seit Build 94 die eine Stelle für «+2.40%» (Kurs), «+2.9 %» (Aktivität), «58.4 %» (Dominanz) und das
 * Pulse-Widget — vorher schrieb jede Stelle das Zeichen selbst und anders.
 */
object PercentText {

    /** Abstand vor «%» (geschützt, bricht nicht um) wie CLDR. */
    private const val NBSP = "\u00A0"

    /** Sprachen mit Abstand vor «%» («2,4 %»). */
    private val SPACED = setOf("de", "fr", "cs", "da", "es", "fi", "nb", "no", "ro", "ru", "sv")

    /**
     * Ausnahmen nach Region zu [SPACED]: diese Sprach-Regionen ohne Abstand (CLDR de_CH, de_LI, fr_CH: «2.4%»,
     * es_MX: «2.4%»; die übrigen spanischen Regionen inkl. es_US und es_419 behalten den Abstand).
     */
    private val UNSPACED_REGIONS = mapOf(
        "de" to setOf("CH", "LI"),
        "fr" to setOf("CH", "LI"),
        "es" to setOf("MX"),
    )

    /** Umgekehrte Ausnahmen: Sprachen ohne Abstand, in diesen Regionen mit (CLDR en_AT, en_DE, en_DK, en_FI, en_SE). */
    private val SPACED_REGIONS = mapOf(
        "en" to setOf("AT", "DE", "DK", "FI", "SE"),
    )

    /** Abstand vor «%» für [language] in [region] nach CLDR (ohne Türkisch und arabische Ziffern). */
    internal fun isSpaced(language: String, region: String): Boolean {
        val r = region.uppercase()
        return if (language in SPACED) {
            r !in UNSPACED_REGIONS[language].orEmpty()
        } else {
            r in SPACED_REGIONS[language].orEmpty()
        }
    }

    /** Arabisches Prozentzeichen «٪» (U+066A) zu arabischen/persischen Ziffern. */
    private const val ARABIC_PERCENT = "\u066A"

    /** Fertige Zahl [number] (ohne Vorzeichen) mit dem Prozentzeichen der Sprache von [locale]. */
    fun wrap(number: String, locale: Locale = LocaleNumbers.appLocale): String {
        val language = locale.language
        return when {
            language == "tr" -> "%$number"
            (language == "ar" || language == "fa") && !LocaleNumbers.hasLatinDigits(locale) -> number + ARABIC_PERCENT
            isSpaced(language, locale.country) -> number + NBSP + "%"
            else -> "$number%"
        }
    }

    /**
     * Betrag von [value] mit genau [decimals] Nachkommastellen (kaufmännisch gerundet, Ziffern und Dezimalzeichen
     * der Sprache, ohne Tausendertrennung) und Prozentzeichen — ohne Vorzeichen.
     */
    fun unsigned(value: Double, decimals: Int, locale: Locale = LocaleNumbers.appLocale): String =
        wrap(LocaleNumbers.decimal(DecimalText.rounded(abs(value), decimals), decimals, locale = locale), locale)

    /**
     * Mit Vorzeichen vor dem Ganzen («+2.40%», «−1.4 %», türkisch «+%2,4»), echtes Minus U+2212; gerundet 0 ohne
     * Vorzeichen. Ohne Richtungs-Insel — die setzt der Aufrufer ([BidiText.ltr]), wie bisher.
     */
    fun signed(value: Double, decimals: Int, locale: Locale = LocaleNumbers.appLocale): String {
        val rounded = DecimalText.rounded(abs(value), decimals)
        val sign = when {
            rounded == 0.0 -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return sign + unsigned(value, decimals, locale)
    }
}
