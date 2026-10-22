package com.cryptochecker.app.ui.features.watchlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zweites Wisch-Löschen, solange der Banner steht: Sammel-Banner mit einem Rückgängig-Schlüssel (wie iOS). */
class RemovedBatchTest {

    @Test
    fun zweitesLoeschenKommtZurLaufendenSammlung() {
        val batch = RemovedBatch()
        assertEquals(7L, batch.add(7L, "BTC/USDT"))
        // Gleicher Schlüssel: «Rückgängig» holt beide zurück
        assertEquals(7L, batch.add(9L, "ETH/USDT"))
        assertEquals(listOf("BTC/USDT", "ETH/USDT"), batch.names)
    }

    @Test
    fun nachDemEndeDesBannersBeginntEineNeueSammlung() {
        val batch = RemovedBatch()
        batch.add(7L, "BTC/USDT")
        batch.closed(batch.generation)
        assertNull(batch.undoKey)
        assertEquals(9L, batch.add(9L, "ETH/USDT"))
        assertEquals(listOf("ETH/USDT"), batch.names)
    }

    @Test
    fun spaetesEndeDesErsetztenBannersBeendetDieNeueSammlungNicht() {
        val batch = RemovedBatch()
        batch.add(7L, "BTC/USDT")
        val first = batch.generation
        batch.add(9L, "ETH/USDT")
        // Der erste Banner meldet sein Ende erst, nachdem der zweite schon steht
        batch.closed(first)
        assertEquals(7L, batch.undoKey)
        assertEquals(2, batch.names.size)
    }
}
