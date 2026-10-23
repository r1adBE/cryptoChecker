package com.cryptochecker.marketdata.model.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reine Hilfsfunktionen der DexScreener-Anbindung ohne Netz und ohne org.json: Kennung «chain/adresse» (nur
 * unkodierte Pfad-Zeichen), Pool-Vergleich, Sammelgruppen, Adressen und Alarm-Liquidität. Die gemeinsamen Fälle mit
 * iOS prüft zusätzlich DexPairIdParityTest im App-Modul.
 */
class DexScreenerIdsTest {

    @Test
    fun gueltigeKennungen() {
        for (id in listOf("ethereum/0xAbC123", "solana/So1111.abc_~-", "eth/a.b", "bsc/0x1")) {
            assertTrue(id, DexScreener.isSafePairId(id))
        }
    }

    @Test
    fun ungueltigeKennungen() {
        val invalid = listOf(
            "", "ethereum", "/0xabc", "ethereum/", "a/b/c", "eth/0x 1", "eth/0x%2F", "eth/0x?x", "eth/0x#",
            "eth/..", "../abc", "./abc", "eth/.", "eth/ä", " eth/0x1",
        )
        for (id in invalid) assertFalse(id, DexScreener.isSafePairId(id))
    }

    @Test
    fun poolVergleichOhneGrossKleinschreibung() {
        assertTrue(DexScreener.isPool("ethereum", "0xABC", "ethereum/0xabc"))
        assertTrue(DexScreener.isPool("Ethereum", "0xabc", " ethereum/0xabc "))
        assertFalse(DexScreener.isPool("bsc", "0xabc", "ethereum/0xabc"))
        assertFalse(DexScreener.isPool("ethereum", "", "ethereum/0xabc"))
        assertFalse(DexScreener.isPool("ethereum", "0xabc", null))
        assertFalse(DexScreener.isPool("ethereum", "0xabc", "ethereum0xabc"))
    }

    @Test
    fun sammelgruppenJeChainSortiertOhneDoppelte() {
        val groups = DexScreener.bulkGroups(listOf("eth/b", "eth/a", " eth/a ", "bsc/z", "bad", "eth/x y", "eth/.."))
        assertEquals(listOf("bsc" to listOf("z"), "eth" to listOf("a", "b")), groups)
    }

    @Test
    fun sammelgruppenHoechstens30Adressen() {
        val ids = (0..30).map { "eth/a" + it.toString().padStart(2, '0') }
        val groups = DexScreener.bulkGroups(ids)
        assertEquals(listOf(DexScreener.BULK_MAX_POOLS, 1), groups.map { it.second.size })
        assertEquals("a30", groups[1].second.single())
    }

    @Test
    fun adressen() {
        assertEquals(
            "https://api.dexscreener.com/latest/dex/pairs/eth/a,b",
            DexScreener.bulkUrl("eth" to listOf("a", "b")),
        )
        assertEquals("https://api.dexscreener.com/latest/dex/search?q=pepe+coin", DexScreener.searchUrl(" pepe coin "))
    }

    @Test
    fun gekuerzteAdresse() {
        assertEquals("0x1234…cdef", DexScreener.shortAddress("0x1234567890abcdef"))
        assertEquals("0x1234567890", DexScreener.shortAddress("0x1234567890"))
        assertEquals("0x12", DexScreener.shortAddress("  0x12  "))
    }

    @Test
    fun alarmNurAbMindestLiquiditaet() {
        assertFalse(DexScreener.alarmCapable(null))
        assertFalse(DexScreener.alarmCapable(9_999.99))
        assertTrue(DexScreener.alarmCapable(DexScreener.DEX_MIN_ALARM_LIQUIDITY_USD))
        assertFalse(DexScreener.alarmCapable(Double.NaN))
        assertFalse(DexScreener.alarmCapable(Double.POSITIVE_INFINITY))
    }
}
