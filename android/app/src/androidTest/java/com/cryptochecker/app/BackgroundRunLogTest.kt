package com.cryptochecker.app

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.remote.BlockedSources
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.FakeExchange
import com.cryptochecker.app.work.BackgroundCheck
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * «Letzte Prüfungen» ehrlich: Bremst die einzige Börse (429, pausiert), vermerkt
 * die Hintergrund-Prüfung «pausiert» statt «OK» und zählt nicht als volle Prüfung; ebenso keine volle Prüfung, wenn das
 * Budget aufgebraucht ist; mit Kursen «OK» und volle Prüfung.
 * Echter [BackgroundCheck] über Hilt, Antworten aus [FakeExchange] (kein Netz).
 */
@HiltAndroidTest
class BackgroundRunLogTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var backgroundCheck: BackgroundCheck

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var priceRefresher: PriceRefresher

    @Inject lateinit var refreshStats: RefreshStats

    @Inject lateinit var checkRuns: CheckRunStore

    @Before
    fun setUp() {
        hiltRule.inject()
        clearPauses()
        runBlocking { watchRepository.deleteAllWatches() }
    }

    @After
    fun tearDown() {
        FakeExchange.reset()
        clearPauses()
        runBlocking { watchRepository.deleteAllWatches() }
    }

    /** Pausen sind prozessweit bzw. gespeichert: kein anderer Test darf eine gebremste Börse erben. */
    private fun clearPauses() {
        refreshStats.setBackoffStates(emptyMap())
        listOf("binance", "api.binance.com", "api.binance.us").forEach(BlockedSources::clearForTest)
    }

    private suspend fun run(deadlineAt: Long? = null): CheckResult {
        backgroundCheck.run(
            CheckTrigger.WORKER,
            networkGuaranteed = true,
            ttsWaitMillis = 0,
            deadlineAt = deadlineAt,
            dedupeWindowMillis = 0
        )
        return checkRuns.runs.value.maxBy { it.at }.result
    }

    @Test
    fun pausedExchangeIsLoggedAsPausedAndNotAsFullCheck() = runBlocking {
        val id = checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
        priceRefresher.refreshOne(id)
        FakeExchange.rateLimited = true
        refreshStats.setLastFullCheck(0)
        // Erster Lauf: 429 → Börse pausiert; zweiter Lauf: alles pausiert, nichts geprüft
        run()
        assertEquals(CheckResult.PAUSED, run())
        assertEquals("pausiert zählt nicht als volle Prüfung", 0L, refreshStats.lastFullCheck())
    }

    @Test
    fun pricesAreLoggedAsOkAndFullCheck() = runBlocking {
        checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
        refreshStats.setLastFullCheck(0)
        assertEquals(CheckResult.OK, run())
        assertNotEquals(0L, refreshStats.lastFullCheck())
    }

    @Test
    fun budgetCutIsNoFullCheck() = runBlocking {
        checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
        refreshStats.setLastFullCheck(0)
        // Frist schon erreicht: jedes Paar scheitert am Budget, nicht an der Börse
        run(deadlineAt = System.currentTimeMillis())
        assertEquals("Budget-Lücke zählt nicht als volle Prüfung", 0L, refreshStats.lastFullCheck())
    }
}
