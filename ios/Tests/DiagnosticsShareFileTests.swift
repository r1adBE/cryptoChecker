import XCTest
@testable import CryptoChecker

/// Datei des geteilten Diagnoseberichts: entsteht erst beim Teilen, verschwindet wieder. Gegenstück: `DiagnosticsFilesTest.kt`.
final class DiagnosticsShareFileTests: XCTestCase {

    override func tearDown() {
        DiagnosticsShareFile.remove()
        super.tearDown()
    }

    func testSchreibtErstAufAnforderungUndLoeschtWieder() throws {
        DiagnosticsShareFile.remove()
        XCTAssertFalse(FileManager.default.fileExists(atPath: DiagnosticsShareFile.url.path))

        try DiagnosticsShareFile(text: "Bericht").write()
        XCTAssertEqual(try String(contentsOf: DiagnosticsShareFile.url, encoding: .utf8), "Bericht")
        XCTAssertEqual(DiagnosticsShareFile.url.lastPathComponent, DiagnosticReport.fileName)

        DiagnosticsShareFile.remove()
        XCTAssertFalse(FileManager.default.fileExists(atPath: DiagnosticsShareFile.url.path))
        // Zweimal löschen schadet nicht
        DiagnosticsShareFile.remove()
    }
}
