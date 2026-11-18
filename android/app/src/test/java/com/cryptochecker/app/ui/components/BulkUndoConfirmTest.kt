package com.cryptochecker.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.saveable.SaverScope
import com.cryptochecker.app.domain.BulkAddRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Rückgängig» nach «Mehrere Paare auf einmal» löscht erst nach der Rückfrage ([BulkUndoConfirm]: nur «Entfernen» ruft
 * den Undo-Weg, und nur solange «Rückgängig» noch gilt) und Meldungen mit Aktion tragen das Schliessen-✕
 * ([AppSnackbarVisuals.withDismissAction]). Wie iOS `BulkUndoConfirmTests`. Der Dialog auf dem Gerät:
 * `BulkUndoConfirmDialogTest`; nur über ihn entfernen: Regel `bulk-undo`.
 */
class BulkUndoConfirmTest {

    private val end = 1_000_000L
    private val inWindow = end + 5_000L

    private fun BulkUndoConfirm.removeNow(now: Long = inWindow, expired: () -> Unit = {}, undo: (List<Long>) -> Unit) =
        remove(now, expired, undo)

    @Test
    fun `undo button only opens the dialog, undo only after removing`() {
        val undone = mutableListOf<List<Long>>()
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(1L, 2L, 3L), end)
        assertEquals(listOf(1L, 2L, 3L), confirm.pending)
        assertTrue("noch nichts entfernt", undone.isEmpty())

        confirm.removeNow { undone += it }
        assertEquals(listOf(listOf(1L, 2L, 3L)), undone)
        assertNull(confirm.pending)
        // Doppelt getippt: nur einmal entfernt
        confirm.removeNow { undone += it }
        assertEquals(1, undone.size)
    }

    @Test
    fun `keep removes nothing`() {
        val undone = mutableListOf<List<Long>>()
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(7L), end)
        confirm.keep()
        assertNull(confirm.pending)
        confirm.removeNow { undone += it }
        assertTrue(undone.isEmpty())
    }

    @Test
    fun `without new pairs no dialog`() {
        val confirm = BulkUndoConfirm()
        confirm.request(emptyList(), end)
        assertNull(confirm.pending)
        assertNull(BulkUndoConfirm(emptyList()).pending)
    }

    @Test
    fun `removing after the time window deletes nothing`() {
        val undone = mutableListOf<List<Long>>()
        var expired = 0
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(1L, 2L), end)
        confirm.removeNow(now = end + BulkAddRunner.UNDO_WINDOW_MS + 1, expired = { expired++ }) { undone += it }
        assertTrue("abgelaufen: nichts gelöscht", undone.isEmpty())
        assertEquals(1, expired)
        assertNull("Dialog zu", confirm.pending)
        // Genau an der Grenze gilt es noch (wie BulkAddRunner.undoOffered)
        confirm.request(listOf(3L), end)
        confirm.removeNow(now = end + BulkAddRunner.UNDO_WINDOW_MS, expired = { expired++ }) { undone += it }
        assertEquals(listOf(listOf(3L)), undone)
        assertEquals(1, expired)
    }

    @Test
    fun `open dialog survives saving including end of recording`() {
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(4L, 5L), end)
        val saved = save(confirm)
        assertEquals(listOf(end, 4L, 5L), saved?.toList())
        val restored = BulkUndoConfirm.Saver.restore(saved!!)
        assertEquals(listOf(4L, 5L), restored?.pending)
        assertEquals(end, restored?.finishedAt)
        // Nach dem Wiederherstellen prüft «Entfernen» weiter das ursprüngliche Ende
        var expired = false
        restored?.removeNow(now = end + BulkAddRunner.UNDO_WINDOW_MS + 1, expired = { expired = true }) { }
        assertTrue(expired)
        assertNull("kein Dialog: nichts zu sichern", save(BulkUndoConfirm()))
    }

    private fun save(confirm: BulkUndoConfirm): LongArray? =
        with(BulkUndoConfirm.Saver) { SaverScope { true }.save(confirm) }

    @Test
    fun `message with action shows close, without action not`() {
        assertTrue(AppSnackbarVisuals("746 hinzugefügt", actionLabel = "Rückgängig").withDismissAction)
        assertFalse(AppSnackbarVisuals("Gesichert").withDismissAction)
        // Dauerhinweis, dessen Aktion selbst «Schliessen» ist: kein zweites ✕
        val resting = AppSnackbarVisuals("x", "Schliessen", SnackbarDuration.Indefinite, withDismissAction = false)
        assertFalse(resting.withDismissAction)
    }
}
