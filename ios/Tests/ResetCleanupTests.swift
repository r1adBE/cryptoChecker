import XCTest
@testable import CryptoChecker

/// «App zurücksetzen» leert seit Build 99 auch den Ordner `tmp` (`AppData.clearTemporaryFiles`) — in einem eigenen
/// Testordner statt im echten `tmp` geprüft.
final class ResetCleanupTests: XCTestCase {
    func testClearTemporaryFilesRemovesFilesAndFolders() throws {
        let fm = FileManager.default
        let folder = fm.temporaryDirectory.appendingPathComponent("reset-\(UUID().uuidString)")
        let nested = folder.appendingPathComponent("export")
        try fm.createDirectory(at: nested, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: folder) }
        try Data("Bericht".utf8).write(to: folder.appendingPathComponent(DiagnosticReport.fileName))
        try Data("x".utf8).write(to: nested.appendingPathComponent("a.csv"))

        AppData.clearTemporaryFiles(in: folder)

        XCTAssertTrue(fm.fileExists(atPath: folder.path), "der Ordner selbst bleibt")
        XCTAssertEqual(try fm.contentsOfDirectory(atPath: folder.path), [])
    }
}
