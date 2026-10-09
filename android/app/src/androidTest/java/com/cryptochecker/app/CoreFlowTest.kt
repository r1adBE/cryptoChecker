package com.cryptochecker.app

import android.app.NotificationManager
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.FakeMarket
import com.cryptochecker.app.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Kernablauf auf Gerät/Emulator (Hilt + Compose UI Test, Netz durch feste Kurse ersetzt):
 * Start-Auswahl → BTC hinzufügen → Kursalarm «über» → neuer Kurs → Alarm ausgelöst und
 * Mitteilung → Wischen löscht → «Rückgängig» holt die Zeile zurück; Portfolio-Sperre an →
 * Portfolio-Tab zeigt den Sperr-Zustand.
 *
 * Ausführen: `./gradlew :app:connectedDebugAndroidTest` (Emulator oder Gerät nötig; nicht in der CI).
 * Erwartet eine Oberfläche von links nach rechts gelesen oder RTL (Wischrichtung wird gespiegelt).
 */
@HiltAndroidTest
class CoreFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var watchRepository: WatchRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var priceRefresher: PriceRefresher

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true) // keine Begrüssung
            settingsRepository.setAppLock(false)
            settingsRepository.setPortfolioEnabled(true)
            watchRepository.deleteAllWatches() // leere Merkliste → Start-Auswahl
        }
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun waitForNode(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun watches(): List<WatchEntity> = runBlocking { watchRepository.getWatches() }

    private fun waitForWatch(base: String): WatchEntity {
        var found: WatchEntity? = null
        compose.waitUntil(TIMEOUT) {
            found = watches().firstOrNull { it.baseAsset == base }
            found != null
        }
        return checkNotNull(found)
    }

    /** Zeile der Merkliste: Beschreibung mit dem Paar, antippbar, mit Screenreader-Aktionen (Löschen …). */
    private fun row(displayName: String): SemanticsMatcher =
        hasContentDescription(displayName, substring = true) and hasClickAction() and
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    @Test
    fun starterAlarmSwipeDeleteUndo() {
        // 1. Start-Auswahl: nichts gewählt, dann nur Bitcoin, hinzufügen
        waitForNode(hasText(string(R.string.starter_title)))
        compose.onNode(hasText(string(R.string.starter_select_none)) and hasClickAction()).performClick()
        compose.onNode(hasContentDescription("Bitcoin", substring = true) and isToggleable())
            .performScrollTo()
            .performClick()
        compose.onNode(hasText(string(R.string.starter_add_selected, 1)) and hasClickAction())
            .performScrollTo()
            .performClick()

        val watch = waitForWatch("BTC")
        assertEquals(1, watches().size)
        waitForNode(row(watch.displayName))

        // 2. Kursalarm «über 100’000», dann steigt der Kurs der Fake-Börse auf 105’000
        val alarmId = runBlocking {
            watchRepository.saveAlarm(
                AlarmEntity(
                    watchId = watch.id,
                    condition = AlarmCondition.PRICE_ABOVE,
                    threshold = 100_000.0,
                    repeating = true,
                )
            )
        }
        FakeMarket.btcPrice = 105_000.0
        // Ein gleichzeitiger voller Durchlauf (Live-Aktualisierung) kann den Alarm ebenso auslösen;
        // geprüft wird deshalb der gespeicherte Zustand, nicht die Zählung dieses Aufrufs.
        runBlocking { priceRefresher.refreshOne(watch.id) }
        val alarm = runBlocking { watchRepository.observeAlarms(watch.id).first() }.single { it.id == alarmId }
        assertTrue("alarm marked as triggered", alarm.lastTriggeredAt > 0)
        // Kursmarke: nach dem Melden nicht mehr scharf (bis zur Rückkehr unter die Marke)
        assertTrue("level alarm disarmed", alarm.referenceAt > 0)
        assertEquals(105_000.0, alarm.lastTriggeredPrice ?: 0.0, 0.0)
        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        compose.waitUntil(TIMEOUT) {
            notifications.activeNotifications.any { it.notification.channelId?.startsWith("alarms") == true }
        }

        // 3. Wischen löscht (zum Zeilenende hin), «Rückgängig» holt das Paar zurück
        val rtl = compose.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        compose.onAllNodes(row(watch.displayName)).onFirst().performTouchInput {
            if (rtl) swipeRight() else swipeLeft()
        }
        compose.waitUntil(TIMEOUT) { watches().none { it.baseAsset == "BTC" } }
        val undo = hasText(string(R.string.action_undo)) and hasClickAction()
        waitForNode(undo)
        compose.onNode(undo).performClick()
        val restored = waitForWatch("BTC")
        assertEquals(watch.marketKey, restored.marketKey)
        waitForNode(row(restored.displayName))
    }

    @Test
    fun portfolioLockShowsTheLockedState() {
        val activity = compose.activity
        // Ohne Displaysperre hebt die App die Sperre sofort auf (sonst wäre sie nie zu öffnen):
        // für den Test eine PIN setzen und danach wieder entfernen.
        val hadLock = AppLockAuth.canAuthenticate(activity)
        if (!hadLock) AppTestEnvironmentRule.shell("locksettings set-pin $PIN")
        try {
            assumeTrue("device lock could not be set (locksettings)", AppLockAuth.canAuthenticate(activity))
            runBlocking { settingsRepository.setAppLock(true) }

            val tab = hasText(string(R.string.portfolio_title)) and hasClickAction()
            waitForNode(tab)
            compose.onAllNodes(tab).onFirst().performClick()

            waitForNode(hasText(string(R.string.portfolio_locked_title)))
            compose.onNode(hasText(string(R.string.app_lock_unlock)) and hasClickAction()).assertExists()
        } finally {
            // Abfrage des Systems schliessen, PIN entfernen
            AppTestEnvironmentRule.shell("input keyevent KEYCODE_BACK")
            if (!hadLock) AppTestEnvironmentRule.shell("locksettings clear --old $PIN")
            runBlocking { settingsRepository.setAppLock(false) }
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val PIN = "4711"
    }
}
