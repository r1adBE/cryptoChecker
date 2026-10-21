import XCTest
@testable import CryptoChecker

/// «Portfolio in Systemsicherung»: das Backup-Merkmal der Portfolio-Datei (`BackupExclusion`,
/// `PortfolioStore.applyBackupPolicy`). Läuft auf einer eigenen Datei im temporären Ordner.
final class BackupExclusionTests: XCTestCase {

    private var url: URL!

    override func setUpWithError() throws {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent("backup-exclusion-\(UUID().uuidString).json")
        try Data("{}".utf8).write(to: url, options: [.atomic])
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: url)
    }

    func testExcludeAndIncludeAgain() {
        XCTAssertTrue(BackupExclusion.set(excluded: true, for: url))
        XCTAssertEqual(BackupExclusion.isExcluded(url), true)
        XCTAssertTrue(BackupExclusion.set(excluded: false, for: url))
        XCTAssertEqual(BackupExclusion.isExcluded(url), false)
    }

    /// Ein atomares Schreiben ersetzt die Datei (das Merkmal geht dabei verloren) — darum setzt
    /// `PortfolioStore.save` es nach jedem Schreiben neu; das muss auf der neuen Datei greifen.
    func testFlagCanBeSetAgainAfterAtomicWrite() throws {
        XCTAssertTrue(BackupExclusion.set(excluded: true, for: url))
        try Data("{\"transactions\":[]}".utf8).write(to: url, options: [.atomic])
        XCTAssertTrue(BackupExclusion.set(excluded: true, for: url))
        XCTAssertEqual(BackupExclusion.isExcluded(url), true)
    }

    func testMissingFileIsIgnored() {
        let missing = url.appendingPathExtension("missing")
        XCTAssertFalse(BackupExclusion.set(excluded: true, for: missing))
        XCTAssertNil(BackupExclusion.isExcluded(missing))
    }

    /// Wie `PortfolioLockPolicyTest.kt`: Einschalten nur entsperrt, Ausschalten immer.
    func testEnablingNeedsUnlockWhileLocked() {
        XCTAssertTrue(PortfolioLockPolicy.systemBackupNeedsUnlock(locked: true, enabling: true))
        XCTAssertFalse(PortfolioLockPolicy.systemBackupNeedsUnlock(locked: true, enabling: false))
        XCTAssertFalse(PortfolioLockPolicy.systemBackupNeedsUnlock(locked: false, enabling: true))
    }

    func testDefaultKeepsPortfolioOutOfBackups() throws {
        XCTAssertFalse(AppSettings().portfolioSystemBackup)
        // Ältere Einstellungen ohne den Schlüssel: Standard (aus)
        let old = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertFalse(old.portfolioSystemBackup)
        var on = AppSettings()
        on.portfolioSystemBackup = true
        let roundTrip = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(on))
        XCTAssertTrue(roundTrip.portfolioSystemBackup)
    }

    /// Merker der Bestandsübernahme reist mit der Portfolio-Datei; alte Dateien ohne Feld = false.
    func testHoldingsImportDoneRoundTrip() throws {
        let old = try JSONDecoder().decode(PortfolioFile.self, from: Data(#"{"transactions":[],"nextId":3}"#.utf8))
        XCTAssertFalse(old.holdingsImportDone)
        var file = PortfolioFile(transactions: [], nextId: 3)
        file.holdingsImportDone = true
        let back = try JSONDecoder().decode(PortfolioFile.self, from: JSONEncoder().encode(file))
        XCTAssertTrue(back.holdingsImportDone)
        XCTAssertEqual(back.nextId, 3)
    }
}

/// Seit Build 93: Merkmal unverschlüsselt gebliebener Dateien (`PlainBackupExclusion`), reine Regeln.
final class PlainBackupExclusionTests: XCTestCase {
    func testRememberedBeforeIsKeptFromFirstTime() {
        XCTAssertFalse(PlainBackupExclusion.rememberedBefore(current: false, remembered: nil))
        XCTAssertTrue(PlainBackupExclusion.rememberedBefore(current: true, remembered: nil))
        // Späterer Start: aktuell schon das eigene «ausgenommen», gemerkt bleibt das von vorher
        XCTAssertFalse(PlainBackupExclusion.rememberedBefore(current: true, remembered: false))
        XCTAssertFalse(PlainBackupExclusion.rememberedBefore(current: nil, remembered: nil))
    }

    func testAfterSealingPrefersRemembered() {
        XCTAssertFalse(PlainBackupExclusion.afterSealing(current: true, remembered: false))
        XCTAssertTrue(PlainBackupExclusion.afterSealing(current: false, remembered: true))
        // Nichts gemerkt: wie bis Build 92 das aktuelle Merkmal
        XCTAssertTrue(PlainBackupExclusion.afterSealing(current: true, remembered: nil))
        XCTAssertFalse(PlainBackupExclusion.afterSealing(current: false, remembered: nil))
    }

    func testPolicyNeverIncludesPlainFiles() {
        XCTAssertFalse(PlainBackupExclusion.policyExcluded(includeInBackup: true, plain: false))
        XCTAssertTrue(PlainBackupExclusion.policyExcluded(includeInBackup: true, plain: true))
        XCTAssertTrue(PlainBackupExclusion.policyExcluded(includeInBackup: false, plain: false))
        XCTAssertTrue(PlainBackupExclusion.policyExcluded(includeInBackup: false, plain: true))
    }
}
