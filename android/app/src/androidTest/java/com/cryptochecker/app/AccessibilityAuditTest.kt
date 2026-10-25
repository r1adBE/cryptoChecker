package com.cryptochecker.app

import android.content.Intent
import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.ComposeA11yAudit
import com.cryptochecker.app.testing.DeviceOverrides
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.marketdata.model.MarketPair
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
 * Automatische Barrierefreiheits-Prüfung ([ComposeA11yAudit]) der vier Hauptbereiche Merkliste, Portfolio, Alarme und
 * Einstellungen — wie `AccessibilityAuditTests` in iOS (`performAccessibilityAudit`). Je ein Lauf in Standardgrösse,
 * mit grosser Schrift (font_scale 2.0) und auf Arabisch (Rechts nach links): Die Bildschirme laden und es gibt keine
 * Befunde. Die Activity startet erst nach dem Umstellen ([DeviceOverrides]), darum die leere Compose-Regel.
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
    val compose = createEmptyComposeRule()

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var portfolioRepository: PortfolioRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
            // Ein Paar mit Alarm: Merkliste mit Zeile, Alarm-Übersicht mit Karte
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
    fun mainScreensPassAudit() = auditMainScreens("standard")

    @Test
    fun mainScreensPassAuditWithLargeText() = DeviceOverrides.with(fontScale = LARGE_FONT_SCALE) {
        auditMainScreens("large text")
    }

    @Test
    fun mainScreensPassAuditRightToLeft() = DeviceOverrides.with(languageTag = "ar") {
        auditMainScreens("ar") { activity ->
            assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        }
    }

    /** Merkliste, Portfolio und Einstellungen über die Tableiste, die Alarm-Übersicht über ihre Verknüpfung. */
    private fun auditMainScreens(run: String, checkActivity: (MainActivity) -> Unit = {}) {
        val findings = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { checkActivity(it) }
            waitForTag(UiTestTags.WATCH_ROW)
            findings += ComposeA11yAudit.findings(compose, "$run/watchlist", SUPPRESSIONS)
            openTab(UiTestTags.TAB_PORTFOLIO)
            findings += ComposeA11yAudit.findings(compose, "$run/portfolio", SUPPRESSIONS)
            openTab(UiTestTags.TAB_SETTINGS)
            findings += ComposeA11yAudit.findings(compose, "$run/settings", SUPPRESSIONS)
        }
        val alarms = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "alarms")
        ActivityScenario.launch<MainActivity>(alarms).use {
            waitForTag(UiTestTags.ALARM_CARD)
            findings += ComposeA11yAudit.findings(compose, "$run/alarms", SUPPRESSIONS)
        }
        assertTrue("Barrierefreiheits-Befunde:\n" + findings.joinToString("\n"), findings.isEmpty())
    }

    private fun openTab(tag: String) {
        compose.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(tag) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .fetchSemanticsNodes().isNotEmpty()
        }
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

        /** Grösste Systemschrift (Android 14+: 200 %). */
        const val LARGE_FONT_SCALE = 2.0f

        /**
         * Bekannte, unvermeidbare Befunde mit Begründung. Leer: Neue Befunde zuerst in der App beheben; nur was sich
         * nicht beheben lässt (z. B. Bibliotheks-Bausteine), mit Begründung hier eintragen.
         */
        val SUPPRESSIONS = emptyList<ComposeA11yAudit.Suppression>()
    }
}
