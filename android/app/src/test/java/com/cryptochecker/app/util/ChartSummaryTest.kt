package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartSummaryTest {

    @Test
    fun `Kennzahlen einer Reihe`() {
        val s = ChartSummary.of(listOf(100.0, 120.0, 90.0, 110.0))!!
        assertEquals(100.0, s.start, 0.0)
        assertEquals(110.0, s.end, 0.0)
        assertEquals(120.0, s.high, 0.0)
        assertEquals(90.0, s.low, 0.0)
        assertEquals(10.0, s.changePercent!!, 1e-9)
    }

    @Test
    fun `zu wenige Werte oder Start 0`() {
        assertNull(ChartSummary.of(listOf(1.0)))
        assertNull(ChartSummary.of(emptyList()))
        assertNull(ChartSummary(0.0, 1.0, 1.0, 0.0).changePercent)
    }

    @Test
    fun `Richtung mit derselben Schwelle wie PriceFormat`() {
        assertEquals(ChangeDirection.UP, ChartSummary.direction(0.005))
        assertEquals(ChangeDirection.DOWN, ChartSummary.direction(-2.0))
        assertEquals(ChangeDirection.FLAT, ChartSummary.direction(0.004))
        assertEquals(ChangeDirection.FLAT, ChartSummary.direction(-0.004))
        assertEquals(ChangeDirection.FLAT, ChartSummary.direction(null))
    }

    @Test
    fun `Prozent ohne Vorzeichen`() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("2.50%", ChartSummary.unsignedPercent(-2.5))
            assertEquals("1130%", ChartSummary.unsignedPercent(1130.2, decimals = 0))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
