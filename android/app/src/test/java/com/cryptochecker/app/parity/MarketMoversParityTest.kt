@file:Suppress("UNCHECKED_CAST")

package com.cryptochecker.app.parity

import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.MarketMovers
import com.cryptochecker.app.domain.market.MarketMoversResult
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.market.PulseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Gewinner und Verlierer 24 h» aus `market_movers.json` (iOS: `MarketMoversTests`). */
class MarketMoversParityTest {

    private val data = ParityJson.load("market_movers.json")

    private fun coins(raw: Any?): List<MoverCoin> = raw.list().map { it.obj() }.map {
        MoverCoin(
            symbol = it["symbol"] as String,
            name = it["name"] as String,
            rank = it["rank"].num()?.toInt(),
            change24h = it["change"].num(),
        )
    }

    private fun expected(raw: Any?): List<Triple<String, String, Double>> =
        raw.list().map { it.obj() }.map { Triple(it["symbol"] as String, it["name"] as String, it["change"].num()!!) }

    private fun actual(list: List<MoverCoin>) = list.map { Triple(it.symbol, it.name, it.change24h!!) }

    @Test
    fun constants() {
        assertEquals(data["universe"].num()!!.toInt(), MarketMovers.UNIVERSE)
        assertEquals(data["count"].num()!!.toInt(), MarketMovers.COUNT)
        assertEquals(data["minCoins"].num()!!.toInt(), MarketMovers.MIN_COINS)
    }

    @Test
    fun cases() {
        data["cases"].list().map { it.obj() }.forEach { c ->
            val note = c["note"] as String
            val result = MarketMovers.select(coins(c["coins"]))
            assertEquals("gainers: $note", expected(c["gainers"]), actual(result.gainers))
            assertEquals("losers: $note", expected(c["losers"]), actual(result.losers))
            // Nie derselbe Coin in beiden Listen, nie mehr als COUNT
            assertTrue(note, result.gainers.none { g -> result.losers.any { it.symbol == g.symbol } })
            assertTrue(note, result.gainers.size <= MarketMovers.COUNT && result.losers.size <= MarketMovers.COUNT)
        }
    }

    @Test
    fun emptyInput() {
        assertTrue(MarketMovers.select(emptyList()).isEmpty)
    }

    /** Pulse reicht die Liste durch; leer = keine Karte. */
    @Test
    fun pulseReportCarriesMovers() {
        val movers = MarketMovers.select(coins(data["cases"].list().first().obj()["coins"]))
        val input = PulseInput(1.0, 1.0, 1.0, null, null, null, null, time = 1L, movers = movers)
        assertEquals(movers, CryptoPulse.evaluate(input)!!.movers)
        assertNull(CryptoPulse.evaluate(input.copy(movers = MarketMoversResult(emptyList(), emptyList())))!!.movers)
        assertNull(CryptoPulse.evaluate(input.copy(movers = null))!!.movers)
    }

    /** Zwischenspeicher auf dem Gerät: hin und zurück gleich; ältere Einträge ohne «movers» = null. */
    @Test
    fun cacheRoundTrip() {
        val movers = MarketMovers.select(coins(data["cases"].list().first().obj()["coins"]))
        val input = PulseInput(1.0, -2.0, 3.0, null, null, null, null, time = 5L, topChanges = listOf(1.0, -1.0), movers = movers)
        val json = CycleCacheCodecs.pulseInput.encode(input)
        assertEquals(input, CycleCacheCodecs.pulseInput.decode(json))
        json.remove("movers")
        assertNull(CycleCacheCodecs.pulseInput.decode(json).movers)
    }
}
