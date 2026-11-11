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

    /// Mit Passwort neu gesetzter Server wird genannt (SET, mit Hostnamen) — ohne Passwort bleibt es bei KEPT (wie Android).
    func testNeuGesetzterServerWirdMitHostGenannt() {
        let value = "https://node.example.org:3006/api"
        XCTAssertEqual(BackupRestorePolicy.addressServerNote(value: value, current: nil, trusted: true), .SET)
        XCTAssertEqual(BackupRestorePolicy.addressServerSetHost(value: value, current: nil, trusted: true), "node.example.org")
        XCTAssertNil(BackupRestorePolicy.addressServerSetHost(value: value, current: nil, trusted: false))
        XCTAssertNil(BackupRestorePolicy.addressServerSetHost(value: "https://b.example", current: "https://a.example",
                                                              trusted: true))
        XCTAssertNil(BackupRestorePolicy.addressServerSetHost(value: "http://b.example", current: nil, trusted: true))
        XCTAssertNil(BackupRestorePolicy.addressServerSetHost(value: nil, current: nil, trusted: true))
    }

    /// Mit Passwort abgeschalteter Schutz wird genannt; ohne Passwort wird nichts abgeschaltet, also nichts genannt.
    func testAbgeschalteterSchutzNieStill() {
        func lowered(_ sys: Bool, _ sysNow: Bool, _ hide: Bool, _ hideNow: Bool, trusted: Bool)
            -> BackupRestorePolicy.LoweredProtection {
            BackupRestorePolicy.loweredProtection(wantedSystemBackup: sys, currentSystemBackup: sysNow,
                                                  wantedHideAmounts: hide, currentHideAmounts: hideNow, trusted: trusted)
        }
        XCTAssertEqual(lowered(true, false, false, true, trusted: true),
                       BackupRestorePolicy.LoweredProtection(systemBackupOn: true, amountsShown: true))
        XCTAssertEqual(lowered(true, false, false, true, trusted: false), BackupRestorePolicy.LoweredProtection())
        XCTAssertFalse(lowered(true, true, false, false, trusted: true).any)
        XCTAssertFalse(lowered(false, true, true, false, trusted: true).any)
    }

    /// Die Meldung nach dem Wiederherstellen nennt Hostnamen und abgeschalteten Schutz (`BackupSettingsPage.restoredText`).
    @MainActor
    func testMeldungNenntHostUndAbgeschaltetenSchutz() {
        var result = BackupManager.RestoreResult(watches: 1, alarms: 0, appLockKept: false)
        let plain = BackupSettingsPage.restoredText(result)
        result.addressServerNote = .SET
        result.addressServerHost = "node.example.org"
        result.lowered = BackupRestorePolicy.LoweredProtection(systemBackupOn: true, amountsShown: true)
        let text = BackupSettingsPage.restoredText(result)
        XCTAssertTrue(text.contains("node.example.org"))
        XCTAssertTrue(text.contains(L("backup_restore_system_backup_on")))
        XCTAssertTrue(text.contains(L("backup_restore_amounts_shown")))
        XCTAssertEqual(text.components(separatedBy: "\n").count, plain.components(separatedBy: "\n").count + 3)
        XCTAssertTrue(BackupSettingsPage.restoredAsDialog(result, text: text))
    }
}
