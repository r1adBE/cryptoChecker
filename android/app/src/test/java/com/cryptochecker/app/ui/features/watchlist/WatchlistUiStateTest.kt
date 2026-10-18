package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.WatchlistSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gebündelter Zustand der Merkliste ([WatchlistUiState]) und seine reinen Abbildungen. */
class WatchlistUiStateTest {

    private fun watch(id: Long, group: String? = null, favorite: Boolean = false) = WatchEntity(
        id = id, marketKey = "Binance", marketName = "Binance", baseAsset = "C$id", quoteAsset = "USDT",
        groupName = group, favorite = favorite,
    )

    private val settings = WatchlistSettings.of(AppSettings())

    @Test
    fun displayFollowsSettings() {
        val s = settings.copy(
            sparkline = true,
            activityCard = false,
            columnSort = ColumnSort(SortKey.PRICE, descending = true).encode(),
            showConverted = true,
            portfolioCurrency = "CHF",
            backgroundIntervalMinutes = 30,
            changeBasis = ChangeBasis.DEFAULT,
            showChangePeriod = true,
            priceNotifications = false,
            gestureHintSeen = false,
            firstAlarmCardDismissed = false,
            activitySensitivity = ActivitySensitivity.LESS,
            sheetChartLine = true,
        )
        val d = WatchlistDisplay.of(s)
        assertTrue(d.sparklineEnabled)
        assertFalse(d.activityCardEnabled)
        assertEquals(ColumnSort(SortKey.PRICE, descending = true), d.columnSort)
        assertEquals("CHF", d.convertTarget)
        assertEquals(LiveStatus.pollingStaleAfterMillis(30), d.staleAfterMillis)
        assertTrue(d.showChangePeriod)
        assertFalse(d.priceNotificationsOn)
        assertTrue(d.showGestureHint)
        assertFalse(d.firstAlarmCardDismissed)
        assertEquals(ActivitySensitivity.LESS, d.activitySensitivity)
        assertTrue(d.sheetChartLine)
    }

    @Test
    fun conversionOffMeansNoTarget() {
        assertNull(WatchlistDisplay.of(settings.copy(showConverted = false, portfolioCurrency = "EUR")).convertTarget)
        assertNull(WatchlistDisplay.of(settings.copy(columnSort = null)).columnSort)
        // Gesehener Gesten-Hinweis: nicht mehr zeigen
        assertFalse(WatchlistDisplay.of(settings.copy(gestureHintSeen = true)).showGestureHint)
    }

    @Test
    fun initialStateDoesNotFlashCards() {
        // Bis die Einstellungen gelesen sind: kein Gesten-Hinweis, Karte «Ersten Alarm» als geschlossen, 45 Min.
        val d = WatchlistDisplay.initial(AppSettings(gestureHintSeen = false, firstAlarmCardDismissed = false))
        assertFalse(d.showGestureHint)
        assertTrue(d.firstAlarmCardDismissed)
        assertEquals(45 * 60_000L, d.staleAfterMillis)
        // Übrige Werte aus dem Zwischenspeicher
        assertTrue(WatchlistDisplay.initial(AppSettings(watchlistSparkline = true)).sparklineEnabled)
    }

    @Test
    fun loadedOnlyWithListAndGroups() {
        val starter = StarterUiState(
            coins = emptyList(), deselected = emptySet(),
            prices = StarterPricesState(loading = true, prices = emptyMap()),
            adding = false, logosReady = false, quote = "USDT",
        )
        val display = WatchlistDisplay.initial(AppSettings())
        val live = WatchlistLive(
            refreshing = false, online = true, liveExchanges = emptyList(), liveWatchIds = emptySet(),
            convertRates = emptyMap(), convertFxTimes = emptyMap(), sortRates = emptyMap(), changeStamp = null,
        )
        val initial = WatchlistUiState(WatchlistContent.Initial, display, live, starter)
        assertFalse(initial.loaded)
        assertTrue(initial.watches.isEmpty())
        assertNull(initial.selectedGroup)

        val onlyList = initial.copy(content = WatchlistContent.Initial.copy(watches = listOf(watch(1))))
        assertFalse(onlyList.loaded)

        val filter = GroupFilter(groups = listOf("DeFi"), selected = "DeFi", kinds = mapOf(1L to AssetKind.ALPHA))
        val loaded = initial.copy(content = WatchlistContent.Initial.copy(watches = emptyList(), groupFilter = filter))
        assertTrue(loaded.loaded)
        assertEquals(listOf("DeFi"), loaded.groups)
        assertEquals("DeFi", loaded.selectedGroup)
        assertEquals(AssetKind.ALPHA, loaded.kinds[1L])
    }

    @Test
    fun filterByGroupMatchesChips() {
        val a = watch(1, group = "DeFi")
        val b = watch(2, favorite = true)
        val c = watch(3)
        val all = listOf(a, b, c)
        assertEquals(all, filterByGroup(all, null, emptyMap()))
        assertEquals(listOf(a), filterByGroup(all, "DeFi", emptyMap()))
        assertEquals(listOf(b), filterByGroup(all, WatchFilter.FAVORITES, emptyMap()))
        assertEquals(listOf(c), filterByGroup(all, WatchFilter.ALPHA, mapOf(3L to AssetKind.ALPHA)))
    }

    @Test
    fun sparklinesNeedRoom() {
        assertFalse(sparklinesFit(enabled = false, screenWidthDp = 800, fontScale = 1f))
        assertTrue(sparklinesFit(enabled = true, screenWidthDp = SPARKLINE_MIN_SCREEN_DP, fontScale = 1f))
        assertFalse(sparklinesFit(enabled = true, screenWidthDp = SPARKLINE_MIN_SCREEN_DP - 1, fontScale = 1f))
        // Grössere Schrift braucht mehr Breite; kleinere gibt keine zusätzliche
        assertFalse(sparklinesFit(enabled = true, screenWidthDp = SPARKLINE_MIN_SCREEN_DP, fontScale = 1.3f))
        assertFalse(sparklinesFit(enabled = true, screenWidthDp = SPARKLINE_MIN_SCREEN_DP - 1, fontScale = 0.8f))
    }
}
