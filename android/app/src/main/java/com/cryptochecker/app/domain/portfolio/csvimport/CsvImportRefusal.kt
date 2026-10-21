package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Warum der CSV-Import keine Vorschau zeigt — die Meldung nennt den Grund, statt eine zu grosse Datei als «kein
 * unterstütztes Format» abzutun. Seit Build 99 (vorher wurde [CsvImportResult.tooLarge] nie ausgewertet).
 * Spiegel: `CsvImportRefusal.swift`.
 */
enum class CsvImportRefusal {
    /** Über 16 MB (`BackupLimits.CSV_MAX_BYTES`) oder über [CsvImportParser.MAX_ROWS]/[CsvImportParser.MAX_CELLS_PER_ROW]. */
    TOO_LARGE,

    /** Fremdes Format, leere oder nicht lesbare Datei, keine unterstützte Zeile. */
    UNREADABLE;

    companion object {
        /**
         * [fileTooLarge] = Lesen brach an der Grössengrenze ab; [preview] = Vorschau der Datei (null = nicht lesbar).
         * null = Vorschau zeigen (etwas Neues, Doppeltes oder Übersprungenes zu nennen).
         */
        fun of(fileTooLarge: Boolean, preview: CsvImportPreview?): CsvImportRefusal? = when {
            fileTooLarge || preview?.tooLarge == true -> TOO_LARGE
            preview == null || preview.format == CsvImportFormat.UNKNOWN -> UNREADABLE
            preview.transactions.isEmpty() && preview.duplicates == 0 && preview.skipped.isEmpty() -> UNREADABLE
            else -> null
        }
    }
}
