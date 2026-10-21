import Foundation

/// Warum der CSV-Import keine Vorschau zeigt — die Meldung nennt den Grund, statt eine zu grosse Datei als «kein
/// unterstütztes Format» abzutun. Seit Build 99 (vorher wurde `CsvImportResult.tooLarge` nie ausgewertet).
/// Spiegel: `CsvImportRefusal.kt` (Android).
enum CsvImportRefusal: Error, Equatable {
    /// Über 16 MB (`PortfolioCsvImporter.maxBytes`) oder über `CsvImportParser.maxRows`/`maxCellsPerRow`.
    case tooLarge
    /// Fremdes Format, leere oder nicht lesbare Datei, keine unterstützte Zeile.
    case unreadable

    /// `fileTooLarge` = Lesen brach an der Grössengrenze ab; `preview` = Vorschau der Datei (nil = nicht lesbar).
    /// nil = Vorschau zeigen (etwas Neues, Doppeltes oder Übersprungenes zu nennen).
    static func of(fileTooLarge: Bool, preview: CsvImportPreview?) -> CsvImportRefusal? {
        if fileTooLarge || preview?.tooLarge == true { return .tooLarge }
        guard let preview, preview.format != .UNKNOWN else { return .unreadable }
        if preview.transactions.isEmpty && preview.duplicates == 0 && preview.skipped.isEmpty { return .unreadable }
        return nil
    }
}
