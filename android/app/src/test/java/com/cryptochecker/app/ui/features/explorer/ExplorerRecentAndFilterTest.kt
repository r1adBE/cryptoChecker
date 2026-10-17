package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.data.TickerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Zuletzt gesucht» und die Schnellfilter der Liste «Alle …-Paare» — wie die iOS-Tests in `ExplorerPairSearchTests.swift`. */
class ExplorerRecentAndFilterTest {

    // ---- Zuletzt gesucht

    @Test
    fun newestFirst() {
        val list = RecentSearches.add(RecentSearches.add(emptyList(), "btc"), "eth usdt")
        assertEquals(listOf("ETH USDT", "BTC"), list)
    }

    @Test
    fun duplicateMovesToFrontOnce() {
        var list = listOf("ETH", "SOL", "BTC")
        list = RecentSearches.add(list, "  btc ")
        assertEquals(listOf("BTC", "ETH", "SOL"), list)
        // Leerräume zusammengefasst: «eth   usdt» = «ETH USDT»
        list = RecentSearches.add(listOf("ETH USDT"), "eth   usdt")
        assertEquals(listOf("ETH USDT"), list)
    }

    @Test
    fun keepsAtMostEight() {
        var list = emptyList<String>()
        for (i in 1..12) list = RecentSearches.add(list, "C$i")
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("C12", list.first())
        assertEquals("C5", list.last())
    }

    @Test
    fun blankIsIgnored() {
        assertEquals(listOf("BTC"), RecentSearches.add(listOf("BTC"), "   "))
        assertNull(RecentSearches.normalize(""))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val list = listOf("ETH USDT", "BTC", "SOL/USDC")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
        // Doppelte oder leere Zeilen im Speicher werden bereinigt
        assertEquals(listOf("BTC", "ETH"), RecentSearches.decode("btc\n\nBTC\neth"))
    }

    // ---- Schnellfilter

    private val stats = mapOf(
        "A" to PairStats(change24h = 2.0, quoteVolume = 100.0),
        "B" to PairStats(change24h = -5.0, quoteVolume = 900.0),
        "C" to PairStats(change24h = 7.5, quoteVolume = null),
        "D" to null,
        "E" to PairStats(change24h = 0.0, quoteVolume = 50.0),
    )
    private val items = listOf("A", "B", "C", "D", "E")

    private fun sort(filter: QuickFilter?) = QuickFilters.sort(items, filter) { stats[it] }

    @Test
    fun noFilterKeepsOrder() {
        assertEquals(items, sort(null))
    }

    @Test
    fun gainersDescendingMissingLast() {
        assertEquals(listOf("C", "A", "E", "B", "D"), sort(QuickFilter.GAINERS))
    }

    @Test
    fun losersAscendingMissingLast() {
        assertEquals(listOf("B", "E", "A", "C", "D"), sort(QuickFilter.LOSERS))
    }

    @Test
    fun volumeDescendingMissingLastInOriginalOrder() {
        assertEquals(listOf("B", "A", "E", "C", "D"), sort(QuickFilter.VOLUME))
    }

    @Test
    fun sortIsStableForEqualValues() {
        val equal = mapOf("X" to PairStats(1.0, 1.0), "Y" to PairStats(1.0, 1.0))
        assertEquals(listOf("X", "Y"), QuickFilters.sort(listOf("X", "Y"), QuickFilter.GAINERS) { equal[it] })
        assertEquals(listOf("X", "Y"), QuickFilters.sort(listOf("X", "Y"), QuickFilter.LOSERS) { equal[it] })
    }

    @Test
    fun availabilityFollowsData() {
        assertEquals(emptySet<QuickFilter>(), QuickFilters.available(listOf(null, null)))
        assertEquals(setOf(QuickFilter.VOLUME), QuickFilters.available(listOf(PairStats(null, 3.0))))
        assertEquals(
            setOf(QuickFilter.GAINERS, QuickFilter.LOSERS),
            QuickFilters.available(listOf(PairStats(1.0, null)))
        )
    }

    @Test
    fun tapAgainClears() {
        assertEquals(QuickFilter.GAINERS, QuickFilters.toggle(null, QuickFilter.GAINERS))
        assertEquals(QuickFilter.VOLUME, QuickFilters.toggle(QuickFilter.GAINERS, QuickFilter.VOLUME))
        assertNull(QuickFilters.toggle(QuickFilter.VOLUME, QuickFilter.VOLUME))
    }

    @Test
    fun statsFromTicker() {
        val t = TickerImpl().apply { last = 2.0; vol = 10.0; change24hPercent = 1.5 }
        // Kein Volumen in der Gegenwährung: Menge × Kurs
        assertEquals(PairStats(1.5, 20.0), QuickFilters.statsOf(t))
        t.volQuote = 33.0
        assertEquals(33.0, QuickFilters.statsOf(t).quoteVolume!!, 0.0)
        val empty = QuickFilters.statsOf(TickerImpl())
        assertNull(empty.change24h)
        assertNull(empty.quoteVolume)
        t.change24hPercent = Double.NaN
        assertTrue(QuickFilters.statsOf(t).change24h == null)
    }
}
