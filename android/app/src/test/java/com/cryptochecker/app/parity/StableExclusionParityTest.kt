package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.market.MarketMovers
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.starter.CoinExclusion
import com.cryptochecker.app.domain.starter.StarterCoins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stablecoins und Doppelgänger aus `stable_exclusion.json` (iOS: `StableExclusionParityTests.swift`). */
class StableExclusionParityTest {

    private val data = ParityJson.load("stable_exclusion.json")

    /** Beide Plattformen führen genau dieselben Listen. */
    @Test
    fun listsMatchFixture() {
        assertEquals(data["stablecoins"].list().map { it as String }.toSet(), CoinExclusion.STABLECOINS)
        assertEquals(data["wrapped"].list().map { it as String }.toSet(), CoinExclusion.WRAPPED)
        assertEquals(CoinExclusion.STABLECOINS, StarterCoins.STABLECOINS)
        assertEquals(CoinExclusion.WRAPPED, StarterCoins.WRAPPED)
    }

    /** Jeder Eintrag der Listen gilt als ausgeschlossen, auch klein geschrieben und ohne Namen. */
    @Test
    fun everyListedSymbolIsExcluded() {
        (CoinExclusion.STABLECOINS + CoinExclusion.WRAPPED).forEach {
            assertTrue(it, CoinExclusion.isExcluded(it, ""))
            assertTrue(it, CoinExclusion.isExcluded(it.lowercase(), ""))
        }
    }

    @Test
    fun cases() {
        val coins = data["coins"].list().map { it.obj() }
        assertTrue(coins.size >= 40)
        coins.forEach { c ->
            val symbol = c["symbol"] as String
            val name = c["name"] as String
            val expected = c["excluded"] as Boolean
            val label = "${c["id"]} ($symbol / $name)"
            assertEquals(label, expected, CoinExclusion.isExcluded(symbol, name))
            // Start-Coins und «Heute auffällig» gehen über dieselbe Regel
            assertEquals(label, expected, StarterCoins.isExcluded(StarterCoins.MarketCoin(symbol, name, rank = 1)))
        }
    }

    /** Gewinner und Verlierer: ausgeschlossene Coins tauchen nie auf, alle anderen schon. */
    @Test
    fun moversHonourTheSameRule() {
        val coins = data["coins"].list().map { it.obj() }
            .filter { (it["symbol"] as String).isNotBlank() }
            .mapIndexed { i, c ->
                MoverCoin(c["symbol"] as String, c["name"] as String, rank = i + 1, change24h = if (i % 2 == 0) i + 1.0 else -(i + 1.0))
            }
        val result = MarketMovers.select(coins)
        (result.gainers + result.losers).forEach {
            assertTrue(it.symbol, !CoinExclusion.isExcluded(it.symbol, it.name))
        }
    }
}
