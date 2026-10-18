package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zustand der Kopfzeile ([headerStateOf]) aus dem ViewModel-Stand und dem Bildschirm. */
class WatchlistHeaderStateTest {

    private fun watch(id: Long, favorite: Boolean = false) = WatchEntity(
        id = id, marketKey = "Binance", marketName = "Binance", baseAsset = "C$id", quoteAsset = "USDT", favorite = favorite,
    )

    private fun uiState(
        watches: List<WatchEntity>,
        filter: GroupFilter? = GroupFilter(groups = emptyList(), selected = null),
        alarmCounts: Map<Long, Int> = emptyMap(),
        alarmTotal: Int? = null,
        refreshing: Boolean = false,
    ) = WatchlistUiState(
        content = WatchlistContent.Initial.copy(
            watches = watches, groupFilter = filter, alarmCounts = alarmCounts, alarmTotal = alarmTotal,
        ),
        display = WatchlistDisplay.initial(AppSettings()),
        live = WatchlistLive(
            refreshing = refreshing, online = true, liveExchanges = emptyList(), liveWatchIds = emptySet(),
            convertRates = emptyMap(), convertFxTimes = emptyMap(), sortRates = emptyMap(), changeStamp = null,
        ),
        starter = StarterUiState(
            coins = emptyList(), deselected = emptySet(),
            prices = StarterPricesState(loading = false, prices = emptyMap()),
            adding = false, logosReady = true, quote = "USDT",
        ),
    )

    private fun header(
        state: WatchlistUiState,
        activityChip: Boolean = false,
        quickView: QuickView? = null,
        sortMode: Boolean = false,
        selecting: Boolean = false,
        searching: Boolean = false,
        selectedCount: Int = 0,
        shownCount: Int = state.watches.size,
        visibleCount: Int = state.watches.size,
        notTradedCount: Int = 0,
    ) = headerStateOf(
        state, activityChip, quickView, sortMode, selecting, searching, selectedCount, shownCount, visibleCount, notTradedCount,
    )

    @Test
    fun takesGroupsAlarmsAndRefreshFromState() {
        val filter = GroupFilter(groups = listOf("DeFi"), selected = "DeFi", tokens = true, alpha = true)
        val h = header(
            uiState(
                listOf(watch(1, favorite = true), watch(2)), filter,
                alarmCounts = mapOf(1L to 2, 2L to 1), alarmTotal = 5, refreshing = true,
            ),
            notTradedCount = 1,
        )
        assertEquals(listOf("DeFi"), h.groups)
        assertEquals("DeFi", h.selectedGroup)
        assertTrue(h.hasFavorites)
        assertTrue(h.hasTokens)
        assertTrue(h.hasAlpha)
        assertEquals(3, h.activeAlarms)
        assertEquals(5, h.alarmTotal)
        assertTrue(h.refreshing)
        assertTrue(h.canClear)
        assertEquals(1, h.notTradedCount)
        assertEquals(2, h.pairCount)
    }

    @Test
    fun emptyListAndUnloadedFilter() {
        val h = header(uiState(emptyList(), filter = null))
        assertFalse(h.hasFavorites)
        assertFalse(h.hasTokens)
        assertFalse(h.canClear)
        assertFalse(h.canSort)
        assertFalse(h.canSelect)
        assertFalse(h.allSelected)
        assertNull(h.alarmTotal)
        assertEquals(0, h.pairCount)
    }

    @Test
    fun activityChipNotWhileSorting() {
        val state = uiState(listOf(watch(1), watch(2)))
        assertTrue(header(state, activityChip = true).hasActivity)
        assertFalse(header(state, activityChip = true, sortMode = true).hasActivity)
        assertFalse(header(state, activityChip = false).hasActivity)
    }

    @Test
    fun sortAndSelectDependOnVisiblePairs() {
        val state = uiState(listOf(watch(1), watch(2)))
        assertTrue(header(state, visibleCount = 2).canSort)
        assertFalse(header(state, visibleCount = 1).canSort)
        assertTrue(header(state, visibleCount = 1).canSelect)
        assertFalse(header(state, visibleCount = 0).canSelect)
    }

    @Test
    fun allSelectedOnlyWithEveryShownPair() {
        val state = uiState(listOf(watch(1), watch(2), watch(3)))
        assertTrue(header(state, selecting = true, selectedCount = 2, shownCount = 2).allSelected)
        assertFalse(header(state, selecting = true, selectedCount = 1, shownCount = 2).allSelected)
        // Suche ohne Treffer: nichts zum Auswählen, also nicht «alle ausgewählt»
        assertFalse(header(state, selecting = true, selectedCount = 0, shownCount = 0).allSelected)
    }

    @Test
    fun passesScreenFlags() {
        val h = header(
            uiState(listOf(watch(1))), quickView = QuickView.STALE, sortMode = true, selecting = true,
            searching = true, selectedCount = 1,
        )
        assertEquals(QuickView.STALE, h.quickView)
        assertTrue(h.sortMode)
        assertTrue(h.selecting)
        assertTrue(h.searching)
        assertEquals(1, h.selectedCount)
    }
}
