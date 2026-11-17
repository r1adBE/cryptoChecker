package com.cryptochecker.app

import android.view.WindowManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.lock.OverlayShield
import com.cryptochecker.app.ui.lock.SecureShield
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Schutz der App-Fenster: Über dem Portfolio und über «App zurücksetzen» blendet
 * die App fremde Überlagerungen aus (Tapjacking, [OverlayShield]); verlässt man sie, hebt sie es auf. Mehrere Stellen mit
 * FLAG_SECURE zählen: erst die letzte nimmt es weg ([SecureShield]).
 */
@HiltAndroidTest
class OverlayShieldDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            settingsRepository.setPortfolioEnabled(true)
        }
    }

    private fun holders(): Int {
        var count = 0
        compose.runOnUiThread { count = OverlayShield.holders(compose.activity.window) }
        return count
    }

    @Test
    fun portfolioTabHidesOverlaysOnlyWhileShown() {
        click(hasTestTag(UiTestTags.TAB_PORTFOLIO))
        compose.waitUntil(TIMEOUT) { holders() > 0 }
        click(hasTestTag(UiTestTags.TAB_WATCHLIST))
        compose.waitUntil(TIMEOUT) { holders() == 0 }
    }

    @Test
    fun resetQuestionHidesOverlays() {
        click(hasTestTag(UiTestTags.TAB_SETTINGS))
        assertEquals(0, holders())
        click(hasText(compose.activity.getString(R.string.settings_reset_app)))
        compose.waitUntil(TIMEOUT) { holders() > 0 }
    }

    @Test
    fun secureFlagStaysUntilTheLastHolderReleases() {
        compose.runOnUiThread {
            val window = compose.activity.window
            fun secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            SecureShield.acquire(window)
            SecureShield.acquire(window)
            SecureShield.release(window)
            assertTrue("eine Stelle hält noch", secure())
            SecureShield.release(window)
            assertTrue("letzte Stelle hebt auf", !secure())
        }
    }

    private fun click(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        // Weiter unten in einer Liste (noch nicht zusammengesetzt): erst dorthin rollen
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher) }
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
