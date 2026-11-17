package com.cryptochecker.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Löschen einer Buchungszeile (Wischen bzw. TalkBack «Löschen»; Audit A-01, früher Quelltext-Wächter): erst die Rückfrage
 * wie im Editor, gelöscht wird erst nach «Löschen» — ohne «Rückgängig», wie der Editor. Echte Oberfläche.
 * iOS: `CryptoCheckerUITests.testTxSwipeDeleteAsksFirst`.
 */
@HiltAndroidTest
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class TxSwipeDeleteDeviceTest {

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
            portfolioRepository.save(
                PortfolioTxEntity(
                    coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal("0.5"), priceUsdt = BigDecimal("30000"),
                    time = System.currentTimeMillis() - 86_400_000L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking { portfolioRepository.clearAll() }
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun transactions() = runBlocking { portfolioRepository.getTransactions() }

    @Test
    fun deleteAsksFirstAndOffersNoUndo() {
        click(hasTestTag(UiTestTags.TAB_PORTFOLIO))
        click(hasTestTag(UiTestTags.portfolioCoin("BTC")))
        val delete = text(R.string.action_delete)
        val row = SemanticsMatcher("Buchungszeile mit «$delete»") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == delete } == true
        }
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(row).onFirst().performCustomAccessibilityActionWithLabel(delete)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text(R.string.portfolio_tx_delete_title))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("vor der Bestätigung nichts gelöscht", 1, transactions().size)
        // Knopf «Löschen» der Rückfrage (Text und Klick im zusammengeführten Baum)
        val confirm = hasText(delete) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(confirm).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(confirm).onFirst().performClick()
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { transactions().isEmpty() }
        // Wie der Editor: kein «Rückgängig»
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText(text(R.string.action_undo)), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun click(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
