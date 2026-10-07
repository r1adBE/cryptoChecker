package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PortfolioHistoryTest {

    private var nextId = 1L
    private val d = 1e-9
    private val zone = ZoneId.of("Europe/Zurich")
    private val dayEnd: (Long) -> Long = { day ->
        LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    /** Mittag (lokal) des Tags [day]. */
    private fun noon(day: Long): Long =
        LocalDate.ofEpochDay(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun buy(coin: String, amount: Double, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, 1.0, time)

    private fun sell(coin: String, amount: Double, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.SELL, amount, 1.0, time)

    private val today = LocalDate.of(2026, 10, 6).toEpochDay()

    /** Kerzen mit Schluss = Tag − today + 100 (heute 100, gestern 99 …). */
    private fun linearCloses(days: Int): Map<Long, Double> =
        (0 until days).associate { i -> (today - i) to (100.0 - i) }

    @Test
    fun timelineCapsOversell() {
        val trades = listOf(buy("BTC", 1.0, 10), sell("BTC", 3.0, 20), buy("btc", 0.5, 30))
        val t = PortfolioHistory.holdingsTimeline("BTC", trades)
        assertEquals(listOf(10L to 1.0, 20L to 0.0, 30L to 0.5), t)
        assertEquals(0.0, PortfolioHistory.holdingsAt(t, 9), d)
        assertEquals(1.0, PortfolioHistory.holdingsAt(t, 19), d)
        assertEquals(0.5, PortfolioHistory.holdingsAt(t, 30), d)
    }

    @Test
    fun startsAtFirstTransactionDay() {
        val trades = listOf(buy("BTC", 2.0, noon(today - 3)))
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.MONTH, today, dayEnd,
        )
        assertEquals(4, s.points.size)
        assertEquals(today - 3, s.points.first().epochDay)
        assertEquals(2.0 * 97.0, s.points.first().value, d)
        // Heute zählt der aktuelle Kurs
        assertEquals(2.0 * 110.0, s.points.last().value, d)
        assertEquals(220.0 - 194.0, s.change!!, d)
        assertEquals((220.0 - 194.0) / 194.0 * 100.0, s.changePercent!!, d)
        assertFalse(s.tradesInRange)
        assertTrue(s.skipped.isEmpty())
    }

    @Test
    fun rangeStartWhenOlderTransactions() {
        val trades = listOf(buy("ETH", 1.0, noon(today - 500)))
        val s = PortfolioHistory.build(
            trades, mapOf("ETH" to linearCloses(366)), emptyMap(),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(8, s.points.size)                    // 7 Tage zurück bis heute
        assertEquals(today - 7, s.points.first().epochDay)
        // Ohne aktuellen Kurs: letzter Schluss (heute = 100)
        assertEquals(100.0, s.points.last().value, d)
        val year = PortfolioHistory.build(
            trades, mapOf("ETH" to linearCloses(366)), emptyMap(),
            PortfolioHistoryRange.YEAR, today, dayEnd,
        )
        assertEquals(366, year.points.size)
    }

    @Test
    fun holdingsChangeOverTimeAndTradesFlagged() {
        val trades = listOf(
            buy("BTC", 1.0, noon(today - 5)),
            buy("BTC", 1.0, noon(today - 2)),
            sell("BTC", 0.5, noon(today - 1)),
        )
        val closes = mapOf("BTC" to (0..10).associate { (today - it) to 10.0 })
        val s = PortfolioHistory.build(trades, closes, mapOf("BTC" to 10.0), PortfolioHistoryRange.WEEK, today, dayEnd)
        assertEquals(listOf(10.0, 10.0, 10.0, 20.0, 15.0, 15.0), s.points.map { it.value })
        assertTrue(s.tradesInRange)
    }

    @Test
    fun coinsWithoutPairAreSkippedStablesCount() {
        val trades = listOf(
            buy("BTC", 1.0, noon(today - 2)),
            buy("XYZ", 100.0, noon(today - 2)),
            buy("USDT", 50.0, noon(today - 2)),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(10), "XYZ" to emptyMap()), mapOf("XYZ" to 3.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(listOf("XYZ"), s.skipped)
        assertEquals(98.0 + 50.0, s.points.first().value, d)
    }

    @Test
    fun soldBeforeRangeIsNotSkipped() {
        val trades = listOf(
            buy("XYZ", 1.0, noon(today - 100)),
            sell("XYZ", 1.0, noon(today - 90)),
            buy("BTC", 1.0, noon(today - 100)),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(10)), emptyMap(),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.skipped.isEmpty())
    }

    @Test
    fun onlyUnpricedCoinsGiveNoChart() {
        val trades = listOf(buy("XYZ", 1.0, noon(today - 3)))
        val s = PortfolioHistory.build(trades, emptyMap(), emptyMap(), PortfolioHistoryRange.WEEK, today, dayEnd)
        assertTrue(s.points.isEmpty())
        assertFalse(s.hasChart)
        assertEquals(listOf("XYZ"), s.skipped)
        assertNull(s.change)
    }

    @Test
    fun gapsUseLastKnownCloseAndFxApplies() {
        val trades = listOf(buy("SOL", 2.0, noon(today - 4)))
        val closes = mapOf("SOL" to mapOf(today - 4 to 10.0, today - 1 to 20.0))
        val s = PortfolioHistory.build(
            trades, closes, emptyMap(), PortfolioHistoryRange.WEEK, today, dayEnd, fxRate = 0.5,
        )
        assertEquals(listOf(10.0, 10.0, 10.0, 20.0, 20.0), s.points.map { it.value })
    }

    @Test
    fun beforeFirstCandleCoinDoesNotCount() {
        val trades = listOf(buy("NEW", 1.0, noon(today - 3)))
        val closes = mapOf("NEW" to mapOf(today - 1 to 5.0))
        val s = PortfolioHistory.build(trades, closes, emptyMap(), PortfolioHistoryRange.WEEK, today, dayEnd)
        assertEquals(listOf(0.0, 0.0, 5.0, 5.0), s.points.map { it.value })
        assertNull(s.changePercent)                       // Ausgangswert 0
        assertEquals(5.0, s.change!!, d)
    }

    @Test
    fun firstTradeTodayGivesSinglePoint() {
        val trades = listOf(buy("BTC", 1.0, noon(today)))
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(5)), mapOf("BTC" to 101.0),
            PortfolioHistoryRange.MONTH, today, dayEnd,
        )
        assertEquals(1, s.points.size)
        assertFalse(s.hasChart)
        assertNull(s.change)
    }

    @Test
    fun lateEveningTradeBelongsToLocalDay() {
        // 23:30 Zürich = 21:30/22:30 UTC — gehört zum lokalen Tag, nicht zum nächsten
        val t = LocalDate.ofEpochDay(today - 2).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val s = PortfolioHistory.build(
            listOf(buy("BTC", 1.0, t)), mapOf("BTC" to linearCloses(10)), emptyMap(),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(today - 2, s.points.first().epochDay)
    }

    @Test
    fun closeLookup() {
        val sorted = listOf(1L to 1.0, 5L to 5.0, 9L to 9.0)
        assertNull(PortfolioHistory.closeOnOrBefore(sorted, 0))
        assertEquals(1.0, PortfolioHistory.closeOnOrBefore(sorted, 4)!!, d)
        assertEquals(5.0, PortfolioHistory.closeOnOrBefore(sorted, 5)!!, d)
        assertEquals(9.0, PortfolioHistory.closeOnOrBefore(sorted, 100)!!, d)
        assertEquals(0L, PortfolioHistory.epochDayOfUtcMillis(86_399_999L))
        assertEquals(-1L, PortfolioHistory.epochDayOfUtcMillis(-1L))
    }
}
