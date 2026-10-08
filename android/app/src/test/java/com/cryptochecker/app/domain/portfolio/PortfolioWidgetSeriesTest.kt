package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioWidgetSeriesTest {

    private val h = PortfolioWidgetSeries.HOUR_MILLIS
    private val now = 1_000L * h + 17 * 60_000L
    private val stables = setOf("USDT", "USDC")

    /** Stundenkurse der letzten 24 h: Kurs = [f](Stunden zurück). */
    private fun series(f: (Int) -> Double): List<TimedPrice> =
        (24 downTo 1).map { k -> TimedPrice(now - k * h, f(k)) }

    @Test
    fun hourlyClosesGetTimesBackFromFetch() {
        val timed = PortfolioWidgetSeries.fromHourlyCloses(listOf(1.0, 2.0, Double.NaN, 4.0), fetchedAt = now)
        assertEquals(listOf(now - 3 * h, now - 2 * h, now), timed.map { it.time })
        assertEquals(listOf(1.0, 2.0, 4.0), timed.map { it.price })
        assertTrue(PortfolioWidgetSeries.fromHourlyCloses(listOf(1.0), 0L).isEmpty())
        // Kerzen: Schluss am Stundenende, die laufende zur Abrufzeit
        val candles = PortfolioWidgetSeries.fromCandles(listOf(now - 2 * h to 5.0, now - 10 * 60_000L to 6.0), now)
        assertEquals(listOf(now - h, now), candles.map { it.time })
    }

    @Test
    fun mergeKeepsNewestPerHourAndPrunes() {
        val stored = mapOf(
            "btc" to listOf(TimedPrice(now - 30 * h, 1.0), TimedPrice(now - 5 * h - 10, 2.0)),
            "ETH" to listOf(TimedPrice(now + h, 9.0)), // Zukunft
        )
        val hourStart = Math.floorDiv(now - 5 * h - 10, h) * h
        val fresh = mapOf("BTC" to listOf(TimedPrice(hourStart + 1, 3.0), TimedPrice(now, 4.0)))
        val merged = PortfolioWidgetSeries.merge(stored, fresh, now)
        assertEquals(setOf("BTC"), merged.keys)
        // Gleiche Stunde: der jüngere Kurs bleibt; zu alt und Zukunft fallen weg
        val btc = merged.getValue("BTC")
        assertEquals(2, btc.size)
        assertTrue(btc.zipWithNext().all { (a, b) -> a.time < b.time })
        assertEquals(4.0, btc.last().price, 0.0)
    }

    @Test
    fun priceAtUsesLatestWithinTolerance() {
        val prices = listOf(TimedPrice(now - 3 * h, 1.0), TimedPrice(now - h, 2.0))
        assertEquals(2.0, PortfolioWidgetSeries.priceAt(prices, now)!!, 0.0)
        assertEquals(1.0, PortfolioWidgetSeries.priceAt(prices, now - 2 * h)!!, 0.0)
        // Mehr als 90 Minuten Lücke: kein Kurs
        assertNull(PortfolioWidgetSeries.priceAt(prices, now - h - 1))
        assertNull(PortfolioWidgetSeries.priceAt(prices, now - 4 * h))
        assertNull(PortfolioWidgetSeries.priceAt(null, now))
    }

    @Test
    fun hourlySeriesSumsHoldingsTimesCloses() {
        val prices = mapOf("BTC" to series { k -> 100.0 + (24 - k) }, "ETH" to series { 10.0 })
        val points = PortfolioWidgetSeries.hourly(
            holdings = mapOf("BTC" to 1.0, "ETH" to 2.0, "USDT" to 50.0),
            current = mapOf("BTC" to 130.0, "ETH" to 10.0, "USDT" to 1.0),
            prices = prices,
            now = now,
            fxRate = 2.0,
            stables = stables,
        )
        assertEquals(25, points.size)
        assertEquals(now - 24 * h, points.first().time)
        assertEquals(now, points.last().time)
        // 24 h zurück: BTC 100, ETH 2 × 10, USDT 50 → 170 × 2
        assertEquals(340.0, points.first().value, 1e-9)
        assertEquals((130.0 + 20.0 + 50.0) * 2.0, points.last().value, 1e-9)
        assertTrue(PortfolioWidgetSeries.drawable(points))
        assertTrue(PortfolioWidgetSeries.coversDay(points))
    }

    @Test
    fun coverageBelowEightyPercentDropsHours() {
        // BTC hat Kurse, macht aber nur 70 % des Werts aus
        val prices = mapOf("BTC" to series { 100.0 })
        val holdings = mapOf("BTC" to 0.7, "XYZ" to 30.0)
        val current = mapOf("BTC" to 100.0, "XYZ" to 1.0)
        val points = PortfolioWidgetSeries.hourly(holdings, current, prices, now, 1.0, stables)
        assertEquals(listOf(now), points.map { it.time })
        assertFalse(PortfolioWidgetSeries.drawable(points))
        // Mit 80 %: zählt; der Rest geht flach mit dem aktuellen Kurs ein
        val ok = PortfolioWidgetSeries.hourly(mapOf("BTC" to 0.8, "XYZ" to 20.0), current, mapOf("BTC" to series { 50.0 }), now, 1.0, stables)
        assertEquals(25, ok.size)
        assertEquals(0.8 * 50.0 + 20.0, ok.first().value, 1e-9)
    }

    @Test
    fun fewHoursAreNotDrawable() {
        val prices = mapOf("BTC" to (4 downTo 1).map { TimedPrice(now - it * h, 100.0) })
        val points = PortfolioWidgetSeries.hourly(mapOf("BTC" to 1.0), mapOf("BTC" to 100.0), prices, now, 1.0, stables)
        assertEquals(5, points.size)
        assertFalse(PortfolioWidgetSeries.drawable(points))
        assertNull(PortfolioWidgetSeries.change(points))
        assertTrue(PortfolioWidgetSeries.hourly(emptyMap(), emptyMap(), prices, now, 1.0, stables).isEmpty())
    }

    @Test
    fun changeOverTheDay() {
        val points = (24 downTo 0).map { k -> PortfolioValuePoint(now - k * h, if (k == 24) 200.0 else 190.0) }
        val change = PortfolioWidgetSeries.change(points)!!
        assertEquals(-10.0, change.amount, 1e-9)
        assertEquals(-5.0, change.percent!!, 1e-9)
        // Nur 10 h Spanne: keine 24-h-Veränderung
        val short = (10 downTo 0).map { k -> PortfolioValuePoint(now - k * h, 1.0) }
        assertNull(PortfolioWidgetSeries.change(short))
        assertEquals(-1, PortfolioWidgetSeries.direction(-1.0))
        assertEquals(0, PortfolioWidgetSeries.direction(0.004))
        assertEquals(1, PortfolioWidgetSeries.direction(0.01))
    }

    @Test
    fun coinChangesAgainstPriceADayAgo() {
        val prices = mapOf("BTC" to series { k -> if (k == 24) 100.0 else 105.0 }, "ETH" to listOf(TimedPrice(now - 2 * h, 5.0)))
        val changes = PortfolioWidgetSeries.coinChanges(mapOf("BTC" to 110.0, "ETH" to 6.0), prices, now)
        assertEquals(10.0, changes.getValue("BTC"), 1e-9)
        assertFalse("ETH" in changes)
    }

    @Test
    fun snapshotPrefersHourlySeries() {
        val s = PortfolioSnapshotMath.snapshot(
            holdings = mapOf("BTC" to 1.0),
            totalUsd = 110.0,
            current = mapOf("BTC" to 110.0),
            history = emptyList(),
            now = now,
            fxRate = 1.0,
            currency = "USD",
            hourlyPrices = mapOf("BTC" to series { k -> if (k == 24) 100.0 else 105.0 }),
            stables = stables,
        )
        assertEquals(25, s.history.size)
        assertEquals(10.0, s.changeAmount!!, 1e-9)
        assertEquals(10.0, s.changePercent!!, 1e-9)
        assertEquals(10.0, s.positions.single().change24hPercent!!, 1e-9)
    }

    @Test
    fun hourlySinceDayStartAndChange() {
        val dayStart = Math.floorDiv(now, h) * h - 5 * h // fünf volle Stunden vor der laufenden
        val prices = mapOf("BTC" to (0..6).map { TimedPrice(dayStart - h + it * h, 100.0 + it) })
        val points = PortfolioWidgetSeries.hourlySince(mapOf("BTC" to 2.0), mapOf("BTC" to 110.0), prices, dayStart, now, 1.0, stables)
        // dayStart, +1 h … +5 h (vor jetzt) und jetzt
        assertEquals(dayStart, points.first().time)
        assertEquals(7, points.size)
        assertEquals(now, points.last().time)
        assertEquals(2 * 101.0, points.first().value, 1e-9)
        val change = PortfolioWidgetSeries.changeSince(points, dayStart)!!
        assertEquals(2 * 110.0 - 2 * 101.0, change.amount, 1e-9)
        assertEquals((110.0 / 101.0 - 1) * 100, change.percent!!, 1e-9)
        // Kein Kurs zum Tagesbeginn: keine Veränderung
        val late = mapOf("BTC" to listOf(TimedPrice(dayStart + 2 * h, 105.0)))
        val partial = PortfolioWidgetSeries.hourlySince(mapOf("BTC" to 2.0), mapOf("BTC" to 110.0), late, dayStart, now, 1.0, stables)
        assertNull(PortfolioWidgetSeries.changeSince(partial, dayStart))
        assertEquals(mapOf("BTC" to (110.0 / 101.0 - 1) * 100), PortfolioWidgetSeries.coinChangesSince(mapOf("BTC" to 110.0), prices, dayStart))
        assertTrue(PortfolioWidgetSeries.coinChangesSince(mapOf("BTC" to 110.0), late, dayStart).isEmpty())
    }

    @Test
    fun candlesOnlyForCoinsWithGaps() {
        val full = series { 100.0 }
        val sparse = full.takeLast(5)
        assertFalse(PortfolioWidgetSeries.needsCandles(full, now))
        assertTrue(PortfolioWidgetSeries.needsCandles(sparse, now))
        assertTrue(PortfolioWidgetSeries.needsCandles(null, now))
        // Älter als 24 h zählt nicht
        assertTrue(PortfolioWidgetSeries.needsCandles(full.map { it.copy(time = it.time - 30 * h) }, now))

        val prices = mapOf("BTC" to full, "ETH" to sparse)
        // Reihenfolge = Vorrang; Stablecoins und vollständige Coins fallen weg; Grossschreibung
        assertEquals(
            listOf("ETH", "PEPE"),
            PortfolioWidgetSeries.candleCoins(listOf("btc", "eth", "USDT", "pepe", "ETH"), prices, emptyMap(), now, stables),
        )
        // Höchstens ein Versuch je Stunde
        val tried = mapOf("ETH" to now - 30 * 60_000L, "PEPE" to now - h)
        assertEquals(listOf("PEPE"), PortfolioWidgetSeries.candleCoins(listOf("ETH", "PEPE"), prices, tried, now, stables))
        // Höchstens CANDLE_MAX_COINS
        val many = (1..20).map { "C$it" }
        assertEquals(
            PortfolioWidgetSeries.CANDLE_MAX_COINS,
            PortfolioWidgetSeries.candleCoins(many, emptyMap(), emptyMap(), now, stables).size,
        )
    }

    @Test
    fun dayChartFromTwoPoints() {
        val two = listOf(PortfolioValuePoint(now - h, 1.0), PortfolioValuePoint(now, 2.0))
        assertTrue(PortfolioWidgetSeries.drawableDay(two))
        assertFalse(PortfolioWidgetSeries.drawable(two))
        assertFalse(PortfolioWidgetSeries.drawableDay(two.take(1)))
    }
}
