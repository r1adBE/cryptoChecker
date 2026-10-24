package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.data.BulkAddRunner.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.app.data.BulkAddResult as RepoResult

/** Stand der app-weiten Aufnahme ([com.cryptochecker.app.data.BulkAddRunner]) als Anzeige des Explorers. */
class BulkAddProgressTest {

    @Test
    fun runningShowsDoneAndTotal() {
        val shown = bulkAddFromRunner(Progress(done = 123, total = 746))
        assertTrue(shown!!.running)
        assertEquals(123, shown.done)
        assertEquals(746, shown.total)
    }

    @Test
    fun finishedShowsCounts() {
        val shown = bulkAddFromRunner(Progress(746, 746, result = RepoResult(added = 700, skipped = 40, refused = 6)))
        assertFalse(shown!!.running)
        assertEquals(700, shown.added)
        assertEquals(40, shown.skipped)
        assertEquals(6, shown.refused)
    }

    @Test
    fun lockedOrNothingShowsNoCount() {
        assertNull(bulkAddFromRunner(null))
        assertNull(bulkAddFromRunner(Progress(10, 10, locked = true)))
        assertFalse(Progress(10, 10, locked = true).running)
    }

    @Test
    fun newCountSkipsPairsAlreadyInWatchlist() {
        val pairs = listOf(MarketPair("BTC", "USDT", "BTCUSDT"), MarketPair("ETH", "USDT", "ETHUSDT"), MarketPair("SOL", "USDT", "SOLUSDT"))
        val watched = setOf(watchKey("Binance", "ETH", "USDT", "NONE"))
        assertEquals(2, bulkNewCount("Binance", pairs, watched))
        assertEquals(3, bulkNewCount("Binance", pairs, emptySet()))
        // Andere Börse zählt nicht als vorhanden
        assertEquals(3, bulkNewCount("Kraken", pairs, watched))
        val all = pairs.map { watchKey("Binance", it.base, it.quote, "NONE") }.toSet()
        assertEquals(0, bulkNewCount("Binance", pairs, all))
    }

    @Test
    fun finishedKeepsAddedIdsForUndo() {
        val shown = bulkAddFromRunner(Progress(2, 2, result = RepoResult(added = 2, skipped = 0, addedIds = listOf(7L, 8L))))
        assertEquals(listOf(7L, 8L), shown!!.addedIds)
    }
}
