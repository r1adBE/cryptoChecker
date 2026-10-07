package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PortfolioSnapshotTest {

    private val h = 60 * 60_000L
    private val now = 1_000L * h

    @Test
    fun samplesAreSpacedAndPruned() {
        var history = emptyList<PriceSample>()
        history = PortfolioSnapshotMath.addSample(history, PriceSample(now - 50 * h, mapOf("BTC" to 1.0)))
        history = PortfolioSnapshotMath.addSample(history, PriceSample(now - 30 * h, mapOf("BTC" to 2.0)))
        history = PortfolioSnapshotMath.addSample(history, PriceSample(now - 30 * h + 10 * 60_000L, mapOf("BTC" to 3.0)))
        assertEquals(2, history.size)
        history = PortfolioSnapshotMath.addSample(history, PriceSample(now, mapOf("BTC" to 4.0)))
        // 50 h alt fällt weg (> 48 h), 30 h bleibt
        assertEquals(listOf(now - 30 * h, now), history.map { it.time })
        // Leere Kurse werden nicht aufgenommen
        assertEquals(history, PortfolioSnapshotMath.addSample(history, PriceSample(now + h, emptyMap())))
    }

    @Test
    fun baselineIsNewestAtLeast20HoursOld() {
        val history = listOf(
            PriceSample(now - 47 * h, mapOf("BTC" to 1.0)),
            PriceSample(now - 21 * h, mapOf("BTC" to 2.0)),
            PriceSample(now - 19 * h, mapOf("BTC" to 3.0)),
        )
        assertEquals(now - 21 * h, PortfolioSnapshotMath.baseline(history, now)?.time)
        assertNull(PortfolioSnapshotMath.baseline(history.drop(2), now))
        assertNull(PortfolioSnapshotMath.baseline(listOf(PriceSample(now - 49 * h, mapOf("BTC" to 1.0))), now))
    }

    @Test
    fun changeUsesTodaysHoldings() {
        val change = PortfolioSnapshotMath.todayChange(
            holdings = mapOf("BTC" to 0.5, "ETH" to 2.0, "NEW" to 10.0),
            current = mapOf("BTC" to 110.0, "ETH" to 45.0, "NEW" to 1.0),
            baseline = mapOf("BTC" to 100.0, "ETH" to 50.0),
        )!!
        // then 0.5*100 + 2*50 = 150; now 55 + 90 = 145 → −5 (NEW ohne Basis zählt nicht)
        assertEquals(-5.0, change.amountUsd, 1e-9)
        assertEquals(-5.0 / 150.0 * 100.0, change.percent!!, 1e-9)
        assertNull(PortfolioSnapshotMath.todayChange(mapOf("X" to 1.0), mapOf("X" to 1.0), emptyMap()))
    }

    @Test
    fun snapshotConvertsCurrency() {
        val history = listOf(PriceSample(now - 24 * h, mapOf("BTC" to 100.0, "USDT" to 1.0)))
        val s = PortfolioSnapshotMath.snapshot(
            holdings = mapOf("BTC" to 1.0, "USDT" to 50.0),
            totalUsd = 170.0,
            current = mapOf("BTC" to 120.0, "USDT" to 1.0),
            history = history,
            now = now,
            fxRate = 0.5,
            currency = "CHF",
        )
        assertEquals(85.0, s.total, 1e-9)
        assertEquals(10.0, s.changeAmount!!, 1e-9)   // +20 USD × 0.5
        assertEquals(20.0 / 150.0 * 100.0, s.changePercent!!, 1e-9)
        assertEquals("CHF", s.currency)
        assertEquals(false, s.empty)

        val empty = PortfolioSnapshotMath.snapshot(emptyMap(), 0.0, emptyMap(), history, now, 1.0, "USD")
        assertTrue(empty.empty)
        assertNull(empty.changeAmount)
    }

    @Test
    fun formatting() {
        assertEquals("+1,234.50 CHF", PortfolioSnapshotMath.signedAmount(1234.5, "CHF", Locale.US))
        assertEquals("−12.00 USD", PortfolioSnapshotMath.signedAmount(-12.0, "USD", Locale.US))
        assertEquals("0.00 USD", PortfolioSnapshotMath.signedAmount(0.001, "USD", Locale.US))
        assertEquals("+1.23%", PortfolioSnapshotMath.signedPercent(1.234, Locale.US))
        assertEquals("−0.50%", PortfolioSnapshotMath.signedPercent(-0.5, Locale.US))
        assertEquals("0.00%", PortfolioSnapshotMath.signedPercent(-0.001, Locale.US))
    }
}
