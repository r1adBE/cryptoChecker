package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.format.MonthYearFormat
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regelstand auf der Seite «Steuer-Arbeitsunterlage» und in der CSV-Kopfzeile ([taxRulesDateLabel]): Monat und Jahr
 * in Sprache und Ziffern
 * der App statt «2026-10»; der Hinweis selbst ([TaxRulesNotice]) hängt an `TaxRulesDate.rulesStale`
 * (`TaxRulesStaleParityTest`). Muster fest mitgegeben (android.icu fehlt in JVM-Tests).
 */
class TaxRulesNoticeTest {

    @Test
    fun monthAndYearInsteadOfRawValue() {
        val f = MonthYearFormat.formatter(Locale.GERMAN) { "MMMM y" }
        assertEquals("Oktober 2026", taxRulesDateLabel("2026-10", f))
    }

    @Test
    fun arabicWithNativeDigits() {
        val ar = Locale.forLanguageTag("ar")
        val text = taxRulesDateLabel("2026-10", MonthYearFormat.formatter(ar) { "MMM y" })
        val zero = java.text.DecimalFormatSymbols.getInstance(ar).zeroDigit
        val year = "2026".map { zero + (it - '0') }.joinToString("")
        assertTrue(text, text.endsWith(year))
        assertFalse(text, text.contains("2026"))
    }

    @Test
    fun unreadableRulesDateStaysRawValue() {
        assertEquals("2026-13", taxRulesDateLabel("2026-13", MonthYearFormat.formatter(Locale.ENGLISH) { "MMM y" }))
    }

    @Test
    fun storedRulesDateIsFormatted() {
        val text = taxRulesDateLabel(TaxTemplate.RULES_DATE, MonthYearFormat.formatter(Locale.ENGLISH) { "MMM y" })
        assertFalse(text, text == TaxTemplate.RULES_DATE)
    }
}
