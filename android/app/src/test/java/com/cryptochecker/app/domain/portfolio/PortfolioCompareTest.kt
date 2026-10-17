package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Vergleich Währung gegen USDT in Prozent und Währungseffekt ([PortfolioCompare]); wie `PortfolioCompareTests.swift`. */
class PortfolioCompareTest {

    private val d = 1e-9

    private fun <T> nn(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    private fun points(vararg values: Double, from: Long = 100L): List<PortfolioHistoryPoint> =
        values.mapIndexed { i, v -> PortfolioHistoryPoint(from + i, v) }

    @Test
    fun percentSinceFirstPointAndCurrencyEffect() {
        // In USDT +12 %, in CHF nur +7 % → Währungseffekt −5 Prozentpunkte
        val usdt = points(1000.0, 1050.0, 1120.0)
        val chf = points(900.0, 920.0, 963.0)
        val c = nn(PortfolioCompare.build(chf, usdt))
        assertEquals(listOf(100L, 101L, 102L), c.epochDays)
        assertEquals(0.0, c.currency[0], d)
        assertEquals(0.0, c.usdt[0], d)
        assertEquals(5.0, c.usdt[1], d)
        assertEquals(12.0, c.usdt[2], d)
        assertEquals(7.0, c.currency[2], d)
        assertEquals(-5.0, c.effect, d)
        assertEquals(920.0 / 900.0 * 100.0 - 100.0 - 5.0, c.effectAt(1)!!, d)
        assertEquals(0.0, c.effectAt(0)!!, d)
        assertNull(c.effectAt(3))
        assertNull(c.effectAt(-1))
        assertEquals(2, c.lastIndex)
    }

    @Test
    fun emptyStartUsesFirstPositivePointAsBase() {
        // «Seit 1. Kauf»: am Anfang noch nichts im Portfolio
        val usdt = points(0.0, 0.0, 200.0, 220.0)
        val chf = points(0.0, 0.0, 180.0, 216.0)
        val c = nn(PortfolioCompare.build(chf, usdt))
        assertEquals(listOf(102L, 103L), c.epochDays)
        assertEquals(10.0, c.usdt.last(), d)
        assertEquals(20.0, c.currency.last(), d)
        assertEquals(10.0, c.effect, d)
    }

    @Test
    fun baseNeedsBothSeriesPositive() {
        assertEquals(1, PortfolioCompare.baseIndex(listOf(1.0, 2.0, 3.0), listOf(0.0, 2.0, 3.0)))
        assertEquals(2, PortfolioCompare.baseIndex(listOf(-1.0, Double.NaN, 3.0), listOf(5.0, 5.0, 5.0)))
        assertNull(PortfolioCompare.baseIndex(listOf(0.0, 0.0), listOf(0.0, 0.0)))
        assertNull(PortfolioCompare.baseIndex(emptyList(), emptyList()))
    }

    @Test
    fun noComparisonWithoutBaseOrEnoughPoints() {
        // Durchgehend leer
        assertNull(PortfolioCompare.build(points(0.0, 0.0, 0.0), points(0.0, 0.0, 0.0)))
        // Negativ (sollte nicht vorkommen) zählt nicht als Ausgangswert
        assertNull(PortfolioCompare.build(points(-5.0, -3.0), points(-5.0, -3.0)))
        // Erst der letzte Tag hat einen Wert: nur ein Punkt ab dem Ausgangspunkt
        assertNull(PortfolioCompare.build(points(0.0, 0.0, 10.0), points(0.0, 0.0, 11.0)))
        // Zu kurz
        assertNull(PortfolioCompare.build(points(10.0), points(11.0)))
        assertNull(PortfolioCompare.build(emptyList(), emptyList()))
    }

    @Test
    fun seriesMustMatchDayByDay() {
        assertNull(PortfolioCompare.build(points(1.0, 2.0, 3.0), points(1.0, 2.0)))
        assertNull(PortfolioCompare.build(points(1.0, 2.0, from = 100L), points(1.0, 2.0, from = 101L)))
    }

    @Test
    fun valuesDroppingToZeroGiveMinusHundred() {
        val c = nn(PortfolioCompare.build(points(50.0, 0.0), points(40.0, 0.0)))
        assertEquals(-100.0, c.currency.last(), d)
        assertEquals(-100.0, c.usdt.last(), d)
        assertEquals(0.0, c.effect, d)
    }

    @Test
    fun withDailyFxRatesFlatUsdtShowsOnlyTheCurrencyEffect() {
        // 1000 USDT gleichbleibend, der Franken legt zu (0.80 → 0.76 CHF je USD)
        val today = 104L
        val series = PortfolioHistorySeries(points(1000.0, 1000.0, 1000.0, 1000.0, 1000.0), emptyList(), 0.0, 0.0, false)
        val rates = mapOf(100L to 0.80, 101L to 0.79, 102L to 0.78, 103L to 0.77)
        val converted = PortfolioHistoryFx.convert(series, rates, 0.76, today)
        val c = nn(PortfolioCompare.build(converted.series.points, series.points))
        assertEquals(0.0, c.usdt.last(), d)
        assertEquals((0.76 / 0.80 - 1.0) * 100.0, c.currency.last(), d)
        assertEquals((0.76 / 0.80 - 1.0) * 100.0, c.effect, d)
        // Der Wert in der Währung entspricht der Veränderung des konvertierten Verlaufs
        assertEquals(converted.series.changePercent!!, c.currency.last(), d)
    }

    @Test
    fun boundsCoverBothSeriesAndZero() {
        val up = nn(PortfolioCompare.build(points(100.0, 110.0, 105.0), points(100.0, 120.0, 130.0)))
        assertEquals(0.0, PortfolioCompare.bounds(up).first, d)
        assertEquals(30.0, PortfolioCompare.bounds(up).second, d)
        val down = nn(PortfolioCompare.build(points(100.0, 90.0), points(100.0, 80.0)))
        assertEquals(-20.0, PortfolioCompare.bounds(down).first, d)
        assertEquals(0.0, PortfolioCompare.bounds(down).second, d)
    }

    @Test
    fun effectiveViewFallsBackToCurrency() {
        val v = PortfolioHistoryView.entries
        // Ohne unumgerechneten Verlauf (USD gewählt oder nur heutiger Kurs): immer in der Währung
        v.forEach { assertEquals(PortfolioHistoryView.CURRENCY, PortfolioCompare.effectiveView(it, false, false)) }
        v.forEach { assertEquals(PortfolioHistoryView.CURRENCY, PortfolioCompare.effectiveView(it, false, true)) }
        assertEquals(PortfolioHistoryView.USDT, PortfolioCompare.effectiveView(PortfolioHistoryView.USDT, true, false))
        assertEquals(PortfolioHistoryView.CURRENCY, PortfolioCompare.effectiveView(PortfolioHistoryView.COMPARE, true, false))
        assertEquals(PortfolioHistoryView.COMPARE, PortfolioCompare.effectiveView(PortfolioHistoryView.COMPARE, true, true))
        assertEquals(PortfolioHistoryView.CURRENCY, PortfolioCompare.effectiveView(PortfolioHistoryView.CURRENCY, true, true))
    }

    @Test
    fun viewFromStoredName() {
        assertEquals(PortfolioHistoryView.COMPARE, PortfolioHistoryView.fromName("COMPARE"))
        assertEquals(PortfolioHistoryView.USDT, PortfolioHistoryView.fromName("USDT"))
        assertEquals(PortfolioHistoryView.CURRENCY, PortfolioHistoryView.fromName(null))
        assertEquals(PortfolioHistoryView.CURRENCY, PortfolioHistoryView.fromName("bogus"))
    }
}
