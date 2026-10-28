package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Grundbausteine von [PriceFormat]: Pfeil zur Kursbewegung, Kurs, Schwellwert und Bestandswert mit Währung, exakte
 * Menge, Eingabe lesen, Uhrzeit ohne Sekunden und Dauer einer Aktualisierung. Ergänzt PriceFormatThresholdTest.
 */
class PriceFormatBasicsTest {

    private val usValueFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US))

    // ---- Pfeil ----

    @Test
    fun changeArrowFollowsSignAndIgnoresPracticallyZero() {
        assertEquals("", PriceFormat.changeArrow(null))
        assertEquals("", PriceFormat.changeArrow(0.0))
        assertEquals("", PriceFormat.changeArrow(0.004))
        assertEquals("", PriceFormat.changeArrow(-0.004))
        assertEquals(PriceFormat.ARROW_UP, PriceFormat.changeArrow(0.005))
        assertEquals(PriceFormat.ARROW_UP, PriceFormat.changeArrow(12.0))
        assertEquals(PriceFormat.ARROW_DOWN, PriceFormat.changeArrow(-0.5))
        assertEquals("↗", PriceFormat.ARROW_UP)
        assertEquals("↘", PriceFormat.ARROW_DOWN)
    }

    // ---- Kurs mit Währung ----

    @Test
    fun priceWithCurrencyDashWithoutPositivePrice() {
        assertEquals("—", PriceFormat.priceWithCurrency(null, "USDT"))
        assertEquals("—", PriceFormat.priceWithCurrency(0.0, "USDT"))
        assertEquals("—", PriceFormat.priceWithCurrency(-3.0, "USDT"))
        assertEquals("—", PriceFormat.price(null))
    }

    @Test
    fun priceWithCurrencyAppendsQuote() {
        val text = PriceFormat.priceWithCurrency(65_000.0, "USDT")
        assertEquals(PriceFormat.price(65_000.0) + " USDT", text)
        assertTrue(text.endsWith(" USDT"))
    }

    // ---- Schwellwert mit Währung ----

    @Test
    fun thresholdWithCurrencyKeepsExactValue() {
        assertEquals(PriceFormat.threshold(1.0005) + " USDT", PriceFormat.thresholdWithCurrency(1.0005, "USDT"))
        assertEquals("1.0005", PriceFormat.threshold(1.0005, Locale.US))
        assertEquals("65,000", PriceFormat.threshold(65_000.0, Locale.US))
    }

    @Test
    fun thresholdWithCurrencyDashForInvalidValues() {
        assertEquals("—", PriceFormat.thresholdWithCurrency(null, "USDT"))
        assertEquals("—", PriceFormat.thresholdWithCurrency(0.0, "USDT"))
        assertEquals("—", PriceFormat.thresholdWithCurrency(Double.NaN, "USDT"))
        assertEquals("—", PriceFormat.thresholdWithCurrency(Double.POSITIVE_INFINITY, "USDT"))
        assertEquals("—", PriceFormat.threshold(-1.0))
    }

    @Test
    fun thresholdFormatAllowsEighteenDecimals() {
        val format = PriceFormat.thresholdFormat(Locale.US)
        assertEquals(PriceFormat.THRESHOLD_MAX_DECIMALS, format.maximumFractionDigits)
        assertEquals(0, format.minimumFractionDigits)
        assertEquals("0.000000000000000001", format.format(BigDecimal("1E-18")))
    }

    // ---- Bestandswert ----

    @Test
    fun valueWithCurrencyTwoDecimals() {
        assertEquals("1,234.50 CHF", PriceFormat.valueWithCurrency(1_234.5, "CHF", usValueFormat))
        assertEquals("0.00 USD", PriceFormat.valueWithCurrency(0.0, "USD", usValueFormat))
        assertEquals("-12.50 EUR", PriceFormat.valueWithCurrency(-12.5, "EUR", usValueFormat))
    }

    @Test
    fun valueWithCurrencyDefaultFormatMatchesValueFormat() {
        assertEquals(PriceFormat.valueFormat().format(42.0) + " USDT", PriceFormat.valueWithCurrency(42.0, "USDT"))
        assertEquals(2, PriceFormat.valueFormat().minimumFractionDigits)
        assertEquals(2, PriceFormat.valueFormat().maximumFractionDigits)
    }

    // ---- Exakte Menge ----

    @Test
    fun exactAmountFormatKeepsAllDecimals() {
        val format = PriceFormat.exactAmountFormat()
        assertEquals(18, format.maximumFractionDigits)
        assertEquals(0, format.minimumFractionDigits)
        val us = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).apply {
            maximumFractionDigits = 18
            minimumFractionDigits = 0
        }
        assertEquals("0.00000001", PriceFormat.amount(BigDecimal("0.00000001"), us))
        assertEquals("1,234.5", PriceFormat.amount(BigDecimal("1234.500"), us))
    }

    // ---- Eingabe lesen ----

    @Test
    fun parseDecimalAmountBlankIsZeroAndInvalidIsNull() {
        assertEquals(BigDecimal.ZERO, PriceFormat.parseDecimalAmount("", '.'))
        assertEquals(BigDecimal.ZERO, PriceFormat.parseDecimalAmount("   ", ','))
        assertEquals(0, BigDecimal("0.00000001").compareTo(PriceFormat.parseDecimalAmount("0.00000001", '.')))
        assertEquals(0, BigDecimal("2.125").compareTo(PriceFormat.parseDecimalAmount("2,125", ',')))
        assertNull(PriceFormat.parseDecimalAmount("1.5f", '.'))
        assertNull(PriceFormat.parseDecimalAmount("-3", '.'))
    }

    @Test
    fun amountForInputRoundTripsThroughParse() {
        val value = BigDecimal("1234.56789")
        val text = PriceFormat.amountForInput(value, ',')
        assertTrue(text.contains(','))
        assertEquals(0, value.compareTo(PriceFormat.parseDecimalAmount(text, ',')))
        assertEquals("", PriceFormat.amountForInput(null as BigDecimal?, '.'))
    }

    // ---- Uhrzeit und Dauer ----

    @Test
    fun shortTimeDashWithoutTime() {
        assertEquals("—", PriceFormat.shortTime(0L, true, Locale.US))
        assertEquals("—", PriceFormat.shortTime(-5L, false, Locale.US))
    }

    @Test
    fun shortTimeWithoutSeconds() {
        // 13:05:42 UTC (Unit-Tests laufen in UTC)
        val millis = 13 * 3_600_000L + 5 * 60_000L + 42_000L
        val text = PriceFormat.shortTime(millis, true, Locale.US)
        assertEquals(ClockText.format(Locale.US, withSeconds = false, is24 = true).format(java.util.Date(millis)), text)
        // Minuten ja, Sekunden nein (Zeitzonen verschieben nur um ganze Viertelstunden)
        assertTrue(text, !text.contains("42"))
        assertTrue(text, text.contains(':'))
    }

    @Test
    fun durationInMillisecondsAndSeconds() {
        assertEquals("", PriceFormat.duration(0L))
        assertEquals("", PriceFormat.duration(-1L))
        assertEquals(LocaleNumbers.integer(500L) + " ms", PriceFormat.duration(500L))
        assertEquals("%.1f s".format(1.5), PriceFormat.duration(1_500L))
    }

    @Test
    fun zeroPercentHasNoSign() {
        val zero = PriceFormat.zeroPercent()
        assertTrue(zero, !zero.contains("+") && !zero.contains("−"))
        assertNull(PriceFormat.changePercent(0.001))
    }
}
