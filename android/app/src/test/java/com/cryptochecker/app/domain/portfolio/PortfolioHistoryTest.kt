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
            trades, mapOf("ETH" to linearCloses(366)), mapOf("ETH" to 100.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(8, s.points.size)                    // 7 Tage zurück bis heute
        assertEquals(today - 7, s.points.first().epochDay)
        assertEquals(100.0, s.points.last().value, d)
        val year = PortfolioHistory.build(
            trades, mapOf("ETH" to linearCloses(366)), mapOf("ETH" to 100.0),
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
            trades, mapOf("BTC" to linearCloses(10), "XYZ" to emptyMap()), mapOf("BTC" to 100.0, "XYZ" to 3.0),
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
            trades, mapOf("BTC" to linearCloses(10)), mapOf("BTC" to 100.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.skipped.isEmpty())
    }

    @Test
    fun heldCoinWithoutLivePriceIsSkipped() {
        // Strenge Identitätsprüfung: ohne aktuellen Kurs ist der Token nicht bestätigt → «ohne ETH»
        val trades = listOf(buy("ETH", 1.0, noon(today - 500)), buy("BTC", 1.0, noon(today - 500)))
        val s = PortfolioHistory.build(
            trades, mapOf("ETH" to linearCloses(366), "BTC" to linearCloses(366)), mapOf("BTC" to 100.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(listOf("ETH"), s.skipped)
        assertEquals(93.0, s.points.first().value, d)     // nur BTC
        assertEquals(100.0, s.points.last().value, d)
        // Nur ein Coin, ohne Kurs: kein Verlauf statt eines ungeprüften
        val alone = PortfolioHistory.build(
            listOf(buy("ETH", 1.0, noon(today - 500))), mapOf("ETH" to linearCloses(366)), emptyMap(),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(listOf("ETH"), alone.skipped)
        assertTrue(alone.points.isEmpty())
    }

    @Test
    fun soldWithinRangeWithoutLivePriceStillCounts() {
        // Heute kein Bestand: kein aktueller Kurs zum Vergleichen, die Reihe bleibt (heute 0)
        val trades = listOf(
            buy("XYZ", 1.0, noon(today - 100)),
            sell("XYZ", 1.0, noon(today - 2)),
            buy("BTC", 1.0, noon(today - 100)),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(10), "XYZ" to linearCloses(10)), mapOf("BTC" to 100.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.skipped.isEmpty())
        assertEquals(93.0 + 93.0, s.points.first().value, d)
        assertEquals(100.0, s.points.last().value, d)
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
            trades, closes, mapOf("SOL" to 20.0), PortfolioHistoryRange.WEEK, today, dayEnd, fxRate = 0.5,
        )
        assertEquals(listOf(10.0, 10.0, 10.0, 20.0, 20.0), s.points.map { it.value })
    }

    @Test
    fun beforeFirstCandleCoinDoesNotCount() {
        val trades = listOf(buy("NEW", 1.0, noon(today - 3)))
        val closes = mapOf("NEW" to mapOf(today - 1 to 5.0))
        val s = PortfolioHistory.build(trades, closes, mapOf("NEW" to 5.0), PortfolioHistoryRange.WEEK, today, dayEnd)
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
            listOf(buy("BTC", 1.0, t)), mapOf("BTC" to linearCloses(10)), mapOf("BTC" to 100.0),
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
            trades, mapOf("BTC" to flatCloses(900)), mapOf("BTC" to 50.0),
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
    fun sinceFirstIsNotLimitedToFiveYears() {
        // Erster Kauf vor gut 7 Jahren: ganz gezeigt, nicht mehr auf 5 Jahre gekappt
        val first = noon(today - 2600)
        val trades = listOf(buy("BTC", 1.0, first))
        assertEquals(today - 2600, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        assertFalse(PortfolioHistory.isCapped(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to flatCloses(2700)), mapOf("BTC" to 50.0),
            PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
        )
        assertEquals(2601, s.points.size)
        assertFalse(s.capped)
        assertEquals(2602, PortfolioHistory.candleDays(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
    }

    @Test
    fun sinceFirstStartsNoEarlierThanTheFirstPriceData() {
        val dataStart = PortfolioHistory.DATA_START_EPOCH_DAY
        assertEquals(LocalDate.of(2017, 8, 17).toEpochDay(), dataStart)
        val first = noon(today - 4000)
        val trades = listOf(buy("BTC", 1.0, first))
        assertEquals(dataStart, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        assertTrue(PortfolioHistory.isCapped(PortfolioHistoryRange.SINCE_FIRST, first, today, dayEnd))
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to flatCloses(4100)), mapOf("BTC" to 50.0),
            PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
        )
        assertEquals(today - dataStart + 1, s.points.size.toLong())
        assertTrue(s.capped)
        // Erster Kauf genau am ersten Tag mit Kursdaten: nicht begrenzt
        assertFalse(PortfolioHistory.isCapped(PortfolioHistoryRange.SINCE_FIRST, noon(dataStart), today, dayEnd))
        assertEquals(dataStart, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, noon(dataStart), today, dayEnd))
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
        // EINE Tagesgrenze (UTC): kurz nach Mitternacht UTC zählt schon der neue Tag …
        val justAfterUtcMidnight = PortfolioHistory.utcDayEnd(today - 2) + 1
        assertEquals(today - 1, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, justAfterUtcMidnight, today, dayEnd))
        // … kurz nach Mitternacht Zürich-Sommerzeit (22:00 UTC) noch der UTC-Vortag, wie bei den Tageskerzen
        val justAfterLocalMidnight = dayEnd(today - 2) + 1
        assertEquals(today - 2, PortfolioHistory.startDay(PortfolioHistoryRange.SINCE_FIRST, justAfterLocalMidnight, today, dayEnd))
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
        // Nur durch den ersten Tag mit Kursdaten begrenzt: ab da samt heute und einem Tag davor
        val sinceDataStart = (today - PortfolioHistory.DATA_START_EPOCH_DAY + 2).toInt()
        assertEquals(sinceDataStart, PortfolioHistory.candleDays(PortfolioHistoryRange.SINCE_FIRST, noon(today - 9000), today, dayEnd))
        // … in höchstens vier Abfragen zu 1000 Kerzen (Stand 2026)
        assertEquals(4, PortfolioHistory.candleChunks(sinceDataStart, today).size)
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

    @Test
    fun netChangeTakesOutBuysInRange() {
        val trades = listOf(
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, 50.0, noon(today - 500)),
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, 97.0, noon(today - 3), 1.0, PortfolioFeeUnit.QUOTE),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.tradesInRange)
        // Brutto: 93 → 220 (samt Kauf); netto: Kauf zu 97 + 1 USDT Gebühr herausgerechnet
        assertEquals(127.0, s.change!!, d)
        assertEquals(listOf(PortfolioCashFlow(today - 3, 98.0)), s.cashFlows)
        assertEquals(29.0, s.netChange!!, d)
        assertEquals(29.0 / (93.0 + 98.0) * 100.0, s.netChangePercent!!, d)

        // Devisen-Tageskurse: Zufluss mit dem Kurs seines Tags
        val rates = (today - 10 until today).associateWith { if (it == today - 3) 3.0 else 2.0 }
        val fx = PortfolioHistoryFx.convert(s, rates, 2.0, today).series
        assertEquals(listOf(PortfolioCashFlow(today - 3, 294.0)), fx.cashFlows)
        assertEquals(440.0 - 186.0 - 294.0, fx.netChange!!, d)
    }

    @Test
    fun netChangeUnknownWithUnpricedTradeAndEqualWithoutTrades() {
        val unpriced = listOf(
            buy("BTC", 1.0, noon(today - 500)),
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, null, noon(today - 3)),
        )
        val s = PortfolioHistory.build(
            unpriced, mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.tradesInRange)
        assertNull(s.cashFlows)
        assertNull(s.netChange)
        // Ohne Transaktion im Zeitraum: netto = brutto
        val plain = PortfolioHistory.build(
            listOf(buy("BTC", 1.0, noon(today - 500))), mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(plain.change!!, plain.netChange!!, d)
        assertEquals(plain.changePercent!!, plain.netChangePercent!!, d)
    }

    @Test
    fun pnlCurveIgnoresBuysAndStartsAtZeroInRange() {
        val trades = listOf(
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, 50.0, noon(today - 500)),
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, 97.0, noon(today - 3), 1.0, PortfolioFeeUnit.QUOTE),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.hasPnlChart)
        assertTrue(s.pnlSkipped.isEmpty())
        val pnl = s.pnlPoints.associate { it.epochDay to it.value }
        // Ab 0 am ersten Tag (93); der Kauf (97 + 1 Gebühr) verschiebt die Kurve nicht, nur der Kurs
        assertEquals(0.0, pnl.getValue(today - 7), d)
        assertEquals(3.0, pnl.getValue(today - 4), d)
        assertEquals(194.0 - 93.0 - 98.0, pnl.getValue(today - 3), d)
        assertEquals(220.0 - 93.0 - 98.0, pnl.getValue(today), d)
        // Gewinn über den Zeitraum = wie «ohne Käufe», Prozent vom eingesetzten Kapital (93 + 98)
        assertEquals(29.0, s.pnlChange!!, d)
        assertEquals(191.0, s.pnlCapital!!, d)
        assertEquals(29.0 / 191.0 * 100.0, s.pnlPercent!!, d)

        // Devisen-Tageskurse: Werte und Zuflüsse je mit dem Kurs ihres Tags
        val rates = (today - 10 until today).associateWith { if (it == today - 3) 3.0 else 2.0 }
        val fx = PortfolioHistoryFx.convert(s, rates, 2.0, today).series
        assertEquals(440.0 - 186.0 - 294.0, fx.pnlChange!!, d)
    }

    @Test
    fun pnlSinceFirstIsValueMinusNetInvested() {
        val trades = listOf(
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, 95.0, noon(today - 5), 1.0, PortfolioFeeUnit.QUOTE),
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.SELL, 0.5, 98.0, noon(today - 2)),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
        )
        assertTrue(s.pnlSinceFirst)
        val pnl = s.pnlPoints.associate { it.epochDay to it.value }
        // Netto investiert: 96 (Kauf samt Gebühr), nach dem Verkauf 96 − 49 = 47
        assertEquals(95.0 - 96.0, pnl.getValue(today - 5), d)
        assertEquals(49.0 - 47.0, pnl.getValue(today - 2), d)
        assertEquals(55.0 - 47.0, s.pnlChange!!, d)
        // Basis: alles eingesetzte Geld (96), der Verkauf wird nicht abgezogen
        assertEquals(96.0, s.pnlCapital!!, d)
        assertEquals(8.0 / 96.0 * 100.0, s.pnlPercent!!, d)
    }

    @Test
    fun pnlPercentBaseIgnoresSales() {
        // Mehr verkauft als investiert: die Basis bleibt das eingesetzte Geld (10), nie ~0
        val trades = listOf(
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.BUY, 1.0, 10.0, noon(today - 5)),
            PortfolioTrade(nextId++, "BTC", PortfolioTxType.SELL, 0.5, 98.0, noon(today - 2)),
        )
        val s = PortfolioHistory.build(
            trades, mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0),
            PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
        )
        assertEquals(10.0, s.pnlCapital!!, d)
        assertEquals(55.0 + 39.0, s.pnlChange!!, d)
        assertEquals(94.0 / 10.0 * 100.0, s.pnlPercent!!, d)
        // Adress-Guthaben vor dem Zeitraum beobachtet, ohne Zugang: Basis = Wert am ersten Tag
        val held = PortfolioHistory.build(
            emptyList(), mapOf("BTC" to linearCloses(400)), mapOf("BTC" to 110.0), PortfolioHistoryRange.WEEK, today, dayEnd,
            addresses = listOf(observed(noon(today - 20) to "1")),
        )
        assertEquals(93.0, held.pnlCapital!!, d)
    }

    private fun observed(vararg points: Pair<Long, String>) = AddressBalanceHistory(
        AddressChain.BTC, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
        points.map { AddressObservation(it.first, java.math.BigDecimal(it.second)) },
    )

    @Test
    fun pnlAddressBalanceCountsOnlyPriceMoves() {
        // Beobachtetes Guthaben: erste Beobachtung = Zufluss zum Marktkurs (2 × 95), Zuwachs ebenso (1 × 98)
        val closes = mapOf("BTC" to linearCloses(400))
        val s = PortfolioHistory.build(
            emptyList(), closes, mapOf("BTC" to 110.0), PortfolioHistoryRange.SINCE_FIRST, today, dayEnd,
            addresses = listOf(observed(noon(today - 5) to "2", noon(today - 2) to "3")),
        )
        assertTrue(s.pnlSkipped.isEmpty())
        assertEquals(today - 5, s.points.first().epochDay)
        assertEquals(330.0, s.points.last().value, d)
        assertEquals(2.0 * (110.0 - 95.0) + 1.0 * (110.0 - 98.0), s.pnlChange!!, d)
        // Im Zeitraum (vor dem Zeitraum beobachtet): Guthaben × Kursänderung
        val week = PortfolioHistory.build(
            emptyList(), closes, mapOf("BTC" to 110.0), PortfolioHistoryRange.WEEK, today, dayEnd,
            addresses = listOf(observed(noon(today - 20) to "2")),
        )
        assertEquals(2.0 * (110.0 - 93.0), week.pnlChange!!, d)
        // Adress-Einzahlung in den Transaktionen (negative Id): nie rückwirkend — zählt gar nicht
        val legacy = PortfolioHistory.build(
            listOf(buy("BTC", 1.0, noon(today - 5)), PortfolioTrade(-1, "BTC", PortfolioTxType.DEPOSIT, 2.0, null, noon(today - 20))),
            closes, mapOf("BTC" to 110.0), PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(110.0, legacy.points.last().value, d)
    }

    @Test
    fun withdrawalWithoutPriceIsValuedAtTheMarket() {
        // Flacher Kurs: eine Auszahlung ohne Preis darf keinen Gewinn/Verlust erzeugen
        val flat = mapOf("SOL" to (0 until 40).associate { i -> (today - i) to 20.0 })
        val trades = listOf(
            PortfolioTrade(nextId++, "SOL", PortfolioTxType.BUY, 2.0, 10.0, noon(today - 20)),
            PortfolioTrade(nextId++, "SOL", PortfolioTxType.WITHDRAWAL, 1.0, null, noon(today - 3)),
        )
        val week = PortfolioHistory.build(trades, flat, mapOf("SOL" to 20.0), PortfolioHistoryRange.WEEK, today, dayEnd)
        assertTrue(week.pnlSkipped.isEmpty())
        assertEquals(0.0, week.pnlChange!!, d)
        assertEquals(0.0, week.netChange!!, d)
    }

    @Test
    fun pnlLeavesOutCoinWithUnpricedTradeAndNamesIt() {
        val trades = listOf(
            buy("BTC", 1.0, noon(today - 500)),
            buy("ETH", 1.0, noon(today - 500)),
            PortfolioTrade(nextId++, "ETH", PortfolioTxType.BUY, 1.0, null, noon(today - 3)),
        )
        val closes = mapOf("BTC" to linearCloses(400), "ETH" to linearCloses(400))
        val s = PortfolioHistory.build(trades, closes, mapOf("BTC" to 110.0, "ETH" to 110.0), PortfolioHistoryRange.WEEK, today, dayEnd)
        assertEquals(listOf("ETH"), s.pnlSkipped)
        // Wertkurve mit beiden Coins, Gewinn/Verlust nur BTC
        assertEquals(110.0 * 3, s.points.last().value, d)
        assertEquals(110.0 - 93.0, s.pnlChange!!, d)
    }

    @Test
    fun withdrawalInRangeIsFlaggedAndDepositWithoutPriceCountsAtMarket() {
        val closes = mapOf("SOL" to (0..40).associate { (today - it) to (20.0 + it % 3) })
        val trades = listOf(
            buy("SOL", 2.0, noon(today - 20)),
            PortfolioTrade(nextId++, "SOL", PortfolioTxType.WITHDRAWAL, 1.0, null, noon(today - 3)),
            PortfolioTrade(nextId++, "SOL", PortfolioTxType.DEPOSIT, 1.0, null, noon(today - 2)),
        )
        val week = PortfolioHistory.build(trades, closes, mapOf("SOL" to 20.0), PortfolioHistoryRange.WEEK, today, dayEnd)
        // Einzahlung ohne Preis zum Tagesschluss: SOL bleibt in der Gewinn/Verlust-Kurve
        assertEquals(emptyList<String>(), week.pnlSkipped)
        assertTrue(week.withdrawalsInRange)
        // Auszahlung vor dem Zeitraum: kein Hinweis
        val older = trades.take(2).map { if (it.type == PortfolioTxType.WITHDRAWAL) it.copy(time = noon(today - 15)) else it }
        assertFalse(PortfolioHistory.build(older, closes, mapOf("SOL" to 20.0), PortfolioHistoryRange.WEEK, today, dayEnd).withdrawalsInRange)
        assertTrue(PortfolioHistory.build(older, closes, mapOf("SOL" to 20.0), PortfolioHistoryRange.SINCE_FIRST, today, dayEnd).withdrawalsInRange)
    }
}
