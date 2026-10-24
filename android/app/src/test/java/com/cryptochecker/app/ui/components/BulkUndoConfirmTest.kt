package com.cryptochecker.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.saveable.SaverScope
import com.cryptochecker.app.data.BulkAddRunner
import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Rückgängig» nach «Mehrere Paare auf einmal» löscht erst nach der Rückfrage ([BulkUndoConfirm]: nur «Entfernen» ruft
 * den Undo-Weg, und nur solange «Rückgängig» noch gilt) und Meldungen mit Aktion tragen das Schliessen-✕
 * ([AppSnackbarVisuals.withDismissAction]). Wie iOS `BulkUndoConfirmTests`.
 */
class BulkUndoConfirmTest {

    private val end = 1_000_000L
    private val inWindow = end + 5_000L

    private fun BulkUndoConfirm.removeNow(now: Long = inWindow, expired: () -> Unit = {}, undo: (List<Long>) -> Unit) =
        remove(now, expired, undo)

    @Test
    fun `Rueckgaengig oeffnet nur den Dialog, Undo erst nach Entfernen`() {
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
    fun `Behalten entfernt nichts`() {
        val undone = mutableListOf<List<Long>>()
        val confirm = BulkUndoConfirm()
        confirm.request(listOf(7L), end)
        confirm.keep()
        assertNull(confirm.pending)
        confirm.removeNow { undone += it }
        assertTrue(undone.isEmpty())
    }

    @Test
    fun `ohne neue Paare kein Dialog`() {
        val confirm = BulkUndoConfirm()
        confirm.request(emptyList(), end)
        assertNull(confirm.pending)
        assertNull(BulkUndoConfirm(emptyList()).pending)
    }

    @Test
    fun `Entfernen nach Ablauf des Zeitfensters loescht nichts`() {
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
    fun `offener Dialog uebersteht das Speichern samt Ende der Aufnahme`() {
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
    fun `Meldung mit Aktion zeigt Schliessen, ohne Aktion nicht`() {
        assertTrue(AppSnackbarVisuals("746 hinzugefügt", actionLabel = "Rückgängig").withDismissAction)
        assertFalse(AppSnackbarVisuals("Gesichert").withDismissAction)
        // Dauerhinweis, dessen Aktion selbst «Schliessen» ist: kein zweites ✕
        val resting = AppSnackbarVisuals("x", "Schliessen", SnackbarDuration.Indefinite, withDismissAction = false)
        assertFalse(resting.withDismissAction)
    }

    /** Beide Stellen, die das Ergebnis melden, rufen den Undo-Weg nur über den Dialog. */
    @Test
    fun `Explorer und Merkliste fragen vor dem Entfernen`() {
        val feedback = source("ui/features/explorer/ExplorerFeedback.kt")
        assertTrue(feedback.contains("bulkUndo.request(state.addedIds, state.finishedAt)"))
        assertTrue(feedback.contains("BulkUndoConfirmDialog(bulkUndo, onExpired = onUndoExpired, onRemove = onUndoBulk)"))
        assertTrue(feedback.contains("R.string.bulk_undo_expired"))
        assertFalse("kein direktes Undo aus der Meldung", feedback.contains("onUndoBulk(state.addedIds)"))

        val watchlist = source("ui/features/watchlist/WatchlistBulkAdd.kt")
        assertTrue(watchlist.contains("undoConfirm.request(ids, current.finishedAt)"))
        assertTrue(watchlist.contains("R.string.bulk_undo_expired"))
        val undoCalls = Regex("""runner\.undo\(""").findAll(watchlist).count()
        assertEquals("runner.undo nur in onRemove des Dialogs", 1, undoCalls)
        assertTrue(watchlist.contains("onRemove = { viewModel.runner.undo(it) }"))

        val ios = iosSource("App/Features/Explorer/ExplorerScreen.swift")
        // iOS: Dialog über bulkUndo.request, gelöscht nur im Entfernen-Weg (Prüfung des Zeitfensters dort)
        assertTrue(ios.contains("bulkUndo.request("))
        assertTrue(ios.contains("vm.undoBulkAdd("))

        // Erfolgs-Haptik erst bei «Entfernen»: im Dialog, nicht beim Tippen auf «Rückgängig» in der Merkliste
        assertTrue(source("ui/components/BulkUndoConfirm.kt").contains("haptics.play(HapticEvent.SUCCESS)"))
        assertTrue(watchlist.contains("undoHaptic = false"))
        assertTrue(ios.substringAfter("bulkUndoShown").substringBefore("undoBulkAdd").contains("Haptics.play(.success)"))
    }

    private fun source(rel: String): String {
        val file = findUp(listOf("", "app/", "android/app/").map { "${it}src/main/java/com/cryptochecker/app/$rel" })
        return SourceGuard.android(file, rel).readText(Charsets.UTF_8)
    }

    private fun iosSource(rel: String): String {
        val file = findUp(listOf("ios/$rel", "../ios/$rel", "../../ios/$rel"))
        return SourceGuard.ios(file, "ios/$rel").readText(Charsets.UTF_8)
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
