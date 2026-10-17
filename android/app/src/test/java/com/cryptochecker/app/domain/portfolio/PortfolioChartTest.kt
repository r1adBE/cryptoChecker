package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Kerzen und Achsen des Wertverlaufs ([PortfolioCandles], [PortfolioChartAxis]) samt Eröffnung/Hoch/Tief aus [PortfolioHistory.build]. */
class PortfolioChartTest {

    private val d = 1e-9

    /** Fünf Jahre (5 × 365 + 1 Schalttag) — Beispiel für einen langen Verlauf. */
    private val fiveYearsDays = 1826
    private var nextId = 1L
    private val zone = ZoneId.of("Europe/Zurich")
    private val dayEnd: (Long) -> Long = { day ->
        LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    private val today = LocalDate.of(2026, 10, 6).toEpochDay()

    private fun noon(day: Long): Long = LocalDate.ofEpochDay(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun buy(coin: String, amount: Double, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, 1.0, time)

    private fun day(y: Int, m: Int, dd: Int) = LocalDate.of(y, m, dd).toEpochDay()

    private fun points(vararg values: Double, first: Long = today - values.size + 1) =
        values.mapIndexed { i, v -> PortfolioHistoryPoint(first + i, v) }

    // ---- Tageskerzen ----

    @Test
    fun dailyOpenIsPreviousCloseWithoutWicks() {
        val c = PortfolioCandles.daily(points(100.0, 110.0, 105.0))
        assertEquals(3, c.size)
        // Erster Tag ohne eigene Eröffnung: Eröffnung = Schluss
        assertEquals(100.0, c[0].open, d)
        assertEquals(100.0, c[1].open, d)
        assertEquals(110.0, c[1].close, d)
        assertEquals(110.0, c[1].high, d)
        assertEquals(100.0, c[1].low, d)
        assertTrue(c[1].up)
        assertEquals(110.0, c[2].open, d)
        assertFalse(c[2].up)
        assertEquals(110.0, c[2].high, d)
        assertEquals(105.0, c[2].low, d)
    }

    @Test
    fun dailyUsesOwnOpenOnFirstDayAndClampsBounds() {
        val p = listOf(
            PortfolioHistoryPoint(today - 1, 100.0, open = 90.0, high = 120.0, low = 80.0),
            // Hoch unter dem Schluss / Tief über der Eröffnung (Schranken passen nicht): begrenzt
            PortfolioHistoryPoint(today, 130.0, open = 999.0, high = 125.0, low = 101.0),
        )
        val c = PortfolioCandles.daily(p)
        assertEquals(90.0, c[0].open, d)
        assertEquals(120.0, c[0].high, d)
        assertEquals(80.0, c[0].low, d)
        // Ab dem zweiten Tag zählt der Vortag, nicht die eigene Eröffnung
        assertEquals(100.0, c[1].open, d)
        assertEquals(130.0, c[1].high, d)
        assertEquals(100.0, c[1].low, d)
    }

    // ---- Zusammenfassen ----

    @Test
    fun bucketThresholds() {
        assertEquals(PortfolioCandleBucket.DAY, PortfolioCandles.bucketFor(8))
        assertEquals(PortfolioCandleBucket.DAY, PortfolioCandles.bucketFor(31))
        assertEquals(PortfolioCandleBucket.DAY, PortfolioCandles.bucketFor(PortfolioCandles.MAX_DAILY_DAYS))
        assertEquals(PortfolioCandleBucket.WEEK, PortfolioCandles.bucketFor(PortfolioCandles.MAX_DAILY_DAYS + 1))
        assertEquals(PortfolioCandleBucket.WEEK, PortfolioCandles.bucketFor(366))
        assertEquals(PortfolioCandleBucket.MONTH, PortfolioCandles.bucketFor(PortfolioCandles.MAX_WEEKLY_DAYS + 1))
        assertEquals(PortfolioCandleBucket.MONTH, PortfolioCandles.bucketFor(fiveYearsDays))
        assertEquals(PortfolioCandleBucket.MONTH, PortfolioCandles.bucketFor(3400))
    }

    @Test
    fun bucketStartIsMondayOrFirstOfMonth() {
        // 6.10.2026 ist ein Dienstag
        assertEquals(day(2026, 10, 5), PortfolioCandles.bucketStart(day(2026, 10, 6), PortfolioCandleBucket.WEEK))
        assertEquals(day(2026, 10, 5), PortfolioCandles.bucketStart(day(2026, 10, 5), PortfolioCandleBucket.WEEK))
        assertEquals(day(2026, 10, 5), PortfolioCandles.bucketStart(day(2026, 10, 11), PortfolioCandleBucket.WEEK))
        assertEquals(day(1969, 12, 29), PortfolioCandles.bucketStart(0, PortfolioCandleBucket.WEEK))
        assertEquals(day(2026, 10, 1), PortfolioCandles.bucketStart(day(2026, 10, 31), PortfolioCandleBucket.MONTH))
        assertEquals(42L, PortfolioCandles.bucketStart(42, PortfolioCandleBucket.DAY))
    }

    @Test
    fun aggregateWeeks() {
        // Mi 30.9. bis Di 6.10.2026: zwei Wochen (30.9.–4.10., 5.–6.10.)
        val daily = PortfolioCandles.daily(points(10.0, 12.0, 8.0, 9.0, 11.0, 15.0, 14.0, first = day(2026, 9, 30)))
        val weeks = PortfolioCandles.aggregate(daily, PortfolioCandleBucket.WEEK)
        assertEquals(2, weeks.size)
        assertEquals(day(2026, 9, 30), weeks[0].firstDay)
        assertEquals(day(2026, 10, 4), weeks[0].lastDay)
        assertEquals(10.0, weeks[0].open, d)
        assertEquals(11.0, weeks[0].close, d)
        assertEquals(12.0, weeks[0].high, d)
        assertEquals(8.0, weeks[0].low, d)
        assertEquals(day(2026, 10, 5), weeks[1].firstDay)
        assertEquals(11.0, weeks[1].open, d)
        assertEquals(14.0, weeks[1].close, d)
        assertEquals(15.0, weeks[1].high, d)
        assertEquals(11.0, weeks[1].low, d)
    }

    @Test
    fun buildCapsCandleCount() {
        val year = points(*DoubleArray(366) { 100.0 + it })
        val weekly = PortfolioCandles.build(year)
        assertTrue(weekly.size in 52..54)
        assertEquals(year.first().epochDay, weekly.first().firstDay)
        assertEquals(today, weekly.last().lastDay)
        assertEquals(year.last().value, weekly.last().close, d)

        val five = points(*DoubleArray(fiveYearsDays) { 50.0 + it % 17 })
        val monthly = PortfolioCandles.build(five)
        assertTrue(monthly.size in 60..62)
        // Lückenlos: jede Kerze beginnt am Tag nach der vorigen
        for (i in 1 until monthly.size) assertEquals(monthly[i - 1].lastDay + 1, monthly[i].firstDay)

        val month = points(*DoubleArray(31) { 1.0 + it })
        assertEquals(31, PortfolioCandles.build(month).size)
        assertTrue(PortfolioCandles.build(emptyList()).isEmpty())
    }

    // ---- Eröffnung/Hoch/Tief aus den Coin-Kerzen ----

    private val closes = mapOf("BTC" to mapOf(today - 2 to 100.0, today - 1 to 110.0, today to 120.0))
    private val ranges = mapOf(
        "BTC" to mapOf(
            today - 2 to PortfolioDayRange(95.0, 105.0, 90.0),
            today - 1 to PortfolioDayRange(100.0, 118.0, 99.0),
            today to PortfolioDayRange(110.0, 125.0, 108.0),
        )
    )
    private val trades by lazy { listOf(buy("BTC", 2.0, noon(today - 2)), buy("USDT", 50.0, noon(today - 2))) }

    @Test
    fun buildSumsHoldingsTimesCoinOhlc() {
        val s = PortfolioHistory.build(
            trades, closes, mapOf("BTC" to 121.0), PortfolioHistoryRange.MONTH, today, dayEnd, ranges = ranges,
        )
        val p = s.points
        assertEquals(3, p.size)
        // USDT zählt mit 1 in allen dreien
        assertEquals(250.0, p[0].value, d)
        assertEquals(240.0, p[0].open!!, d)
        assertEquals(260.0, p[0].high!!, d)
        assertEquals(230.0, p[0].low!!, d)
        assertEquals(286.0, p[1].high!!, d)
        assertEquals(248.0, p[1].low!!, d)
        // Heute: aktueller Kurs als Wert; Hoch/Tief schliessen ihn ein
        assertEquals(292.0, p[2].value, d)
        assertEquals(270.0, p[2].open!!, d)
        assertEquals(300.0, p[2].high!!, d)
        assertEquals(266.0, p[2].low!!, d)

        val c = PortfolioCandles.daily(p)
        assertEquals(240.0, c[0].open, d)
        assertEquals(250.0, c[1].open, d)
        assertEquals(248.0, c[1].low, d)
        assertEquals(270.0, c[2].open, d)
        assertEquals(292.0, c[2].close, d)
    }

    @Test
    fun buildWithoutRangesKeepsPlainPoints() {
        val plain = PortfolioHistory.build(trades, closes, mapOf("BTC" to 121.0), PortfolioHistoryRange.MONTH, today, dayEnd)
        assertTrue(plain.points.all { it.open == null && it.high == null && it.low == null })
        // Tag ohne Coin-Kerze: kein Hoch/Tief (nur fortgeschriebener Schluss)
        val gap = ranges.mapValues { (_, m) -> m - (today - 1) }
        val s = PortfolioHistory.build(
            trades, closes, mapOf("BTC" to 121.0), PortfolioHistoryRange.MONTH, today, dayEnd, ranges = gap,
        )
        assertNull(s.points[1].high)
        assertEquals(plain.points.map { it.value }, s.points.map { it.value })
    }

    @Test
    fun fxConvertsOhlcWithSameDailyRate() {
        val s = PortfolioHistory.build(
            trades, closes, mapOf("BTC" to 121.0), PortfolioHistoryRange.MONTH, today, dayEnd, ranges = ranges,
        )
        val r = PortfolioHistoryFx.convert(s, mapOf(today - 2 to 0.9, today - 1 to 0.8), 0.5, today)
        val p = r.series.points
        assertEquals(250.0 * 0.9, p[0].value, d)
        assertEquals(240.0 * 0.9, p[0].open!!, d)
        assertEquals(260.0 * 0.9, p[0].high!!, d)
        assertEquals(230.0 * 0.9, p[0].low!!, d)
        assertEquals(286.0 * 0.8, p[1].high!!, d)
        assertEquals(300.0 * 0.5, p[2].high!!, d)
    }

    // ---- Achsen ----

    @Test
    fun gridUnitsByLength() {
        assertEquals(PortfolioGridUnit.DAY, PortfolioChartAxis.gridUnit(8))
        assertEquals(PortfolioGridUnit.WEEK, PortfolioChartAxis.gridUnit(31))
        assertEquals(PortfolioGridUnit.MONTH, PortfolioChartAxis.gridUnit(366))
        assertEquals(PortfolioGridUnit.QUARTER, PortfolioChartAxis.gridUnit(800))
        assertEquals(PortfolioGridUnit.YEAR, PortfolioChartAxis.gridUnit(1826))
    }

    @Test
    fun gridDaysOnCalendarBoundaries() {
        // 7 T: jeder Tag nach dem ersten
        assertEquals((today - 6..today).toList(), PortfolioChartAxis.gridDays(today - 7, today))
        // 30 T: Montage strikt nach dem Beginn (6.9.2026 = Sonntag)
        assertEquals(
            listOf(day(2026, 9, 7), day(2026, 9, 14), day(2026, 9, 21), day(2026, 9, 28), day(2026, 10, 5)),
            PortfolioChartAxis.gridDays(day(2026, 9, 6), today),
        )
        // 1 J: Monatserste
        val year = PortfolioChartAxis.gridDays(today - 365, today)
        assertEquals(12, year.size)
        assertTrue(year.all { LocalDate.ofEpochDay(it).dayOfMonth == 1 })
        // Lang: Quartale bzw. Jahre
        val quarters = PortfolioChartAxis.gridDays(day(2024, 5, 10), today)
        assertEquals(day(2024, 7, 1), quarters.first())
        assertTrue(quarters.all { LocalDate.ofEpochDay(it).monthValue in listOf(1, 4, 7, 10) })
        assertEquals(
            listOf(day(2022, 1, 1), day(2023, 1, 1), day(2024, 1, 1), day(2025, 1, 1), day(2026, 1, 1)),
            PortfolioChartAxis.gridDays(day(2021, 10, 7), today),
        )
        assertTrue(PortfolioChartAxis.gridDays(today, today).isEmpty())
    }

    @Test
    fun slotPositionWithinSpans() {
        val spans = listOf(10L..16L, 17L..23L, 24L..24L)
        assertEquals(0.0, PortfolioChartAxis.slotPosition(spans, 10)!!, d)
        assertEquals(3.0 / 7.0, PortfolioChartAxis.slotPosition(spans, 13)!!, d)
        assertEquals(1.0, PortfolioChartAxis.slotPosition(spans, 17)!!, d)
        assertEquals(2.0, PortfolioChartAxis.slotPosition(spans, 24)!!, d)
        assertNull(PortfolioChartAxis.slotPosition(spans, 9))
        assertNull(PortfolioChartAxis.slotPosition(spans, 25))
        assertEquals(1, PortfolioChartAxis.slotOfDay(spans, 20))
    }

    @Test
    fun maxIndexAndLevels() {
        assertEquals(1, PortfolioChartAxis.maxIndex(listOf(1.0, 5.0, 5.0, 2.0)))
        assertEquals(2, PortfolioChartAxis.maxIndex(listOf(Double.NaN, 1.0, 3.0)))
        assertNull(PortfolioChartAxis.maxIndex(emptyList()))
        assertEquals(Triple(10.0, 15.0, 20.0), PortfolioChartAxis.levels(10.0, 20.0))
        val c = PortfolioCandles.daily(points(100.0, 120.0, 90.0))
        assertEquals(90.0 to 120.0, PortfolioChartAxis.candleBounds(c))
        assertNull(PortfolioChartAxis.candleBounds(emptyList()))
    }
}
