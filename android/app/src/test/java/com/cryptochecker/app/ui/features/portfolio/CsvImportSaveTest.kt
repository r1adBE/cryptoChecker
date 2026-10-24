package com.cryptochecker.app.ui.features.portfolio

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

    /** Portfolio mit Transaktions-Verhalten: angelegte Zeilen zählen erst, wenn [insert] ohne Ausnahme endet. */
    private class FakePortfolio(existing: List<String> = emptyList()) {
        val rows = ArrayList(existing)
        var linkCalls = 0

        /** Wie `PortfolioRepository.insertImported`: 1 ms je Zeile, vor jeder Zeile [cancelled] geprüft. */
        suspend fun insert(keys: List<String>, onRow: (Int) -> Unit, cancelled: () -> Boolean): Int {
            val staged = ArrayList<String>()
            val count = CsvImportInsert.run(keys, rows.toHashSet(), { listOf(it) }, cancelled, onRow) {
                delay(1)
                staged += it
                true
            }
            rows += staged
            return count
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
            outcome = saveCsvImport(
                insert = { portfolio.insert(keys, throttle::row, cancel::get) },
                link = { portfolio.linkCalls++; 0 },
            )
        }
        advanceTimeBy(2_500)
        assertTrue("Fortschritt läuft: $progress", progress.isNotEmpty() && progress.last().done in 1 until keys.size)
        cancel.set(true)
        advanceUntilIdle()
        assertEquals(CsvImportSaveOutcome.Cancelled, outcome)
        assertEquals("Daten unverändert", listOf("manual"), portfolio.rows)
        assertEquals("nach dem Abbruch nicht mehr verknüpft", 0, portfolio.linkCalls)
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
            outcome = saveCsvImport(
                insert = { portfolio.insert(keys, throttle::row) { false } },
                link = { portfolio.linkCalls++; 2 },
            )
        }
        advanceUntilIdle()
        // Eine Zeile war schon da (übersprungen wie bisher), zwei gespeicherte Seiten verknüpft
        assertEquals(CsvImportSaveOutcome.Saved(inserted = 9_999, count = 10_001), outcome)
        assertEquals(10_000, portfolio.rows.size)
        assertEquals(CsvImportProgress(CsvImportStep.SAVE, 0, keys.size), progress.first())
        assertEquals(CsvImportProgress(CsvImportStep.SAVE, keys.size, keys.size), progress.last())
        assertTrue("gedrosselt: ${progress.size}", progress.size <= 2 + keys.size / CsvProgressThrottle.MIN_ROWS)
        assertEquals(progress.map { it.done }.sorted(), progress.map { it.done })
        job.cancel()
    }

    @Test
    fun failedInsertIsReportedAndNotLinked() = runTest {
        var linkCalls = 0
        val locked = saveCsvImport(insert = { throw PortfolioStorageLockedException() }, link = { linkCalls++; 1 })
        assertEquals(CsvImportSaveOutcome.Failed(locked = true), locked)
        val failed = saveCsvImport(insert = { error("Platte voll") }, link = { linkCalls++; 1 })
        assertEquals(CsvImportSaveOutcome.Failed(locked = false), failed)
        assertEquals(0, linkCalls)
        // Verknüpfen gescheitert, aber Zeilen angelegt: zählt (wie bisher)
        val partly = saveCsvImport(insert = { 3 }, link = { error("Verknüpfen gescheitert") })
        assertEquals(CsvImportSaveOutcome.Saved(inserted = 3, count = 3), partly)
    }
}
