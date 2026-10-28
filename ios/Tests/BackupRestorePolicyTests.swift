import XCTest
@testable import CryptoChecker

/// Wie `BackupRestorePolicyTest.kt`: gleiche Fälle auf beiden Seiten; dazu der einmalige Hinweis
/// `PortfolioRestoreNotice` (nur iOS: Prüfsumme der Datei).
final class BackupRestorePolicyTests: XCTestCase {
    private typealias D = BackupRestorePolicy.AppLockDecision

    func testUnencryptedBackupNeverTurnsLockOff() {
        // Sperre an, Sicherung ohne Passwort will sie aus → bleibt an, Hinweis
        XCTAssertEqual(BackupRestorePolicy.appLock(wanted: false, current: true, encrypted: false, canAuthenticate: true),
                       D(apply: nil, keptOn: true))
        // Mit Passwort: aus wie gewünscht
        XCTAssertEqual(BackupRestorePolicy.appLock(wanted: false, current: true, encrypted: true, canAuthenticate: true),
                       D(apply: false, keptOn: false))
        // Sperre schon aus: nichts zu schützen
        XCTAssertEqual(BackupRestorePolicy.appLock(wanted: false, current: false, encrypted: false, canAuthenticate: true),
                       D(apply: false, keptOn: false))
    }

    func testLockOnOnlyIfDeviceCanUnlock() {
        XCTAssertEqual(BackupRestorePolicy.appLock(wanted: true, current: false, encrypted: false, canAuthenticate: true),
                       D(apply: true, keptOn: false))
        XCTAssertEqual(BackupRestorePolicy.appLock(wanted: true, current: false, encrypted: true, canAuthenticate: false),
                       D(apply: nil, keptOn: false))
    }

    func testNoticeTotalSumsWithoutOverflow() {
        XCTAssertEqual(BackupRestorePolicy.noticeTotal(pending: 2, added: 3), 5)
        XCTAssertEqual(BackupRestorePolicy.noticeTotal(pending: -1, added: 3), 3)
        XCTAssertEqual(BackupRestorePolicy.noticeTotal(pending: Int(Int32.max), added: 1), Int(Int32.max))
    }

    func testRestoreNoticeOncePerFile() throws {
        let suite = "PortfolioRestoreNoticeTests"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        let file = Data(#"{"transactions":[{"x":1},{"coin":"BTC"}]}"#.utf8)
        XCTAssertEqual(PortfolioRestoreNotice.take(defaults: defaults), 0)
        PortfolioRestoreNotice.record(dropped: 2, fileData: file, defaults: defaults)
        // Gleiche Datei beim nächsten Start: nicht nochmals
        PortfolioRestoreNotice.record(dropped: 2, fileData: file, defaults: defaults)
        XCTAssertEqual(PortfolioRestoreNotice.take(defaults: defaults), 2)
        XCTAssertEqual(PortfolioRestoreNotice.take(defaults: defaults), 0)
        PortfolioRestoreNotice.record(dropped: 2, fileData: file, defaults: defaults)
        XCTAssertEqual(PortfolioRestoreNotice.take(defaults: defaults), 0)
        // Andere Datei: wieder melden; ohne verworfene Einträge nie
        PortfolioRestoreNotice.record(dropped: 0, fileData: Data("{}".utf8), defaults: defaults)
        XCTAssertEqual(PortfolioRestoreNotice.take(defaults: defaults), 0)
        PortfolioRestoreNotice.record(dropped: 1, fileData: Data("{ }".utf8), defaults: defaults)
        XCTAssertEqual(PortfolioRestoreNotice.take(defaults: defaults), 1)
    }

    func testSignatureDiffersByContent() {
        XCTAssertEqual(PortfolioRestoreNotice.signature(Data()), "0-cbf29ce484222325")
        XCTAssertNotEqual(PortfolioRestoreNotice.signature(Data("a".utf8)), PortfolioRestoreNotice.signature(Data("b".utf8)))
        XCTAssertTrue(PortfolioRestoreNotice.shouldRecord(dropped: 1, signature: "x", seen: nil))
        XCTAssertFalse(PortfolioRestoreNotice.shouldRecord(dropped: 1, signature: "x", seen: "x"))
        XCTAssertFalse(PortfolioRestoreNotice.shouldRecord(dropped: 0, signature: "x", seen: nil))
    }

    /// Ohne Passwort: «Portfolio in Systemsicherung» nur aus, nie ein (wie Android).
    func testUnverschluesseltSchaltetSystemsicherungNieEin() {
        XCTAssertNil(BackupRestorePolicy.systemBackup(wanted: true, current: false, trusted: false))
        XCTAssertEqual(BackupRestorePolicy.systemBackup(wanted: false, current: true, trusted: false), false)
        XCTAssertEqual(BackupRestorePolicy.systemBackup(wanted: true, current: true, trusted: false), true)
        XCTAssertEqual(BackupRestorePolicy.systemBackup(wanted: true, current: false, trusted: true), true)
        XCTAssertEqual(BackupRestorePolicy.systemBackup(wanted: false, current: true, trusted: true), false)
    }

    /// Ohne Passwort: «Beträge verbergen» nur ein, nie aus (wie Android).
    func testUnverschluesseltSchaltetBetraegeVerbergenNieAus() {
        XCTAssertNil(BackupRestorePolicy.hideAmounts(wanted: false, current: true, trusted: false))
        XCTAssertEqual(BackupRestorePolicy.hideAmounts(wanted: true, current: false, trusted: false), true)
        XCTAssertEqual(BackupRestorePolicy.hideAmounts(wanted: false, current: false, trusted: false), false)
        XCTAssertEqual(BackupRestorePolicy.hideAmounts(wanted: false, current: true, trusted: true), false)
        XCTAssertEqual(BackupRestorePolicy.hideAmounts(wanted: true, current: false, trusted: true), true)
    }

    /// `applySettings` hält sich daran: ohne Passwort bleiben Systemsicherung aus und Beträge verborgen.
    func testApplySettingsOhnePasswortNurMehrSchutz() throws {
        var current = AppSettings()
        current.portfolioSystemBackup = false
        current.hidePortfolioAmounts = true
        let backup = TestSupport.settingsBackup(current, ["portfolioSystemBackup": true, "hidePortfolioAmounts": false])
        let untrusted = try BackupManager.applySettings(backup, onto: current, trusted: false)
        XCTAssertFalse(untrusted.portfolioSystemBackup)
        XCTAssertTrue(untrusted.hidePortfolioAmounts)
        let trusted = try BackupManager.applySettings(backup, onto: current, trusted: true)
        XCTAssertTrue(trusted.portfolioSystemBackup)
        XCTAssertFalse(trusted.hidePortfolioAmounts)
    }
}
