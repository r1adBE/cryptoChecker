package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketTotalsTest {

    private val totals = MarketTotals(
        marketCap = mapOf("usd" to 3.45e12, "chf" to 2.8e12, "eur" to 3.0e12, "jpy" to Double.NaN),
        volume = mapOf("usd" to 1.2e11, "chf" to 9.7e10, "jpy" to 1.8e13),
        changePercent24h = 1.25,
    )

    @Test
    fun usesConversionCurrencyWhenAvailable() {
        assertEquals(MarketTotals.Values("CHF", 2.8e12, 9.7e10), totals.valuesIn("CHF"))
        assertEquals(MarketTotals.Values("CHF", 2.8e12, 9.7e10), totals.valuesIn(" chf "))
    }

    @Test
    fun fallsBackToUsd() {
        // EUR fehlt beim Volumen, JPY ist ungültig, XYZ unbekannt
        assertEquals("USD", totals.valuesIn("EUR")?.currency)
        assertEquals("USD", totals.valuesIn("JPY")?.currency)
        assertEquals("USD", totals.valuesIn("XYZ")?.currency)
        assertEquals("USD", totals.valuesIn(null)?.currency)
        assertEquals(3.45e12, totals.valuesIn("")!!.marketCap, 0.0)
    }

    @Test
    fun nothingWithoutUsd() {
        assertNull(MarketTotals(mapOf("chf" to 1.0), mapOf("chf" to 1.0), null).valuesIn("EUR"))
        assertNull(MarketTotals(emptyMap(), emptyMap(), null).valuesIn("USD"))
    }
}
