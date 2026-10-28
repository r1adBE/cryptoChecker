// Speichern des CSV-Imports: Zeilen vorbereiten (im Hintergrund), neue Transaktionen anlegen und gewählte Umbuchungen mit
// gespeicherten Seiten verknüpfen (abbrechbar, alles in einer Datenbank-Transaktion). Eigene Datei, weil
// `PortfolioCsvImportViewModel.kt` an der Längengrenze ist; gespiegelt im Speichern von `PortfolioCsvImport.swift`.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.CsvImportWritten
import com.cryptochecker.app.data.portfolio.PortfolioStorageLockedException
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportCancelledException
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportProgress
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportStep
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.fallbacks
import com.cryptochecker.app.domain.portfolio.csvimport.withFallbacks
import timber.log.Timber

/** Ergebnis von [saveCsvImport]. */
internal sealed interface CsvImportSaveOutcome {
    /** Gespeichert: [inserted] neu angelegt, [count] samt verknüpfter gespeicherter Seiten. */
    data class Saved(val inserted: Int, val count: Int) : CsvImportSaveOutcome

    /** «Abbrechen» vor dem Ende des Anlegens: nichts gespeichert (die Transaktion ist zurückgerollt). */
    data object Cancelled : CsvImportSaveOutcome

    /** Nicht geschrieben ([locked] = Portfolio gesperrt, sonst Fehler): nichts importiert. */
    data class Failed(val locked: Boolean) : CsvImportSaveOutcome
}

/**
 * [write] legt die neuen Transaktionen an und verknüpft die gespeicherten Seiten in EINER Transaktion
 * ([com.cryptochecker.app.data.portfolio.insertImportedLinked]); wirft bei «Abbrechen» [CsvImportCancelledException],
 * dann ist nichts geschrieben. Scheitert es, ist ebenfalls nichts geschrieben: Fehler mit Grund statt «0 importiert».
 */
internal suspend fun saveCsvImport(write: suspend () -> CsvImportWritten): CsvImportSaveOutcome {
    val written = runCatchingCancellable { write() }
    val failure = written.exceptionOrNull()
    if (failure is CsvImportCancelledException) return CsvImportSaveOutcome.Cancelled
    if (failure != null) {
        Timber.w(failure, "CSV-Import: Speichern fehlgeschlagen")
        return CsvImportSaveOutcome.Failed(locked = failure is PortfolioStorageLockedException)
    }
    val done = written.getOrThrow()
    return CsvImportSaveOutcome.Saved(done.inserted, done.inserted + done.linked)
}

/**
 * Zum Speichern vorbereitet ([prepareCsvImport]): Zeilen samt Ersatzzeilen, Kennung des Laufs, gespeicherte Seiten zum
 * Verknüpfen und welche Zeile Ersatz für welche Seite ist ([fallbacks], `CsvTransferMatch.fallbacks`).
 */
internal class CsvImportSavePlan(
    val entities: List<PortfolioTxEntity>,
    val batch: String,
    val storedUpdates: List<CsvStoredTransfer>,
    val fallbacks: Map<String, Long> = emptyMap(),
)

/**
 * Gewählte Umbuchungen anwenden, die Wahl je Auszahlung übernehmen (Ziel aus [destinations]), älteste zuerst ordnen
 * (Id folgt der Zeit), in Zeilen umwandeln und den Lauf kennzeichnen («Import rückgängig»: Format, [now], Hash der
 * Zeilenschlüssel). Neue Zeilen, die gegen eine gespeicherte Seite aufgehen, kommen als Ersatz mit
 * ([CsvTransferMatch.withFallbacks]): gespeichert nur, wenn die Verknüpfung beim Speichern nicht mehr geht. Bei grossen
 * Dateien rechenintensiv, darum im Hintergrund aufgerufen.
 * @return null, wenn nicht jede Auszahlung (ohne verknüpfte Umbuchung) eine Wahl hat
 */
internal fun prepareCsvImport(
    preview: CsvImportPreview,
    links: List<CsvTransferLink>,
    linked: Set<String>,
    choices: Map<String, CsvWithdrawalChoice>,
    destinations: Map<String, String?>,
    now: Long,
): CsvImportSavePlan? {
    val plan = CsvTransferMatch.apply(preview.transactions, links, linked)
    if (!CsvImportMapping.allWithdrawalsDecided(plan.transactions, choices)) return null
    val rows = CsvTransferMatch.withFallbacks(preview.transactions, plan)
    val txs = CsvImportMapping.insertOrder(CsvImportMapping.applyWithdrawalChoices(rows, choices, destinations))
    val batch = PortfolioImportBatch.newId(preview.format.name, now, txs.map { it.importKey })
    return CsvImportSavePlan(txs.map { it.toEntity() }, batch, plan.storedUpdates, CsvTransferMatch.fallbacks(plan))
}

/**
 * «Abbrechen» im Lade- bzw. Speicherdialog möglich: beim Laden immer; beim Speichern, bis alle Zeilen angelegt sind
 * ([progress] im Schritt [CsvImportStep.SAVE] mit done ≥ total) — danach folgen nur noch die Verknüpfungen, die nicht mehr
 * abbrechbar sind.
 */
internal fun canCancelSave(saving: Boolean, progress: CsvImportProgress?): Boolean =
    !saving || progress?.let { it.step == CsvImportStep.SAVE && it.done >= it.total } != true

/**
 * Ablauf von «Importieren» nach dem Zustand [CsvImportState.Saving]: [prepare] (wirft = Fehler, null = nicht jede
 * Auszahlung gewählt), [cancelled] nach dem Vorbereiten geprüft, dann [save] ([saveCsvImport]) und bei neu angelegten
 * Zeilen [enable] (Portfolio einschalten; true = eingeschaltet).
 * @return neuer Zustand: [CsvImportState.Done], [CsvImportState.Cancelled], [CsvImportState.Locked] oder
 *   [CsvImportState.SaveFailed]; null = die Vorschau bleibt (nicht jede Auszahlung gewählt)
 */
internal suspend fun runCsvImportSave(
    prepare: suspend () -> CsvImportSavePlan?,
    cancelled: () -> Boolean,
    save: suspend (CsvImportSavePlan) -> CsvImportSaveOutcome,
    enable: suspend (inserted: Int) -> Boolean,
): CsvImportState? {
    val prepared = runCatchingCancellable { prepare() }
        .onFailure { Timber.w(it, "CSV-Import: Vorbereiten fehlgeschlagen") }
    if (prepared.isFailure) return CsvImportState.SaveFailed
    val plan = prepared.getOrNull() ?: return null
    if (cancelled()) return CsvImportState.Cancelled
    return when (val outcome = save(plan)) {
        CsvImportSaveOutcome.Cancelled -> CsvImportState.Cancelled
        // Nichts geschrieben, weil Schreiben scheiterte (gesperrt oder Fehler): Grund nennen statt «0 importiert»
        is CsvImportSaveOutcome.Failed -> if (outcome.locked) CsvImportState.Locked else CsvImportState.SaveFailed
        // Nur neu angelegte Transaktionen schalten ein (verknüpfte gespeicherte gab es schon)
        is CsvImportSaveOutcome.Saved ->
            CsvImportState.Done(outcome.count, enabled = outcome.inserted > 0 && enable(outcome.inserted))
    }
}
