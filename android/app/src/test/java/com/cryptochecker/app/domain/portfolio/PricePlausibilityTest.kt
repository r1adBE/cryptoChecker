package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.DayChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 25-%-Prüfung (Stichtag) und Identitätsprüfung Faktor 3 (Wertverlauf) aus [PricePlausibility]. */
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
        // Ohne aktuellen Kurs ist der Token nicht bestätigt: Reihe zählt nicht
        assertFalse(PricePlausibility.closesMatchLive(closes, null))
        assertFalse(PricePlausibility.closesMatchLive(closes, Double.NaN))
        assertFalse(PricePlausibility.closesMatchLive(closes, 0.0))
        assertTrue(PricePlausibility.closesMatchLive(emptyList(), 5.0))
        assertTrue(PricePlausibility.closesMatchLive(emptyList(), null))
    }

    @Test
    fun sameTokenWithinFactorThree() {
        assertTrue(PricePlausibility.sameToken(300.0, 100.0))
        assertTrue(PricePlausibility.sameToken(34.0, 100.0))
        assertTrue(PricePlausibility.sameToken(140.0, 100.0))
        assertFalse(PricePlausibility.sameToken(300.1, 100.0))
        assertFalse(PricePlausibility.sameToken(33.3, 100.0))
        // Streng: ohne gültigen Bezug nicht bestätigt
        assertFalse(PricePlausibility.sameToken(3.0, null))
        assertFalse(PricePlausibility.sameToken(3.0, 0.0))
        assertFalse(PricePlausibility.sameToken(3.0, Double.NaN))
        assertFalse(PricePlausibility.sameToken(3.0, Double.POSITIVE_INFINITY))
        assertFalse(PricePlausibility.sameToken(null, 100.0))
        assertFalse(PricePlausibility.sameToken(null, null))
    }

    @Test
    fun closesMatchLiveToleratesBigRealMoves() {
        // Echter Tag mit +40 % / −30 %: Reihe bleibt gültig (keine 25-%-Regel mehr)
        val closes = listOf(1L to 90.0, 2L to 100.0)
        assertTrue(PricePlausibility.closesMatchLive(closes, 140.0))
        assertTrue(PricePlausibility.closesMatchLive(closes, 70.0))
        assertFalse(PricePlausibility.closesMatchLive(closes, 301.0))
    }

    @Test
    fun historyKeepsCoinAfterThirtyPercentDay() {
        val trades = listOf(buy("BTC", 1.0, noon(today - 2)))
        // Zwischengespeicherte Kerzen enden gestern bei 100, jetzt +30 %
        val closes = mapOf("BTC" to (1..5).associate { (today - it) to 100.0 })
        val s = PortfolioHistory.build(
            trades, closes, mapOf("BTC" to 130.0), PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.skipped.isEmpty())
        assertEquals(100.0, s.points.first().value, 1e-9)
        // heutiger Punkt mit dem aktuellen Kurs
        assertEquals(130.0, s.points.last().value, 1e-9)
    }

    @Test
    fun cutoffSourceCheck() {
        // Binance-Spot: Identitätsprüfung 1/3…3 gegen den aktuellen Kurs (wie Wertverlauf)
        assertTrue(PricePlausibility.acceptSourceClose(5.0, primary = true, sourceLatest = 2.5, current = 1.0))
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = true, sourceLatest = 3.5, current = 1.0))
        // Binance-Spot ohne heutigen Kurs der Quelle oder ohne aktuellen Kurs: nicht bestätigt
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = true, sourceLatest = null, current = 900.0))
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = true, sourceLatest = 1.0, current = null))
        // Ausweich-Quelle führt heute denselben Coin (±25 %)
        assertTrue(PricePlausibility.acceptSourceClose(5.0, primary = false, sourceLatest = 1.1, current = 1.0))
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = false, sourceLatest = 1.5, current = 1.0))
        // … oder einen anderen Token gleichen Kürzels
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = false, sourceLatest = 40.0, current = 1.0))
        // Prüfung nötig, aber die Quelle nennt keinen heutigen Kurs
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = false, sourceLatest = null, current = 1.0))
        // Kein aktueller Kurs bekannt: streng → nicht übernommen (früher: gilt ungeprüft)
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = false, sourceLatest = 1.0, current = null))
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = false, sourceLatest = null, current = null))
        assertFalse(PricePlausibility.acceptSourceClose(5.0, primary = true, sourceLatest = 1.0, current = Double.NaN))
        assertFalse(PricePlausibility.acceptSourceClose(null, primary = true, sourceLatest = 1.0, current = 1.0))
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
    fun historyStableWithImplausibleClosesIsSkipped() {
        // Kein stilles 1 mehr: passt die Reihe nicht, fehlt auch ein Stablecoin (ausser USDT)
        val trades = listOf(buy("USDC", 10.0, noon(today - 1)))
        val s = PortfolioHistory.build(
            trades, mapOf("USDC" to mapOf(today - 1 to 7.0, today to 7.0)), mapOf("USDC" to 1.0),
            PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(listOf("USDC"), s.skipped)
        assertTrue(s.points.isEmpty())
    }

    @Test
    fun candleReferencePrefersWatchlistMedianElseStored() {
        // Merkliste: Median der gültigen Kurse (ungültige zählen nicht)
        assertEquals(2.0, PricePlausibility.candleReference(listOf(3.0, 1.0, 2.0, Double.NaN, -1.0), 50.0)!!, 1e-9)
        assertEquals(1.5, PricePlausibility.candleReference(listOf(1.0, 2.0), null)!!, 1e-9)
        // Ohne Merkliste: gemerkter letzter guter Kurs
        assertEquals(50.0, PricePlausibility.candleReference(emptyList(), 50.0)!!, 1e-9)
        // Gar kein Bezug
        assertNull(PricePlausibility.candleReference(emptyList(), null))
        assertNull(PricePlausibility.candleReference(listOf(0.0), Double.NaN))
    }

    @Test
    fun candlePriceNeedsSameTokenAsReference() {
        // Gleiches Kürzel, anderer Token (Faktor > 3): kein Kurs
        assertFalse(PricePlausibility.acceptCandle(0.002, 1.0))
        assertFalse(PricePlausibility.acceptCandle(3.01, 1.0))
        // Innerhalb 1/3…3 (auch grosse echte Bewegungen): Kurs gilt
        assertTrue(PricePlausibility.acceptCandle(2.9, 1.0))
        assertTrue(PricePlausibility.acceptCandle(0.34, 1.0))
        // Ohne Bezug: NICHT übernommen — ungeprüft zählt nicht als Geld (wie der Stichtag-Export)
        assertFalse(PricePlausibility.acceptCandle(0.002, null))
        assertFalse(PricePlausibility.acceptCandle(50.0, Double.NaN))
        // Ungültiger Kerzenkurs: nie
        assertFalse(PricePlausibility.acceptCandle(null, 1.0))
        assertFalse(PricePlausibility.acceptCandle(Double.NaN, null))
    }

    @Test
    fun candleWithoutReferenceIsUnchecked() {
        // Gültige Kerze ohne Bezug → «ungeprüft» (Coin ohne Kurs)
        assertTrue(PricePlausibility.candleUnchecked(0.002, null))
        assertTrue(PricePlausibility.candleUnchecked(0.002, 0.0))
        // Mit Bezug geprüft (passt oder passt nicht) bzw. ohne gültige Kerze: nicht «ungeprüft»
        assertFalse(PricePlausibility.candleUnchecked(0.002, 1.0))
        assertFalse(PricePlausibility.candleUnchecked(2.0, 1.0))
        assertFalse(PricePlausibility.candleUnchecked(null, null))
        assertFalse(PricePlausibility.candleUnchecked(-1.0, null))
    }
}
