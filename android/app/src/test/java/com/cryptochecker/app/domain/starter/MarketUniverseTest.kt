package com.cryptochecker.app.domain.starter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketUniverseTest {

    private fun mc(symbol: String, rank: Int?, cap: Double? = 1e9, name: String = symbol) =
        StarterCoins.MarketCoin(symbol.lowercase(), name, rank, cap)

    private val ranked = listOf(
        mc("BTC", 1, 1.2e12, "Bitcoin"), mc("ETH", 2), mc("USDT", 3), mc("XRP", 4), mc("BNB", 5),
        mc("SOL", 6), mc("USDC", 7), mc("STETH", 8), mc("DOGE", 9), mc("TRX", 10), mc("ADA", 11),
        mc("HYPE", 12), mc("LINK", 13), mc("XLM", 14), mc("BCH", 15),
    )

    private val binance = setOf("BTC", "ETH", "XRP", "BNB", "SOL", "DOGE", "TRX", "ADA", "LINK", "XLM", "BCH")

    @Test
    fun pick_withoutStablecoinsWrappedAndMissingPairs_inRankOrder() {
        val picked = MarketUniverse.pick(ranked.shuffled(), binance)!!
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL", "DOGE", "TRX", "ADA", "LINK", "XLM", "BCH"),
            picked.map { it.symbol })
        assertEquals(1.2e12, picked.first().marketCap!!, 0.0)
        assertEquals("Bitcoin", picked.first().name)
    }

    @Test
    fun pick_atMostThirty() {
        val many = (1..60).map { mc("C$it", it) }
        val picked = MarketUniverse.pick(many, many.map { it.symbol.uppercase() }.toSet())!!
        assertEquals(MarketUniverse.COUNT, picked.size)
        assertEquals("C1", picked.first().symbol)
    }

    @Test
    fun pick_tooFew_null() {
        assertNull(MarketUniverse.pick(ranked, setOf("BTC", "ETH", "SOL")))
    }

    @Test
    fun pick_starterPickUnchanged() {
        // Die Start-Merkliste nimmt weiterhin genau fünf
        assertEquals(5, StarterCoins.pick(ranked, binance)!!.size)
    }

    @Test
    fun pick_invalidCapIgnored() {
        val coins = ranked.map { if (it.symbol == "eth") it.copy(marketCap = -1.0) else it }
        assertNull(MarketUniverse.pick(coins, binance)!!.first { it.symbol == "ETH" }.marketCap)
    }

    @Test
    fun encodeDecode_roundTrip() {
        val picked = MarketUniverse.pick(ranked, binance)!!
        assertEquals(picked, MarketUniverse.decode(MarketUniverse.encode(picked)))
    }

    @Test
    fun decode_invalid() {
        assertNull(MarketUniverse.decode(null))
        assertNull(MarketUniverse.decode(""))
        assertNull(MarketUniverse.decode("BTC|Bitcoin|1"))
        assertNull(MarketUniverse.decode((1..8).joinToString(";") { "C$it||" }))
        assertNull(MarketUniverse.decode((1..8).joinToString(";") { "C1|X|" }))
    }

    @Test
    fun freshness() {
        assertTrue(MarketUniverse.isFresh(1_000L, 2_000L))
        assertFalse(MarketUniverse.isFresh(0L, 2_000L))
        assertFalse(MarketUniverse.isFresh(3_000L, 2_000L))
    }

    @Test
    fun binanceSymbols_format() {
        val coins = listOf(MarketUniverse.Coin("BTC", "Bitcoin", null), MarketUniverse.Coin("ETH", "Ethereum", null))
        assertEquals("[\"BTCUSDT\",\"ETHUSDT\"]", MarketUniverse.binanceSymbols(coins))
    }
}
