import Foundation

/// Tage, für die der CSV-Import Kurse geladen hat, und ob sie nach einem Neu-Lesen noch reichen — wie Android
/// `CsvImportRateDays`. Eine andere Zeitzone schiebt Zeilen ohne Versatz um einen Tag: dann liegt ein Tag ausserhalb der
/// geladenen Kurse, und der Kurs des Vortags gälte still für ihn — darum neu laden (`cover`).
enum CsvImportRateDays {
    /// Schlüssel der USDT/USD-Tagesschlüsse in `of` (nie eine Währung von `CsvImportMapping.fxNeeds`).
    static let usdtUsd = "USDT"

    /// Gebrauchte Tage: Tageskurse je Währung (`CsvImportMapping.fxNeeds`) und unter `usdtUsd` die USDT/USD-Schlüsse vom
    /// frühesten bis zum spätesten Tag aller Zeilen — alles in `zone` (Zeitzone der Tageskurse, `CsvImportResult.fxZone`).
    static func of(_ rows: [CsvImportRow], zone: String = CsvImportParser.defaultZone) -> [String: ClosedRange<Int64>] {
        var out = CsvImportMapping.fxNeeds(rows, zone: zone)
        let days = rows.map { CsvImport.epochDay($0.timeMillis, zone: zone) }
        if let first = days.min(), let last = days.max() { out[usdtUsd] = first...last }
        return out
    }

    /// Decken die geladenen Kurse (`loaded` = `of` beim Laden) die nun gebrauchten Tage (`needed`) ab? Nein, sobald ein Tag
    /// einer Währung ausserhalb des geladenen Bereichs liegt (oder die Währung neu ist).
    static func cover(_ loaded: [String: ClosedRange<Int64>], _ needed: [String: ClosedRange<Int64>]) -> Bool {
        needed.allSatisfy { currency, days in
            guard let range = loaded[currency] else { return false }
            return days.lowerBound >= range.lowerBound && days.upperBound <= range.upperBound
        }
    }
}
