package com.cryptochecker.app.domain.starter

import org.junit.Assert.assertEquals
import org.junit.Test

class StarterPairsTest {

    @Test
    fun binanceUsdtOutsideUs() {
        val p = StarterPairs.pairFor("btc", "CH")
        assertEquals(StarterPairs.Pair("Binance", "Binance", "BTC", "USDT", "BTCUSDT"), p)
        assertEquals("Binance", StarterPairs.pairFor("ETH", null).marketKey)
        assertEquals("Binance", StarterPairs.pairFor("ETH", "").marketKey)
    }

    @Test
    fun coinbaseUsdInUs() {
        assertEquals(
            StarterPairs.Pair("Coinbase", "Coinbase", "SOL", "USD", "SOL-USD"),
            StarterPairs.pairFor("SOL", "us")
        )
    }

    @Test
    fun marketNameFromRegistry() {
        assertEquals("Binance Spot", StarterPairs.pairFor("BTC", "DE") { "Binance Spot" }.marketName)
    }

    @Test
    fun skipsExistingAndDuplicates() {
        val wanted = listOf("BTC", "ETH", "SOL").map { StarterPairs.pairFor(it, "CH") } +
            StarterPairs.pairFor("BTC", "CH")
        val existing = listOf(
            Triple("Binance", "btc", "usdt"),
            Triple("Coinbase", "ETH", "USD"),   // andere Börse: zählt nicht
        )
        assertEquals(listOf("ETH", "SOL"), StarterPairs.missing(wanted, existing).map { it.base })
    }

    @Test
    fun fallbackFiveCoins() {
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL"), StarterCoins.fallback("CH").map { it.symbol })
        // Coinbase führt kein BNB
        assertEquals(listOf("BTC", "ETH", "XRP", "SOL", "DOGE"), StarterCoins.fallback("US").map { it.symbol })
    }
}
