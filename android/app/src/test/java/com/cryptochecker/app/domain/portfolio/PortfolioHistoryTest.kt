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

    // ---------------- «Seit 1. Kauf» ----------------

    /** Kerzen mit gleichem Schluss 50 über [days] Tage bis heute. */
    private fun flatCloses(days: Int): Map<Long, Double> = (0 until days).associate { i -> (today - i) to 50.0 }

    @Test
    fun sinceFirstStartsAtTheFirstTransaction() {
        val first = noon(today - 800)
        val trades = listOf(buy("BTC", 1.0, first), buy("BTC", 1.0, noon(today - 10)))
        assertEquals(today - 800, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        assertFalse(PortfolioHistory.isCapped(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to flatCloses(900)), emptyMap(),
            PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
        )
        assertEquals(801, s.points.size)
        assertEquals(today - 800, s.points.first().epochDay)
        assertTrue(s.tradesInRange)
        assertFalse(s.capped)
        assertEquals(50.0, s.points.first().value, d)
        assertEquals(100.0, s.points.last().value, d)
    }

    @Test
    fun sinceFirstIsCappedAtFiveYears() {
        val first = noon(today - 4000)
        val trades = listOf(buy("BTC", 1.0, first))
        val max = PortfolioHistory.SINCE_FIRST_MAX_DAYS.toLong()
        assertEquals(1826L, max)
        assertEquals(today - max, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        assertTrue(PortfolioHistory.isCapped(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to flatCloses(2000)), emptyMap(),
            PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
        )
        assertEquals(max + 1, s.points.size.toLong())
        assertTrue(s.capped)
        // Erster Kauf genau am ältesten gezeigten Tag: nicht begrenzt
        assertFalse(PortfolioHistory.isCapped(PortfolioHistoryRange.SINCE_FIRST, noon(today - max), today, dayEnd))
        // Andere Zeiträume sind nie «begrenzt»
        assertFalse(PortfolioHistory.isCapped(PortfolioHistoryRange.YEAR, first, today, dayEnd))
    }

    @Test
    fun startDayMatchesTheFixedRanges() {
        val old = noon(today - 5000)
        assertEquals(today - 7, PortfolioHistory.startDay(PortfolioHistoryRange.WEEK, old, today, dayEnd))
        assertEquals(today - 30, PortfolioHistory.startDay(PortfolioHistoryRange.MONTH, old, today, dayEnd))
        assertEquals(today - 365, PortfolioHistory.startDay(PortfolioHistoryRange.YEAR, old, today, dayEnd))
        // Erster Kauf nach dem Beginn: dessen Tag; heute gekauft: heute
        assertEquals(today - 3, PortfolioHistory.startDay(PortfolioHistoryRange.MONTH, noon(today - 3), today, dayEnd))
        assertEquals(today, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, noon(today), today, dayEnd))
        // Kurz nach Mitternacht (lokal) zählt schon der neue Tag
        val justAfterMidnight = dayEnd(today - 2) + 1
        assertEquals(today - 1, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, justAfterMidnight, today, dayEnd))
    }

    @Test
    fun candleDaysOnlyGrowForSinceFirst() {
        val old = noon(today - 1500)
        assertEquals(366, PortfolioHistory.candleDays(PortfolioHistoryRange.WEEK, old, today, dayEnd))
        assertEquals(366, PortfolioHistory.candleDays(PortfolioHistoryRange.MONTH, old, today, dayEnd))
        assertEquals(366, PortfolioHistory.candleDays(PortfolioHistoryRange.YEAR, old, today, dayEnd))
        assertEquals(1502, PortfolioHistory.candleDays(PortfolioHistoryRange.SINCE_FIRST, old, today, dayEnd))
        // Junges Portfolio: nicht weniger als die gemeinsame Jahres-Abfrage
        assertEquals(366, PortfolioHistory.candleDays(PortfolioHistoryRange.SINCE_FIRST, noon(today - 20), today, dayEnd))
        assertEquals(366, PortfolioHistory.candleDays(PortfolioHistoryRange.SINCE_FIRST, null, today, dayEnd))
        // Begrenzt: 5 Jahre samt heute und einem Tag davor
        assertEquals(1828, PortfolioHistory.candleDays(PortfolioHistoryRange.SINCE_FIRST, noon(today - 9000), today, dayEnd))
    }

    @Test
    fun candleChunksAreContiguousAndAtMostOneThousand() {
        assertEquals(listOf((today - 365)..today), PortfolioHistory.candleChunks(366, today))
        assertEquals(listOf((today - 999)..today), PortfolioHistory.candleChunks(1000, today))
        val chunks = PortfolioHistory.candleChunks(1828, today)
        assertEquals(listOf((today - 1827)..(today - 1000), (today - 999)..today), chunks)
        val many = PortfolioHistory.candleChunks(2500, today, perRequest = 1000)
        assertEquals(3, many.size)
        assertEquals(today - 2499, many.first().first)
        assertEquals(today, many.last().last)
        many.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(many.all { it.last - it.first + 1 <= 1000 })
        assertEquals(2500L, many.sumOf { it.last - it.first + 1 })
        assertTrue(PortfolioHistory.candleChunks(0, today).isEmpty())
    }

    @Test
    fun rangeFromStoredName() {
        assertEquals(PortfolioHistoryRange.SINCE_FIRST, PortfolioHistoryRange.fromName("SINCE_FIRST"))
        assertEquals(PortfolioHistoryRange.WEEK, PortfolioHistoryRange.fromName("WEEK"))
        assertEquals(PortfolioHistoryRange.MONTH, PortfolioHistoryRange.fromName(null))
        assertEquals(PortfolioHistoryRange.MONTH, PortfolioHistoryRange.fromName("bogus"))
    }

    @Test
    fun scrubIndexPicksNearestPoint() {
        // 5 Punkte auf 0..100 (Abstand 25), Rand 2
        assertEquals(0, PortfolioHistory.scrubIndex(2f, 2f, 100f, 5))
        assertEquals(0, PortfolioHistory.scrubIndex(14f, 2f, 100f, 5))
        assertEquals(1, PortfolioHistory.scrubIndex(15f, 2f, 100f, 5))
        assertEquals(2, PortfolioHistory.scrubIndex(52f, 2f, 100f, 5))
        assertEquals(4, PortfolioHistory.scrubIndex(102f, 2f, 100f, 5))
    }

    @Test
    fun scrubIndexClampsAndHandlesEdgeCases() {
        assertEquals(0, PortfolioHistory.scrubIndex(-50f, 2f, 100f, 5))
        assertEquals(4, PortfolioHistory.scrubIndex(500f, 2f, 100f, 5))
        assertEquals(0, PortfolioHistory.scrubIndex(40f, 2f, 100f, 1))
        assertEquals(0, PortfolioHistory.scrubIndex(40f, 2f, 0f, 5))
        assertNull(PortfolioHistory.scrubIndex(40f, 2f, 100f, 0))
        assertNull(PortfolioHistory.scrubIndex(Float.NaN, 2f, 100f, 5))
    }
}
