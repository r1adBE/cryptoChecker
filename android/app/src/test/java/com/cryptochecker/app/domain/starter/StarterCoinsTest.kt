package com.cryptochecker.app.domain.starter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterCoinsTest {

    private fun c(symbol: String, name: String, rank: Int?) = StarterCoins.MarketCoin(symbol, name, rank)

    /** Ungefähr die CoinGecko-Rangliste 2026, gemischt mit Stablecoins und Doppelgängern. */
    private val ranked = listOf(
        c("BTC", "Bitcoin", 1),
        c("ETH", "Ethereum", 2),
        c("USDT", "Tether", 3),
        c("XRP", "XRP", 4),
        c("BNB", "BNB", 5),
        c("SOL", "Solana", 6),
        c("USDC", "USDC", 7),
        c("STETH", "Lido Staked Ether", 8),
        c("TRX", "TRON", 9),
        c("DOGE", "Dogecoin", 10),
        c("ADA", "Cardano", 11),
        c("WBTC", "Wrapped Bitcoin", 12),
        c("HYPE", "Hyperliquid", 13),
    )

    private val binance = setOf("BTC", "ETH", "XRP", "BNB", "SOL", "TRX", "DOGE", "ADA", "USDC", "WBTC")
    private val coinbase = setOf("BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "USDT")

    @Test
    fun topFiveWithoutStablecoinsAndWrapped() {
        assertEquals(
            listOf("BTC", "ETH", "XRP", "BNB", "SOL"),
            StarterCoins.pick(ranked, binance)!!.map { it.symbol }
        )
    }

    @Test
    fun onlyCoinsListedAtStarterMarket() {
        // Coinbase (USA): kein BNB, also rückt DOGE nach
        assertEquals(
            listOf("BTC", "ETH", "XRP", "SOL", "DOGE"),
            StarterCoins.pick(ranked, coinbase)!!.map { it.symbol }
        )
    }

    @Test
    fun ordersByRankNotByListPosition() {
        val shuffled = ranked.reversed()
        assertEquals(
            listOf("BTC", "ETH", "XRP", "BNB", "SOL"),
            StarterCoins.pick(shuffled, binance)!!.map { it.symbol }
        )
        // Ohne Rang ans Ende
        val noRank = listOf(c("ADA", "Cardano", null)) + ranked
        assertEquals("BTC", StarterCoins.pick(noRank, binance)!!.first().symbol)
    }

    @Test
    fun namesAndSymbolsFromList() {
        val coins = StarterCoins.pick(ranked.map { it.copy(symbol = it.symbol.lowercase()) }, binance)!!
        assertEquals(StarterPairs.Coin("Bitcoin", "BTC"), coins.first())
        assertEquals(StarterPairs.Coin("Solana", "SOL"), coins.last())
    }

    @Test
    fun tooFewMeansFallback() {
        assertNull(StarterCoins.pick(ranked, setOf("BTC", "ETH")))
        assertNull(StarterCoins.pick(emptyList(), binance))
    }

    @Test
    fun duplicatesCountOnce() {
        val dup = listOf(c("BTC", "Bitcoin", 1), c("btc", "Bitcoin (alt)", 2)) + ranked.drop(1)
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL"), StarterCoins.pick(dup, binance)!!.map { it.symbol })
    }

    @Test
    fun stablecoinHeuristic() {
        assertTrue(StarterCoins.isExcluded(c("USDE", "Ethena USDe", 20)))
        assertTrue(StarterCoins.isExcluded(c("PYUSD", "PayPal USD", 40)))
        assertTrue(StarterCoins.isExcluded(c("USDQ", "Some new stable", 50)))       // beginnt mit USD
        assertTrue(StarterCoins.isExcluded(c("XUSD", "Another", 51)))               // endet mit USD
        assertTrue(StarterCoins.isExcluded(c("GLO", "Glo Dollar", 52)))
        assertTrue(StarterCoins.isExcluded(c("EURQ", "Quantoz Euro", 53)))
        assertTrue(StarterCoins.isExcluded(c("BSC-USD", "Binance Bridged USDT (BNB Smart Chain)", 9)))
        assertTrue(StarterCoins.isExcluded(c("WEETH", "Wrapped eETH", 15)))
        assertTrue(StarterCoins.isExcluded(c("CBBTC", "Coinbase Wrapped BTC", 16)))
        assertTrue(StarterCoins.isExcluded(c("WSTETH", "Wrapped stETH", 17)))
        assertTrue(StarterCoins.isExcluded(c("", "Leer", 1)))
        // Kein Fehlalarm bei Wörtern, die nur «euro»/«usd» enthalten
        assertFalse(StarterCoins.isExcluded(c("NCN", "Neurochain", 60)))
        assertFalse(StarterCoins.isExcluded(c("SUI", "Sui", 18)))
        assertFalse(StarterCoins.isExcluded(c("XRP", "XRP", 4)))
    }

    @Test
    fun cacheRoundTrip() {
        val coins = StarterCoins.FALLBACK
        assertEquals(coins, StarterCoins.decode(StarterCoins.encode(coins)))
        // Trennzeichen im Namen stören nicht
        val odd = coins.dropLast(1) + StarterPairs.Coin("Odd;Name|X", "ODD")
        assertEquals("Odd Name X", StarterCoins.decode(StarterCoins.encode(odd))!!.last().name)
    }

    @Test
    fun cacheRejectsBrokenContent() {
        assertNull(StarterCoins.decode(null))
        assertNull(StarterCoins.decode(""))
        assertNull(StarterCoins.decode("BTC|Bitcoin;ETH|Ethereum"))               // zu wenige
        assertNull(StarterCoins.decode("BTC|Bitcoin;BTC|Bitcoin;A|a;B|b;C|c"))   // doppelt
        assertNull(StarterCoins.decode("BTC|;ETH|Ethereum;A|a;B|b;C|c"))         // ohne Namen
    }

    @Test
    fun freshForOneDay() {
        val now = 1_800_000_000_000L
        assertTrue(StarterCoins.isFresh(now - 60_000, now))
        assertTrue(StarterCoins.isFresh(now - StarterCoins.TTL_MILLIS + 1, now))
        assertFalse(StarterCoins.isFresh(now - StarterCoins.TTL_MILLIS, now))
        assertFalse(StarterCoins.isFresh(0L, now))
        assertFalse(StarterCoins.isFresh(now + 60_000, now))   // Uhr zurückgestellt
    }
}
