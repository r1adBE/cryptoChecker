package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleSeriesTest {

    private val h = ActivityAnalyzer.HOUR_MILLIS
    private val t0 = 1_700_000_000_000L - 1_700_000_000_000L % ActivityAnalyzer.HOUR_MILLIS

    /** 27 Stundenkerzen ab [start]; Schluss steigt je Stunde um [step], Umsatz [volume]. */
    private fun series(start: Long = t0, step: Double = 0.1, volume: Double = 100.0): List<HourCandle> =
        (0 until 27).map { i ->
            val close = 100.0 + i * step
            HourCandle(start + i * h, close - step, close, close - step, close, volume)
        }

    /** Mitten in der laufenden (27.) Stunde der Reihe ab [t0]. */
    private val now = t0 + 26 * h + 30 * 60_000L

    @Test
    fun status_freshSeriesIsOk() {
        assertEquals(CandleSeries.Status.OK, CandleSeries.status(series(), now, 2.0))
        assertEquals(CandleSeries.Status.OK, CandleSeries.status(series(), now, null))
    }

    @Test
    fun status_missingOrTooShort() {
        assertEquals(CandleSeries.Status.MISSING, CandleSeries.status(null, now, null))
        assertEquals(CandleSeries.Status.MISSING, CandleSeries.status(emptyList(), now, null))
        assertEquals(CandleSeries.Status.MISSING, CandleSeries.status(series().take(1), now, null))
    }

    @Test
    fun status_staleAfterTwoHours() {
        // Letzte Kerze genau 2 h alt: noch gültig; eine Minute mehr: veraltet
        val lastOpen = series().last().openTime
        assertEquals(CandleSeries.Status.OK, CandleSeries.status(series(), lastOpen + 2 * h, null))
        assertEquals(CandleSeries.Status.STALE, CandleSeries.status(series(), lastOpen + 2 * h + 60_000L, null))
        // Delisteter Spot-Markt: Kerzen enden Monate vor «jetzt»
        val delisted = series(start = t0 - 400L * 24 * h)
        assertEquals(CandleSeries.Status.STALE, CandleSeries.status(delisted, now, 4.77))
        assertNull(CandleSeries.usable(delisted, now, 4.77))
    }

    @Test
    fun status_flatWhileTickerMoves() {
        val flat = series(step = 0.0)
        assertEquals(CandleSeries.Status.FLAT, CandleSeries.status(flat, now, 4.77))
        assertEquals(CandleSeries.Status.FLAT, CandleSeries.status(flat, now, -0.5))
        // Ticker ruhig oder unbekannt (z. B. Stablecoin): flach ist plausibel
        assertEquals(CandleSeries.Status.OK, CandleSeries.status(flat, now, 0.01))
        assertEquals(CandleSeries.Status.OK, CandleSeries.status(flat, now, null))
        assertEquals(CandleSeries.Status.OK, CandleSeries.status(flat, now, Double.NaN))
    }

    @Test
    fun status_noVolumeAtAll() {
        assertEquals(CandleSeries.Status.NO_VOLUME, CandleSeries.status(series(volume = 0.0), now, null))
    }

    @Test
    fun usable_returnsSameList() {
        val candles = series()
        assertSame(candles, CandleSeries.usable(candles, now, 1.0))
    }

    @Test
    fun isFlat_tolerance() {
        assertTrue(CandleSeries.isFlat(series(step = 0.0)))
        assertFalse(CandleSeries.isFlat(series(step = 0.001)))
        assertFalse(CandleSeries.isFlat(emptyList()))
    }

    @Test
    fun isLive_perInterval() {
        val candles = series()
        val lastOpen = candles.last().openTime
        // Stunde: mindestens 24 h Spielraum
        assertTrue(CandleSeries.isLive(candles, h, lastOpen + 24 * h))
        assertFalse(CandleSeries.isLive(candles, h, lastOpen + 24 * h + 1))
        // Woche: vier Intervalle
        assertTrue(CandleSeries.isLive(candles, 7 * 24 * h, lastOpen + 27 * 24 * h))
        assertFalse(CandleSeries.isLive(candles, 7 * 24 * h, lastOpen + 29 * 24 * h))
        assertFalse(CandleSeries.isLive(null, h, now))
        assertFalse(CandleSeries.isLive(emptyList(), h, now))
    }

    @Test
    fun change24h_prefersTicker() {
        assertEquals(4.77, CandleSeries.change24h(4.77, 0.0)!!, 1e-9)
        assertEquals(1.5, CandleSeries.change24h(null, 1.5)!!, 1e-9)
        assertEquals(1.5, CandleSeries.change24h(Double.NaN, 1.5)!!, 1e-9)
        assertNull(CandleSeries.change24h(null, null))
    }

    // ---------------- Paar für «Heute auffällig» ----------------

    private data class W(val base: String, val quote: String, val spot: Boolean, val perp: Boolean, val id: Int)

    private fun pick(items: List<W>, symbol: String) =
        CandleSeries.pickWatch(items, symbol, { it.base }, { it.quote }, { it.spot }, { it.perp })

    @Test
    fun pickWatch_spotFirstThenPerpetual() {
        val perp = W("XMR", "USDT", spot = false, perp = true, id = 1)
        val spotEur = W("XMR", "EUR", spot = true, perp = false, id = 2)
        val spotUsdt = W("xmr", "USDT", spot = true, perp = false, id = 3)
        val quarterly = W("XMR", "USDT", spot = false, perp = false, id = 4)
        assertEquals(3, pick(listOf(perp, spotEur, spotUsdt, quarterly), "XMR")?.id)
        assertEquals(2, pick(listOf(perp, spotEur, quarterly), "XMR")?.id)
        assertEquals(1, pick(listOf(quarterly, perp), "xmr")?.id)
        assertEquals(4, pick(listOf(quarterly), "XMR")?.id)
        assertNull(pick(listOf(perp), "BTC"))
    }

    // ---------------- Auswirkung auf «Warum» ----------------

    private fun why(candles: List<HourCandle>?, ticker: Double?) = ActivityAnalyzer.explain(
        WhyInput(
            baseAsset = "XMR",
            candles = candles,
            referenceCandles = series(step = -0.07),
            fundingPercent = 0.01,
            openInterestChangePercent = null,
            fearGreed = 71,
            fearGreedYesterday = 73,
            now = now,
            tickerChange24h = ticker,
        )
    )

    @Test
    fun explain_staleSeriesIsNoData() {
        val report = why(series(start = t0 - 400L * 24 * h), ticker = 4.77)
        assertFalse(report.hasMarketData)
        // Keine erfundene 0.00 % mehr: 1h unbekannt, 24h wie Pille/Karte
        assertNull(report.change1h)
        assertEquals(4.77, report.change24h!!, 1e-9)
        assertNull(report.price)
        assertTrue(report.reasons.none { it.kind == ReasonKind.VOLUME_NORMAL || it.kind == ReasonKind.VOLATILITY_NORMAL })
        // Coin gegen den Markt (BTC rund −1.8 %) statt «folgt dem Gesamtmarkt»
        assertEquals(ReasonKind.AGAINST_MARKET, report.reasons.first().kind)
    }

    @Test
    fun explain_tickerChangeWinsOverCandles() {
        val report = why(series(), ticker = 4.77)
        assertTrue(report.hasMarketData)
        assertEquals(4.77, report.change24h!!, 1e-9)
        val market = report.reasons.first { it.kind == ReasonKind.AGAINST_MARKET }
        assertEquals(4.77, market.value, 1e-9)
    }

    @Test
    fun explain_withoutTickerUsesCandles() {
        val report = why(series(), ticker = null)
        assertTrue(report.hasMarketData)
        assertEquals((102.6 / 100.0 - 1.0) * 100.0, report.change24h!!, 0.3)
    }
}
