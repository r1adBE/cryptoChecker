package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.cryptochecker.app.domain.BulkAddRunner
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.BulkUndoConfirm
import com.cryptochecker.app.ui.components.BulkUndoConfirmDialog
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Rückfrage nach «Rückgängig» bei «Mehrere Paare auf einmal»: «Behalten» entfernt
 * nichts, «Entfernen» entfernt genau einmal, nach Ablauf des Zeitfensters nichts (Hinweis «abgelaufen»). Echter Dialog;
 * dass Coin-Suche und Merkliste nur über ihn entfernen, prüft die Regel `bulk-undo` (source_rules.py).
 */
@HiltAndroidTest
class BulkUndoConfirmDialogTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    private val removed = mutableListOf<List<Long>>()
    private var expired = 0

    private fun show(confirm: BulkUndoConfirm) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { BulkUndoConfirmDialog(confirm, onExpired = { expired++ }, onRemove = { removed += it }) }
            }
        }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun keepRemovesNothingAndRemoveRemovesOnce() {
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(1L, 2L, 3L), System.currentTimeMillis())
        show(confirm)
        tap(UiTestTags.BULK_UNDO_KEEP)
        assertTrue("Behalten: nichts entfernt", removed.isEmpty())
        compose.runOnUiThread { confirm.request(listOf(1L, 2L, 3L), System.currentTimeMillis()) }
        tap(UiTestTags.BULK_UNDO_REMOVE)
        assertEquals(listOf(listOf(1L, 2L, 3L)), removed)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasTestTag(UiTestTags.BULK_UNDO_REMOVE)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun afterTheUndoWindowNothingIsRemoved() {
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(9L), System.currentTimeMillis() - BulkAddRunner.UNDO_WINDOW_MS - 1_000)
        show(confirm)
        tap(UiTestTags.BULK_UNDO_REMOVE)
        assertTrue(removed.isEmpty())
        assertEquals(1, expired)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
