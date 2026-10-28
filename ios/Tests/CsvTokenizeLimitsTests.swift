import XCTest
@testable import CryptoChecker

/// Grenzen beim Zerlegen einer CSV-Datei: höchstens `CsvImportParser.maxCellsPerRow` Zellen je Zeile und
/// `maxRows` Datensätze; mehr = Datei zu gross, nicht lesbar wie eine Datei über 16 MB. Gleiche Fälle: Android
/// `CsvTokenizeLimitsTest`.
final class CsvTokenizeLimitsTests: XCTestCase {
    private let header = "Date,Type,Coin,Amount,Price,Currency\n"
    private let row = "2024-02-02T10:00:00Z,Staking Reward,ETH,0.01,2000,USD\n"

    private func scalars(_ text: String) -> [Unicode.Scalar] { Array(text.unicodeScalars) }

    func testGrenzenWieAndroid() {
        XCTAssertEqual(CsvImportParser.maxCellsPerRow, 200)
        XCTAssertEqual(CsvImportParser.maxRows, 200_000)
    }

    func testZellenJeZeileHoechstensGrenze() {
        let atLimit = (0..<CsvImportParser.maxCellsPerRow).map { "c\($0)" }.joined(separator: ",")
        XCTAssertEqual(CsvImportParser.tokenizeOrNil(scalars(atLimit), delimiter: ",")?.first?.cells.count,
                       CsvImportParser.maxCellsPerRow)
        XCTAssertNil(CsvImportParser.tokenizeOrNil(scalars(atLimit + ",zuviel"), delimiter: ","))
        // Lauter Trenner: ebenfalls zu viele (leere) Zellen
        XCTAssertNil(CsvImportParser.tokenizeOrNil(scalars("a" + String(repeating: ",", count: 100_000)), delimiter: ","))
        // Im Feld in Anführungszeichen zählt der Trenner nicht
        let quoted = "\"" + String(repeating: ",", count: 500) + "\",b"
        XCTAssertEqual(CsvImportParser.tokenizeOrNil(scalars(quoted), delimiter: ",")?.first?.cells.count, 2)
    }

    func testDatensaetzeHoechstensGrenze() {
        let text = scalars((1...5).map { "a\($0);b\($0)" }.joined(separator: "\n"))
        XCTAssertEqual(CsvImportParser.tokenizeOrNil(text, delimiter: ";", maxRows: 5)?.count, 5)
        XCTAssertNil(CsvImportParser.tokenizeOrNil(text, delimiter: ";", maxRows: 4))
        // Leere Zeilen zählen nicht
        XCTAssertEqual(CsvImportParser.tokenizeOrNil(scalars("a\n\n\n\nb\n"), delimiter: ";", maxRows: 2)?.count, 2)
        // Erkennung der Kopfzeile (maxRecords) hört vorher auf und ist nicht «zu gross»
        XCTAssertEqual(CsvImportParser.tokenizeOrNil(text, delimiter: ";", maxRecords: 3, maxRows: 4)?.count, 3)
    }

    func testZuGrosseDateiIstNichtLesbar() {
        let ok = CsvImportParser.parse(header + row)
        XCTAssertEqual(ok.format, .GENERIC)
        XCTAssertFalse(ok.tooLarge)
        let wideRow = Array(repeating: "x", count: CsvImportParser.maxCellsPerRow + 1).joined(separator: ",")
        let wide = CsvImportParser.parse(header + row + wideRow + "\n")
        XCTAssertEqual(wide.format, .UNKNOWN)
        XCTAssertTrue(wide.tooLarge)
        XCTAssertTrue(wide.rows.isEmpty)
        // Kopfzeile selbst zu breit
        let wideHeader = Array(repeating: "Date", count: CsvImportParser.maxCellsPerRow + 1).joined(separator: ",")
        XCTAssertTrue(CsvImportParser.parse(wideHeader).tooLarge)
        let long = CsvImportParser.parse(header + String(repeating: "x\n", count: CsvImportParser.maxRows))
        XCTAssertEqual(long.format, .UNKNOWN)
        XCTAssertTrue(long.tooLarge)
    }

    /// Zu gross wird als «zu gross» gemeldet, nicht als fremdes Format: die Vorschau trägt
    /// `CsvImportResult.tooLarge` weiter, `CsvImportRefusal` wählt die Meldung. Gleiche Fälle: Android `CsvTokenizeLimitsTest`.
    func testZuGrosseDateiMeldetZuGross() {
        func preview(_ text: String) -> CsvImportPreview {
            CsvImportMapping.preview(CsvImportParser.parse(text), existingKeys: []) { _, _ in nil }
        }
        let wideRow = Array(repeating: "x", count: CsvImportParser.maxCellsPerRow + 1).joined(separator: ",")
        let wide = preview(header + row + wideRow + "\n")
        XCTAssertTrue(wide.tooLarge)
        XCTAssertEqual(CsvImportRefusal.of(fileTooLarge: false, preview: wide), .tooLarge)
        let long = preview(header + String(repeating: "x\n", count: CsvImportParser.maxRows))
        XCTAssertEqual(CsvImportRefusal.of(fileTooLarge: false, preview: long), .tooLarge)
        // Über 16 MB: das Lesen bricht ab, keine Vorschau
        XCTAssertEqual(CsvImportRefusal.of(fileTooLarge: true, preview: nil), .tooLarge)
        // Fremdes Format oder nicht lesbar: unlesbar
        XCTAssertEqual(CsvImportRefusal.of(fileTooLarge: false, preview: preview("a,b,c\n1,2,3\n")), .unreadable)
        XCTAssertEqual(CsvImportRefusal.of(fileTooLarge: false, preview: nil), .unreadable)
        // Lesbare Datei: Vorschau
        let ok = preview(header + row)
        XCTAssertFalse(ok.tooLarge)
        XCTAssertNil(CsvImportRefusal.of(fileTooLarge: false, preview: ok))
    }

    /// Bestehender Aufruf ohne Grenzen-Ergebnis: über den Grenzen leer.
    func testTokenizeUeberGrenzenLeer() {
        XCTAssertTrue(CsvImportParser.tokenize(scalars("a" + String(repeating: ",", count: 300)), delimiter: ",").isEmpty)
    }
}
