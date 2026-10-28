package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioCalculatorTest {

    private var nextId = 1L

    private fun buy(coin: String, amount: Double, price: Double?, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, price, time)

    private fun sell(coin: String, amount: Double, price: Double?, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.SELL, amount, price, time)

    private val d = 1e-9

    @Test
    fun averageCostOverTwoBuys() {
        val trades = listOf(buy("BTC", 1.0, 100.0, 1), buy("BTC", 3.0, 200.0, 2))
        val p = PortfolioCalculator.position("BTC", trades, 250.0)
        assertEquals(4.0, p.holdings, d)
        assertEquals(175.0, p.avgCost!!, d)          // (100 + 600) / 4
        assertEquals(700.0, p.costBasis!!, d)
        assertEquals(1000.0, p.value!!, d)
        assertEquals(300.0, p.unrealized!!, d)
        assertEquals(300.0 / 700.0 * 100.0, p.unrealizedPercent!!, d)
        assertFalse(p.priceMissing)
        assertFalse(p.oversold)
    }

    @Test
    fun tradesAreProcessedInTimeOrderNotListOrder() {
        // Verkauf liegt zeitlich zwischen den Käufen
        val trades = listOf(
            buy("ETH", 1.0, 300.0, 30),
            sell("ETH", 1.0, 200.0, 20),
            buy("ETH", 1.0, 100.0, 10),
        )
        val p = PortfolioCalculator.position("ETH", trades, 300.0)
        assertEquals(1.0, p.holdings, d)
        assertEquals(300.0, p.avgCost!!, d)          // erster Lot ganz verkauft
        assertEquals(100.0, p.realized, d)           // (200 − 100) × 1
        assertFalse(p.oversold)
    }

    @Test
    fun sellKeepsAverageAndBooksRealized() {
        val trades = listOf(
            buy("BTC", 2.0, 100.0, 1),
            buy("BTC", 2.0, 200.0, 2),
            sell("BTC", 1.0, 300.0, 3),
        )
        val p = PortfolioCalculator.position("BTC", trades, 150.0, CostMethod.AVERAGE)
        assertEquals(3.0, p.holdings, d)
        assertEquals(150.0, p.avgCost!!, d)          // Ø bleibt
        assertEquals(450.0, p.costBasis!!, d)
        assertEquals(150.0, p.realized, d)           // (300 − 150) × 1
        assertEquals(0.0, p.unrealized!!, d)
    }

    @Test
    fun realizedLossIsNegative() {
        val trades = listOf(buy("SOL", 10.0, 50.0, 1), sell("SOL", 4.0, 40.0, 2))
        val p = PortfolioCalculator.position("SOL", trades, 40.0)
        assertEquals(-40.0, p.realized, d)
        assertEquals(6.0, p.holdings, d)
        assertEquals(-60.0, p.unrealized!!, d)
    }

    @Test
    fun buyWithoutPriceMarksPriceMissing() {
        val trades = listOf(buy("BTC", 1.0, null, 1), buy("BTC", 1.0, 100.0, 2))
        val p = PortfolioCalculator.position("BTC", trades, 120.0)
        assertEquals(2.0, p.holdings, d)
        assertEquals(100.0, p.avgCost!!, d)          // nur über Käufe mit Preis
        assertTrue(p.priceMissing)
        assertNull(p.costBasis)
        // «Seit Kauf» nur für den Teil mit Kaufpreis: 1 × 120 − 100, markiert «ohne 1 BTC ohne Kaufpreis»
        assertEquals(20.0, p.unrealized!!, d)
        assertEquals(20.0, p.unrealizedPercent!!, d)
        assertTrue(p.unrealizedPartial)
        assertEquals(1.0, p.unpricedHoldingsExact.toDouble(), d)
        assertEquals(240.0, p.value!!, d)            // Wert trotzdem bekannt
    }

    @Test
    fun onlyUnpricedBuysHaveNoAverage() {
        val p = PortfolioCalculator.position("ADA", listOf(buy("ADA", 5.0, null, 1)), 1.0)
        assertNull(p.avgCost)
        assertTrue(p.priceMissing)
        assertEquals(5.0, p.value!!, d)
    }

    @Test
    fun sellWhileCostMissingRealizesPricedShare() {
        // Gemischt: 1 ohne Preis + 1 zu 100, Teilverkauf 1 zu 300
        val trades = listOf(
            buy("BTC", 1.0, null, 1),
            buy("BTC", 1.0, 100.0, 2),
            sell("BTC", 1.0, 300.0, 3),
        )
        val p = PortfolioCalculator.position("BTC", trades, 300.0, CostMethod.AVERAGE)
        assertEquals(1.0, p.holdings, d)
        // Hälfte des Verkaufs mit Kaufpreis: (300 − 100) × 0.5; die andere Hälfte unbekannt
        assertEquals(100.0, p.realized, d)
        assertEquals(0.5, p.realizedUnknownAmount, d)
        assertTrue(p.realizedIncomplete)
        assertTrue(p.priceMissing)                   // anteilig verkauft, Rest weiter ohne Preis
        assertEquals(100.0, p.avgCost!!, d)
    }

    @Test
    fun mixedBuysThenPartialSellWithUnevenShares() {
        // 3 zu 100 + 1 ohne Preis; Verkauf 2 zu 150 → 1.5 mit Kaufpreis, 0.5 unbekannt
        val trades = listOf(
            buy("ETH", 3.0, 100.0, 1),
            buy("ETH", 1.0, null, 2),
            sell("ETH", 2.0, 150.0, 3),
        )
        val p = PortfolioCalculator.position("ETH", trades, 150.0, CostMethod.AVERAGE)
        assertEquals(2.0, p.holdings, d)
        assertEquals(75.0, p.realized, d)
        assertEquals(0.5, p.realizedUnknownAmount, d)
        assertNull(p.costBasis)
    }

    @Test
    fun pricedOnlySellIsComplete() {
        val trades = listOf(buy("BTC", 2.0, 100.0, 1), sell("BTC", 1.0, 150.0, 2))
        val p = PortfolioCalculator.position("BTC", trades, 150.0)
        assertEquals(50.0, p.realized, d)
        assertFalse(p.realizedIncomplete)
    }

    @Test
    fun sellWithoutPriceBooksNoRealized() {
        val trades = listOf(buy("BTC", 2.0, 100.0, 1), sell("BTC", 1.0, null, 2))
        val p = PortfolioCalculator.position("BTC", trades, 100.0)
        assertEquals(1.0, p.holdings, d)
        assertEquals(0.0, p.realized, d)
        assertEquals(100.0, p.avgCost!!, d)
        // Verkauf ohne Preis: Gewinn/Verlust unbekannt
        assertEquals(1.0, p.realizedUnknownAmount, d)
    }

    @Test
    fun missingCurrentPriceHidesValueAndPl() {
        val trades = listOf(buy("XYZ", 1.0, 10.0, 1))
        val p = PortfolioCalculator.position("XYZ", trades, null)
        assertNull(p.value)
        assertNull(p.unrealized)
        assertEquals(10.0, p.costBasis!!, d)
        val s = PortfolioCalculator.summarize(trades, emptyMap())
        assertEquals(listOf("XYZ"), s.missingCurrentPrices)
        assertNull(s.unrealized)
        assertEquals(10.0, s.invested!!, d)
        assertEquals(0.0, s.totalValue, d)
    }

    @Test
    fun oversellIsClampedAndFlagged() {
        val trades = listOf(buy("BTC", 1.0, 100.0, 1), sell("BTC", 3.0, 150.0, 2))
        val p = PortfolioCalculator.position("BTC", trades, 200.0)
        assertTrue(p.oversold)
        assertEquals(0.0, p.holdings, d)
        assertFalse(p.isOpen)
        assertEquals(50.0, p.realized, d)            // nur die gehaltene Menge zählt
        assertNull(p.costBasis)
        assertEquals(0.0, p.value!!, d)
    }

    @Test
    fun sellingEverythingIsNotOversold() {
        val trades = listOf(
            buy("BTC", 0.1, 100.0, 1),
            buy("BTC", 0.2, 100.0, 2),
            sell("BTC", 0.3, 100.0, 3),             // 0.1 + 0.2 ≠ 0.3 in Double
        )
        val p = PortfolioCalculator.position("BTC", trades, 100.0)
        assertFalse(p.oversold)
        assertFalse(p.isOpen)
    }

    @Test
    fun buyAfterFullSellStartsFreshAverage() {
        val trades = listOf(
            buy("BTC", 1.0, 100.0, 1),
            sell("BTC", 1.0, 200.0, 2),
            buy("BTC", 1.0, 300.0, 3),
        )
        val p = PortfolioCalculator.position("BTC", trades, 300.0)
        assertEquals(300.0, p.avgCost!!, d)
        assertEquals(100.0, p.realized, d)
        assertEquals(0.0, p.unrealized!!, d)
    }

    @Test
    fun usdtIsWorthOne() {
        val p = PortfolioCalculator.position("usdt", listOf(buy("USDT", 500.0, 1.0, 1)), null)
        assertEquals("USDT", p.coin)
        assertEquals(1.0, p.currentPrice!!, d)
        assertEquals(500.0, p.value!!, d)
    }

    @Test
    fun summaryTotalsAndSorting() {
        val trades = listOf(
            buy("BTC", 1.0, 100.0, 1),
            buy("ETH", 10.0, 10.0, 1),
            buy("DOGE", 100.0, 1.0, 1),
            sell("DOGE", 100.0, 2.0, 2),             // geschlossen, realisiert +100
        )
        val s = PortfolioCalculator.summarize(trades, mapOf("BTC" to 150.0, "ETH" to 30.0, "DOGE" to 3.0))
        assertEquals(listOf("ETH", "BTC"), s.open.map { it.coin })   // 300 vor 150
        assertEquals(listOf("DOGE"), s.closed.map { it.coin })
        assertEquals(450.0, s.totalValue, d)
        assertEquals(200.0, s.invested!!, d)
        assertEquals(250.0, s.unrealized!!, d)
        assertEquals(125.0, s.unrealizedPercent!!, d)
        assertEquals(100.0, s.realized, d)
        assertFalse(s.costMissing)
        assertTrue(s.missingCurrentPrices.isEmpty())
    }

    @Test
    fun summaryExcludesOnlyCoinWithoutCost() {
        // Ein Kauf ohne Preis nimmt nur diesen Coin aus dem Gewinn seit Kauf, nicht das ganze Portfolio
        val trades = listOf(buy("BTC", 1.0, 100.0, 1), buy("ETH", 1.0, null, 1))
        val s = PortfolioCalculator.summarize(trades, mapOf("BTC" to 200.0, "ETH" to 50.0))
        assertTrue(s.costMissing)
        assertEquals(listOf("ETH"), s.missingCostCoins)
        assertEquals(100.0, s.invested!!, d)
        assertEquals(100.0, s.unrealized!!, d)
        assertEquals(100.0, s.unrealizedPercent!!, d)
        assertEquals(250.0, s.totalValue, d)
    }

    @Test
    fun summaryWithoutAnyCostHasNoGain() {
        val s = PortfolioCalculator.summarize(listOf(buy("ETH", 1.0, null, 1)), mapOf("ETH" to 50.0))
        assertNull(s.invested)
        assertNull(s.unrealized)
    }

    @Test
    fun closedAtBreakEvenStaysVisible() {
        val trades = listOf(buy("BTC", 1.0, 100.0, 1), sell("BTC", 1.0, 100.0, 2))
        val s = PortfolioCalculator.summarize(trades, mapOf("BTC" to 120.0))
        assertTrue(s.open.isEmpty())
        assertEquals(listOf("BTC"), s.closed.map { it.coin })
        assertEquals(0.0, s.closed[0].realized, d)
        assertFalse(s.isEmpty)
        assertFalse(s.realizedIncomplete)
        assertNull(s.invested)
    }

    @Test
    fun fullSellWithUnpricedBuysAppearsInClosed() {
        val trades = listOf(
            buy("SOL", 1.0, null, 1),
            buy("SOL", 1.0, 10.0, 2),
            sell("SOL", 2.0, 20.0, 3),
        )
        val s = PortfolioCalculator.summarize(trades, mapOf("SOL" to 25.0))
        assertTrue(s.open.isEmpty())
        assertEquals(listOf("SOL"), s.closed.map { it.coin })
        val p = s.closed[0]
        assertEquals(10.0, p.realized, d)            // (20 − 10) × 1
        assertEquals(1.0, p.realizedUnknownAmount, d)
        assertTrue(p.realizedIncomplete)
        assertTrue(s.realizedIncomplete)
        assertEquals(10.0, s.realized, d)
    }

    @Test
    fun fullSellOfOnlyUnpricedBuysAppearsInClosed() {
        val trades = listOf(buy("DOT", 5.0, null, 1), sell("DOT", 5.0, 7.0, 2))
        val s = PortfolioCalculator.summarize(trades, emptyMap())
        assertEquals(listOf("DOT"), s.closed.map { it.coin })
        assertEquals(0.0, s.closed[0].realized, d)
        assertTrue(s.closed[0].realizedIncomplete)
    }

    @Test
    fun sellWithoutHoldingsIsNotClosed() {
        val s = PortfolioCalculator.summarize(listOf(sell("ADA", 1.0, 1.0, 1)), emptyMap())
        assertTrue(s.closed.isEmpty())
        assertTrue(s.isEmpty)
    }

    @Test
    fun coinsAreMatchedCaseInsensitive() {
        val trades = listOf(buy("btc", 1.0, 100.0, 1), buy(" BTC ", 1.0, 200.0, 2))
        val s = PortfolioCalculator.summarize(trades, mapOf("BTC" to 150.0))
        assertEquals(1, s.open.size)
        assertEquals(2.0, s.open[0].holdings, d)
        assertEquals(150.0, s.open[0].avgCost!!, d)
    }
}
