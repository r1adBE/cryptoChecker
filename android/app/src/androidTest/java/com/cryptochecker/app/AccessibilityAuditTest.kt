package com.cryptochecker.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.ComposeA11yAudit
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.marketdata.model.MarketPair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Automatische Barrierefreiheits-Prüfung ([ComposeA11yAudit]) der Hauptbereiche Merkliste, Portfolio und Einstellungen
 * in Standardgrösse — wie `AccessibilityAuditTests` in iOS (`performAccessibilityAudit`). Läufe mit grosser Schrift und
 * auf Arabisch sowie die Alarm-Übersicht folgen (brauchen eine vor dem Start umgestellte Gerätekonfiguration,
 * [com.cryptochecker.app.testing.DeviceOverrides]).
 *
 * Ausführen: `./gradlew :app:connectedDebugAndroidTest` (Emulator oder Gerät).
 */
@HiltAndroidTest
class AccessibilityAuditTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var portfolioRepository: PortfolioRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true) // keine Begrüssung
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            settingsRepository.setPortfolioEnabled(true) // Tab «Portfolio»
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
            // Ein Paar mit Alarm: Merkliste mit Zeile
            val watchId = checkNotNull(
                watchRepository.addWatch(MarketInfo("Binance", "Binance"), MarketPair("BTC", "USDT", "BTCUSDT"))
            )
            watchRepository.saveAlarm(
                AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_ABOVE, threshold = 1_000_000.0)
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
        }
    }

    @Test
    fun mainScreensPassAudit() {
        val findings = mutableListOf<String>()
        waitForTag(UiTestTags.WATCH_ROW)
        findings += ComposeA11yAudit.findings(compose, "watchlist", SUPPRESSIONS)
        clickTag(UiTestTags.TAB_PORTFOLIO)
        findings += ComposeA11yAudit.findings(compose, "portfolio", SUPPRESSIONS)
        clickTag(UiTestTags.TAB_SETTINGS)
        findings += ComposeA11yAudit.findings(compose, "settings", SUPPRESSIONS)
        assertTrue("Barrierefreiheits-Befunde:\n" + findings.joinToString("\n"), findings.isEmpty())
    }

    private fun clickTag(tag: String) {
        val enabled = hasTestTag(tag) and isEnabled()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(enabled, useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L

        /**
         * Bekannte, unvermeidbare Befunde mit Begründung. Leer: Neue Befunde zuerst in der App beheben; nur was sich
         * nicht beheben lässt (z. B. Bibliotheks-Bausteine), mit Begründung hier eintragen.
         */
        val SUPPRESSIONS = emptyList<ComposeA11yAudit.Suppression>()
    }
}
