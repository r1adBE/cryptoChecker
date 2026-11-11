// Zustand des CSV-Imports (`PortfolioCsvImportViewModel`). Eigene Datei, weil das ViewModel an der Längengrenze ist.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRefusal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvReadChoice

/** Ablauf des CSV-Imports. */
sealed interface CsvImportState {
    data object Idle : CsvImportState
    data object Loading : CsvImportState
    /**
     * [rereading]: Lesart geändert, Datei wird neu gelesen — «Importieren» aus, Vorschau gedimmt.
     * [choice]: Lesart, mit der GENAU diese Vorschau gebaut wurde ([CsvReadChoice.canConfirm]).
     */
    data class Preview(
        val preview: CsvImportPreview,
        val rereading: Boolean = false,
        val choice: CsvReadChoice = CsvReadChoice(),
    ) : CsvImportState
    /** Keine unterstützte Zeile (fremdes Format, leere Datei, nicht lesbar). */
    data object Unreadable : CsvImportState
    /** Datei zu gross ([CsvImportRefusal.TOO_LARGE]): über 16 MB oder zu viele Zeilen bzw. Spalten. */
    data object TooLarge : CsvImportState
    data object Saving : CsvImportState
    /** Portfolio gesperrt ([com.cryptochecker.app.data.portfolio.PortfolioStorageLock]): nichts gelesen bzw. geschrieben. */
    data object Locked : CsvImportState
    /** Schreiben fehlgeschlagen: nichts importiert — gemeldet statt «0 importiert». */
    data object SaveFailed : CsvImportState
    /**
     * Gespeichert: [count] Zeilen, [enabled] = Portfolio dabei eingeschaltet. Als Zustand (nicht als Rückruf), damit
     * das Ergebnis ein Drehen übersteht; die Ansicht meldet es und setzt zurück ([PortfolioCsvImportViewModel.reset]).
     */
    data class Done(val count: Int, val enabled: Boolean) : CsvImportState
    /** «Abbrechen» beim Speichern: nichts gespeichert; die Ansicht schliesst und setzt zurück. */
    data object Cancelled : CsvImportState
}
