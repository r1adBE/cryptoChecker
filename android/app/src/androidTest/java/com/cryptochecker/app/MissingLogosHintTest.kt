package com.cryptochecker.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.dp
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
import com.cryptochecker.app.ui.features.watchlist.WatchlistLogoHint
import com.cryptochecker.app.ui.features.watchlist.WatchlistMissingLogos
import com.cryptochecker.app.domain.util.PercentText
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Keine Logos; [notYet] = Adresse bekannt, noch nicht geladen; [loadingNow]/[percentNow] = Hinweis «Logos werden geladen». */
    private class NoLogos(
        private val notYet: Set<String>,
        loadingNow: Boolean = false,
        percentNow: Int? = null,
        private val firstDone: Boolean = true,
    ) : CoinLogoSource {
        val dismissed = MutableStateFlow<Set<String>?>(emptySet())
        override val missingDismissed: StateFlow<Set<String>?> = dismissed
        override suspend fun firstLoadDone(): Boolean = firstDone
        override fun dismissMissing(keys: Set<String>) {
            dismissed.value = dismissed.value.orEmpty() + keys
        }
        override val loading: StateFlow<Boolean> = MutableStateFlow(loadingNow)
        override val loadingPercent: StateFlow<Int?> = MutableStateFlow(percentNow)
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
        setList(NoLogos(notYet))
        // Zeile erscheint nach dem ersten Laden (produceState)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(MISSING_LOGOS_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(MISSING_LOGOS_TAG).performClick()
        compose.waitForIdle()
    }

    private fun setList(source: NoLogos) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalCoinLogoSource provides source) {
                        WatchlistMissingLogos(listOf(watch(1, "ZZQA"), watch(2, "ZZQB")), reduceMotion = true)
                    }
                }
            }
        }
    }

    private fun rowShown() = compose.onAllNodesWithTag(MISSING_LOGOS_TAG).fetchSemanticsNodes().isNotEmpty()

    /**
     * Zeile «2 Coins ohne Logo» (Audit A-01, früher Quelltext-Wächter): Zahl in der Mehrzahl-Form, ✕ mit eigener
     * Bedienhilfe und mindestens 48 dp; ✕ merkt die Coins, danach bleibt die Zeile weg.
     */
    @Test
    fun rowCountsTheCoinsAndCloseRemembersThem() {
        val source = NoLogos(emptySet())
        setList(source)
        compose.waitUntil(5_000) { rowShown() }
        val title = compose.activity.resources.getQuantityString(R.plurals.watchlist_missing_logos, 2, 2)
        compose.onAllNodes(hasText(title)).assertCountEquals(1)
        // Knopf (zusammengeführt mit seinem Symbol), nicht das 16-dp-Symbol allein
        val close = compose.onNode(hasContentDescription(text(R.string.watchlist_missing_logos_dismiss)) and hasClickAction())
        close.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        close.performClick()
        compose.waitUntil(5_000) { !rowShown() }
        assertEquals("beide Coins gemerkt", 2, source.dismissed.value.orEmpty().size)
    }

    /** Während des ersten Ladens bzw. solange «Logos werden geladen» steht: keine Zeile (sonst hiesse es zu früh «ohne Logo»). */
    @Test
    fun noRowBeforeTheFirstLoadOrWhileLoading() {
        for (source in listOf(NoLogos(emptySet(), firstDone = false), NoLogos(emptySet(), loadingNow = true))) {
            setList(source)
            compose.waitForIdle()
            Thread.sleep(SETTLE_MILLIS)
            compose.waitForIdle()
            assertFalse(rowShown())
        }
        // Schalter «Coin-Logos» aus: keine Logo-Quelle → nie
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { WatchlistMissingLogos(listOf(watch(1, "ZZQA")), reduceMotion = true) }
            }
        }
        compose.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
        assertFalse(rowShown())
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private companion object {
        const val SETTLE_MILLIS = 1_500L
    }

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

    /** Zeile «Logos werden geladen …»: mit Prozent «· 40 %» (Zahlenformat der App), ohne Prozent nur der Text. */
    @Test
    fun loadingLineShowsThePercentOrThePlainText() {
        for ((percent, expected) in listOf(
            40 to compose.activity.getString(R.string.watchlist_logos_loading_percent, PercentText.unsigned(40.0, 0)),
            null to text(R.string.watchlist_logos_loading),
        )) {
            val source = NoLogos(emptySet(), loadingNow = true, percentNow = percent)
            compose.runOnUiThread {
                compose.activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalCoinLogoSource provides source) { WatchlistLogoHint(reduceMotion = true) }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
