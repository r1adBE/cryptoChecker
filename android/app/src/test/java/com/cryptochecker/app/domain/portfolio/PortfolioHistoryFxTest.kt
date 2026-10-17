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

    /** K-1: USDT zum USD-Kurs, Verlauf in der Anzeigewährung samt Devisenbewegung. */
    @Test
    fun fxLabelFlags() {
        assertTrue(PortfolioHistoryFx.convertsUsdtAsUsd("chf"))
        assertFalse(PortfolioHistoryFx.convertsUsdtAsUsd("USD"))
        assertFalse(PortfolioHistoryFx.convertsUsdtAsUsd("USDT"))
        assertTrue(PortfolioHistoryFx.includesFxMovement("CHF", approximate = false))
        assertFalse(PortfolioHistoryFx.includesFxMovement("CHF", approximate = true))
        assertFalse(PortfolioHistoryFx.includesFxMovement("USD", approximate = false))
        val series = PortfolioHistorySeries(listOf(PortfolioHistoryPoint(1, 100.0), PortfolioHistoryPoint(2, 100.0)), emptyList(), 0.0, 0.0, false)
        assertTrue(PortfolioHistoryFx.convert(series, mapOf(1L to 0.9), 0.8, 2).includesFx)
        assertFalse(PortfolioHistoryFx.convert(series, null, 0.8, 2).includesFx)
    }

    /**
     * «7 T» im Kopf in der Anzeigewährung = Gewinn/Verlust der umgerechneten Kurve (gleiche Tageskurse): Wert am Anfang
     * zum Kurs des Stichtags, Kauf zum Kurs seines Tags, Wert jetzt zum heutigen Kurs. Gegenstück: `PeriodFxTests.swift`.
     */
    @Test
    fun weekHeaderMatchesConvertedCurve() {
        val today = 20_000L
        fun t(day: Long) = day * 86_400_000L + 43_200_000L
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, t(19_980)),
            PortfolioTrade(2, "BTC", PortfolioTxType.BUY, 0.5, 110.0, t(19_996)),
        )
        val closes = mapOf("BTC" to (19_970L..19_999L).associateWith { if (it <= 19_993L) 100.0 else 105.0 })
        val live = mapOf("BTC" to 120.0)
        val fxRates = (19_960L..19_999L).associateWith { if (it <= 19_993L) 0.9 else 0.95 }
        val fx = PortfolioHistoryFx.rateFunction(fxRates, 1.25, today)

        val usdt = PortfolioPeriodChange.gain(trades, live, closes, today - PortfolioPeriodChange.DAYS)!!
        assertEquals(180.0 - 100.0 - 55.0, usdt.amountUsd, d)
        val header = PortfolioPeriodChange.gain(trades, live, closes, today - PortfolioPeriodChange.DAYS, fx = fx)!!
        // 1.5 × 120 × 1.25 − 1 × 100 × 0.9 − 0.5 × 110 × 0.95
        assertEquals(225.0 - 90.0 - 52.25, header.amountUsd, d)
        assertEquals((225.0 - 90.0 - 52.25) / (90.0 + 52.25) * 100.0, header.percent!!, d)

        val series = PortfolioHistory.build(trades, closes, live, PortfolioHistoryRange.WEEK, today)
        val curve = PortfolioHistoryFx.convert(series, fxRates, 1.25, today).series
        assertEquals(curve.pnlChange!!, header.amountUsd, d)
        // Karte zeigt Beträge × heutiger Kurs: Gegenwert so, dass genau die Zahl der Kurve erscheint
        assertEquals(curve.pnlChange!!, PortfolioHistoryFx.forCard(header, 1.25).amountUsd * 1.25, d)
    }

    /**
     * USDT zum Marktkurs (Owner-Entscheid Okt. 2026): jeder Tag × USDT/USD-Schluss DES Tags, heute × Live-Kurs; ein Tag
     * ohne Schluss rechnet mit 1 und markiert die Näherung ([PortfolioHistoryConverted.usdtApproximate]). Wie iOS.
     */
    @Test
    fun usdtAtMarketScalesEachDayAndFlagsMissingClose() {
        val usdt = UsdtUsdRates(
            live = 0.98,
            daily = mapOf(day("2026-10-02") to 0.97, day("2026-10-03") to 0.97, day("2026-10-04") to 0.97),
        )
        val r = PortfolioHistoryFx.convert(flatUsd, rates, currentRate = 0.82, todayEpochDay = today, usdt = usdt)
        assertFalse(r.approximate)
        assertTrue(r.usdtApproximate)
        // 800 × 0.97 (Fr–So), 810 × 1 (Mo ohne Schluss: Näherung), heute 820 × 0.98
        assertEquals(listOf(776.0, 776.0, 776.0, 810.0, 803.6), r.series.points.map { Math.round(it.value * 1e6) / 1e6 })
    }

    @Test
    fun usdtAt097LowersUsdValueByThreePercentWithAllCloses() {
        val usdt = UsdtUsdRates(0.97, (day("2026-10-02")..day("2026-10-05")).associateWith { 0.97 })
        val usd = PortfolioHistoryFx.usdParity(day("2026-10-02"), today)
        val r = PortfolioHistoryFx.convert(flatUsd, usd, currentRate = 1.0, todayEpochDay = today, usdt = usdt)
        assertFalse(r.approximate)
        assertFalse(r.usdtApproximate)
        assertTrue(r.series.points.all { kotlin.math.abs(it.value - 970.0) < 1e-6 })
        // In USD wird mit USDT zum Marktkurs umgerechnet, in USDT nie
        assertTrue(PortfolioHistoryFx.convertsUsdt("USD", usdtMarket = true))
        assertFalse(PortfolioHistoryFx.convertsUsdt("USD", usdtMarket = false))
        assertFalse(PortfolioHistoryFx.convertsUsdt(" usdt ", usdtMarket = true))
        assertTrue(PortfolioHistoryFx.convertsUsdt("CHF", usdtMarket = false))
    }

    @Test
    fun offlineUsdtFallsBackToOneAndFlags() {
        val r = PortfolioHistoryFx.convert(flatUsd, rates, currentRate = 0.82, todayEpochDay = today, usdt = UsdtUsdRates(live = null))
        assertTrue(r.usdtApproximate)
        // Werte bleiben (nie leer): wie mit USDT = 1
        assertEquals(listOf(800.0, 800.0, 800.0, 810.0, 820.0), r.series.points.map { Math.round(it.value * 1e6) / 1e6 })
        // Ohne USDT/USD-Kurse (bisherige Rechnung) keine Markierung
        assertFalse(PortfolioHistoryFx.convert(flatUsd, rates, currentRate = 0.82, todayEpochDay = today).usdtApproximate)
    }

    /** «7 T» im Kopf rechnet mit denselben USDT/USD-Tageskursen wie die Kurve ([PortfolioHistoryFx.rateFunction] `usdt`). */
    @Test
    fun rateFunctionMultipliesUsdtUsdOfTheDay() {
        val usdt = UsdtUsdRates(0.98, mapOf(day("2026-10-02") to 0.97))
        val f = PortfolioHistoryFx.rateFunction(rates, 0.82, today, usdt)
        assertEquals(0.80 * 0.97, f(day("2026-10-02")), d)
        assertEquals(0.80, f(day("2026-10-03")), d)
        assertEquals(0.82 * 0.98, f(today), d)
    }
}
