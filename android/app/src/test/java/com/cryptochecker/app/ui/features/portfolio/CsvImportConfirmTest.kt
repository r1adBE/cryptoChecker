package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportFormat
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportProgress
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportStep
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * «Importieren» ohne ViewModel: Vorbereiten ([prepareCsvImport]), Ablauf und Ergebnis ([runCsvImportSave]),
 * «Abbrechen» im Speicherdialog ([canCancelSave]) und Schliessen nach einem Prozessende ([csvImportOrphaned]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvImportConfirmTest {

    private fun tx(line: Int, type: PortfolioTxType, time: Long) = CsvImportTx(
        line = line, coin = "BTC", type = type, amount = BigDecimal("0.01"), priceUsdt = BigDecimal("60000"), time = time,
        fee = null, feeUnit = null, feeCoin = null, feeCoinAmount = null, note = null, importKey = "RELAI|op-$line",
    )

    /** Datei «neueste zuerst»: Kauf (3), Auszahlung (2), Kauf (1). */
    private val preview = CsvImportPreview(
        format = CsvImportFormat.RELAI,
        transactions = listOf(
            tx(2, PortfolioTxType.BUY, 3_000L),
            tx(3, PortfolioTxType.WITHDRAWAL, 2_000L),
            tx(4, PortfolioTxType.BUY, 1_000L),
        ),
        duplicates = 0,
        skipped = emptyList(),
        missingFx = emptyList(),
        missingPrice = emptyList(),
    )

    private fun plan() = CsvImportSavePlan(emptyList(), "RELAI:1:00000000", emptyList())

    @Test
    fun prepareNeedsAChoiceForEveryWithdrawal() {
        assertNull(prepareCsvImport(preview, emptyList(), emptySet(), emptyMap(), emptyMap(), now = 1L))
    }

    @Test
    fun prepareAppliesChoiceOrdersOldestFirstAndMarksTheRun() {
        val choices = mapOf("RELAI|op-3" to CsvWithdrawalChoice.TRANSFER)
        val plan = prepareCsvImport(preview, emptyList(), emptySet(), choices, mapOf("RELAI|op-3" to "Ledger"), now = 42L)
        assertNotNull(plan)
        plan ?: return
        assertEquals(listOf("RELAI|op-4", "RELAI|op-3", "RELAI|op-2"), plan.entities.map { it.importKey })
        val transfer = plan.entities[1]
        assertEquals(PortfolioTxType.TRANSFER, transfer.type)
        assertEquals("Ledger", transfer.transferTo)
        assertTrue(plan.batch, plan.batch.startsWith("RELAI:42:"))
        assertTrue(plan.storedUpdates.isEmpty())
    }

    @Test
    fun successReportsCountAndEnablesOnlyWithNewRows() = runTest {
        var enableCalls = 0
        val done = runCsvImportSave(
            prepare = { plan() },
            cancelled = { false },
            save = { CsvImportSaveOutcome.Saved(inserted = 3, count = 4) },
            enable = { enableCalls++; true },
        )
        assertEquals(CsvImportState.Done(count = 4, enabled = true), done)
        // Nur verknüpfte gespeicherte Seiten: nichts neu angelegt, Portfolio bleibt wie es ist
        val linkedOnly = runCsvImportSave(
            prepare = { plan() },
            cancelled = { false },
            save = { CsvImportSaveOutcome.Saved(inserted = 0, count = 2) },
            enable = { enableCalls++; true },
        )
        assertEquals(CsvImportState.Done(count = 2, enabled = false), linkedOnly)
        assertEquals(1, enableCalls)
    }

    @Test
    fun cancelBeforeOrDuringSaveSavesNothing() = runTest {
        var saveCalls = 0
        val early = runCsvImportSave(
            prepare = { plan() },
            cancelled = { true },
            save = { saveCalls++; CsvImportSaveOutcome.Saved(1, 1) },
            enable = { true },
        )
        assertEquals(CsvImportState.Cancelled, early)
        assertEquals("nach dem Vorbereiten abgebrochen: gar nicht erst gespeichert", 0, saveCalls)
        val during = runCsvImportSave(
            prepare = { plan() },
            cancelled = { false },
            save = { CsvImportSaveOutcome.Cancelled },
            enable = { true },
        )
        assertEquals(CsvImportState.Cancelled, during)
    }

    @Test
    fun failuresNameTheReason() = runTest {
        val locked = runCsvImportSave({ plan() }, { false }, { CsvImportSaveOutcome.Failed(locked = true) }, { true })
        assertEquals(CsvImportState.Locked, locked)
        val failed = runCsvImportSave({ plan() }, { false }, { CsvImportSaveOutcome.Failed(locked = false) }, { true })
        assertEquals(CsvImportState.SaveFailed, failed)
    }

    @Test
    fun preparationFailureOrMissingChoice() = runTest {
        var saveCalls = 0
        val failed = runCsvImportSave({ error("kaputt") }, { false }, { saveCalls++; CsvImportSaveOutcome.Saved(1, 1) }, { true })
        assertEquals(CsvImportState.SaveFailed, failed)
        // Nicht jede Auszahlung gewählt: null = Vorschau bleibt
        val open = runCsvImportSave({ null }, { false }, { saveCalls++; CsvImportSaveOutcome.Saved(1, 1) }, { true })
        assertNull(open)
        assertEquals(0, saveCalls)
    }

    @Test
    fun cancelledCoroutineDuringPreparationReportsNothing() = runTest {
        var result: CsvImportState? = CsvImportState.Idle
        var saveCalls = 0
        // Im Vordergrund gestartet (advanceUntilIdle wartet darauf); ViewModel endet während des Vorbereitens
        val job = launch {
            result = runCsvImportSave({ awaitCancellation() }, { false }, { saveCalls++; CsvImportSaveOutcome.Saved(1, 1) }, { true })
        }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertEquals("weder Fehler noch Ergebnis gemeldet", CsvImportState.Idle, result)
        assertEquals(0, saveCalls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancelPossibleUntilAllRowsAreSaved() {
        assertTrue("Laden: immer", canCancelSave(saving = false, progress = null))
        assertTrue("Speichern ohne Zeilenzahl", canCancelSave(saving = true, progress = null))
        assertTrue(canCancelSave(saving = true, progress = CsvImportProgress(CsvImportStep.SAVE, 9, 10)))
        assertFalse("alle angelegt, Verknüpfen läuft", canCancelSave(true, CsvImportProgress(CsvImportStep.SAVE, 10, 10)))
        // Fortschritt eines anderen Schritts zählt nicht
        assertTrue(canCancelSave(saving = true, progress = CsvImportProgress(CsvImportStep.PREVIEW, 10, 10)))
    }

    @Test
    fun flowClosesOnlyWhenNothingIsLeftAfterProcessDeath() {
        assertTrue(csvImportOrphaned(picked = true, awaitingPick = false, state = CsvImportState.Idle))
        assertFalse("Dateiauswahl offen", csvImportOrphaned(picked = true, awaitingPick = true, state = CsvImportState.Idle))
        assertFalse("noch nicht gestartet", csvImportOrphaned(picked = false, awaitingPick = false, state = CsvImportState.Idle))
        assertFalse("Drehen beim Laden", csvImportOrphaned(picked = true, awaitingPick = false, state = CsvImportState.Loading))
        assertFalse(csvImportOrphaned(picked = true, awaitingPick = false, state = CsvImportState.Saving))
    }
}
