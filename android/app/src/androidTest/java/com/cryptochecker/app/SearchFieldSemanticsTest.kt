package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.features.watchlist.GroupSearchField
import com.cryptochecker.app.ui.features.watchlist.WatchSearchField
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Suchfelder (BasicTextField) tragen den Platzhalter als Screenreader-Bezeichnung, der sichtbare Platzhalter selbst
 * ist für Screenreader ausgeblendet. Braucht ein Gerät (Compose-Semantik ohne Robolectric).
 */
@HiltAndroidTest
class SearchFieldSemanticsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun merklistenSucheHatBezeichnung() {
        val hint = compose.activity.getString(R.string.watchlist_search_hint)
        show { WatchSearchField(query = "", onQueryChange = {}, onClose = {}) }
        assertLabelled(hint)
    }

    @Test
    fun gruppenSucheHatBezeichnung() {
        val hint = compose.activity.getString(R.string.group_search_hint)
        show { GroupSearchField(query = "", onQueryChange = {}) }
        assertLabelled(hint)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { content() } } }
        compose.waitForIdle()
    }

    private fun assertLabelled(hint: String) {
        compose.onNode(hasSetTextAction() and hasContentDescription(hint), useUnmergedTree = true).assertExists()
        // Platzhalter nicht doppelt vorgelesen
        compose.onAllNodes(hasText(hint), useUnmergedTree = true).assertCountEquals(0)
    }
}
