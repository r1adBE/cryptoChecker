package com.cryptochecker.app.parity

import com.cryptochecker.app.data.BackupRestoreLimits
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.exchange.DexScreener
import org.junit.Assert.assertEquals
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DexScreener-Kennung «chain/adresse» (`testdata/parity/dex_pair_id.json`): ungültige Kennungen gehen
 * nie unkodiert in den Pfad — Einzelkurs «nicht gefunden» ohne Anfrage, Suchtreffer fallen weg, Sicherung lehnt ab.
 * Gegenstück: `Tests/DexPairIdParityTests`.
 */
class DexPairIdParityTest {

    private val fixture = ParityJson.load("dex_pair_id.json")
    private val exchange = DexScreener()

    private fun cases() = fixture["cases"].list().map { it as Map<*, *> }

    @Test
    fun kennungWieGemeinsameFaelle() {
        for (c in cases()) {
            val id = c["pairId"] as String
            assertEquals(id, c["valid"] as Boolean, DexScreener.isSafePairId(id))
        }
    }

    @Test
    fun einzelkursNurMitGueltigerKennung() {
        val base = fixture["tickerBase"] as String
        for (c in cases()) {
            val id = c["pairId"] as String
            val info = PairQuery("X", "USD (ethereum)", id, FuturesContractType.NONE)
            if (c["valid"] as Boolean) {
                assertEquals(id, base + id, exchange.tickerAddress(0, info))
            } else {
                try {
                    exchange.tickerAddress(0, info)
                    fail("$id: Adresse statt «nicht gefunden»")
                } catch (e: ExchangeParseException) {
                    assertEquals(id, DexScreener.POOL_NOT_FOUND, e.message)
                }
            }
        }
        try {
            exchange.tickerAddress(0, PairQuery("X", "USD (ethereum)", null, FuturesContractType.NONE))
            fail("ohne Kennung: Adresse statt «nicht gefunden»")
        } catch (e: ExchangeParseException) {
            assertEquals(DexScreener.POOL_NOT_FOUND, e.message)
        }
    }

    /** SEC3-R1: der Pfad bleibt nach dem URL-Parser «…/<chain>/<adresse>» (keine Punkt-Segmente). */
    @Test
    fun pfadBleibtNachUrlParser() {
        for (c in cases().filter { it["valid"] as Boolean }) {
            val id = c["pairId"] as String
            val url = exchange.tickerAddress(0, PairQuery("X", "USD (ethereum)", id, FuturesContractType.NONE)).toHttpUrl()
            assertTrue(id, url.encodedPath.endsWith("/latest/dex/pairs/$id"))
        }
    }

    /** SEC3-R1: die Sammelabfrage nimmt dieselben Kennungen wie der Einzelkurs. */
    @Test
    fun sammelabfrageNurMitGueltigerKennung() {
        for (c in cases()) {
            val id = c["pairId"] as String
            if (id != id.trim()) continue // Sammelabfrage schneidet Leerzeichen am Rand ab
            val groups = DexScreener.bulkGroups(listOf(id))
            assertEquals(id, c["valid"] as Boolean, groups.isNotEmpty())
            groups.forEach { assertTrue(id, DexScreener.bulkUrl(it).toHttpUrl().encodedPath.endsWith("/$id")) }
        }
    }

    @Test
    fun suchtrefferMitUngueltigerKennungFallenWeg() {
        val search = fixture["search"] as Map<*, *>
        val pools = DexScreener.parseSearch(search["body"] as String)
        assertEquals(search["expectedPairIds"].list(), pools.map { it.pairId })
    }

    @Test
    fun sicherungUebernimmtNurGueltigeKennung() {
        for (c in cases()) {
            val id = c["pairId"] as String
            val kept = BackupRestoreLimits.acceptsPair(DexScreener.KEY, "DexScreener", "X", "USD (ethereum)", id)
            assertEquals(id, c["backupKept"] as Boolean, kept)
        }
        assertFalse(BackupRestoreLimits.acceptsPair(DexScreener.KEY, "DexScreener", "X", "USD (ethereum)", null))
    }
}
