package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DayChangeTest {

    @Test
    fun `reference needs positive finite values`() {
        assertNotNull(DayReference.of(100.0, 110.0))
        assertNull(DayReference.of(null, 110.0))
        assertNull(DayReference.of(100.0, null))
        assertNull(DayReference.of(0.0, 110.0))
        assertNull(DayReference.of(100.0, -1.0))
        assertNull(DayReference.of(Double.NaN, 110.0))
        assertNull(DayReference.of(100.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `live price against the 24h open`() {
        val ref = DayReference(open = 100.0, lastClose = 101.0)
        assertEquals(2.3, DayChange.fromPrice(102.3, ref)!!, 1e-9)
        assertEquals(-4.0, DayChange.fromPrice(96.0, ref)!!, 1e-9)
    }

    @Test
    fun `no price or unrelated series - no value`() {
        val ref = DayReference(open = 100.0, lastClose = 100.0)
        assertNull(DayChange.fromPrice(null, ref))
        assertNull(DayChange.fromPrice(0.0, ref))
        assertNull(DayChange.fromPrice(Double.NaN, ref))
        assertNull(DayChange.fromPrice(100.0, null))
        // Gleiches Kürzel, anderer Token (z. B. DEX): Kurs weit weg vom Kerzenschluss
        assertNull(DayChange.fromPrice(130.0, ref))
        assertNull(DayChange.fromPrice(70.0, ref))
        assertNotNull(DayChange.fromPrice(124.0, ref))
    }

    @Test
    fun `series only uses last close against open`() {
        assertEquals(10.0, DayChange.fromSeries(DayReference(100.0, 110.0))!!, 1e-9)
        assertNull(DayChange.fromSeries(null))
    }

    @Test
    fun `candle quote maps usd-like quotes to the sparkline series`() {
        assertEquals("USDT", DayChange.candleQuote("usd"))
        assertEquals("USDT", DayChange.candleQuote("USDC"))
        assertEquals("USDT", DayChange.candleQuote("FDUSD"))
        assertEquals("USDT", DayChange.candleQuote("USDT"))
        assertEquals("EUR", DayChange.candleQuote(" eur "))
        assertEquals("BTC", DayChange.candleQuote("BTC"))
    }

    @Test
    fun `select prefers the pair series with the live price`() {
        val pair = DayReference(100.0, 105.0)
        val usdt = DayReference(50.0, 60.0)
        assertEquals(5.0, DayChange.select(105.0, pair, usdt, quoteIsFiat = true)!!, 1e-9)
        // Pair series present but price does not fit: no fallback to another series
        assertNull(DayChange.select(500.0, pair, usdt, quoteIsFiat = true))
    }

    @Test
    fun `select falls back to the usdt series only for fiat quotes`() {
        val usdt = DayReference(50.0, 60.0)
        assertEquals(20.0, DayChange.select(55.0, null, usdt, quoteIsFiat = true)!!, 1e-9)
        assertNull(DayChange.select(55.0, null, usdt, quoteIsFiat = false))
        assertNull(DayChange.select(55.0, null, null, quoteIsFiat = true))
    }
}
