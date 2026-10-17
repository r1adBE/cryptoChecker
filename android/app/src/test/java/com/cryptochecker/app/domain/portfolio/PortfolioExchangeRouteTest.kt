package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kurs von der eingetragenen Börse ([PortfolioExchangeRoute]); Swift: `PortfolioExchangeRouteTests`. */
class PortfolioExchangeRouteTest {

    private val markets = mapOf(
        "Binance" to "Binance", "Kraken" to "Kraken", "BinanceFutures" to "Binance Futures",
        "Bitstamp" to "Bitstamp", "DexScreener" to "DEX",
    )

    @Test
    fun `one exchange per coin, mixed coins fall back`() {
        val (single, mixed) = PortfolioExchangeRoute.exchangeByCoin(
            listOf("btc" to "Kraken", "BTC" to "kraken", "ETH" to "Kraken", "ETH" to "Bitstamp", "SOL" to null, "SOL" to " "),
        )
        assertEquals(mapOf("BTC" to "Kraken"), single)
        assertEquals(setOf("ETH"), mixed)
    }

    @Test
    fun `only spot markets of the app, binance and free text give no route`() {
        assertEquals("Kraken" to "Kraken", PortfolioExchangeRoute.market("kraken", markets))
        assertNull(PortfolioExchangeRoute.market("Binance", markets))
        assertNull(PortfolioExchangeRoute.market("Binance Futures", markets))
        assertNull(PortfolioExchangeRoute.market("DEX", markets))
        assertNull(PortfolioExchangeRoute.market("Meine Bank", markets))
        assertTrue(PortfolioExchangeRoute.isSpot("Kraken"))
        assertFalse(PortfolioExchangeRoute.isSpot("BybitFutures"))
    }

    @Test
    fun `pair preference usdt, usd, then fiat`() {
        val pairs = listOf(
            PortfolioExchangeRoute.Pair3("BTC", "EUR", "XXBTZEUR"),
            PortfolioExchangeRoute.Pair3("BTC", "USD", "XXBTZUSD"),
            PortfolioExchangeRoute.Pair3("ETH", "USDT", "ETHUSDT"),
            PortfolioExchangeRoute.Pair3("BTC", "USDC", "XBTUSDC"),
        )
        assertEquals("USD", PortfolioExchangeRoute.pickPair("btc", pairs)?.quote)
        assertEquals("USDT", PortfolioExchangeRoute.pickPair("ETH", pairs)?.quote)
        assertNull(PortfolioExchangeRoute.pickPair("SOL", pairs))
        // USDC allein reicht nicht (gilt im Portfolio nicht automatisch als 1)
        assertNull(PortfolioExchangeRoute.pickPair("BTC", listOf(PortfolioExchangeRoute.Pair3("BTC", "USDC", null))))
    }

    @Test
    fun `conversion to usdt`() {
        assertEquals(100.0, PortfolioExchangeRoute.toUsdt(100.0, "USD") { null }!!, 1e-12)
        // 1 USD = 0.8 CHF → 80 CHF = 100 USDT
        assertEquals(100.0, PortfolioExchangeRoute.toUsdt(80.0, "CHF") { if (it == "CHF") 0.8 else null }!!, 1e-9)
        assertNull(PortfolioExchangeRoute.toUsdt(80.0, "CHF") { null })
        assertNull(PortfolioExchangeRoute.toUsdt(80.0, "XYZ") { 1.0 })
        assertNull(PortfolioExchangeRoute.toUsdt(0.0, "USD") { null })
    }

    @Test
    fun `daily closes are scaled to the exchange price`() {
        val closes = mapOf("BTC" to mapOf(1L to 100.0, 2L to 110.0), "ETH" to mapOf(1L to 10.0))
        val scaled = PortfolioExchangeRoute.scaleCloses(closes, mapOf("BTC" to 101.0, "ETH" to 10.0), mapOf("BTC" to 100.0))
        assertEquals(101.0, scaled.getValue("BTC").getValue(1L), 1e-9)
        assertEquals(111.1, scaled.getValue("BTC").getValue(2L), 1e-9)
        assertEquals(10.0, scaled.getValue("ETH").getValue(1L), 1e-9)
    }

    @Test
    fun `history ranges follow their closes - scaled with the factor, dropped without reference`() {
        val closes = mapOf("BTC" to mapOf(1L to 100.0), "SOL" to mapOf(1L to 20.0), "ETH" to mapOf(1L to 10.0))
        val ranges = mapOf(
            "BTC" to mapOf(1L to PortfolioDayRange(98.0, 104.0, 96.0)),
            "SOL" to mapOf(1L to PortfolioDayRange(19.0, 21.0, 18.0)),
            "ETH" to mapOf(1L to PortfolioDayRange(9.0, 11.0, 8.0)),
        )
        // BTC Ausweich mit Bezug, SOL Ausweich ohne Binance-Kurs (Neustart), ETH ohne eigene Börse
        val result = PortfolioExchangeRoute.historyCloses(
            closes, ranges, own = emptySet(), routed = setOf("BTC", "SOL"), failed = emptySet(),
            prices = mapOf("BTC" to 102.0, "SOL" to 21.0, "ETH" to 10.0), binance = mapOf("BTC" to 100.0),
        )
        assertEquals(setOf("BTC", "ETH"), result.closes.keys)
        assertEquals(setOf("BTC", "ETH"), result.ranges.keys)
        assertEquals(106.08, result.ranges.getValue("BTC").getValue(1L).high, 1e-9)
        assertEquals(9.0, result.ranges.getValue("ETH").getValue(1L).open, 1e-12)
        assertEquals(setOf("BTC"), result.scaled)
    }

    @Test
    fun `own daily candles to usdt convert open high low with the same day rate`() {
        val daily = PortfolioCoinDaily(mapOf(10L to 90.0), mapOf(10L to PortfolioDayRange(88.0, 99.0, 81.0)))
        val usdt = PortfolioExchangeRoute.ownDailyUsdt(daily, "EUR", mapOf(10L to 0.9))!!
        assertEquals(100.0, usdt.closes.getValue(10L), 1e-9)
        assertEquals(110.0, usdt.ranges.getValue(10L).high, 1e-9)
        assertEquals(90.0, usdt.ranges.getValue(10L).low, 1e-9)
    }

    @Test
    fun `stitched binance days keep their wicks scaled, own days keep their own`() {
        val own = PortfolioCoinDaily(mapOf(5L to 200.0), mapOf(5L to PortfolioDayRange(190.0, 210.0, 180.0)))
        val binance = PortfolioCoinDaily(
            mapOf(4L to 95.0, 5L to 100.0),
            mapOf(4L to PortfolioDayRange(90.0, 99.0, 89.0), 5L to PortfolioDayRange(1.0, 1.0, 1.0)),
        )
        val stitched = PortfolioExchangeRoute.stitch(own, binance)
        assertEquals(5L, stitched.binanceBefore)
        assertEquals(198.0, stitched.daily.ranges.getValue(4L).high, 1e-9)
        assertEquals(210.0, stitched.daily.ranges.getValue(5L).high, 1e-12)
        assertNull(PortfolioExchangeRoute.stitch(own, null).binanceBefore)
    }

    @Test
    fun `sources text groups by exchange`() {
        assertEquals(
            "Bitstamp (SOL); Kraken (BTC, ETH)",
            PortfolioExchangeRoute.sourcesText(mapOf("ETH" to "Kraken", "BTC" to "Kraken", "SOL" to "Bitstamp")),
        )
    }

    @Test
    fun usdtParityShownWhenValuesDependOnIt() {
        // CHF mit Devisenkurs: umgerechnet aus USDT
        assertTrue(PortfolioExchangeRoute.usdtParityShown("CHF", converted = true, sources = emptyMap()))
        // USD gewählt: USDT steht für USD
        assertTrue(PortfolioExchangeRoute.usdtParityShown("usd", converted = false, sources = emptyMap()))
        // Kraken-Kurse (USD) gemischt mit Binance (USDT)
        assertTrue(PortfolioExchangeRoute.usdtParityShown("USDT", converted = false, sources = mapOf("BTC" to "Kraken")))
        // Reine USDT-Anzeige mit Binance-Kursen (oder CHF ohne Devisenkurs): nichts hängt daran
        assertFalse(PortfolioExchangeRoute.usdtParityShown("USDT", converted = false, sources = emptyMap()))
        assertFalse(PortfolioExchangeRoute.usdtParityShown("CHF", converted = false, sources = emptyMap()))
    }
}
