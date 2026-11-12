import XCTest
@testable import CryptoChecker

/// Wiederherstellen scheitert am Speicher der App (Merkliste bzw. App-Gruppe nicht beschreibbar, `RestoreSteps`
/// `settled()` false): eigene Meldung `backup_restore_storage_unavailable` statt `backup_failed`. Zu dem Zeitpunkt ist alles
/// zurückgerollt bzw. das Journal bleibt (der nächste Start rollt zurück, `restore_steps.json`): die bisherigen Daten sind
/// unverändert. Wie Android `RestoreStorageMessageTest.kt`.
final class RestoreStorageMessageTests: XCTestCase {

    func testOnlyTheWatchlistWriteCountsAsStorage() {
        XCTAssertTrue(BackupManager.isStorageFailure(BackupManager.Failure.invalid(BackupManager.watchlistStep)))
        XCTAssertFalse(BackupManager.isStorageFailure(BackupManager.Failure.invalid("portfolio")))
        XCTAssertFalse(BackupManager.isStorageFailure(BackupManager.Failure.notABackup))
        XCTAssertFalse(BackupManager.isStorageFailure(BackupManager.Failure.journalUnavailable))
        XCTAssertFalse(BackupManager.isStorageFailure(CocoaError(.fileWriteOutOfSpace)))
    }

    /// Nicht gespeichert → zurückgerollt, Fehler `NotSettled` (wird zu `Failure.invalid(watchlistStep)`).
    func testNotSettledRollsBack() {
        var state = "old"
        var journalDeleted = false
        XCTAssertThrowsError(try RestoreSteps.execute({ steps in
            steps.run({ state = "new" }, undo: { state = "old" })
        }, settled: { false }, deleteJournal: { journalDeleted = true })) { error in
            XCTAssertEqual(error as? RestoreSteps.NotSettled, RestoreSteps.NotSettled())
        }
        XCTAssertEqual(state, "old")
        // Auch der zurückgerollte Stand nicht auf der Platte: das Journal bleibt, der nächste Start rollt zurück
        XCTAssertFalse(journalDeleted)
    }

    func testLongMessageAndWiring() throws {
        XCTAssertTrue(BackupResultPresentation.longKeys.contains("backup_restore_storage_unavailable"))
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let page = try String(contentsOf: root.appendingPathComponent("App/Features/Settings/SettingsDataPages.swift"),
                              encoding: .utf8)
        XCTAssertTrue(page.contains("} catch let error where BackupManager.isStorageFailure(error) {"))
        XCTAssertTrue(page.contains("fail(\"backup_restore_storage_unavailable\")"))
    }
}
