package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.DayChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 25-%-Prüfung für Wertverlauf und Stichtag ([PricePlausibility]). */
class PricePlausibilityTest {

    private var nextId = 1L
    private val zone = ZoneId.of("Europe/Zurich")
    private val dayEnd: (Long) -> Long = { day ->
        LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    private val today = LocalDate.of(2026, 10, 6).toEpochDay()

    private fun noon(day: Long): Long =
        LocalDate.ofEpochDay(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun buy(coin: String, amount: Double, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, 1.0, time)

    @Test
    fun sameThresholdAsDayChange() {
        assertEquals(DayChange.MAX_PRICE_GAP, PricePlausibility.MAX_GAP, 0.0)
    }

    @Test
    fun matchesWithinTwentyFivePercent() {
        assertTrue(PricePlausibility.matches(125.0, 100.0))
        assertTrue(PricePlausibility.matches(75.0, 100.0))
        assertFalse(PricePlausibility.matches(125.1, 100.0))
        assertFalse(PricePlausibility.matches(74.9, 100.0))
        // ohne Bezug nicht prüfbar → gilt; ohne Wert → nie
        assertTrue(PricePlausibility.matches(3.0, null))
        assertTrue(PricePlausibility.matches(3.0, Double.NaN))
        assertFalse(PricePlausibility.matches(null, 100.0))
        assertFalse(PricePlausibility.matches(-1.0, 100.0))
    }

    @Test
    fun closesMatchLiveUsesLatestClose() {
        val closes = listOf(1L to 10.0, 2L to 100.0)
        assertTrue(PricePlausibility.closesMatchLive(closes, 110.0))
        assertFalse(PricePlausibility.closesMatchLive(closes, 10.0))
        assertTrue(PricePlausibility.closesMatchLive(closes, null))
        assertTrue(PricePlausibility.closesMatchLive(emptyList(), 5.0))
    }

    @Test
    fun cutoffSourceCheck() {
        // Binance-Spot (Quelle der aktuellen Kurse): ungeprüft
        assertTrue(PricePlausibility.acceptSourceClose(5.0, trusted = true, sourceLatest = null, current = 900.0))
        // Ausweich-Quelle führt heute denselben Coin (±25 %)
        assertTrue(PricePlausibility.acceptSourceClose(5.0, trusted = false, sourceLatest = 1.1, current = 1.0))
        // … oder einen anderen Token gleichen Kürzels
        assertFalse(PricePlausibility.acceptSourceClose(5.0, trusted = false, sourceLatest = 40.0, current = 1.0))
        // Prüfung nötig, aber die Quelle nennt keinen heutigen Kurs
        assertFalse(PricePlausibility.acceptSourceClose(5.0, trusted = false, sourceLatest = null, current = 1.0))
        // Kein aktueller Kurs bekannt: nicht prüfbar → gilt
        assertTrue(PricePlausibility.acceptSourceClose(5.0, trusted = false, sourceLatest = null, current = null))
        assertFalse(PricePlausibility.acceptSourceClose(null, trusted = true, sourceLatest = null, current = null))
    }

    @Test
    fun historyDropsImplausibleSeries() {
        val trades = listOf(buy("BTC", 1.0, noon(today - 2)), buy("FOO", 10.0, noon(today - 2)))
        val closes = mapOf(
            "BTC" to (0..5).associate { (today - it) to 100.0 },
            // Kerzen eines anderen «FOO» (Kurs 50 statt 1)
            "FOO" to (0..5).associate { (today - it) to 50.0 },
        )
        val s = PortfolioHistory.build(
            trades, closes, mapOf("BTC" to 101.0, "FOO" to 1.0), PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(listOf("FOO"), s.skipped)
        assertEquals(100.0, s.points.first().value, 1e-9)
        assertEquals(101.0, s.points.last().value, 1e-9)
    }

    @Test
    fun historyStableWithImplausibleClosesFallsBackToOne() {
        val trades = listOf(buy("USDC", 10.0, noon(today - 1)))
        val s = PortfolioHistory.build(
            trades, mapOf("USDC" to mapOf(today - 1 to 7.0, today to 7.0)), mapOf("USDC" to 1.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.skipped.isEmpty())
        assertEquals(listOf(10.0, 10.0), s.points.map { it.value })
    }
}
