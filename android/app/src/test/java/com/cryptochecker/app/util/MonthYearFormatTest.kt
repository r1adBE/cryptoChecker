package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Monat und Jahr ([MonthYearFormat]) ohne android.icu: Muster fest mitgegeben (wie es ICU liefert), geprüft werden
 * Ziffern nach der App-Regel und der Ersatz bei unlesbarem Muster. Die echten ICU-Muster (ja, de-CH, ar) prüft
 * `MonthYearFormatIcuTest` (androidTest).
 */
class MonthYearFormatTest {

    private val date = LocalDate.of(2028, 4, 15)

    @Test
    fun japanischesMusterMitJahrZuerst() {
        val f = MonthYearFormat.formatter(Locale.JAPANESE) { "y年M月" }
        assertEquals("2028年4月", date.format(f))
    }

    @Test
    fun arabischMitArabischenZiffernWieLocaleNumbers() {
        val ar = Locale.forLanguageTag("ar")
        val f = MonthYearFormat.formatter(ar) { "MMM y" }
        val expected = date.format(LocaleNumbers.dates(java.time.format.DateTimeFormatter.ofPattern("MMM y", ar), ar))
        assertEquals(expected, date.format(f))
        // Jahr in den Ziffern der Locale (java.time allein schriebe «2028»)
        val zero = java.text.DecimalFormatSymbols.getInstance(ar).zeroDigit
        val year = "2028".map { zero + (it - '0') }.joinToString("")
        assertEquals(true, date.format(f).endsWith(year))
    }

    @Test
    fun unlesbaresMusterFaelltAufFestesZurueck() {
        val f = MonthYearFormat.formatter(Locale.ENGLISH) { "MMM y {x}" }
        assertEquals("Apr 2028", date.format(f))
    }

    @Test
    fun vorlageWieIos() {
        assertEquals("MMMyyyy", MonthYearFormat.SKELETON)
    }
}
