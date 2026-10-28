package com.cryptochecker.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.ComposeA11yAudit
import com.cryptochecker.app.testing.DeviceOverride
import com.cryptochecker.app.testing.DeviceOverridesRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.marketdata.model.ExchangePair
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
 * Automatische Barrierefreiheits-Prüfung ([ComposeA11yAudit]) der Hauptbereiche Merkliste, Portfolio, Einstellungen und
 * der Alarm-Übersicht (Glocke) — wie `AccessibilityAuditTests` in iOS (`performAccessibilityAudit`): je ein Lauf in
 * Standardgrösse, mit doppelter Schrift und auf Arabisch (Rechts nach links). Schrift und Sprache gelten nur für die
 * Oberfläche der App ([DeviceOverride], [com.cryptochecker.app.ui.TestDisplayOverride]), nie für das ganze Gerät.
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
    val overrides = DeviceOverridesRule()

    @get:Rule(order = 3)
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
                watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT"))
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
    fun mainScreensPassAudit() = auditMainScreens()

    @Test
    @DeviceOverride(fontScale = 2f)
    fun mainScreensPassAuditWithLargeFont() = auditMainScreens()

    @Test
    @DeviceOverride(language = "ar")
    fun mainScreensPassAuditInArabic() {
        waitForTag(UiTestTags.WATCH_ROW)
        assertEquals("Arabisch: rechts nach links", LayoutDirection.Rtl, layoutDirection())
        auditMainScreens()
    }

    /** Merkliste, Alarm-Übersicht (Glocke, es gibt einen Alarm), Portfolio und Einstellungen. */
    private fun auditMainScreens() {
        val findings = mutableListOf<String>()
        waitForTag(UiTestTags.WATCH_ROW)
        findings += ComposeA11yAudit.findings(compose, "watchlist", SUPPRESSIONS)
        clickTag(UiTestTags.WATCH_BELL)
        waitForTag(UiTestTags.ALARMS_OVERVIEW)
        findings += ComposeA11yAudit.findings(compose, "alarms", SUPPRESSIONS)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag(UiTestTags.WATCH_ROW)
        clickTag(UiTestTags.TAB_PORTFOLIO)
        findings += ComposeA11yAudit.findings(compose, "portfolio", SUPPRESSIONS)
        clickTag(UiTestTags.TAB_SETTINGS)
        findings += ComposeA11yAudit.findings(compose, "settings", SUPPRESSIONS)
        assertTrue("Barrierefreiheits-Befunde:\n" + findings.joinToString("\n"), findings.isEmpty())
    }

    /** Leserichtung der Oberfläche, wie Compose sie anwendet (Wurzel der Merkliste). */
    private fun layoutDirection(): LayoutDirection =
        compose.onAllNodes(hasTestTag(UiTestTags.WATCH_ROW), useUnmergedTree = true).fetchSemanticsNodes()
            .first().layoutInfo.layoutDirection

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
