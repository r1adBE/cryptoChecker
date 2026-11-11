package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThresholdParserTest {

    private fun p(text: String, decimal: Char = '.', hint: Double? = null) = ThresholdParser.parse(text, decimal, hint)

    private fun eq(expected: Double, actual: Double?) = assertEquals(expected, actual!!, expected * 1e-12)

    @Test
    fun `ambiguous comma follows the current price`() {
        eq(60_000.0, p("60,000", ',', hint = 65_000.0))
        eq(60.0, p("60,000", ',', hint = 58.0))
        eq(60_000.0, p("60,000", '.', hint = 65_000.0))
        eq(60.0, p("60,000", '.', hint = 61.0))
    }

    @Test
    fun `ambiguous dot follows the current price`() {
        eq(60_000.0, p("60.000", '.', hint = 65_000.0))
        eq(60.0, p("60.000", '.', hint = 58.0))
        eq(60_000.0, p("60.000", ',', hint = 50_000.0))
    }

    @Test
    fun `ambiguous without a price follows the locale`() {
        eq(60.0, p("60,000", ','))
        eq(60_000.0, p("60,000", '.'))
        eq(60.0, p("60.000", '.'))
        eq(60_000.0, p("60.000", ','))
    }

    @Test
    fun `apostrophes and spaces are grouping`() {
        eq(60_000.0, p("60’000"))
        eq(60_000.0, p("60'000"))
        eq(60_000.0, p("60 000"))
        eq(60_000.0, p("60 000"))
        eq(60_000.0, p("60 000"))
        eq(60_000.5, p("60’000.5"))
        eq(60_000.5, p(" 60 000,5 ", ','))
    }

    @Test
    fun `small values with a decimal comma or dot`() {
        eq(0.00012, p("0,00012", '.', hint = 60_000.0))
        eq(0.00012, p("0.00012", ',', hint = 60_000.0))
        eq(12.5, p("12,5"))
        eq(0.5, p(",5"))
        eq(60.0, p("60."))
    }

    @Test
    fun `both separators - the last one is the decimal`() {
        eq(1_234.56, p("1.234,56"))
        eq(1_234.56, p("1,234.56", ','))
        eq(1_234_567.89, p("1.234.567,89"))
        eq(1_234_567.89, p("1’234’567.89"))
    }

    @Test
    fun `repeated separator is grouping`() {
        eq(1_234_567.0, p("1.234.567"))
        eq(1_234_567.0, p("1,234,567", ','))
    }

    @Test
    fun `invalid input`() {
        assertNull(p(""))
        assertNull(p("   "))
        assertNull(p("60k"))
        assertNull(p("1e5"))
        assertNull(p("-5"))
        assertNull(p("0"))
        assertNull(p("0,000"))
        assertNull(p("1,2,3"))
        assertNull(p("1.234,5,6"))
        assertNull(p("12,34.5"))
        assertNull(p("."))
        assertNull(p("abc"))
    }

    @Test
    fun `three decimals with a leading zero group are never grouping`() {
        eq(0.123, p("0,123", '.', hint = 60_000.0))
    }

    @Test
    fun `arabic-indic and persian digits read like latin digits`() {
        // ٦٠٠٠٠ / ۶۰۰۰۰ = 60000; «٫» Dezimalzeichen, «٬» Tausendertrennung
        eq(60_000.0, p("\u0666\u0660\u0660\u0660\u0660"))
        eq(60_000.0, p("\u06F6\u06F0\u06F0\u06F0\u06F0"))
        eq(1_234.5, p("\u0661\u066C\u0662\u0663\u0664\u066B\u0665"))
        eq(0.5, p("\u0660\u066B\u0665"))
        // Richtungszeichen aus eingefügtem Text stören nicht
        eq(42.0, p("\u200E42\u200F"))
        eq(42.0, p("\u206642\u2069"))
        assertEquals("1234.5", ThresholdParser.latinDigits("\u0661\u0662\u0663\u0664\u066B\u0665"))
        assertEquals("abc", ThresholdParser.latinDigits("abc"))
        assertNull(p("\u0660"))
    }

    @Test
    fun `zero is allowed for amounts, everything else like parse`() {
        assertEquals(0.0, ThresholdParser.parseAllowingZero("0", '.'))
        assertEquals(0.0, ThresholdParser.parseAllowingZero("0,00", ','))
        assertEquals(0.0, ThresholdParser.parseAllowingZero(".0", '.'))
        assertEquals(0.0, ThresholdParser.parseAllowingZero("\u0660", '.'))
        // Mehrdeutig: Region bzw. Kurs entscheidet wie bei den Schwellwerten
        assertEquals(60_000.0, ThresholdParser.parseAllowingZero("60.000", ','))
        assertEquals(60.0, ThresholdParser.parseAllowingZero("60.000", '.'))
        assertEquals(60.0, ThresholdParser.parseAllowingZero("60.000", ',', priceHint = 58.0))
        assertEquals(1_234.0, ThresholdParser.parseAllowingZero("1,234", '.'))
        assertEquals(1_234.56, ThresholdParser.parseAllowingZero("1.234,56", '.'))
        assertEquals(1_234.5, ThresholdParser.parseAllowingZero("1\u00a0234,5", ','))
        assertEquals(1_234.5, ThresholdParser.parseAllowingZero("1\u202f234.5", '.'))
        // Kotlin-Zahlensuffixe, Exponent, Vorzeichen, leer: ung\u00fcltig
        for (bad in listOf("1.5f", "2d", "1e3", "-5", "NaN", "Infinity", "", " ", "0,0,0")) {
            assertNull(bad, ThresholdParser.parseAllowingZero(bad, '.'))
        }
    }
}
