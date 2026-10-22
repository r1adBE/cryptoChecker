package com.cryptochecker.app.parity

import com.cryptochecker.app.data.BackupRestoreLimits
import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.market.DexScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * DexScreener-Kennung «chain/adresse» (`testdata/parity/dex_pair_id.json`, seit Build 104): ungültige Kennungen gehen
 * nie unkodiert in den Pfad — Einzelkurs «nicht gefunden» ohne Anfrage, Suchtreffer fallen weg, Sicherung lehnt ab.
 * Gegenstück: `Tests/DexPairIdParityTests.swift`.
 */
class DexPairIdParityTest {

    private val fixture = ParityJson.load("dex_pair_id.json")
    private val market = DexScreener()

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
                assertEquals(id, base + id, market.tickerAddress(0, info))
            } else {
                try {
                    market.tickerAddress(0, info)
                    fail("$id: Adresse statt «nicht gefunden»")
                } catch (e: MarketParseException) {
                    assertEquals(id, DexScreener.POOL_NOT_FOUND, e.message)
                }
            }
        }
        try {
            market.tickerAddress(0, PairQuery("X", "USD (ethereum)", null, FuturesContractType.NONE))
            fail("ohne Kennung: Adresse statt «nicht gefunden»")
        } catch (e: MarketParseException) {
            assertEquals(DexScreener.POOL_NOT_FOUND, e.message)
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
