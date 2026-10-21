package com.cryptochecker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cryptochecker.app.util.MonthYearFormat
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Monat und Jahr mit dem ICU-Muster des Geräts (android.icu gibt es nur hier, nicht in JVM-Tests): Reihenfolge und
 * Schreibweise der Sprache wie iOS `setLocalizedDateFormatFromTemplate("MMMyyyy")`.
 */
@RunWith(AndroidJUnit4::class)
class MonthYearFormatIcuTest {

    private val date = LocalDate.of(2028, 4, 15)

    private fun format(tag: String): String = date.format(MonthYearFormat.formatter(Locale.forLanguageTag(tag)))

    @Test
    fun japanischJahrVorMonat() {
        assertEquals("2028年4月", format("ja"))
    }

    @Test
    fun deutschSchweizMonatKurzUndJahr() {
        val text = format("de-CH")
        assertTrue(text, text.startsWith("Apr"))
        assertTrue(text, text.endsWith("2028"))
    }

    @Test
    fun arabischMitArabischenZiffern() {
        val text = format("ar")
        assertTrue(text, text.contains("٢٠٢٨"))
        assertTrue(text, !text.contains("2028"))
    }
}
