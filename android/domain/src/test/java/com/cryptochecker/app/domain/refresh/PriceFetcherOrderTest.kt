package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.util.KeyValueStore
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.DexPool
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceFetcher.fetchGroup] als Verhalten: Paare mit Alarm werden zuerst
 * abgefragt ([FetchOrder]); ist das Budget der Prüfung aufgebraucht, gilt das Paar als «am Budget gescheitert»
 * ([Fetched.budgetCut]) — das zählt nie als volle Prüfung ([BackgroundSchedule.isFullCheck]).
 */
class PriceFetcherOrderTest {

    private val repo = RecordingRepository()
    private val fetcher = PriceFetcher(repo, BulkDelistedStore { EmptyStore })

    /** Zwei Paare: unter [PriceFetcher.MIN_WATCHES_FOR_BULK], also nur Einzelabfragen. */
    private val group = listOf(watch(1), watch(2))

    @Test
    fun alarmedPairsAreAskedFirst() = runBlocking {
        fetcher.fetchGroup(group, includeRollingFutures = false, alarmed = setOf(2L))
        assertEquals(listOf("C2", "C1"), repo.asked.toList())
    }

    @Test
    fun withoutAlarmsTheListOrderStays() = runBlocking {
        fetcher.fetchGroup(group, includeRollingFutures = false, alarmed = emptySet())
        assertEquals(listOf("C1", "C2"), repo.asked.toList())
    }

    @Test
    fun spentBudgetCutsEveryPairAndIsNoFullCheck() = runBlocking {
        val (results, _) = fetcher.fetchGroup(
            group,
            includeRollingFutures = false,
            deadlineAt = System.currentTimeMillis(),
            alarmed = emptySet(),
        )
        assertTrue(results.values.all { it.budgetCut })
        val cut = results.values.count { it.budgetCut }
        assertTrue(!BackgroundSchedule.isFullCheck(attempted = 2, failed = cut, cutByBudget = cut))
    }

    private fun watch(id: Long) = WatchEntity(
        id = id,
        exchangeKey = "Kraken",
        exchangeName = "Kraken",
        baseAsset = "C$id",
        quoteAsset = "EUR",
        pairId = "C${id}EUR",
    )

    /** Merkt die Reihenfolge der Einzelabfragen; Antworten bleiben aus (Fehler). */
    private class RecordingRepository : ExchangeRepository {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun getExchangeList(): List<ExchangeInfo> = emptyList()
        override suspend fun getExchangeCurrencyPairsInfo(exchange: ExchangeInfo, withAlpha: Boolean) = ExchangePairsInfo()
        override fun isExchangeSupportsUpdatePairs(exchange: ExchangeInfo) = false
        override suspend fun getExchangeTicker(exchange: ExchangeInfo, pairInfo: ExchangePair): ExchangeTickerResult {
            asked += pairInfo.base
            error("no ticker in test")
        }
        override fun isExchangeSupportsBulkTickers(exchange: ExchangeInfo) = false
        override suspend fun getBulkTickers(exchange: ExchangeInfo, pairIds: Collection<String>) = BulkTickers()
        override suspend fun updateExchangeCurrencyPairs(exchange: ExchangeInfo) = Unit
        override suspend fun searchDexPools(query: String): List<DexPool> = emptyList()
    }

    private object EmptyStore : KeyValueStore {
        override fun getInt(key: String, default: Int) = default
        override fun getLong(key: String, default: Long) = default
        override fun getBoolean(key: String, default: Boolean) = default
        override fun getString(key: String): String? = null
        override fun contains(key: String) = false
        override fun all(): Map<String, Any?> = emptyMap()
        override fun edit(block: KeyValueStore.Editor.() -> Unit) = Unit
    }
}
