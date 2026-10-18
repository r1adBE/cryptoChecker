package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.market.DexScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sammelabfrage von DexScreener: Pools derselben Chain in einer Anfrage (`/latest/dex/pairs/{chain}/{a,b,…}`),
 * höchstens 30 Adressen je Anfrage. Gleiche Fälle in `DexScreenerBulkTests.swift`.
 */
class DexScreenerBulkTest {

    private val market = DexScreener()

    @Test
    fun groupsByChainSortedWithoutDuplicates() {
        val groups = DexScreener.bulkGroups(
            listOf(
                "solana/8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj",
                "ethereum/0xB",
                "ethereum/0xA",
                " ethereum/0xA ",
                // ohne Chain oder Adresse, mit Zeichen, die kodiert werden müssten: Einzelabfrage
                "noslash", "ethereum/", "/0xC", "ethereum/0x D", "ethereum/0xE,0xF", "ethereum/0x1/2",
            ),
        )
        assertEquals(
            listOf(
                "ethereum" to listOf("0xA", "0xB"),
                "solana" to listOf("8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj"),
            ),
            groups,
        )
    }

    @Test
    fun atMostThirtyAddressesPerRequest() {
        val ids = (1..31).map { "base/0x%02d".format(it) }
        val groups = DexScreener.bulkGroups(ids)
        assertEquals(listOf(30, 1), groups.map { it.second.size })
        assertEquals(DexScreener.BULK_MAX_POOLS, groups.first().second.size)
        assertEquals(listOf("0x31"), groups.last().second)
    }

    @Test
    fun requestAddressesFollowTheGroups() {
        val ids = listOf("ethereum/0xB", "ethereum/0xA", "bsc/0xC")
        assertEquals(1, market.bulkTickersNumOfRequests)
        assertEquals(2, market.bulkTickersRequestCount(ids))
        assertEquals("https://api.dexscreener.com/latest/dex/pairs/bsc/0xC", market.getBulkTickersUrl(0, ids))
        assertEquals("https://api.dexscreener.com/latest/dex/pairs/ethereum/0xA,0xB", market.getBulkTickersUrl(1, ids))
        assertNull(market.getBulkTickersUrl(2, ids))
        // Keine ungefilterte Liste; ein fehlender Pool geht in die Einzelabfrage
        assertNull(market.getBulkTickersUrl(0))
        assertFalse(market.bulkTickersComplete)
        assertEquals(0, market.bulkTickersRequestCount(listOf("invalid")))
    }
}
