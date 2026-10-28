package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.MarketCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Börsenauswahl der Seite «Paar hinzufügen» in Abschnitten ([exchangeSections]) und die Startwerte der Zustandsgruppen
 * ([ExplorerBulkState], [ExplorerSearchHits], [ExplorerDexState]) sowie der Rückmeldungen ([PairListSync],
 * [BulkAddResult], [ExplorerLoad]).
 */
class ExplorerSelectionStateTest {

    /** Eine Börse der Registry mit Abschnitt [category]. */
    private fun keyOf(category: MarketCategory): String =
        MarketsConfig.MARKETS.entries.first { it.value.category == category }.key

    // ---- Abschnitte ----

    @Test
    fun exchangeSectionsGroupSpotBeforeFutures() {
        val futures = keyOf(MarketCategory.FUTURES)
        // Unbekannte Schlüssel gelten als Spot
        val alphabetical = listOf(MarketInfo("zzSpotA", "A"), MarketInfo(futures, "F"), MarketInfo("zzSpotB", "B"))
        val (exchanges, categories) = exchangeSections(alphabetical, null)
        assertEquals(listOf("zzSpotA", "zzSpotB", futures), exchanges.map { it.key })
        assertEquals(listOf(MarketCategory.SPOT, MarketCategory.SPOT, MarketCategory.FUTURES), categories)
    }

    @Test
    fun exchangeSectionsSortByVolumeWithinSection() {
        val alphabetical = listOf(MarketInfo("zzSpotA", "A"), MarketInfo("zzSpotB", "B"), MarketInfo("zzSpotC", "C"))
        // Ohne Volumen hinten in alphabetischer Reihenfolge
        val (exchanges, categories) = exchangeSections(alphabetical, mapOf("zzSpotB" to 10.0, "zzSpotC" to 50.0))
        assertEquals(listOf("zzSpotC", "zzSpotB", "zzSpotA"), exchanges.map { it.key })
        assertEquals(3, categories.size)
        assertTrue(categories.all { it == MarketCategory.SPOT })
    }

    @Test
    fun exchangeSectionsEmptyListIsEmpty() {
        val (exchanges, categories) = exchangeSections(emptyList(), emptyMap())
        assertTrue(exchanges.isEmpty())
        assertTrue(categories.isEmpty())
    }

    @Test
    fun exchangeSectionsKeepMarketInfoObjects() {
        val dex = keyOf(MarketCategory.DEX)
        val info = MarketInfo(dex, "Pools")
        val (exchanges, categories) = exchangeSections(listOf(info), null)
        assertEquals(listOf(info), exchanges)
        assertEquals(listOf(MarketCategory.DEX), categories)
    }

    // ---- Zustandsgruppen ----

    @Test
    fun bulkStateStartsEmpty() {
        val bulk = ExplorerBulkState()
        assertTrue(bulk.pairs.value.isEmpty())
        assertFalse(bulk.empty.value)
        assertNull(bulk.quote.value)
        assertNull(bulk.add.value)
        assertTrue(bulk.stats.value.isEmpty())
        assertNull(bulk.statsJob)
        assertNull(bulk.statsFor)
        assertEquals(0L, bulk.statsLoadedAt)
    }

    @Test
    fun searchHitsStartEmpty() {
        val hits = ExplorerSearchHits()
        assertTrue(hits.pairs.value.isEmpty())
        assertTrue(hits.coins.value.isEmpty())
        assertFalse(hits.pairsShown.value)
        assertNull(hits.chosenCoin.value)
        assertTrue(hits.chosenCoinPairs.value.isEmpty())
    }

    @Test
    fun dexStateStartsIdle() {
        val dex = ExplorerDexState()
        assertTrue(dex.pools.value.isEmpty())
        assertFalse(dex.busy.value)
        assertNull(dex.notice.value)
        assertNull(dex.job)
    }

    // ---- Rückmeldungen ----

    @Test
    fun pairListSyncAndBulkAddResultDefaults() {
        val sync = PairListSync()
        assertFalse(sync.inProgress)
        assertNull(sync.error)

        val result = BulkAddResult(added = 3, skipped = 1)
        assertFalse(result.running)
        assertEquals(0, result.refused)
        assertEquals(0, result.done)
        assertEquals(0, result.total)
        assertTrue(result.addedIds.isEmpty())
        assertEquals(0L, result.finishedAt)
        assertFalse(result.failed)
    }

    @Test
    fun explorerLoadVariants() {
        val error = IllegalStateException("offline")
        val failed: ExplorerLoad = ExplorerLoad.Failed(error)
        assertTrue(failed is ExplorerLoad.Failed)
        assertEquals(error, (failed as ExplorerLoad.Failed).error)
        assertEquals(ExplorerLoad.Loading, ExplorerLoad.Loading)
        assertEquals(4, AddResult.entries.size)
    }
}
