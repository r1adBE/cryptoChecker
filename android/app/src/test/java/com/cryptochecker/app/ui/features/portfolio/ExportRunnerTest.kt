package com.cryptochecker.app.ui.features.portfolio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Ablauf eines Exports ([ExportRunner]): Abbrechen während des Laufs → Idle und die angefangene Datei gelöscht;
 * Fehler mit Art, «Erneut versuchen» in dasselbe bzw. ein neues Ziel. Gegenstück: `Tests/ExportRunTests.swift`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportRunnerTest {

    /** Ziel zum Testen: Dateien als Namen, geschrieben bzw. gelöscht wird nur notiert. */
    private class FakeTarget(var failWrite: Boolean = false) : ExportTarget<String> {
        val written = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()

        override suspend fun write(target: String, csv: String) {
            if (failWrite) throw ExportWriteException(IOException("gone"))
            written[target] = csv
        }

        override suspend fun delete(target: String) {
            written.remove(target)
            deleted += target
        }
    }

    @Test
    fun cancelExportGehtAufIdleUndLoeschtDatei() = runTest {
        val target = FakeTarget()
        val runner = ExportRunner(this, target)
        val prices = CompletableDeferred<String>()
        runner.start("export.csv", "Test") { prices.await() }
        advanceUntilIdle()
        assertEquals(CutoffExportState.Running, runner.state.value)

        runner.cancel()
        assertEquals(CutoffExportState.Idle, runner.state.value)
        advanceUntilIdle()
        assertEquals(listOf("export.csv"), target.deleted)
        assertTrue(target.written.isEmpty())
        // Später eintreffende Kurse schreiben nichts mehr
        prices.complete("a;b")
        advanceUntilIdle()
        assertTrue(target.written.isEmpty())
        assertEquals(CutoffExportState.Idle, runner.state.value)
    }

    @Test
    fun fertigerExportWirdNichtGeloescht() = runTest {
        val target = FakeTarget()
        val runner = ExportRunner(this, target)
        runner.start("ok.csv", "Test") { "x" }
        advanceUntilIdle()
        assertEquals(CutoffExportState.Done, runner.state.value)
        runner.cancel() // nichts mehr am Laufen: ohne Wirkung
        advanceUntilIdle()
        assertEquals(CutoffExportState.Done, runner.state.value)
        assertTrue(target.deleted.isEmpty())
        assertEquals("x", target.written["ok.csv"])
        runner.consume()
        assertEquals(CutoffExportState.Idle, runner.state.value)
    }

    @Test
    fun fehlerartUndErneutVersuchen() = runTest {
        val target = FakeTarget(failWrite = true)
        val runner = ExportRunner(this, target)
        runner.start("a.csv", "Test") { "x" }
        advanceUntilIdle()
        assertEquals(CutoffExportState.Failed(ExportFailureKind.FILE), runner.state.value)
        // Speicherort weg: neues Ziel
        target.failWrite = false
        runner.retry("b.csv")
        advanceUntilIdle()
        assertEquals(CutoffExportState.Done, runner.state.value)
        assertEquals("x", target.written["b.csv"])
    }

    @Test
    fun kurseFehlenUndErneutVersuchenInDieselbeDatei() = runTest {
        val target = FakeTarget()
        val runner = ExportRunner(this, target)
        var attempts = 0
        runner.start("c.csv", "Test") {
            attempts++
            if (attempts == 1) throw UnknownHostException("offline")
            "ok"
        }
        advanceUntilIdle()
        assertEquals(CutoffExportState.Failed(ExportFailureKind.PRICES), runner.state.value)
        runner.retry()
        advanceUntilIdle()
        assertEquals(CutoffExportState.Done, runner.state.value)
        assertEquals("ok", target.written["c.csv"])
        assertEquals(2, attempts)
    }
}
