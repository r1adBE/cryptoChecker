package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.util.BulkPairChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aufteilung der beobachteten Paare auf gefilterte Massenabfragen (Kraken, Bitfinex). */
class BulkPairChunksTest {

    private val prefix = "https://api.kraken.com/0/public/Ticker?pair="

    @Test
    fun fewPairsGiveOneSortedDistinctChunk() {
        val chunks = BulkPairChunks.chunks(prefix, listOf("XXBTZUSD", "XETHZEUR", "XXBTZUSD", ""))!!
        assertEquals(listOf(listOf("XETHZEUR", "XXBTZUSD")), chunks)
        assertEquals("${prefix}XETHZEUR,XXBTZUSD", BulkPairChunks.url(prefix, chunks[0]))
    }

    @Test
    fun emptyListIsNotFilterable() {
        assertNull(BulkPairChunks.chunks(prefix, emptyList()))
        assertNull(BulkPairChunks.chunks(prefix, listOf("")))
    }

    @Test
    fun idsThatNeedEncodingAreNotFilterable() {
        assertNull(BulkPairChunks.chunks(prefix, listOf("XXBTZUSD", "BTC/USD")))
        assertNull(BulkPairChunks.chunks(prefix, listOf("A B")))
        assertNull(BulkPairChunks.chunks(prefix, listOf("A&pair=B")))
    }

    @Test
    fun bitfinexColonIdsAreAllowed() {
        val bitfinex = "https://api-pub.bitfinex.com/v2/tickers?symbols="
        assertEquals(listOf(listOf("tBTCUSD", "tDOGE:USD")), BulkPairChunks.chunks(bitfinex, listOf("tDOGE:USD", "tBTCUSD")))
    }

    @Test
    fun longListsAreSplitBelowTheUrlLimit() {
        val ids = (0 until 500).map { "PAIR%04dUSD".format(it) }
        val chunks = BulkPairChunks.chunks(prefix, ids)!!
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(BulkPairChunks.url(prefix, it).length < BulkPairChunks.MAX_URL_LENGTH) }
        // Nichts geht verloren, nichts doppelt, Reihenfolge sortiert
        assertEquals(ids.sorted(), chunks.flatten())
    }

    @Test
    fun chunkFillsUpToTheLimit() {
        // Präfix 10 + "AAAA" (4) + ",BBBB" (5) = 19 < 20; ",CCCC" ergäbe 24 → neue Gruppe
        val chunks = BulkPairChunks.chunks("0123456789", listOf("AAAA", "BBBB", "CCCC"), maxUrlLength = 20)!!
        assertEquals(listOf(listOf("AAAA", "BBBB"), listOf("CCCC")), chunks)
    }

    @Test
    fun exactlyAtTheLimitStartsANewChunk() {
        // 10 + 4 + 5 = 19; mit Grenze 19 muss BBBB in eine eigene Gruppe (URL muss < Grenze bleiben)
        val chunks = BulkPairChunks.chunks("0123456789", listOf("AAAA", "BBBB"), maxUrlLength = 19)!!
        assertEquals(listOf(listOf("AAAA"), listOf("BBBB")), chunks)
    }

    @Test
    fun singleOverlongIdStaysAlone() {
        val long = "X".repeat(50)
        val chunks = BulkPairChunks.chunks("0123456789", listOf("AAAA", long, "ZZZZ"), maxUrlLength = 30)!!
        assertEquals(listOf(listOf("AAAA"), listOf(long), listOf("ZZZZ")), chunks)
    }
}
