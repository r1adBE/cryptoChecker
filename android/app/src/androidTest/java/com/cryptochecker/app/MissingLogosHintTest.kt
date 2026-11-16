package com.cryptochecker.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.components.CoinLogoSource
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.features.watchlist.MISSING_LOGOS_TAG
import com.cryptochecker.app.ui.features.watchlist.WatchlistMissingLogos
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Hinweis «Coins ohne Logo» (Audit A-01, früher Quelltext-Wächter): die Liste unterscheidet «noch nicht geladen» (kommt
 * mit dem nächsten Abgleich) von «kein Logo verfügbar» — Erklärung und Vermerk je Coin. Echte Zeile und echter Dialog
 * mit einer Logo-Quelle ohne Logos. Wie iOS `MissingLogosModelTests.swift`.
 */
@HiltAndroidTest
class MissingLogosHintTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    /** Keine Logos; [notYet] = Adresse bekannt, noch nicht geladen. */
    private class NoLogos(private val notYet: Set<String>) : CoinLogoSource {
        override val revision: StateFlow<Int> = MutableStateFlow(0)
        override val tradFiPairs: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val names: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
        override fun cached(symbol: String): Bitmap? = null
        override suspend fun load(symbol: String): Bitmap? = null
        override suspend fun notYetLoaded(symbols: List<String>): Set<String> = symbols.filterTo(HashSet()) { it in notYet }
    }

    private fun watch(id: Long, base: String) =
        WatchEntity(id = id, exchangeKey = "Binance", exchangeName = "Binance", baseAsset = base, quoteAsset = "USDT")

    private fun showList(notYet: Set<String>) {
        val source = NoLogos(notYet)
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalCoinLogoSource provides source) {
                        WatchlistMissingLogos(listOf(watch(1, "ZZQA"), watch(2, "ZZQB")), reduceMotion = true)
                    }
                }
            }
        }
        // Zeile erscheint nach dem ersten Laden (produceState)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(MISSING_LOGOS_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(MISSING_LOGOS_TAG).performClick()
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    @Test
    fun notYetLoadedIsNamedAsSuch() {
        showList(notYet = setOf("ZZQA"))
        compose.onAllNodes(hasText(text(R.string.watchlist_missing_logos_explain_pending))).assertCountEquals(1)
        compose.onAllNodes(hasText(text(R.string.watchlist_missing_logos_pending))).assertCountEquals(1)
    }

    @Test
    fun noLogoAvailableWithoutPendingCoins() {
        showList(notYet = emptySet())
        compose.onAllNodes(hasText(text(R.string.watchlist_missing_logos_explain))).assertCountEquals(1)
        compose.onAllNodes(hasText(text(R.string.watchlist_missing_logos_pending))).assertCountEquals(0)
    }
}
