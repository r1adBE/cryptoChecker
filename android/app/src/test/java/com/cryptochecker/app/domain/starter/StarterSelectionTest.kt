package com.cryptochecker.app.domain.starter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterSelectionTest {

    private val coins = StarterCoins.FALLBACK

    @Test
    fun allSelectedByDefaultInDisplayOrder() {
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL"), StarterSelection.selected(coins, emptySet()))
        assertTrue(StarterSelection.allSelected(coins, emptySet()))
    }

    @Test
    fun toggleSingleCoin() {
        val off = StarterSelection.toggle(emptySet(), "ETH")
        assertEquals(listOf("BTC", "XRP", "BNB", "SOL"), StarterSelection.selected(coins, off))
        assertFalse(StarterSelection.isSelected("ETH", off))
        assertFalse(StarterSelection.allSelected(coins, off))
        val on = StarterSelection.toggle(off, "ETH")
        assertTrue(on.isEmpty())
        assertTrue(StarterSelection.isSelected("ETH", on))
    }

    @Test
    fun selectAllAndNone() {
        // Alle gewählt → «Keine auswählen»
        val none = StarterSelection.toggleAll(coins, emptySet())
        assertTrue(StarterSelection.selected(coins, none).isEmpty())
        // Teilweise oder keine gewählt → «Alle auswählen»
        assertTrue(StarterSelection.toggleAll(coins, none).isEmpty())
        assertTrue(StarterSelection.toggleAll(coins, setOf("BTC")).isEmpty())
    }

    @Test
    fun newCoinsOfFresherListAreSelected() {
        val deselected = setOf("BNB")
        val fresher = coins.filterNot { it.symbol == "XRP" } + StarterPairs.Coin("Cardano", "ADA")
        assertEquals(listOf("BTC", "ETH", "SOL", "ADA"), StarterSelection.selected(fresher, deselected))
        // Abwahl eines Coins, der nicht mehr in der Liste ist, stört nicht
        assertTrue(StarterSelection.allSelected(fresher.filterNot { it.symbol == "BNB" }, deselected))
    }

    @Test
    fun changeFromOpenAndLast() {
        assertEquals(10.0, StarterPrices.changePercent(100.0, 110.0)!!, 1e-9)
        assertEquals(-2.5, StarterPrices.changePercent(200.0, 195.0)!!, 1e-9)
        assertNull(StarterPrices.changePercent(0.0, 110.0))
        assertNull(StarterPrices.changePercent(null, 110.0))
        assertNull(StarterPrices.changePercent(100.0, null))
        assertNull(StarterPrices.changePercent(Double.NaN, 110.0))
    }

    @Test
    fun validPriceOnlyPositiveFinite() {
        assertEquals(98_450.0, StarterPrices.validPrice(98_450.0)!!, 0.0)
        assertNull(StarterPrices.validPrice(0.0))
        assertNull(StarterPrices.validPrice(-1.0))
        assertNull(StarterPrices.validPrice(Double.POSITIVE_INFINITY))
        assertNull(StarterPrices.validPrice(null))
    }

    @Test
    fun priceCacheSixtySeconds() {
        val now = 1_800_000_000_000L
        assertTrue(StarterPrices.isFresh(now - 59_999L, now))
        assertFalse(StarterPrices.isFresh(now - 60_000L, now))
        assertFalse(StarterPrices.isFresh(now + 1_000L, now))
        assertFalse(StarterPrices.isFresh(0L, now))
    }

    @Test
    fun binanceSymbolsAndCacheKey() {
        assertEquals("[\"BTCUSDT\",\"ETHUSDT\"]", StarterPrices.binanceSymbols(listOf("btc", "ETH"), "usdt"))
        assertEquals(
            StarterPrices.cacheKey("Binance", listOf("ETH", "BTC")),
            StarterPrices.cacheKey("Binance", listOf("btc", "eth"))
        )
        assertFalse(
            StarterPrices.cacheKey("Binance", listOf("BTC")) == StarterPrices.cacheKey("Coinbase", listOf("BTC"))
        )
    }
}
