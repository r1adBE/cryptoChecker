package com.cryptochecker.app

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import javax.inject.Inject

/**
 * «+»-Knopf des Portfolios verdeckt beim Lesen keine Beträge (Audit A-01, früher Quelltext-Wächter; Regel
 * `AddButtonScroll`): beim Scrollen nach unten blendet er aus, nach oben wieder ein — in der Übersicht und auf der Seite
 * eines Coins. Echte Oberfläche mit langer Liste. Wie iOS `CryptoCheckerUITests.testAddButtonHidesWhileScrollingDown`.
 */
@HiltAndroidTest
class PortfolioFabScrollDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var watchRepository: WatchRepository
    @Inject lateinit var portfolioRepository: PortfolioRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            settingsRepository.setPortfolioEnabled(true)
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            // 30 Coins für die Übersicht, 30 Käufe von BTC für die Seite des Coins
            COINS.forEachIndexed { i, coin ->
                portfolioRepository.save(tx(coin, now - (i + 2) * day))
            }
            repeat(30) { portfolioRepository.save(tx("BTC", now - (it + 40) * day)) }
        }
    }

    @After
    fun tearDown() {
        runBlocking { portfolioRepository.clearAll() }
    }

    private fun tx(coin: String, time: Long) = PortfolioTxEntity(
        coin = coin, type = PortfolioTxType.BUY, amount = BigDecimal.ONE, priceUsdt = BigDecimal("100"), time = time,
    )

    /** Irgendeine Coin-Zeile der Übersicht. */
    private val anyCoin = androidx.compose.ui.test.SemanticsMatcher("Coin-Zeile") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(UiTestTags.portfolioCoin("")) == true
    }

    private val fab get() = hasContentDescription(compose.activity.getString(R.string.portfolio_add_tx))

    private fun fabShown() = compose.onAllNodes(fab, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Ein Stück scrollen (nicht bis ans Ende: dort zeigt sich der Knopf wieder, damit nichts verdeckt bleibt). */
    private fun scroll(down: Boolean) {
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput {
            if (down) swipeUp(startY = centerY + height / 4f, endY = centerY - height / 8f)
            else swipeDown(startY = centerY - height / 8f, endY = centerY + height / 4f)
        }
        compose.waitForIdle()
    }

    private fun hidesWhileScrollingDown() {
        compose.waitUntil(TIMEOUT) { fabShown() }
        scroll(down = true)
        compose.waitUntil(TIMEOUT) { !fabShown() }
        scroll(down = false)
        compose.waitUntil(TIMEOUT) { fabShown() }
    }

    @Test
    fun overviewAndCoinPageHideTheButtonWhileScrollingDown() {
        click(UiTestTags.TAB_PORTFOLIO)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(anyCoin, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        hidesWhileScrollingDown()
        // Seite von BTC (31 Buchungen); die Zeile kann weiter unten in der Liste stehen
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(UiTestTags.portfolioCoin("BTC"))) }
        click(UiTestTags.portfolioCoin("BTC"))
        hidesWhileScrollingDown()
    }

    private fun exists(tag: String) =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        compose.waitUntil(TIMEOUT) { exists(tag) }
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val COINS = listOf(
            "BTC", "ETH", "SOL", "XRP", "ADA", "DOGE", "DOT", "LTC", "LINK", "AVAX", "ATOM", "UNI", "XLM", "ETC", "FIL",
            "APT", "ARB", "OP", "NEAR", "ALGO", "AAVE", "SAND", "MANA", "EGLD", "XTZ", "THETA", "EOS", "FLOW", "KAVA", "ZEC",
        )
    }
}
