package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.exp

/** Swift-Spiegel: BtcCorrelationTests.swift (gleiche Reihen und Erwartungen). */
class BtcCorrelationTest {

    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L - (1_700_000_000_000L % hour)

    /** Kerzen aus Log-Renditen (erste Kerze 100); letzte Kerze = laufende Stunde. */
    private fun series(returns: List<Double>, startPrice: Double = 100.0): List<HourCandle> {
        val out = ArrayList<HourCandle>()
        var price = startPrice
        out += HourCandle(start, price, price, price, price, 1.0)
        returns.forEachIndexed { i, r ->
            price *= exp(r)
            out += HourCandle(start + (i + 1) * hour, price, price, price, price, 1.0)
        }
        return out
    }

    /** Wechselnde, ungleichmässige Renditen (deterministisch). */
    private fun wave(n: Int, scale: Double = 0.01): List<Double> =
        (0 until n).map { i -> scale * (((i * 7) % 11) - 5) / 5.0 }

    @Test
    fun identicalMovesAreTight() {
        val btc = series(wave(30))
        val sol = series(wave(30).map { it * 1.6 }, startPrice = 150.0)
        val r = BtcCorrelation.correlation("SOL", sol, btc)
        assertNotNull(r)
        assertEquals(1.0, r!!, 1e-9)
        assertEquals(BtcLink.TIGHT, BtcCorrelation.link("SOL", sol, btc))
    }

    @Test
    fun oppositeOrUnrelatedMovesAreIndependent() {
        val btc = series(wave(30))
        val inverse = series(wave(30).map { -it })
        assertEquals(BtcLink.INDEPENDENT, BtcCorrelation.link("XRP", inverse, btc))
        // Andere Periode: kaum Zusammenhang
        val other = series((0 until 30).map { i -> 0.01 * (((i * 3) % 4) - 1.5) })
        val r = BtcCorrelation.correlation("XRP", other, btc)!!
        assertEquals(BtcLink.INDEPENDENT, BtcCorrelation.link(r))
    }

    @Test
    fun middleBandGivesNoSentence() {
        assertNull(BtcCorrelation.link(0.5))
        assertNull(BtcCorrelation.link(0.79))
        assertEquals(BtcLink.TIGHT, BtcCorrelation.link(0.8))
        assertEquals(BtcLink.INDEPENDENT, BtcCorrelation.link(0.3))
        assertEquals(BtcLink.INDEPENDENT, BtcCorrelation.link(-0.4))
        assertNull(BtcCorrelation.link(null))
        assertNull(BtcCorrelation.link(Double.NaN))
    }

    @Test
    fun bitcoinItselfAndMissingSeriesGiveNothing() {
        val btc = series(wave(30))
        assertNull(BtcCorrelation.correlation("BTC", btc, btc))
        assertNull(BtcCorrelation.correlation(" btc ", btc, btc))
        assertNull(BtcCorrelation.correlation("SOL", null, btc))
        assertNull(BtcCorrelation.correlation("SOL", btc, null))
    }

    @Test
    fun needsAtLeast24CommonReturnsWithoutRunningCandle() {
        // 25 Renditen → 26 Kerzen; ohne die laufende bleiben 24 Renditen
        val btc25 = series(wave(25))
        assertNotNull(BtcCorrelation.correlation("ETH", btc25, btc25.map { it.copy() }))
        // 24 Renditen → nach dem Weglassen der laufenden nur 23
        val btc24 = series(wave(24))
        assertNull(BtcCorrelation.correlation("ETH", btc24, btc24))
    }

    @Test
    fun onlyCommonConsecutiveHoursCount() {
        val btc = series(wave(30))
        // Lücke im Coin: die Rendite über die Lücke zählt nicht (nur genau eine Stunde)
        val coin = series(wave(30)).filterIndexed { i, _ -> i != 10 }
        val r = BtcCorrelation.correlation("ADA", coin, btc)
        assertNotNull(r)
        assertEquals(1.0, r!!, 1e-9)
        // Zu viele Lücken: unter 24 gemeinsame Renditen
        val sparse = series(wave(30)).filterIndexed { i, _ -> i % 5 != 0 }
        assertNull(BtcCorrelation.correlation("ADA", sparse, btc))
    }

    @Test
    fun flatSeriesHasNoCorrelation() {
        val btc = series(wave(30))
        val flat = series(List(30) { 0.0 })
        assertNull(BtcCorrelation.correlation("USDC", flat, btc))
    }

    @Test
    fun pearsonBasics() {
        assertEquals(1.0, BtcCorrelation.pearson(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))!!, 1e-12)
        assertEquals(-1.0, BtcCorrelation.pearson(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0))!!, 1e-12)
        assertNull(BtcCorrelation.pearson(listOf(1.0), listOf(1.0)))
    }
}
