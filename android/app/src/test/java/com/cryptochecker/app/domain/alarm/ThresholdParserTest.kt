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
}
