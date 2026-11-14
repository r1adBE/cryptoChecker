import XCTest
@testable import CryptoChecker

/// Sichern/Wiederherstellen bei nicht lesbarem Stand (S3-01), Journal mit Alarm-Zustand (S3-09) und Datei ohne Passwort
/// ohne eigenen Bitcoin-Server (S3-04). Gegenstück Android: `BackupRestorePolicyTest.kt` (Ablehnen),
/// `BackupSettingsCodecTest.dateiOhnePasswortOhneServer`.
final class BackupStorageKeptTests: XCTestCase {
    func testEinTeilNichtLesbarLehntAb() {
        XCTAssertFalse(BackupManager.storageKept(watchlistLocked: false, portfolioUnreadable: false,
                                                 addressesUnreadable: false, portfolioAlarmsUnreadable: false))
        XCTAssertTrue(BackupManager.storageKept(watchlistLocked: true, portfolioUnreadable: false,
                                                addressesUnreadable: false, portfolioAlarmsUnreadable: false))
        XCTAssertTrue(BackupManager.storageKept(watchlistLocked: false, portfolioUnreadable: true,
                                                addressesUnreadable: false, portfolioAlarmsUnreadable: false))
        XCTAssertTrue(BackupManager.storageKept(watchlistLocked: false, portfolioUnreadable: false,
                                                addressesUnreadable: true, portfolioAlarmsUnreadable: false))
        XCTAssertTrue(BackupManager.storageKept(watchlistLocked: false, portfolioUnreadable: false,
                                                addressesUnreadable: false, portfolioAlarmsUnreadable: true))
    }

    /// Sichern (Start und vor dem Schreiben) und Wiederherstellen fragen vorher; `restore` lehnt selbst auch ab.
    func testSeiteUndRestorePruefen() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let page = try String(contentsOf: root.appendingPathComponent("App/Features/Settings/SettingsDataPages.swift"),
                              encoding: .utf8)
        let guardLine = "guard !BackupManager.storageKept(data) else { return fail(\"backup_storage_kept\") }"
        XCTAssertEqual(page.components(separatedBy: guardLine).count - 1, 3)
        let manager = try String(contentsOf: root.appendingPathComponent("App/Services/BackupManager.swift"), encoding: .utf8)
        XCTAssertTrue(manager.contains("if journal && storageKept(data) { throw Failure.storageKept }"))
        let restore = try String(contentsOf: root.appendingPathComponent("App/Services/BackupManager+Restore.swift"),
                                 encoding: .utf8)
        XCTAssertTrue(restore.contains("try requireJournal(try? journalData(data)"))
    }

    /// Zurückrollen: gemeldete Alarme bleiben gemeldet (Zustand aus dem Journal), nicht wieder scharf.
    func testJournalZustandKommtZurueck() throws {
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
    func testDateiOhnePasswortOhneServer() throws {
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
