package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.cryptochecker.app.activity.ActivityMonitor
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.domain.activity.WhyCandlesFailure
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.FakeExchange
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.features.watchlist.EmptyReasons
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * «Was bewegt sich mit?» ohne Kerzen (V4-02; Audit A-01, früher Quelltext-Wächter): offline heisst es «Offline» mit
 * «Erneut versuchen», das wirklich neu fragt (nicht den gemerkten Leerstand liefert); führt keine Quelle das Paar, nennt
 * der Leerzustand die Börse des Paars statt «Binance» und bietet kein «Erneut versuchen». Echter [ActivityMonitor] über
 * die Test-Börse ([FakeExchange], ein unbekannter Coin hat keine Kerzen). iOS: `WhyCandlesFailureTests`.
 */
@HiltAndroidTest
class WhyEmptyStateDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var monitor: ActivityMonitor

    private val watch = WatchEntity(id = 1, exchangeKey = "Binance", exchangeName = "Binance", baseAsset = COIN, quoteAsset = "USDT", lastPrice = 1.0)

    @Before
    fun setUp() = hiltRule.inject()

    @After
    fun tearDown() {
        ConnectivityMonitor.forcedOnlineForTest = null
    }

    /** Adressen mit dem Coin, die [block] abfragt. */
    private suspend fun coinRequests(block: suspend () -> Unit): Set<String> {
        val before = FakeExchange.requests.size
        block()
        return FakeExchange.requests.drop(before).filterTo(HashSet()) { COIN in it.uppercase() }
    }

    @Test
    fun offlineNamesItAsSuch() = runBlocking<Unit> {
        ConnectivityMonitor.forcedOnlineForTest = false
        assertEquals(WhyCandlesFailure.OFFLINE, monitor.explain(watch).candlesFailure)
    }

    @Test
    fun retryAsksAgainInsteadOfTheRememberedEmptyState() = runBlocking<Unit> {
        ConnectivityMonitor.forcedOnlineForTest = true
        // Keine Quelle führt den Coin: kein Fehler, der Leerstand der Kerzen wird kurz gemerkt
        var report: com.cryptochecker.app.domain.activity.WhyReport? = null
        val first = coinRequests { report = monitor.explain(watch) }
        assertEquals(null, report?.candlesFailure)
        assertTrue("erste Abfrage ging nicht ans Netz", first.isNotEmpty())
        // Ohne «Erneut versuchen»: was gemerkt ist (mindestens die Kerzen), wird nicht neu gefragt
        val again = coinRequests { monitor.explain(watch) }
        val remembered = first - again
        assertTrue("nichts gemerkt: $again", remembered.isNotEmpty())
        // «Erneut versuchen» fragt das Gemerkte neu
        val retry = coinRequests { monitor.explain(watch, refresh = true) }
        assertTrue("«Erneut versuchen» fragte nicht neu: $remembered / $retry", remembered.any { it in retry })
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun show(failure: WhyCandlesFailure?, onRetry: (() -> Unit)?) {
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme { EmptyReasons(failure = failure, exchange = "Kraken", onRetry = onRetry) } }
        }
        compose.waitForIdle()
    }

    @Test
    fun emptyStateNamesTheExchangeOrOffersRetry() {
        var retries = 0
        show(WhyCandlesFailure.OFFLINE) { retries++ }
        compose.onAllNodes(hasText(text(R.string.offline_status))).assertCountEquals(1)
        compose.onAllNodes(hasText(text(R.string.why_no_data_retry_hint))).assertCountEquals(1)
        compose.onNode(hasText(text(R.string.action_retry)) and hasClickAction()).performClick()
        assertEquals(1, retries)
        // Keine Quelle führt das Paar: Börse des Paars genannt, nichts zu wiederholen
        show(null, null)
        compose.onAllNodes(hasText(text(R.string.why_no_candles_hint, "Kraken"))).assertCountEquals(1)
        compose.onAllNodes(hasText(text(R.string.action_retry))).assertCountEquals(0)
    }

    private companion object {
        const val COIN = "ZZQX"
    }
}
