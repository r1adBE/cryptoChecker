package com.cryptochecker.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.features.portfolio.PortfolioEmptyExample
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Leeres Portfolio (Audit A-01, früher Quelltext-Wächter): nur ein Satz — das Beispiel mit der Menge im Zahlenformat der
 * App — und der Fusstext, kein zweiter Absatz. Wie iOS `CryptoCheckerUITests.testEmptyPortfolioShowsOneSentence`.
 */
@HiltAndroidTest
class PortfolioEmptyStateDeviceTest {

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
            settingsRepository.setHidePortfolioAmounts(false)
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
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
    fun emptyPortfolioShowsOneSentenceAndTheFooter() {
        click(UiTestTags.TAB_PORTFOLIO)
        val example = compose.activity.getString(R.string.portfolio_empty_example_plain, PortfolioEmptyExample.amountText())
        for (text in listOf(example, compose.activity.getString(R.string.portfolio_footer_short))) {
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
        }
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
