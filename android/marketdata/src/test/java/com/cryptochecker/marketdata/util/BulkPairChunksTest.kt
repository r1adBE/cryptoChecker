package com.cryptochecker.marketdata.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aufteilung gefilterter Sammelabfragen auf mehrere Adressen (BulkPairChunks), ohne Netz. */
class BulkPairChunksTest {

    @Test
    fun sortiertOhneDoppelteUndUnterDerGrenze() {
        val chunks = BulkPairChunks.chunks("p=", listOf("BBB", "AAA", "AAA", "CCC"), maxUrlLength = 10)
        assertEquals(listOf(listOf("AAA", "BBB"), listOf("CCC")), chunks)
        assertEquals("p=AAA,BBB", BulkPairChunks.url("p=", chunks!!.first()))
    }

    @Test
    fun ohneFilterbareKennungKeineGruppen() {
        assertNull(BulkPairChunks.chunks("p=", emptyList()))
        assertNull(BulkPairChunks.chunks("p=", listOf("")))
        // Eine Kennung, die kodiert werden müsste, schaltet den Filter ganz ab
        assertNull(BulkPairChunks.chunks("p=", listOf("BTCUSD", "A B")))
        assertNull(BulkPairChunks.chunks("p=", listOf("BTCUSD", "A&B")))
    }

    @Test
    fun erlaubteSonderzeichen() {
        val chunks = BulkPairChunks.chunks("p=", listOf("tDOGE:USD", "XXBTZUSD", "a.b_c~d-e"))
        assertEquals(1, chunks!!.size)
        assertEquals(listOf("XXBTZUSD", "a.b_c~d-e", "tDOGE:USD"), chunks.single())
    }

    @Test
    fun zuLangeKennungBildetEigeneGruppe() {
        val long = "X".repeat(20)
        assertEquals(listOf(listOf(long)), BulkPairChunks.chunks("p=", listOf(long), maxUrlLength = 10))
    }

    @Test
    fun fuelltBisKnappUnterDieGrenze() {
        // Präfix 10 + "AAAA" (4) + ",BBBB" (5) = 19 < 20; ",CCCC" ergäbe 24 → neue Gruppe (wie iOS)
        assertEquals(
            listOf(listOf("AAAA", "BBBB"), listOf("CCCC")),
            BulkPairChunks.chunks("0123456789", listOf("AAAA", "BBBB", "CCCC"), maxUrlLength = 20),
        )
    }

    @Test
    fun genauAufDerGrenzeBeginntNeueGruppe() {
        // 10 + 4 + 5 = 19 = Grenze: die Adresse wäre genau maxUrlLength lang → zweite Kennung in neue Gruppe (wie iOS)
        assertEquals(
            listOf(listOf("AAAA"), listOf("BBBB")),
            BulkPairChunks.chunks("0123456789", listOf("AAAA", "BBBB"), maxUrlLength = 19),
        )
    }

    @Test
    fun standardgrenzeGenauErreichtTeilt() {
        // Präfix so lang, dass «AAAA,BBBB» die Adresse auf genau MAX_URL_LENGTH Zeichen brächte
        val limit = BulkPairChunks.MAX_URL_LENGTH
        val exact = "p".repeat(limit - 9)
        assertEquals(listOf(listOf("AAAA"), listOf("BBBB")), BulkPairChunks.chunks(exact, listOf("AAAA", "BBBB")))
        // Ein Zeichen kürzer: eine Adresse mit MAX_URL_LENGTH - 1 Zeichen
        val below = "p".repeat(limit - 10)
        val one = BulkPairChunks.chunks(below, listOf("AAAA", "BBBB"))!!
        assertEquals(listOf(listOf("AAAA", "BBBB")), one)
        assertEquals(limit - 1, BulkPairChunks.url(below, one.single()).length)
    }

    @Test
    fun vieleKennungenBleibenUnterStandardgrenze() {
        val prefix = "https://example.com/tickers?pair="
        val ids = (0 until 500).map { "PAIR" + it.toString().padStart(3, '0') }
        val chunks = BulkPairChunks.chunks(prefix, ids)!!
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(BulkPairChunks.url(prefix, chunk).length < BulkPairChunks.MAX_URL_LENGTH)
        }
        assertEquals(ids, chunks.flatten())
    }
}
