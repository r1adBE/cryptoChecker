package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity.WatchName
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
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

    /**
     * Ohne bekannten Namen: Binance nur bei eindeutigem Kürzel, sonst «ungeprüft»; mehrdeutig laut anderen Paarlisten:
     * Binance-Kurs, aber «Kurs ungeprüft» — Fixture `portfolio_price_identity.json`.
     */
    @Test
    fun parityBinanceCheckAndSplit() {
        val data = ParityJson.load("portfolio_price_identity.json")
        val names = data["binanceName"].obj().mapValues { it.value as String }
        val others = data["otherNames"].obj().mapValues { e -> e.value.list().map { it as String? } }
        data["checks"].list().forEach { raw ->
            val c = raw.obj()
            val coin = c["coin"] as String
            val got = PortfolioPriceIdentity.binanceCheck(coin, c["knownName"] as String?, names[coin], others[coin].orEmpty())
            assertEquals("$coin / ${c["knownName"]}", c["check"], got.name)
        }
        val split = data["split"].obj()
        val known = split["knownNames"].obj().mapValues { it.value as String? }
        val got = PortfolioPriceIdentity.binanceSplit(split["coins"].list().map { it as String }, known, { names[it] }) {
            others[it].orEmpty()
        }
        assertEquals(split["use"].list(), got.use)
        assertEquals(split["unchecked"].list().toSet(), got.unchecked)
        assertEquals(split["unverified"].list().toSet(), got.unverified)
    }

    /** Ohne andere Paarlisten bleibt alles wie bis Build 100: kein Coin «Kurs ungeprüft». */
    @Test
    fun withoutOtherListsNothingUnverified() {
        assertEquals(PortfolioPriceIdentity.BinanceCheck.USE, PortfolioPriceIdentity.binanceCheck("BEAM", null, "Beam Gaming"))
        val split = PortfolioPriceIdentity.binanceSplit(listOf("BTC", "BEAM"), emptyMap(), { if (it == "BTC") "Bitcoin" else "Beam Gaming" })
        assertEquals(listOf("BTC", "BEAM"), split.use)
        assertTrue(split.unverified.isEmpty())
    }

    /** Widersprechen sich nur die anderen Listen (nicht Binance), ist das Kürzel trotzdem mehrdeutig. */
    @Test
    fun conflictAmongOtherListsIsAmbiguous() {
        assertTrue(PortfolioPriceIdentity.ambiguous("ABC", "Alpha Beta", listOf("Alpha Beta", "Zebra Quux")))
        assertFalse(PortfolioPriceIdentity.ambiguous("ABC", "Alpha Beta", listOf("Alpha Beta", "Alpha Beta Chain")))
        // Name = Kürzel zählt nicht als Name
        assertFalse(PortfolioPriceIdentity.ambiguous("BEAM", "Beam", listOf("Beam", "Zebra Quux")))
    }
}
