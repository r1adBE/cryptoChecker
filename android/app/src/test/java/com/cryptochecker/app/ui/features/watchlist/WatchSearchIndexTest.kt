package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zwischengespeicherte Suchschlüssel der Merkliste (iOS: `WatchlistSearchIndexTests`). */
class WatchSearchIndexTest {
    private val watches = listOf(
        WatchEntity(id = 1, marketKey = "Kraken", marketName = "Kraken", baseAsset = "BTC", quoteAsset = "EUR"),
        WatchEntity(id = 2, marketKey = "Binance", marketName = "Binance", baseAsset = "PEPE", quoteAsset = "USDT",
            note = "Pépé Einstieg"),
        WatchEntity(id = 3, marketKey = "BinanceFutures", marketName = "Binance Futures", baseAsset = "ETH",
            quoteAsset = "USDT", contractType = FuturesContractType.PERPETUAL, note = "Hedge ÉTÉ"),
        WatchEntity(id = 4, marketKey = "Bitstamp", marketName = "Bitstamp", baseAsset = "XRP", quoteAsset = "CHF"),
        WatchEntity(id = 5, marketKey = "Coinbase", marketName = "Coinbase", baseAsset = "SOL", quoteAsset = "USDC",
            note = "langfristig"),
    )
    private val names = mapOf(1L to "Bitcoin", 2L to "Pépé", 3L to "Ethereum", 5L to "Solana")
    private val queries = listOf(
        "btc", "BTC kraken", "bitcoin", "coin", "pepe", "PÉPÉ", "einstieg", "ete", "été hedge", "perp", "eth/usdt",
        "usdt", "binance futures", "chf", "xrp bitstamp", "LANGFRISTIG sol", "solana usdc", "xyz", "btc usdt", "  ",
    )

    @Test
    fun sameResultAsMatchesSearch() {
        val index = WatchSearchIndex()
        for (q in queries) {
            val query = q.trim()
            val expected = watches.filter { it.matchesSearch(query, names[it.id]) }
            assertEquals("Anfrage «$q»", expected, index.filter(watches, query, names))
        }
        // Reihenfolge bleibt die der Liste, auch umgekehrt
        val reversed = watches.reversed()
        assertEquals(reversed.filter { it.matchesSearch("usdt", names[it.id]) }, index.filter(reversed, "usdt", names))
    }

    @Test
    fun foldsEachPairOnlyOnce() {
        val index = WatchSearchIndex()
        for (q in queries) index.filter(watches, q.trim(), names)
        assertEquals(watches.size, index.keysBuilt)
        // Neuer Listenstand mit nur geänderten Kursen: keine neue Faltung
        val ticked = watches.map { it.copy(lastPrice = 42.0, change24h = 1.5) }
        index.filter(ticked, "btc", names)
        assertEquals(watches.size, index.keysBuilt)
    }

    @Test
    fun nameNoteAndExchangeChangesRebuildOnlyThatPair() {
        val index = WatchSearchIndex()
        assertEquals(listOf(1L), index.filter(watches, "bitcoin", names).map { it.id })
        // Coin-Name geändert (z. B. neue Namensliste)
        val renamed = names + (1L to "Bitkoin")
        assertTrue(index.filter(watches, "bitcoin", renamed).isEmpty())
        assertEquals(listOf(1L), index.filter(watches, "bitkoin", renamed).map { it.id })
        assertEquals(watches.size + 1, index.keysBuilt)
        // Notiz geändert
        val noted = watches.map { if (it.id == 4L) it.copy(note = "Ripple Café") else it }
        assertEquals(listOf(4L), index.filter(noted, "cafe", renamed).map { it.id })
        assertEquals(watches.size + 2, index.keysBuilt)
        // Börsenname geändert
        val moved = noted.map { if (it.id == 5L) it.copy(marketName = "Coinbase Advanced") else it }
        assertEquals(listOf(5L), index.filter(moved, "advanced", renamed).map { it.id })
        assertEquals(watches.size + 3, index.keysBuilt)
    }

    @Test
    fun removedPairsAreDropped() {
        val index = WatchSearchIndex()
        index.filter(watches, "btc", names)
        assertEquals(watches.size, index.size)
        index.filter(watches.take(2), "btc", names)
        assertEquals(2, index.size)
        index.clear()
        assertEquals(0, index.size)
    }

    @Test
    fun duplicateIdsFallBackToDirectMatching() {
        val twin = watches[0].copy(baseAsset = "DOGE", quoteAsset = "USD")
        val list = watches + twin
        val index = WatchSearchIndex()
        for (q in listOf("doge", "btc", "kraken")) {
            assertEquals(list.filter { it.matchesSearch(q, names[it.id]) }, index.filter(list, q, names))
        }
    }
}
