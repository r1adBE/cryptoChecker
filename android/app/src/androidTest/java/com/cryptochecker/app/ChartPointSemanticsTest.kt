package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.components.ChartPointStep
import com.cryptochecker.app.ui.components.chartPointSemantics
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Diagramm für Screenreader ([chartPointSemantics]): der Chart-Satz bleibt die Beschreibung, die Aktionen «Nächster
 * Punkt» / «Vorheriger Punkt» sagen Datum und Kurs des gewählten Punkts als Zustand an. Gegenstück in iOS:
 * `ChartAudioGraphTests` (Audio Graph). Braucht ein Gerät (Compose-Semantik ohne Robolectric).
 */
@HiltAndroidTest
class ChartPointSemanticsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val points = listOf("Mon 10:00" to "100 USDT", "Mon 11:00" to "120 USDT", "Mon 12:00" to "90 USDT")

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun customActionsStepThroughPointsWithDateAndPrice() {
        val next = compose.activity.getString(R.string.a11y_chart_next_point)
        val previous = compose.activity.getString(R.string.a11y_chart_previous_point)
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    var index by remember { mutableStateOf<Int?>(null) }
                    val text = index?.let { points[it] }?.let { (time, price) ->
                        compose.activity.getString(R.string.label_value, time, price)
                    }
                    val step: (Boolean) -> Unit = { forward ->
                        index = if (forward) ChartPointStep.next(index, points.size)
                        else ChartPointStep.previous(index, points.size)
                    }
                    Box(Modifier.size(200.dp).chartPointSemantics(SUMMARY, points.size, text, next, previous, step))
                }
            }
        }
        compose.waitForIdle()

        assertNull("ohne Auswahl kein Zustand", state())
        assertEquals(listOf(next, previous), actionLabels())

        perform(next)
        assertEquals(compose.activity.getString(R.string.label_value, "Mon 10:00", "100 USDT"), state())
        perform(next)
        perform(next)
        perform(next) // am Ende bleibt der letzte Punkt
        assertEquals(compose.activity.getString(R.string.label_value, "Mon 12:00", "90 USDT"), state())
        perform(previous)
        assertEquals(compose.activity.getString(R.string.label_value, "Mon 11:00", "120 USDT"), state())
        // Die Zusammenfassung bleibt die Beschreibung
        compose.onNodeWithContentDescription(SUMMARY).assertExists()
    }

    private fun node() = compose.onNodeWithContentDescription(SUMMARY).fetchSemanticsNode()

    private fun state(): String? = node().config.getOrNull(SemanticsProperties.StateDescription)

    private fun actionLabels(): List<String> = node().config[SemanticsActions.CustomActions].map { it.label }

    private fun perform(label: String) {
        val action = node().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    private companion object {
        const val SUMMARY = "Chart 24 hours: from 100 USDT to 90 USDT"
    }
}
