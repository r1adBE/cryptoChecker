package com.cryptochecker.app

import android.content.Intent
import android.view.View
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.cryptochecker.app.testing.DeviceOverride
import com.cryptochecker.app.testing.DeviceOverridesRule
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
import org.junit.rules.RuleChain
import javax.inject.Inject

/**
 * Automatische Barrierefreiheits-Prüfung ([ComposeA11yAudit]) der Hauptbereiche Merkliste, Alarm-Übersicht,
 * Portfolio und Einstellungen — wie `AccessibilityAuditTests` in iOS (`performAccessibilityAudit`). Je ein Lauf in
 * Standardgrösse, mit grosser Schrift (`font_scale` 2.0) und auf Arabisch (rechts nach links). Die Gerätekonfiguration
 * stellt [DeviceOverridesRule] vor dem Start der Activity um (Reihenfolge der Regeln: Hilt → Umgebung →
 * Gerätekonfiguration → Compose) und danach zurück.
 *
 * Ausführen: `./gradlew :app:connectedDebugAndroidTest` (Emulator oder Gerät).
 */
@HiltAndroidTest
class AccessibilityAuditTest {

    private val hiltRule = HiltAndroidRule(this)

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule)
        .around(AppTestEnvironmentRule())
        .around(DeviceOverridesRule())
        .around(compose)

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
    fun mainScreensPassAudit() = auditMainScreens()

    @Test
    @DeviceOverride(fontScale = LARGE_FONT_SCALE)
    fun mainScreensPassAuditWithLargeFont() {
        assertEquals(
            "Systemschrift nicht übernommen",
            LARGE_FONT_SCALE,
            compose.activity.resources.configuration.fontScale,
            FONT_SCALE_TOLERANCE,
        )
        auditMainScreens()
    }

    @Test
    @DeviceOverride(language = "ar")
    fun mainScreensPassAuditInArabic() {
        assertEquals(
            "Arabisch nicht rechts nach links",
            View.LAYOUT_DIRECTION_RTL,
            compose.activity.resources.configuration.layoutDirection,
        )
        auditMainScreens()
    }

    /** Merkliste, Alarm-Übersicht, Portfolio, Einstellungen; alle Befunde gesammelt in einer Meldung. */
    private fun auditMainScreens() {
        val findings = mutableListOf<String>()
        waitForTag(UiTestTags.WATCH_ROW)
        findings += ComposeA11yAudit.findings(compose, "watchlist", SUPPRESSIONS)
        openAlarmsOverview()
        findings += ComposeA11yAudit.findings(compose, "alarms", SUPPRESSIONS)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag(UiTestTags.WATCH_ROW)
        clickTag(UiTestTags.TAB_PORTFOLIO)
        findings += ComposeA11yAudit.findings(compose, "portfolio", SUPPRESSIONS)
        clickTag(UiTestTags.TAB_SETTINGS)
        findings += ComposeA11yAudit.findings(compose, "settings", SUPPRESSIONS)
        assertTrue("Barrierefreiheits-Befunde:\n" + findings.joinToString("\n"), findings.isEmpty())
    }

    /**
     * Alarm-Übersicht wie die App-Verknüpfung «Alle Alarme»: Ziel `alarms` ([MainActivity.EXTRA_OPEN], ohne
     * Geheimnis erlaubt). Aus der laufenden Activity gestartet (singleTop, gleicher Task) kommt es als
     * `onNewIntent` an.
     */
    private fun openAlarmsOverview() {
        val activity = compose.activity
        val intent = Intent(activity, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, "alarms")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        compose.runOnUiThread { activity.startActivity(intent) }
        val title = activity.getString(R.string.alarms_overview_title)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
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
        const val LARGE_FONT_SCALE = 2.0f
        const val FONT_SCALE_TOLERANCE = 0.01f

        /**
         * Bekannte, unvermeidbare Befunde mit Begründung. Leer: Neue Befunde zuerst in der App beheben; nur was sich
         * nicht beheben lässt (z. B. Bibliotheks-Bausteine), mit Begründung hier eintragen.
         */
        val SUPPRESSIONS = emptyList<ComposeA11yAudit.Suppression>()
    }
}
