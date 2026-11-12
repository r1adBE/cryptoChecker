package com.cryptochecker.app.parity

import com.cryptochecker.app.backup.BackupManager.Step
import com.cryptochecker.app.backup.RestoreRollback
import com.cryptochecker.app.backup.RestoreRun
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reihenfolge der Schreibschritte beim Wiederherstellen samt Journal und Zurückrollen (`testdata/parity/restore_steps.json`):
 * [RestoreRun.execute] und [RestoreRollback.run] mit Fälschungen. Gegenstück: `Tests/RestoreStepsParityTests.swift`.
 */
class RestoreStepsParityTest {

    private val fixture = ParityJson.load("restore_steps.json")
    private val platform = fixture["android"].obj()
    private val steps = platform["steps"].list().map { Step.valueOf(it as String) }
    private val rollbackOrder = platform["rollbackOrder"].list().map { it as String }

    /** Reihenfolge der Schritte in `BackupManager.writeRestored` = die der Datei; alle Schritte der Aufzählung kommen vor. */
    @Test
    fun schritteWieImCode() {
        assertEquals(Step.entries.toSet(), steps.toSet())
        val source = sequenceOf("", "app/", "android/app/")
            .map { java.io.File(it + "src/main/java/com/cryptochecker/app/backup/BackupManager.kt") }
            .first { it.isFile }.readText()
        val positions = steps.map { source.indexOf("RestoreRun.Write(Step.${it.name}") }
        assertTrue(positions.toString(), positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun ablaufWieGemeinsameFaelle() = runTest {
        val cases = fixture["cases"].list().map { it.obj() }.filter { (it["platform"] ?: "android") == "android" }
        assertTrue(cases.size >= 9)
        for (c in cases) {
            val name = c["name"] as String
            val failAt = c["failAt"].num()?.toInt()
            val cancelled = c["cancelledAtCommit"] == true
            val rollbackComplete = c["rollbackComplete"] as Boolean
            val log = mutableListOf<String>()
            val state = steps.associateWith { "old" }.toMutableMap()

            val journal = object : RestoreRun.Journal {
                override fun write(): Boolean = true.also { log += "journal written" }
                override fun delete() {
                    log += "journal deleted"
                }
            }
            val writes = steps.mapIndexed { i, step ->
                RestoreRun.Write(step) {
                    log += "write ${step.name}"
                    if (failAt == i && !cancelled) throw IOException("${step.name} scheitert")
                    state[step] = "new"
                    // Abbruch genau beim Commit: geschrieben, der Aufruf endet trotzdem mit einer CancellationException
                    if (failAt == i) throw CancellationException("beim Commit abgebrochen")
                }
            }
            fun undo(step: Step) {
                log += "undo ${step.name}"
                // Unvollständig: der letzte Teil (die Merkliste) lässt sich nicht zurückschreiben
                if (!rollbackComplete && step.name == rollbackOrder.last()) throw IOException("${step.name} nicht zurück")
                state[step] = "old"
            }
            val parts = object : RestoreRollback.Parts {
                override fun mayWritePortfolio() = true
                override fun mayWriteWatchlist() = true
                override suspend fun settings() = undo(Step.SETTINGS)
                override suspend fun favorites() = undo(Step.FAVORITES)
                override suspend fun portfolio() = undo(Step.PORTFOLIO)
                override suspend fun watchlist() = undo(Step.WATCHLIST)
            }
            val error = runCatching {
                RestoreRun.execute(journal, writes) { touched, cause -> RestoreRollback.run(touched, cause, parts) }
            }.exceptionOrNull()

            // Erwartetes Protokoll aus den Regeln der Datei
            val started = if (failAt == null) steps else steps.take(failAt + 1)
            val rolledBack = when {
                failAt == null -> emptyList()
                platform["rollsBackFailedStep"] == true -> steps.take(failAt + 1)
                else -> steps.take(failAt)
            }
            val expected = listOf("journal written") + started.map { "write ${it.name}" } +
                rollbackOrder.filter { r -> rolledBack.any { it.name == r } }.map { "undo $it" } +
                (if (c["journalDeleted"] == true) listOf("journal deleted") else emptyList())
            assertEquals(name, expected, log)
            assertEquals(name, failAt != null, error != null)
            if (cancelled) assertTrue(name, error is CancellationException)
            if (rollbackComplete) {
                assertEquals(name, steps.associateWith { c["state"] as String }, state)
            }
        }
    }
}
