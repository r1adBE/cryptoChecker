package com.cryptochecker.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
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
 * «Beträge verbergen» gilt an der Wurzel der App (U3-01; Audit A-01, früher Quelltext-Wächter): nicht nur in der Liste
 * des Portfolio-Tabs, auch auf der eigenen Seite eines Coins (anderes Ziel der Navigation) stehen die Beträge verdeckt.
 */
@HiltAndroidTest
class HiddenAmountsDeviceTest {

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
            settingsRepository.setHidePortfolioAmounts(true)
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
        runBlocking {
            settingsRepository.setHidePortfolioAmounts(false)
            portfolioRepository.clearAll()
        }
    }

    @Test
    fun amountsStayHiddenOnTheCoinPage() {
        click(UiTestTags.TAB_PORTFOLIO)
        waitForHidden()
        click(UiTestTags.portfolioCoin("BTC"))
        // Eigene Seite des Coins: Transaktionen mit verdecktem Wert
        waitForHidden()
    }

    private fun waitForHidden() = compose.waitUntil(TIMEOUT) {
        compose.onAllNodes(hasText(PortfolioInsights.HIDDEN, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(tag: String) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
