import XCTest
@testable import CryptoChecker

/// Sichern/Wiederherstellen bei nicht lesbarem Stand (gesperrt ≠ beschädigt), Journal mit Alarm-Zustand und Datei ohne
/// Passwort ohne eigenen Bitcoin-Server. Gegenstück Android: `BackupRestorePolicyTest.kt` (Ablehnen),
/// `BackupSettingsCodecTest.fileWithoutPasswordWithoutServer`.
final class BackupStorageKeptTests: XCTestCase {
    /// Gesperrt (abwarten) geht vor beschädigt (ersetzen): Sichern lehnt beides ab, Wiederherstellen nur «gesperrt».
    /// Vorher sperrte auch eine dauerhaft beschädigte Datei das Wiederherstellen — den Rettungsweg — mit «Starte die App
    /// neu», was nie half.
    func testLockedRejectsDamagedIsReplaced() {
        typealias B = BackupManager
        XCTAssertEqual(B.storageAccess(watchlistLocked: false, parts: [.readable, .readable, .readable]), .readable)
        XCTAssertEqual(B.storageAccess(watchlistLocked: true, parts: [.readable, .readable, .readable]), .locked)
        XCTAssertEqual(B.storageAccess(watchlistLocked: false, parts: [.locked, .readable, .readable]), .locked)
        XCTAssertEqual(B.storageAccess(watchlistLocked: false, parts: [.readable, .damaged, .readable]), .damaged)
        XCTAssertEqual(B.storageAccess(watchlistLocked: false, parts: [.damaged, .readable, .locked]), .locked)
        XCTAssertNil(B.backupRefusalKey(.readable))
        XCTAssertEqual(B.backupRefusalKey(.locked), "backup_storage_kept")
        XCTAssertEqual(B.backupRefusalKey(.damaged), "ios_backup_storage_damaged")
        XCTAssertNil(B.restoreRefusalKey(.readable))
        XCTAssertEqual(B.restoreRefusalKey(.locked), "backup_storage_kept")
        XCTAssertNil(B.restoreRefusalKey(.damaged))
        XCTAssertEqual(B.partState(unreadable: false, file: URL(fileURLWithPath: "/fehlt")), .readable)
        // Weg bzw. nicht zugänglich: nie ersetzen
        XCTAssertEqual(B.partState(unreadable: true, file: URL(fileURLWithPath: "/fehlt/portfolio.json")), .locked)
    }

    /// Beschädigt heisst: Bytes lesbar (kaputt bzw. unverschlüsselt); ohne Schlüssel bzw. weg: gesperrt.
    func testDamagedOrLocked() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("damaged-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = Data(repeating: 7, count: 32)
        let keys = StorageKey(keychain: fake)
        let plain = dir.appendingPathComponent("plain.json")
        try Data("kaputt".utf8).write(to: plain)
        XCTAssertTrue(ProtectedFile.unreadableIsDamaged(plain, keys: keys))
        let sealed = dir.appendingPathComponent("sealed.json")
        try ProtectedFile.write(Data("{".utf8), to: sealed, keys: keys, requireSealed: true)
        XCTAssertTrue(ProtectedFile.unreadableIsDamaged(sealed, keys: keys))
        let noKey = StorageKey(keychain: ProtectedFileTests.FakeKeychain())
        XCTAssertFalse(ProtectedFile.unreadableIsDamaged(sealed, keys: noKey))
        XCTAssertFalse(ProtectedFile.unreadableIsDamaged(dir.appendingPathComponent("fehlt.json"), keys: keys))
    }

    /// Daneben gelegt und zurückgelegt: Bytes exakt, auch für die Portfolio-Datei.
    func testAsideAndBack() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("aside-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("portfolio.json")
        let bytes = Data([0, 1, 2, 250])
        try bytes.write(to: file)
        XCTAssertTrue(WatchedAddressStore.setAsideUnreadable(at: file))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        try Data("ersatz".utf8).write(to: file)
        XCTAssertTrue(WatchedAddressStore.putBackUnreadable(at: file))
        XCTAssertEqual(try Data(contentsOf: file), bytes)
        XCTAssertFalse(FileManager.default.fileExists(atPath: SharedStorage.unreadableCopyURL(of: file).path))
    }

    /// Zurückrollen: gemeldete Alarme bleiben gemeldet (Zustand aus dem Journal), nicht wieder scharf.
    func testJournalStateComesBack() throws {
        var pair = TestSupport.alarm(["condition": "PRICE_ABOVE", "threshold": 100.0])
        pair.referenceAt = 1_800_000_000_000
        pair.lastTriggeredAt = 1_800_000_000_001
        pair.lastTriggeredPrice = 101
        let reported = PortfolioAlarm(id: 7, kind: .VALUE_ABOVE, threshold: 1000, currency: "CHF", referenceAt: 5,
                                      lastTriggeredAt: 6, lastTriggeredValue: 1001)
        let state = BackupManager.JournalAlarmState(alarms: [pair], portfolioAlarms: [reported])
        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(state))
        let read = BackupManager.journalAlarmState([BackupManager.journalStateKey: encoded])
        XCTAssertEqual(read, state)
        // So kommt es aus der Sicherungsdatei: ohne Zustand
        var snapshot = SharedStorage.Snapshot()
        var plainPair = pair
        plainPair.referenceAt = 0
        plainPair.lastTriggeredAt = 0
        plainPair.lastTriggeredPrice = nil
        snapshot.alarms = [plainPair]
        snapshot.portfolioAlarms = [PortfolioAlarm(id: 7, kind: .VALUE_ABOVE, threshold: 1000, currency: "CHF")]
        BackupManager.applyJournalAlarmState(read, to: &snapshot)
        XCTAssertEqual(snapshot.alarms, [pair])
        XCTAssertEqual(snapshot.portfolioAlarms, [reported])
        // Sicherungsdatei (kein Journal): nichts übernommen
        XCTAssertNil(BackupManager.journalAlarmState([:]))
    }

    /// Datei ohne Passwort ohne eigenen Server; mit Passwort und im Journal mit.
    func testFileWithoutPasswordWithoutServer() throws {
        var settings = AppSettings()
        settings.addressServerBtc = "https://a.example/api"
        let input = BackupManager.ExportInput(createdAt: 1, watches: [], alarms: [], favorites: [:], settings: settings,
                                              portfolio: [], portfolioAlarms: [], watchedAddresses: [])
        func server(_ encrypted: Bool) throws -> Any? {
            let data = try BackupManager.plainExportData(input, encrypted: encrypted)
            let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
            return try XCTUnwrap(root["settings"] as? [String: Any])["addressServerBtc"]
        }
        XCTAssertTrue(try server(false) is NSNull)
        XCTAssertEqual(try server(true) as? String, "https://a.example/api")
        XCTAssertEqual(BackupManager.exportRoot(input, encrypted: true)["settings"].flatMap {
            ($0 as? [String: Any])?["addressServerBtc"] as? String
        }, "https://a.example/api")
    }
}
