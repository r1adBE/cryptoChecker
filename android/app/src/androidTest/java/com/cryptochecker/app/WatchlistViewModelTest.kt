package com.cryptochecker.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SheetChartRepository
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.features.watchlist.WatchlistSheetData
import com.cryptochecker.app.ui.features.watchlist.WatchlistUiState
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.widget.WidgetPrefs
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.work.PriceUpdateScheduler
import com.cryptochecker.marketdata.model.MarketPair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * [WatchlistViewModel] mit allen echten Teilen (Hilt, Datenbank, Einstellungen; Börsen-Antworten aus
 * [com.cryptochecker.app.testing.FakeMarket]) über seinen gebündelten Zustand [WatchlistViewModel.uiState], mit Turbine
 * beobachtet: Gruppen-Auswahl samt Rückfall auf «Alle», Spalten-Sortierung, Löschen mit «Rückgängig» und die
 * Start-Auswahl der leeren Merkliste. Unit-Tests auf der JVM gehen nicht: Die Teile des ViewModels brauchen
 * Android-Kontext, Room und DataStore.
 */
@HiltAndroidTest
class WatchlistViewModelTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var watchRepository: WatchRepository
    @Inject lateinit var priceRefresher: PriceRefresher
    @Inject lateinit var notifier: AppNotifier
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var scheduler: PriceUpdateScheduler
    @Inject lateinit var manualRefresh: ManualRefresh
    @Inject lateinit var refreshStats: RefreshStats
    @Inject lateinit var connectivity: ConnectivityMonitor
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var activityRepository: ActivityRepository
    @Inject lateinit var widgetPrefs: WidgetPrefs
    @Inject lateinit var currencyConverter: CurrencyConverter
    @Inject lateinit var starterCoinsRepository: StarterCoinsRepository
    @Inject lateinit var addMoments: AddMoments
    @Inject lateinit var sheetChartRepository: SheetChartRepository
    @Inject lateinit var livePriceStream: LivePriceStream
    @Inject lateinit var coinLogoRepository: CoinLogoRepository
    @Inject lateinit var tokenIdentityStore: TokenIdentityStore
    @Inject lateinit var portfolioAlarmRepository: PortfolioAlarmRepository
    @Inject lateinit var sheetData: WatchlistSheetData

    /** Eigener Speicher: [ViewModelStore.clear] beendet den viewModelScope nach jedem Test. */
    private val store = ViewModelStore()
    private lateinit var vm: WatchlistViewModel

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setWatchlistGroup(null)
            settingsRepository.setWatchlistColumnSort(null)
            watchRepository.deleteAllWatches()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WatchlistViewModel(
                watchRepository, priceRefresher, notifier, widgetUpdater, scheduler, manualRefresh, refreshStats,
                connectivity, settingsRepository, activityRepository, widgetPrefs, currencyConverter,
                starterCoinsRepository, addMoments, sheetChartRepository, livePriceStream, coinLogoRepository,
                tokenIdentityStore, portfolioAlarmRepository, sheetData,
            ) as T
        }
        vm = ViewModelProvider(store, factory)[WatchlistViewModel::class.java]
    }

    @After
    fun tearDown() {
        store.clear()
        runBlocking {
            settingsRepository.setWatchlistGroup(null)
            settingsRepository.setWatchlistColumnSort(null)
            watchRepository.deleteAllWatches()
        }
    }

    private suspend fun add(base: String, group: String? = null): Long {
        val id = checkNotNull(watchRepository.addWatch(MarketInfo("Binance", "Binance"), MarketPair(base, "USDT", "${base}USDT")))
        if (group != null) watchRepository.setGroup(id, group)
        return id
    }

    /** Nächster Stand, der [predicate] erfüllt (frühere werden übersprungen). */
    private suspend fun ReceiveTurbine<WatchlistUiState>.awaitUntil(predicate: (WatchlistUiState) -> Boolean): WatchlistUiState {
        var last: WatchlistUiState? = null
        while (true) {
            val state = try {
                awaitItem()
            } catch (e: AssertionError) {
                // Wackelnder Test: im Bericht steht, worauf gewartet wurde (letzter Zustand)
                throw AssertionError(
                    "kein passender Zustand; zuletzt loaded=${last?.loaded}, columnSort=${last?.display?.columnSort}, " +
                        "Paare=${last?.watches?.size}, gespeichert=${settingsRepository.cached.watchlistColumnSort}",
                    e,
                )
            }
            last = state
            if (predicate(state)) return state
        }
    }

    @Test
    fun groupSelectionFiltersAndFallsBackToAllWhenTheGroupVanishes() = runTest {
        add("BTC", group = "Layer 1")
        add("ETH", group = "Layer 1")
        add("DOGE")

        vm.uiState.test(timeout = TIMEOUT) {
            awaitUntil { it.loaded && it.watches.size == 3 && it.groups == listOf("Layer 1") }

            vm.selectGroup("Layer 1")
            val selected = awaitUntil { it.selectedGroup == "Layer 1" }
            assertEquals(listOf("BTC", "ETH"), selected.watches.filter { it.groupName == "Layer 1" }.map { it.baseAsset }.sorted())

            // Gruppe verschwindet (kein Paar nutzt sie mehr): Auswahl fällt auf «Alle» zurück, auch gespeichert
            watchRepository.clearGroup("Layer 1")
            val all = awaitUntil { it.groups.isEmpty() && it.loaded }
            assertNull(all.selectedGroup)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(settingsRepository.current().watchlistGroup)
    }

    @Test
    fun tappingAColumnCyclesTheSortOrder() = runTest {
        add("BTC")
        val first = ColumnSort.next(null, SortKey.PRICE)
        val second = ColumnSort.next(first, SortKey.PRICE)

        vm.uiState.test(timeout = TIMEOUT) {
            awaitUntil { it.loaded && it.display.columnSort == null }

            vm.tapColumn(SortKey.PRICE)
            assertEquals(first, awaitUntil { it.display.columnSort != null }.display.columnSort)

            vm.tapColumn(SortKey.PRICE)
            assertEquals(second, awaitUntil { it.display.columnSort != first }.display.columnSort)

            // Dritter Tipp: wieder die eigene Reihenfolge
            vm.tapColumn(SortKey.PRICE)
            assertNull(awaitUntil { it.display.columnSort != second }.display.columnSort)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun undoDeleteRestoresThePairWithItsAlarm() = runTest {
        val id = add("BTC")
        add("ETH")
        watchRepository.saveAlarm(AlarmEntity(watchId = id, condition = AlarmCondition.PRICE_ABOVE, threshold = 1_000_000.0))

        vm.uiState.test(timeout = TIMEOUT) {
            val loaded = awaitUntil { it.loaded && it.watches.size == 2 && it.content.alarmCounts[id] == 1 }
            val btc = loaded.watches.single { it.id == id }

            vm.deleteWithUndo(btc)
            awaitUntil { state -> state.watches.none { it.id == id } }

            vm.undoDelete(id)
            val restored = awaitUntil { state -> state.watches.any { it.id == id } && state.content.alarmCounts[id] == 1 }
            assertEquals("gleiche Id wie vorher", "BTC", restored.watches.single { it.id == id }.baseAsset)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun starterSelectionAddsOnlyTheChosenCoin() = runTest {
        vm.uiState.test(timeout = TIMEOUT) {
            val empty = awaitUntil { it.loaded && it.watches.isEmpty() && it.starter.coins.isNotEmpty() }
            assertTrue(empty.starter.coins.any { it.symbol == "BTC" })

            vm.loadStarterCoins()
            // «Keine auswählen», dann nur Bitcoin
            vm.toggleAllStarters()
            awaitUntil { state -> state.starter.coins.all { it.symbol in state.starter.deselected } }
            vm.toggleStarter("BTC")
            awaitUntil { "BTC" !in it.starter.deselected }

            vm.addSelectedStarters()
            val added = awaitUntil { it.watches.isNotEmpty() && !it.starter.adding }
            assertEquals(listOf("BTC"), added.watches.map { it.baseAsset })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("BTC"), watchRepository.getWatches().map { it.baseAsset })
    }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
