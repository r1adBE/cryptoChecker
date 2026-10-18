package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity.WatchName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binance-Ticker im Portfolio nur für denselben Token (K-8). Wie `PortfolioPriceIdentityTests.swift`. */
class PortfolioPriceIdentityTest {

    private val binance = mapOf("LUNA" to "Terra", "TON" to "Toncoin", "GMT" to "STEPN", "HOT" to "Holo", "BTC" to "Bitcoin")

    @Test
    fun differentTokenSkipsBinance() {
        assertFalse(PortfolioPriceIdentity.binanceTickerAllowed("LUNA", "Terra Classic", "Terra"))
        assertFalse(PortfolioPriceIdentity.binanceTickerAllowed("TON", "Tokamak Network", "Toncoin"))
        assertFalse(PortfolioPriceIdentity.binanceTickerAllowed("GMT", "GoMining", "STEPN"))
        assertFalse(PortfolioPriceIdentity.binanceTickerAllowed("HOT", "Hydro Protocol", "Holo"))
    }

    @Test
    fun sameOrUnknownNameKeepsBinance() {
        assertTrue(PortfolioPriceIdentity.binanceTickerAllowed("LUNA", "Terra 2.0", "Terra"))
        assertTrue(PortfolioPriceIdentity.binanceTickerAllowed("TON", "The Open Network", "Toncoin"))
        // Ohne Namen in der Merkliste bzw. bei Binance: wie bisher
        assertTrue(PortfolioPriceIdentity.binanceTickerAllowed("GMT", null, "STEPN"))
        assertTrue(PortfolioPriceIdentity.binanceTickerAllowed("GMT", "GoMining", null))
    }

    @Test
    fun watchNamesUseActivePairsAndConsensus() {
        val watches = listOf(
            WatchName("luna", "Terra Classic"),
            WatchName("TON", "Tokamak Network"),
            WatchName("TON", "Toncoin", disabled = true),
            // Widersprüchliche Paare: unklar, welcher Token → kein Name
            WatchName("HOT", "Holo"),
            WatchName("HOT", "Hydro Protocol"),
        )
        val names = PortfolioPriceIdentity.watchNames(listOf("LUNA", "TON", "HOT", "BTC"), watches)
        assertEquals("Terra Classic", names["LUNA"])
        assertEquals("Tokamak Network", names["TON"])
        assertNull(names["HOT"])
        assertNull(names["BTC"])
        val allowed = PortfolioPriceIdentity.binanceTickerCoins(listOf("LUNA", "TON", "HOT", "BTC"), names) { binance[it] }
        assertEquals(listOf("HOT", "BTC"), allowed)
    }
}
