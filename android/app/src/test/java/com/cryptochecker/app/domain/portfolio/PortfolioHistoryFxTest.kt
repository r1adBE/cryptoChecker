package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Wertverlauf in CHF & Co. mit Devisen-Tageskursen ([PortfolioHistoryFx]). */
class PortfolioHistoryFxTest {

    private val d = 1e-9
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()

    // Fr 2.10. bis Di 6.10.2026 (heute); Sa/So ohne EZB-Kurs
    private val today = day("2026-10-06")
    private val flatUsd = PortfolioHistorySeries(
        points = listOf("2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06")
            .map { PortfolioHistoryPoint(day(it), 1000.0) },
        skipped = emptyList(),
        change = 0.0,
        changePercent = 0.0,
        tradesInRange = false,
    )
    private val rates = mapOf(
        day("2026-10-01") to 0.79,
        day("2026-10-02") to 0.80,
        day("2026-10-05") to 0.81,
    )

    @Test
    fun weekendsCarryPreviousBusinessDay() {
        val sorted = rates.entries.sortedBy { it.key }.map { it.key to it.value }
        assertEquals(0.80, PortfolioHistoryFx.rateOn(sorted, day("2026-10-03"))!!, d)
        assertEquals(0.80, PortfolioHistoryFx.rateOn(sorted, day("2026-10-04"))!!, d)
        assertEquals(0.81, PortfolioHistoryFx.rateOn(sorted, day("2026-10-05"))!!, d)
        assertNull(PortfolioHistoryFx.rateOn(sorted, day("2026-09-30")))
    }

    @Test
    fun flatUsdShowsCurrencyMove() {
        val r = PortfolioHistoryFx.convert(flatUsd, rates, currentRate = 0.82, todayEpochDay = today)
        assertFalse(r.approximate)
        assertEquals(listOf(800.0, 800.0, 800.0, 810.0, 820.0), r.series.points.map { Math.round(it.value * 1e6) / 1e6 })
        // BTC flach in USD, Franken bewegt sich → Veränderung ist nicht mehr ~0
        assertEquals(20.0, r.series.change!!, 1e-6)
        assertEquals(2.5, r.series.changePercent!!, 1e-6)
    }

    @Test
    fun missingSeriesFallsBackToTodayAndFlags() {
        for (missing in listOf(null, emptyMap<Long, Double>())) {
            val r = PortfolioHistoryFx.convert(flatUsd, missing, currentRate = 0.82, todayEpochDay = today)
            assertTrue(r.approximate)
            assertTrue(r.series.points.all { kotlin.math.abs(it.value - 820.0) < 1e-6 })
            assertEquals(0.0, r.series.change!!, 1e-6)
        }
    }

    @Test
    fun dayBeforeFirstRateUsesFirst() {
        val r = PortfolioHistoryFx.convert(flatUsd, mapOf(day("2026-10-05") to 0.81), 0.82, today)
        assertEquals(810.0, r.series.points.first().value, 1e-6)
    }

    @Test
    fun ratesByDayParsesAndDerivesBgn() {
        val byDate = mapOf(
            "2025-12-31" to mapOf("BGN" to 1.70, "EUR" to 0.86),
            "2026-01-02" to mapOf("EUR" to 0.85),
            "kaputt" to mapOf("BGN" to 1.0),
            "2026-01-05" to mapOf("BGN" to -1.0),
        )
        val bgn = PortfolioHistoryFx.ratesByDay("bgn", byDate)
        assertEquals(1.70, bgn[day("2025-12-31")]!!, d)
        assertEquals(0.85 * 1.95583, bgn[day("2026-01-02")]!!, d)
        assertFalse(day("2026-01-05") in bgn)
        assertEquals(2, PortfolioHistoryFx.ratesByDay("EUR", byDate).size)
        assertEquals(listOf("BGN", "EUR"), PortfolioHistoryFx.requestCurrencies("BGN"))
        assertEquals(listOf("CHF"), PortfolioHistoryFx.requestCurrencies(" chf "))
    }

    @Test
    fun requestStartsLeadDaysEarlier() {
        val (from, to) = PortfolioHistoryFx.requestRange(30, LocalDate.of(2026, 10, 6))
        assertEquals(LocalDate.of(2026, 8, 30), from)
        assertEquals(LocalDate.of(2026, 10, 6), to)
    }
}
