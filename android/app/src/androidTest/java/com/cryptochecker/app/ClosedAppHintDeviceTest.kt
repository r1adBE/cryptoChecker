package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.features.alarms.AlarmDeliveryScreen
import com.cryptochecker.app.ui.features.alarms.ClosedAppHint
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmDelivery
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Hinweis unter dem Alarm-Formular: ein kurzer Satz und genau ein Verweis «Mehr
 * dazu» zur Seite «Damit Alarme ankommen» — kein langer Text, kein zweiter Knopf. Dort stehen der ganze Satz und
 * «Kurs-Aktualisierung ändern». Echte Composables auf dem Gerät. iOS: `CryptoCheckerUITests.testClosedAppHintLeadsToDelivery`.
 */
@HiltAndroidTest
class ClosedAppHintDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Test
    fun hintIsOneSentenceAndOneLink() {
        var opened = false
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalOpenAlarmDelivery provides { opened = true }) { ClosedAppHint() }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasText(text(R.string.alarm_closed_app_short))).assertCountEquals(1)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(hasText(text(R.string.alarm_change_updates))).assertCountEquals(0)
        compose.onAllNodes(hasText(text(R.string.alarm_timing_hint_android, text(R.string.settings_exact_checks)))).assertCountEquals(0)
        compose.onAllNodes(hasText(text(R.string.action_learn_more)) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertTrue("«Mehr dazu» öffnet die Seite «Damit Alarme ankommen»", opened)
    }

    @Test
    fun deliveryPageKeepsTheFullSentenceAndThePriceUpdateLink() {
        var openedUpdates = false
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    AlarmDeliveryScreen(onBack = {}, onOpenAlarmSettings = {}, onOpenUpdateSettings = { openedUpdates = true })
                }
            }
        }
        compose.waitForIdle()
        val full = hasText(text(R.string.alarm_timing_hint_android, text(R.string.settings_exact_checks)), substring = true)
        val link = hasText(text(R.string.alarm_change_updates), substring = true) and hasClickAction()
        for (matcher in listOf(full, link)) {
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher) }
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onAllNodes(link).onFirst().performClick()
        compose.waitForIdle()
        assertTrue("«Kurs-Aktualisierung ändern» führt zu den Einstellungen", openedUpdates)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
