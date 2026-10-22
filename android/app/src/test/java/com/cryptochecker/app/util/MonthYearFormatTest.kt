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

    /** Die Musterquelle bekommt die gewünschte Locale, und ihr Muster bestimmt Reihenfolge und Monatsnamen. */
    @Test
    fun musterQuelleBekommtDieLocaleUndBestimmtDieAusgabe() {
        val asked = mutableListOf<Locale>()
        val fr = Locale.FRENCH
        val f = MonthYearFormat.formatter(fr) { asked += it; "y MMMM" }
        assertEquals(listOf(fr), asked)
        assertEquals("2028 avril", date.format(f))
    }

    /** Gleiche Vorlage wie iOS (`setLocalizedDateFormatFromTemplate`): sonst wichen Monat und Jahr je Plattform ab. */
    @Test
    fun vorlageWieIos() {
        val ios = SourceGuard.ios(findUp("ios/App/Features/Cycle/MarketHalvingCard.swift"), "ios/App/Features/Cycle/MarketHalvingCard.swift")
        val text = ios.readText(Charsets.UTF_8)
        assertEquals(true, text.contains("setLocalizedDateFormatFromTemplate(\"${MonthYearFormat.SKELETON}\")"))
    }

    private fun findUp(rel: String): java.io.File? {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }
}
