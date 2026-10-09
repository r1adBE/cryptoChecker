package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
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
        // RTL: Vorzeichen bleibt vor der Zahl (links-nach-rechts-Insel)
        val arLatn = Locale.forLanguageTag("ar-u-nu-latn")
        assertEquals("\u2066+1.23%\u2069", PortfolioSnapshotMath.signedPercent(1.234, arLatn))
        assertEquals("\u2066−12.00 USD\u2069", PortfolioSnapshotMath.signedAmount(-12.0, "USD", arLatn))
    }

    @Test
    fun dayBasisComparesWithTheSampleAtDayStart() {
        val dayStart = now - 5 * h
        val history = listOf(
            PriceSample(now - 24 * h, mapOf("BTC" to 80.0)),
            PriceSample(dayStart - 30 * 60_000L, mapOf("BTC" to 100.0)),
            PriceSample(dayStart + h, mapOf("BTC" to 105.0)),
        )
        assertEquals(dayStart - 30 * 60_000L, PortfolioSnapshotMath.baselineAt(history, dayStart)?.time)
        assertNull(PortfolioSnapshotMath.baselineAt(history.drop(1).drop(1), dayStart))
        val stamp = ChangeStamp(ChangeBasis.UTC_DAY, dayStart)
        val s = PortfolioSnapshotMath.snapshot(
            holdings = mapOf("BTC" to 1.0),
            totalUsd = 120.0,
            current = mapOf("BTC" to 120.0),
            history = history,
            now = now,
            fxRate = 1.0,
            currency = "USD",
            stamp = stamp,
        )
        // Seit Tagesbeginn (100 → 120), nicht seit 24 h (80 → 120)
        assertEquals(20.0, s.changeAmount!!, 1e-9)
        assertEquals(20.0, s.changePercent!!, 1e-9)
        assertEquals(stamp, s.stamp)
        assertEquals(20.0, s.positions.single().change24hPercent!!, 1e-9)
        // Verlauf ohne Aufnahmen vor dem Tagesbeginn
        assertTrue(s.history.all { it.time >= dayStart })
        // Rollend wie bisher
        val rolling = PortfolioSnapshotMath.snapshot(mapOf("BTC" to 1.0), 120.0, mapOf("BTC" to 120.0), history, now, 1.0, "USD")
        assertEquals(40.0, rolling.changeAmount!!, 1e-9)
        assertNull(rolling.stamp)
    }
}
