package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.CsvImportWritten
import com.cryptochecker.app.data.portfolio.PortfolioStorageLockedException
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportInsert
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportProgress
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportStep
import com.cryptochecker.app.domain.portfolio.csvimport.CsvProgressThrottle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speichern des CSV-Imports im Hintergrund ([saveCsvImport] mit [CsvImportInsert]): «Abbrechen» mitten im Anlegen lässt
 * die Daten unverändert, ohne Abbruch endet der Fortschritt bei der Gesamtzahl und das Ergebnis ist das bisherige.
 * Gegenstück: `CsvImportProgressTests.swift`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvImportSaveTest {

    /**
     * Portfolio mit Transaktions-Verhalten wie `insertImportedLinked`: angelegte Zeilen und Verknüpfungen zählen erst,
     * wenn [write] ohne Ausnahme endet.
     */
    private class FakePortfolio(existing: List<String> = emptyList()) {
        val rows = ArrayList(existing)
        var linked = 0

        /** 1 ms je Zeile, vor jeder Zeile [cancelled] geprüft; danach [links] gespeicherte Seiten verknüpft. */
        suspend fun write(keys: List<String>, links: Int, onRow: (Int) -> Unit, cancelled: () -> Boolean): CsvImportWritten {
            val staged = ArrayList<String>()
            val count = CsvImportInsert.run(keys, rows.toHashSet(), { listOf(it) }, cancelled, onRow) {
                delay(1)
                staged += it
                true
            }
            rows += staged
            linked += links
            return CsvImportWritten(count, links)
        }
    }

    private val keys = (1..10_000).map { "RELAI|op-$it" }

    @Test
    fun cancelInTheMiddleSavesNothing() = runTest {
        val portfolio = FakePortfolio(existing = listOf("manual"))
        val cancel = AtomicBoolean(false)
        val progress = ArrayList<CsvImportProgress>()
        var outcome: CsvImportSaveOutcome? = null
        // Im Vordergrund gestartet (advanceUntilIdle wartet darauf), am Ende fertig
        val job = launch {
            val throttle = CsvProgressThrottle(CsvImportStep.SAVE, keys.size, clock = { testScheduler.currentTime }) {
                progress += it
            }
            outcome = saveCsvImport { portfolio.write(keys, links = 1, throttle::row, cancel::get) }
        }
        advanceTimeBy(2_500)
        assertTrue("Fortschritt läuft: $progress", progress.isNotEmpty() && progress.last().done in 1 until keys.size)
        cancel.set(true)
        advanceUntilIdle()
        assertEquals(CsvImportSaveOutcome.Cancelled, outcome)
        assertEquals("Daten unverändert", listOf("manual"), portfolio.rows)
        assertEquals("nach dem Abbruch nichts verknüpft", 0, portfolio.linked)
        assertFalse(job.isActive)
        job.cancel()
    }

    @Test
    fun withoutCancelProgressEndsAtTotalAndResultIsUnchanged() = runTest {
        val portfolio = FakePortfolio(existing = listOf("RELAI|op-3"))
        val progress = ArrayList<CsvImportProgress>()
        var outcome: CsvImportSaveOutcome? = null
        val job = launch {
            val throttle = CsvProgressThrottle(CsvImportStep.SAVE, keys.size, clock = { testScheduler.currentTime }) {
                progress += it
            }
            throttle.start()
            outcome = saveCsvImport { portfolio.write(keys, links = 2, throttle::row) { false } }
        }
        advanceUntilIdle()
        // Eine Zeile war schon da (übersprungen), zwei gespeicherte Seiten verknüpft
        assertEquals(CsvImportSaveOutcome.Saved(inserted = 9_999, count = 10_001), outcome)
        assertEquals(10_000, portfolio.rows.size)
        assertEquals(CsvImportProgress(CsvImportStep.SAVE, 0, keys.size), progress.first())
        assertEquals(CsvImportProgress(CsvImportStep.SAVE, keys.size, keys.size), progress.last())
        assertTrue("gedrosselt: ${progress.size}", progress.size <= 2 + keys.size / CsvProgressThrottle.MIN_ROWS)
        assertEquals(progress.map { it.done }.sorted(), progress.map { it.done })
        job.cancel()
    }

    @Test
    fun failedWriteIsReported() = runTest {
        val locked = saveCsvImport { throw PortfolioStorageLockedException() }
        assertEquals(CsvImportSaveOutcome.Failed(locked = true), locked)
        val failed = saveCsvImport { error("Platte voll") }
        assertEquals(CsvImportSaveOutcome.Failed(locked = false), failed)
        // Anlegen und Verknüpfen sind EIN Schreibvorgang: scheitert das Verknüpfen, ist nichts gespeichert (kein «Gespeichert»)
        val linkFailed = saveCsvImport { error("Verknüpfen gescheitert") }
        assertEquals(CsvImportSaveOutcome.Failed(locked = false), linkFailed)
        // Nur Verknüpfungen: zählen als gespeichert, aber nicht als neu angelegt
        assertEquals(CsvImportSaveOutcome.Saved(inserted = 0, count = 2), saveCsvImport { CsvImportWritten(0, 2) })
    }
}
