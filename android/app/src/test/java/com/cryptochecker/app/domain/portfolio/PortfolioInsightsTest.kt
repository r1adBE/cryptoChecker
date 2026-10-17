package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Aufteilung», «Grösste Bewegungen» und «Beträge verbergen» (Spiegel: PortfolioInsightsTests.swift). */
class PortfolioInsightsTest {

    private fun position(coin: String, value: Double?) = CoinPosition(
        coin = coin, holdings = 1.0, avgCost = null, costBasis = null, currentPrice = value, value = value,
        unrealized = null, unrealizedPercent = null, realized = 0.0, priceMissing = false, oversold = false, tradeCount = 1,
    )

    @Test
    fun allocation_topFourAndOthers() {
        val open = listOf(
            position("BTC", 500.0), position("ETH", 200.0), position("SOL", 100.0),
            position("ADA", 100.0), position("DOT", 60.0), position("XRP", 40.0),
        )
        val slices = PortfolioInsights.allocation(open)
        assertEquals(listOf("BTC", "ETH", "ADA", "SOL", null), slices.map { it.coin })
        assertEquals(50.0, slices[0].sharePercent, 1e-9)
        assertEquals(100.0, slices.last().valueUsd, 1e-9)
        assertEquals(10.0, slices.last().sharePercent, 1e-9)
        assertTrue(slices.last().isOther)
        assertEquals(100.0, slices.sumOf { it.sharePercent }, 1e-9)
    }

    @Test
    fun allocation_noOthersWithFourOrFewer_andSkipsUnpriced() {
        val slices = PortfolioInsights.allocation(listOf(position("BTC", 300.0), position("ETH", null), position("SOL", 100.0)))
        assertEquals(listOf("BTC", "SOL"), slices.map { it.coin })
        assertEquals(75.0, slices[0].sharePercent, 1e-9)
        assertTrue(PortfolioInsights.allocation(listOf(position("ETH", null), position("X", 0.0))).isEmpty())
    }

    @Test
    fun valueChange_fromPercent() {
        // 110 jetzt nach +10 % → damals 100, also +10
        assertEquals(10.0, PortfolioInsights.valueChange(110.0, 10.0)!!, 1e-9)
        // 90 jetzt nach −10 % → damals 100, also −10
        assertEquals(-10.0, PortfolioInsights.valueChange(90.0, -10.0)!!, 1e-9)
        assertNull(PortfolioInsights.valueChange(90.0, -100.0))
        assertNull(PortfolioInsights.valueChange(Double.NaN, 1.0))
    }

    @Test
    fun movers_byAbsoluteValueChange() {
        val open = listOf(position("BTC", 1000.0), position("ETH", 500.0), position("SOL", 50.0), position("ADA", 200.0), position("DOT", 80.0))
        val changes = mapOf("BTC" to 1.0, "ETH" to -10.0, "SOL" to 50.0, "ADA" to 0.0, "XRP" to 99.0)
        val movers = PortfolioInsights.movers(open, changes)
        // ETH −55.56, SOL +16.67, BTC +9.90; ADA ohne Bewegung, DOT ohne Veränderung
        assertEquals(listOf("ETH", "SOL", "BTC"), movers.map { it.coin })
        assertEquals(-500.0 + 500.0 / 0.9, -movers[0].changeUsd, 1e-9)
        assertTrue(movers[0].changeUsd < 0)
        assertEquals(-10.0, movers[0].changePercent, 1e-9)
    }

    @Test
    fun movers_limitAndEmpty() {
        assertTrue(PortfolioInsights.movers(listOf(position("BTC", 100.0)), emptyMap()).isEmpty())
        val open = (1..5).map { position("C$it", it * 100.0) }
        val changes = open.associate { it.coin to 5.0 }
        assertEquals(listOf("C5", "C4", "C3"), PortfolioInsights.movers(open, changes).map { it.coin })
    }

    @Test
    fun mask() {
        assertEquals("•••", PortfolioInsights.mask("1’234.00 USDT", hidden = true))
        assertEquals("1’234.00 USDT", PortfolioInsights.mask("1’234.00 USDT", hidden = false))
    }
}
