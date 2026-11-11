import XCTest
@testable import CryptoChecker

/// Schreiben und Löschen ausserhalb der Caches: Fehlschläge gehen ins Protokoll (`AppLog.attempt` / `AppLog.remove`)
/// statt mit `try?` unterzugehen; das Ergebnis bleibt für den Aufrufer dasselbe.
final class AppLogFileOpsTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("applog-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: folder)
    }

    func testRemoveDeletesAndTreatsMissingFileAsDone() throws {
        let file = folder.appendingPathComponent("a.txt")
        try Data("x".utf8).write(to: file)
        XCTAssertTrue(AppLog.remove(file, "Test"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        // Schon weg: nichts zu tun, kein Fehler
        XCTAssertTrue(AppLog.remove(file, "Test"))
    }

    func testAttemptReportsSuccessAndFailure() {
        let source = folder.appendingPathComponent("fehlt.txt")
        let target = folder.appendingPathComponent("ziel.txt")
        XCTAssertFalse(AppLog.attempt("Test") { try FileManager.default.moveItem(at: source, to: target) })
        XCTAssertTrue(AppLog.attempt("Test") { try Data("x".utf8).write(to: source) })
        XCTAssertTrue(AppLog.attempt("Test") { try FileManager.default.moveItem(at: source, to: target) })
        XCTAssertTrue(FileManager.default.fileExists(atPath: target.path))
    }

    func testMissingFileIsRecognised() {
        XCTAssertTrue(AppLog.isMissingFile(NSError(domain: NSCocoaErrorDomain, code: NSFileNoSuchFileError)))
        XCTAssertTrue(AppLog.isMissingFile(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOENT))))
        XCTAssertFalse(AppLog.isMissingFile(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteNoPermissionError)))
    }

    func testNonCacheSitesNoLongerSwallowErrors() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let files = ["App/Services/AppData+Reset.swift", "App/Services/RestoreJournal.swift",
                     "Shared/Storage/ProtectedFile.swift", "Shared/Storage/ProtectedStorage.swift",
                     "Shared/Portfolio/WatchedAddressStore.swift", "Shared/Core/MirrorRevocations.swift"]
        for name in files {
            let source = try String(contentsOf: root.appendingPathComponent(name), encoding: .utf8)
            for call in ["removeItem(", "moveItem(", ".write(to:"] {
                XCTAssertFalse(source.split(separator: "\n").contains { $0.contains("try?") && $0.contains(call) }, "\(name): \(call)")
            }
        }
    }
}
