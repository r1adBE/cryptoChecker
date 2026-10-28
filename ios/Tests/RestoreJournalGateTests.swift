import Security
import XCTest
@testable import CryptoChecker

/// Wiederherstellen nie ohne Journal (`BackupManager.requireJournal`, wie Android `RestoreRun`) —
/// ohne Schlüssel im Schlüsselbund wird nichts ersetzt.
final class RestoreJournalGateTests: XCTestCase {
    /// Schlüsselbund, der gerade nicht lesbar ist (z. B. vor dem ersten Entsperren).
    private final class LockedKeychain: StorageKeychain, @unchecked Sendable { // @unchecked: Test-Fake ohne Zustand
        func read() throws -> Data? { throw StorageKeychainError(status: errSecInteractionNotAllowed) }
        func add(_ key: Data) throws -> Bool { throw StorageKeychainError(status: errSecInteractionNotAllowed) }
        func delete() throws {}
    }

    private func isJournalUnavailable(_ error: Error) -> Bool {
        if case BackupManager.Failure.journalUnavailable = error { return true }
        return false
    }

    func testRefusesWhenJournalCannotBeWritten() {
        var writes = 0
        XCTAssertThrowsError(try BackupManager.requireJournal(Data("{}".utf8), write: { _ in writes += 1; return false })) {
            XCTAssertTrue(isJournalUnavailable($0))
        }
        XCTAssertEqual(writes, 1)
    }

    func testRefusesWithoutPreviousState() {
        var writes = 0
        XCTAssertThrowsError(try BackupManager.requireJournal(nil, write: { _ in writes += 1; return true })) {
            XCTAssertTrue(isJournalUnavailable($0))
        }
        // Ohne bisherigen Stand gar nicht erst schreiben
        XCTAssertEqual(writes, 0)
    }

    func testPassesWhenJournalWritten() throws {
        var written: Data?
        try BackupManager.requireJournal(Data("{\"a\":1}".utf8), write: { written = $0; return true })
        XCTAssertEqual(written, Data("{\"a\":1}".utf8))
    }

    func testJournalWithoutKeyIsNotWritten() {
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("journal-\(UUID().uuidString).sealed")
        defer { try? FileManager.default.removeItem(at: target) }
        let keys = StorageKey(keychain: LockedKeychain(), readGeneration: { nil }, writeGeneration: { _ in })
        XCTAssertFalse(RestoreJournal.write(Data("{}".utf8), keys: keys, to: target))
        XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
        // Zusammen: kein Journal → Wiederherstellen abgelehnt
        XCTAssertThrowsError(try BackupManager.requireJournal(Data("{}".utf8),
                                                             write: { RestoreJournal.write($0, keys: keys, to: target) }))
    }
}
