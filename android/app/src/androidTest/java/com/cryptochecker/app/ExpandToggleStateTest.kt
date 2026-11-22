package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Auf-/Zuklapp-Knopf (`ExpandToggleButton`): Der Screenreader hört den Zustand («aufgeklappt»/«zugeklappt»), auch
 * wenn der Text gleich bleibt (z. B. «Gebühren» beim Erfassen einer Transaktion).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExpandToggleStateTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun toggleAnnouncesItsStateWithAnUnchangedText() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    var expanded by remember { mutableStateOf(false) }
                    ExpandToggleButton(text = "Gebühren", expanded = expanded, onClick = { expanded = !expanded })
                }
            }
        }
        val button = compose.onNode(hasText("Gebühren"), useUnmergedTree = false)
        button.assert(state(compose.activity.getString(R.string.a11y_collapsed)))
        button.performClick()
        compose.waitForIdle()
        button.assert(state(compose.activity.getString(R.string.a11y_expanded)))
    }
}
