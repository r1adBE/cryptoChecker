// Speichern des CSV-Imports: neue Transaktionen anlegen (abbrechbar, in einer Datenbank-Transaktion), danach gewählte
// Umbuchungen mit gespeicherten Seiten verknüpfen. Eigene Datei, weil `PortfolioCsvImportViewModel.kt` an der
// Längengrenze ist; gespiegelt im Speichern von `PortfolioCsvImport.swift`.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioStorageLockedException
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportCancelledException
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
 * Erst [insert] (neue Transaktionen in EINER Transaktion; wirft bei «Abbrechen» [CsvImportCancelledException], dann ist
 * nichts angelegt), danach [link] (gespeicherte Seiten verknüpfter Umbuchungen, eigene Transaktion, nicht mehr
 * abbrechbar). Scheitert das Verknüpfen, zählen die angelegten Zeilen trotzdem; nur ohne jede geschriebene Zeile ist es
 * ein Fehler (Grund statt «0 importiert»).
 */
internal suspend fun saveCsvImport(insert: suspend () -> Int, link: suspend () -> Int): CsvImportSaveOutcome {
    val inserted = runCatchingCancellable { insert() }
    if (inserted.exceptionOrNull() is CsvImportCancelledException) return CsvImportSaveOutcome.Cancelled
    inserted.onFailure { Timber.w(it, "CSV-Import: Speichern fehlgeschlagen") }
    val linked = if (inserted.isFailure) null else runCatchingCancellable { link() }
        .onFailure { Timber.w(it, "CSV-Import: Umbuchung nicht verknüpft") }
    val created = inserted.getOrDefault(0)
    val count = created + (linked?.getOrNull() ?: 0)
    val failure = inserted.exceptionOrNull() ?: linked?.exceptionOrNull()?.takeIf { count == 0 }
    if (failure != null) return CsvImportSaveOutcome.Failed(locked = failure is PortfolioStorageLockedException)
    return CsvImportSaveOutcome.Saved(created, count)
}
