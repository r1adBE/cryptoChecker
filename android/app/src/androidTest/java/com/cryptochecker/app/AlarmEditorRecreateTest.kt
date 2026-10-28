package com.cryptochecker.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Review ARC5-R01: Merkliste wird nicht gespeichert, Alarm-Editor «Speichern» → Rückfrage F-08 offen →
 * Activity neu erstellt (Drehen, Dunkelmodus, Sprache) → «Trotzdem»: Der Editor schliesst sich im NEUEN Bildschirm, es gibt
 * genau einen Alarm und keinen Absturz. Vorher schloss der Rückruf nur den Editor der alten Komposition (der neue blieb mit
 * den Eingaben offen; ein zweites «Speichern» legte einen zweiten Alarm an). Erlaubnis erteilt [AppTestEnvironmentRule]
 * (kein System-Dialog); den Launcher sichert `rememberNotificationPermissionRequest` zusätzlich ab.
 */
@HiltAndroidTest
class AlarmEditorRecreateTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    private var watchId = 0L

    @Before
    fun setUp() {
        hiltRule.inject()
        WatchlistNotSavedGate.resetForTest()
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            watchRepository.deleteAllWatches()
            watchId = checkNotNull(
                watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT"))
            )
        }
    }

    @After
    fun tearDown() {
        WatchlistNotSavedGate.resetForTest()
        DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        runBlocking { watchRepository.deleteAllWatches() }
    }

    @Test
    fun confirmAfterRecreateClosesEditorAndSavesOnce() {
        saveAcrossRecreate()
        // Bleibt so: kein zweiter Alarm, Editor nicht wieder offen; gespeichert ist die Eingabe von vor dem Drehen
        assertEquals(1, alarms())
        assertFalse(exists(UiTestTags.ALARM_THRESHOLD))
        assertEquals(THRESHOLD.toDouble(), storedThreshold(), 0.0)
    }

    /**
     * Review TST6-R1/ARC6 C2: nicht der allererste Alarm — dann kommt nach «Trotzdem» die Zeile «Alarm aktiv · Testen»
     * über das Ereignis im NEUEN Bildschirm (beim ersten Alarm endet der Weg früh im Bestätigungsdialog).
     */
    @Test
    fun confirmAfterRecreateShowsConfirmationForAFurtherAlarm() {
        val before = runBlocking { settingsRepository.current() }
        runBlocking {
            settingsRepository.setFirstAlarmShown(true)
            settingsRepository.setAlarmTimingHintShown(true)
        }
        try {
            saveAcrossRecreate()
            val active = compose.activity.getString(R.string.alarm_saved_active)
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodes(hasText(active, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, alarms())
        } finally {
            runBlocking {
                settingsRepository.setFirstAlarmShown(before.firstAlarmShown)
                settingsRepository.setAlarmTimingHintShown(before.alarmTimingHintShown)
            }
        }
    }

    /** Editor öffnen, Schwelle eingeben, «nicht gespeichert», Speichern → Frage offen → neu erstellen → «Trotzdem». */
    private fun saveAcrossRecreate() {
        clickTag(UiTestTags.WATCH_ROW)
        clickTag(UiTestTags.WATCH_ACTION_ALARM)
        // Paar ohne Alarm: der Editor öffnet sich gleich; neuer Alarm = «über»
        waitForTag(UiTestTags.ALARM_THRESHOLD)
        compose.onAllNodes(hasTestTag(UiTestTags.ALARM_THRESHOLD), useUnmergedTree = true).onFirst()
            .performTextInput(THRESHOLD)
        compose.waitForIdle()

        // Ab jetzt «nicht gespeichert» (wie ohne Schlüssel): die nächste Änderung fragt
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME)
        compose.waitUntil(TIMEOUT) { WatchlistNotSavedGate.mayAsk() }
        clickTag(UiTestTags.ALARM_SAVE)
        compose.waitUntil(TIMEOUT) { WatchlistNotSavedGate.request.value != null }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        // Neuer Bildschirm: Editor samt Eingaben wieder da, die Frage noch offen
        compose.waitUntil(TIMEOUT) { exists(UiTestTags.ALARM_THRESHOLD) && WatchlistNotSavedGate.request.value != null }
        assertEquals(0, alarms())
        // Review TST6-R1: die Eingabe steht nach dem Drehen noch im Feld (nur Ziffern verglichen, Gruppierung egal)
        assertEquals(THRESHOLD, thresholdDigits())

        WatchlistNotSavedGate.answer(true)
        compose.waitUntil(TIMEOUT) { alarms() == 1 && !exists(UiTestTags.ALARM_THRESHOLD) }
        compose.waitForIdle()
    }

    // ── Hilfen (wie UiFlowsTest) ──

    private fun alarms(): Int = runBlocking { watchRepository.observeAlarms(watchId).first() }.size

    private fun storedThreshold(): Double = runBlocking { watchRepository.observeAlarms(watchId).first() }.single().threshold

    /** Ziffern im Schwellen-Feld (EditableText des ersten Knotens mit dem Tag). */
    private fun thresholdDigits(): String {
        val node = compose.onAllNodes(hasTestTag(UiTestTags.ALARM_THRESHOLD), useUnmergedTree = true).fetchSemanticsNodes().first()
        val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        return text.filter { it.isDigit() }
    }

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String) {
        compose.waitUntil(TIMEOUT) { exists(tag) }
    }

    private fun clickTag(tag: String) {
        val enabled: SemanticsMatcher = hasTestTag(tag) and isEnabled()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(enabled, useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val THRESHOLD = "200000"
    }
}
