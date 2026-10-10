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
        assertEquals(Locale("de", "CH"), LocaleNumbers.inputLocale(Locale("de"), "CH"))
        assertEquals('.', LocaleNumbers.inputDecimalSeparator(Locale("de"), "CH"))
        assertEquals(',', LocaleNumbers.inputDecimalSeparator(Locale("de"), "DE"))
        assertEquals(',', LocaleNumbers.inputDecimalSeparator(Locale("de"), ""))
        assertEquals(',', LocaleNumbers.inputDecimalSeparator(Locale("en"), "DE"))
        assertEquals('.', LocaleNumbers.inputDecimalSeparator(Locale("en", "US"), "US"))
        // Ungültige Region: App-Sprache bleibt
        assertEquals(Locale("de"), LocaleNumbers.inputLocale(Locale("de"), "not a region"))
        // «10.000» ohne Kurs: Schweiz 10, Deutschland 10000
        assertEquals(10.0, ThresholdParser.parse("10.000", LocaleNumbers.inputDecimalSeparator(Locale("de"), "CH")))
        assertEquals(10_000.0, ThresholdParser.parse("10.000", LocaleNumbers.inputDecimalSeparator(Locale("de"), "DE")))
    }
}
