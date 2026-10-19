package com.cryptochecker.app.util

import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.market.GasFees
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class LocaleNumbersTest {

    // Ziffern ausdrücklich gewählt: so hängt der Test nicht davon ab, ob «ar» ohne Region
    // auf diesem JDK/ICU arabische oder lateinische Ziffern als Standard hat
    private val arabic = Locale.forLanguageTag("ar-EG-u-nu-arab")
    private val persian = Locale.forLanguageTag("fa-IR-u-nu-arabext")

    @Test
    fun integerUsesLocaleDigits() {
        assertEquals("٧٢", LocaleNumbers.integer(72, arabic))
        assertEquals("۷۲", LocaleNumbers.integer(72, persian))
        assertEquals("72", LocaleNumbers.integer(72, Locale.ROOT))
        // Jahre ohne Tausendertrennung
        assertEquals("٢٠٢٤", LocaleNumbers.integer(2024, arabic))
        assertEquals("2024", LocaleNumbers.integer(2024, Locale.GERMANY))
    }

    @Test
    fun decimalRoundsLikeFormatAndUsesLocaleDigits() {
        assertEquals("58.4", LocaleNumbers.decimal(58.44, 1, locale = Locale.ROOT))
        assertEquals("0.3", LocaleNumbers.decimal(0.25, 1, locale = Locale.ROOT))
        assertEquals("1.5", LocaleNumbers.decimal(1.5, 3, minDecimals = 0, locale = Locale.ROOT))
        assertEquals("٥٨٫٤", LocaleNumbers.decimal(58.44, 1, locale = arabic))
        val fa = LocaleNumbers.decimal(58.44, 1, locale = persian)
        assertEquals("۵۸", fa.take(2))
        assertEquals("۴", fa.takeLast(1))
    }

    /** Grundlage für die Betragsschrift: Rubik nur bei 0–9, sonst Systemschrift (`amountNumbers`). */
    @Test
    fun hasLatinDigitsOnlyForZeroToNine() {
        assertEquals(false, LocaleNumbers.hasLatinDigits(arabic))
        assertEquals(false, LocaleNumbers.hasLatinDigits(persian))
        assertEquals(true, LocaleNumbers.hasLatinDigits(Locale.forLanguageTag("ar-u-nu-latn")))
        assertEquals(true, LocaleNumbers.hasLatinDigits(Locale.forLanguageTag("he-IL")))
        assertEquals(true, LocaleNumbers.hasLatinDigits(Locale.GERMANY))
        assertEquals(true, LocaleNumbers.hasLatinDigits(Locale.ROOT))
    }

    @Test
    fun digitsMapsOnlyAsciiDigits() {
        assertEquals("٣ / ١٠", LocaleNumbers.digits("3 / 10", arabic))
        assertEquals("۱۲s", LocaleNumbers.digits("12s", persian))
        assertEquals("3 / 10", LocaleNumbers.digits("3 / 10", Locale.ROOT))
    }

    @Test
    fun datesUseLocaleDigits() {
        // java.time schreibt sonst immer lateinische Ziffern
        val date = LocalDate.of(2028, 4, 18)
        assertEquals("١٨.٤.٢٠٢٨", date.format(LocaleNumbers.dates(DateTimeFormatter.ofPattern("d.M.yyyy"), arabic)))
        assertEquals("18.4.2028", date.format(LocaleNumbers.dates(DateTimeFormatter.ofPattern("d.M.yyyy"), Locale.ROOT)))
    }

    @Test
    fun localizedOutputParsesBack() {
        // Eingaben nehmen lokale Ziffern an (Runde 29): Anzeige → Eingabe ergibt denselben Wert
        assertEquals("58.4", ThresholdParser.latinDigits(LocaleNumbers.decimal(58.4, 1, locale = arabic)))
        assertEquals("1234", ThresholdParser.latinDigits(LocaleNumbers.integer(1234, persian)))
    }

    @Test
    fun gasFeesFollowLocale() {
        assertEquals("1.4", GasFees.formatGwei(1.44, Locale.ROOT))
        assertEquals("١٫٤", GasFees.formatGwei(1.44, arabic))
        assertEquals("٢٣", GasFees.formatGwei(23.2, arabic))
        assertEquals("\$0.63", GasFees.formatUsd(0.63, Locale.ROOT))
    }

    @Test
    fun inputSeparatorUsesDeviceRegionLikeIos() {
        // App-Sprache «de» ohne Region, Gerät in der Schweiz: Punkt (de_CH) — wie Locale.current unter iOS
        assertEquals(Locale.forLanguageTag("de-CH"), LocaleNumbers.inputLocale(Locale.forLanguageTag("de"), "CH"))
        assertEquals('.', LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("de"), "CH"))
        assertEquals(',', LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("de"), "DE"))
        assertEquals(',', LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("de"), ""))
        assertEquals(',', LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("en"), "DE"))
        assertEquals('.', LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("en-US"), "US"))
        // Ungültige Region: App-Sprache bleibt
        assertEquals(Locale.forLanguageTag("de"), LocaleNumbers.inputLocale(Locale.forLanguageTag("de"), "not a region"))
        // «10.000» ohne Kurs: Schweiz 10, Deutschland 10000
        assertEquals(10.0, ThresholdParser.parse("10.000", LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("de"), "CH")))
        assertEquals(10_000.0, ThresholdParser.parse("10.000", LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag("de"), "DE")))
    }

    @Test
    fun displayUsesSameLocaleAsInput() {
        fun fmt(lang: String, region: String) = LocaleNumbers.formatLocale(Locale.forLanguageTag(lang), region)
        // «Deutsch» in der App auf einem Schweizer Gerät: de-CH wie die Eingabe (nicht «90.000,00»)
        val deCh = fmt("de", "CH")
        assertEquals(Locale.forLanguageTag("de-CH"), deCh)
        assertEquals("90\u2019000.00", LocaleNumbers.decimal(90_000.0, 2, locale = deCh, grouping = true))
        assertEquals("90.000,00", LocaleNumbers.decimal(90_000.0, 2, locale = fmt("de", "DE"), grouping = true))
        assertEquals("90,000.00", LocaleNumbers.decimal(90_000.0, 2, locale = fmt("en-US", "US"), grouping = true))
        // Region passt nicht zur Sprache (de + US): Sprach-Daten, Komma
        assertEquals("1,5", LocaleNumbers.decimal(1.5, 1, locale = fmt("de", "US")))
        // Anzeige und Eingabe: dasselbe Dezimalzeichen, Ausgabe lässt sich wieder einlesen
        for ((lang, region) in listOf("de" to "CH", "de" to "DE", "fr" to "CH", "en-US" to "US", "it" to "CH")) {
            val locale = fmt(lang, region)
            val separator = LocaleNumbers.inputDecimalSeparator(Locale.forLanguageTag(lang), region)
            val shown = LocaleNumbers.decimal(1234.5, 2, locale = locale, grouping = true)
            assertEquals("$lang-$region", separator, shown[shown.length - 3])
            assertEquals("$lang-$region", 1234.5, ThresholdParser.parse(shown, separator) ?: Double.NaN, 1e-9)
        }
    }

    @Test
    fun applyFormatLocaleSetsFormatCategory() {
        val before = Locale.getDefault(Locale.Category.FORMAT)
        try {
            LocaleNumbers.applyFormatLocale(Locale.forLanguageTag("de"), "CH")
            assertEquals(Locale.forLanguageTag("de-CH"), LocaleNumbers.appLocale)
            assertEquals("1\u2019234.50", java.text.DecimalFormat("#,##0.00").format(1234.5))
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before)
        }
    }
}
