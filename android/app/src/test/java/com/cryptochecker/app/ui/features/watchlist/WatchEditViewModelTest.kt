package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.domain.PairEditResult
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.FavoriteKind
import com.cryptochecker.app.domain.watch.FavoriteStore
import com.cryptochecker.app.ui.fakes.FakeUiWatchStore
import com.cryptochecker.app.ui.fakes.ViewModelTestRule
import com.cryptochecker.app.ui.fakes.recorder
import com.cryptochecker.marketdata.model.ExchangePair
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** «Paar bearbeiten» ([WatchEditViewModel]): Vorbelegung, Auswahl, Paarliste laden und Speichern samt Aufräumen. */
class WatchEditViewModelTest {

    @get:Rule
    val rule = ViewModelTestRule()

    /** Schreibwege der Merkliste und Nebenwirkungen in Reihenfolge. */
    private val log = mutableListOf<String>()
    private val watchStore = FakeUiWatchStore(log)
    private val exchanges = FakeExchanges(mutableListOf())
    private val effects = FakeEffects()

    private val binance = ExchangeInfo("Binance", "Binance")
    private val kraken = ExchangeInfo("Kraken", "Kraken")

    private val btcUsdt = ExchangePair("BTC", "USDT", "BTCUSDT")
    private val ethUsdt = ExchangePair("ETH", "USDT", "ETHUSDT")
    private val ethBtc = ExchangePair("ETH", "BTC", "ETHBTC")

    private val watch = WatchEntity(
        id = 1,
        exchangeKey = "Binance",
        exchangeName = "Binance",
        baseAsset = "ETH",
        quoteAsset = "BTC",
        lastPrice = 50.0,
    )

    /** Börsen und Paarlisten im Speicher; [syncFails] = «Paare aktualisieren» scheitert. */
    private class FakeExchanges(val calls: MutableList<String>) : ExchangeRepository by recorder(ExchangeRepository::class.java, calls) {
        var list: List<ExchangeInfo> = emptyList()
        var listFails = false
        val pairs = HashMap<String, ExchangePairsInfo>()
        val synced = HashMap<String, ExchangePairsInfo>()
        var syncFails = false

        override suspend fun getExchangeList(): List<ExchangeInfo> {
            if (listFails) throw IOException("offline")
            return list
        }

        override suspend fun getExchangeCurrencyPairsInfo(exchange: ExchangeInfo, withAlpha: Boolean) =
            pairs[exchange.key] ?: ExchangePairsInfo()

        override fun isExchangeSupportsUpdatePairs(exchange: ExchangeInfo) = true

        override suspend fun updateExchangeCurrencyPairs(exchange: ExchangeInfo) {
            calls += "update(${exchange.key})"
            if (syncFails) throw IOException("sync failed")
            synced[exchange.key]?.let { pairs[exchange.key] = it }
        }
    }

    private inner class FakeEffects : WatchEditEffects {
        var locked = false

        override suspend fun <T> withRefreshLock(block: suspend () -> T): T {
            locked = true
            log += "lock"
            return block().also {
                log += "unlock"
                locked = false
            }
        }

        override fun forgetLivePrices(watchIds: Collection<Long>) {
            log += "forgetLive($watchIds, locked=$locked)"
        }

        override fun forgetActivity(watchId: Long) {
            log += "forgetActivity($watchId)"
        }

        override fun cancelActivityNotification(watchId: Long) {
            log += "cancelActivity($watchId)"
        }

        override suspend fun cancelPriceNotification(watchId: Long) {
            log += "cancelPrice($watchId)"
        }

        override suspend fun updateWidgets() {
            log += "updateWidgets"
        }

        override suspend fun refreshOne(watchId: Long) {
            log += "refreshOne($watchId, locked=$locked)"
        }
    }

    private object NoFavorites : FavoriteStore {
        private val none = MutableStateFlow(emptySet<String>())

        override fun favorites(kind: FavoriteKind): StateFlow<Set<String>> = none

        override fun toggle(kind: FavoriteKind, item: String) = Unit
    }

    private fun viewModel() = rule.hold { WatchEditViewModel(exchanges, watchStore, NoFavorites, effects, rule.dispatcher) }

    private fun started(): WatchEditViewModel {
        exchanges.list = listOf(kraken, ExchangeInfo("DexScreener", "DEX"), binance)
        exchanges.pairs["Binance"] = ExchangePairsInfo(pairs = listOf(btcUsdt, ethUsdt, ethBtc))
        watchStore.watches.value = listOf(watch)
        return viewModel().also { it.start(watch) }
    }

    @Test
    fun `start offers exchanges without the DEX and preselects the pair`() = runTest(rule.dispatcher) {
        val vm = started()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf(binance, kraken), s.exchanges)
        assertEquals(binance, s.exchange)
        assertEquals("ETH", s.base)
        assertEquals("BTC", s.quote)
        assertEquals(ethBtc.pairId, s.pair?.pairId)
    }

    @Test
    fun `unavailable exchange list leaves an empty choice`() = runTest(rule.dispatcher) {
        exchanges.listFails = true
        val vm = viewModel()
        vm.start(watch)
        advanceUntilIdle()
        assertEquals(emptyList<ExchangeInfo>(), vm.state.value.exchanges)
        assertNull(vm.state.value.exchange)
    }

    @Test
    fun `another coin keeps the quote only when the exchange lists it`() = runTest(rule.dispatcher) {
        val vm = started()
        advanceUntilIdle()
        vm.setBase("BTC")
        assertEquals("USDT", vm.state.value.quote)
        vm.setBase("ETH")
        assertEquals("USDT", vm.state.value.quote)
    }

    @Test
    fun `empty pair list syncs the exchange first`() = runTest(rule.dispatcher) {
        val vm = started()
        advanceUntilIdle()
        exchanges.synced["Kraken"] = ExchangePairsInfo(pairs = listOf(btcUsdt))
        vm.setExchange(kraken)
        advanceUntilIdle()
        assertEquals(listOf("update(Kraken)"), exchanges.calls)
        val s = vm.state.value
        assertFalse(s.syncing)
        assertEquals("BTC", s.base)
        assertEquals("USDT", s.quote)
    }

    @Test
    fun `failed sync names the error and leaves no pairs`() = runTest(rule.dispatcher) {
        val vm = started()
        advanceUntilIdle()
        exchanges.syncFails = true
        vm.setExchange(kraken)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("sync failed", s.syncError)
        assertFalse(s.syncing)
        assertTrue(s.bases.isEmpty())
        assertNull(s.pair)
    }

    @Test
    fun `saving a new pair cleans up the old one and fetches the new price outside the lock`() = runTest(rule.dispatcher) {
        val vm = started()
        advanceUntilIdle()
        vm.setQuote("USDT")
        vm.save(watch.id)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "confirmUserWrite()",
                "lock",
                "changePair(1, Binance, ETHUSDT)",
                "forgetLive([1], locked=true)",
                "unlock",
                "forgetActivity(1)",
                "cancelActivity(1)",
                "cancelPrice(1)",
                "updateWidgets",
                "refreshOne(1, locked=false)",
                "setMissingPercentReferences(1, 50.0)",
            ),
            log,
        )
        assertEquals(1, vm.state.value.savedCount)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun `duplicate pair stays open and touches nothing else`() = runTest(rule.dispatcher) {
        watchStore.pairEditResult = PairEditResult.DUPLICATE
        val vm = started()
        advanceUntilIdle()
        vm.save(watch.id)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()", "lock", "changePair(1, Binance, ETHBTC)", "unlock"), log)
        assertTrue(vm.state.value.duplicate)
        assertEquals(0, vm.state.value.savedCount)
    }

    @Test
    fun `unchanged pair closes the sheet without cleanup`() = runTest(rule.dispatcher) {
        watchStore.pairEditResult = PairEditResult.UNCHANGED
        val vm = started()
        advanceUntilIdle()
        vm.save(watch.id)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()", "lock", "changePair(1, Binance, ETHBTC)", "unlock"), log)
        assertEquals(1, vm.state.value.savedCount)
    }

    @Test
    fun `cancelled confirmation saves nothing`() = runTest(rule.dispatcher) {
        watchStore.confirm = false
        val vm = started()
        advanceUntilIdle()
        vm.save(watch.id)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()"), log)
        assertFalse(vm.state.value.saving)
        assertEquals(0, vm.state.value.savedCount)
    }
}
