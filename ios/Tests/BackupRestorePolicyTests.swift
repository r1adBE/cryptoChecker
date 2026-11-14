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

    /// Mit Passwort neu gesetzter bzw. ersetzter Server wird mit Hostnamen genannt — ohne Passwort bleibt es bei KEPT
    /// (wie Android).
    func testNeuGesetzterServerWirdMitHostGenannt() {
        let value = "https://node.example.org:3006/api"
        XCTAssertEqual(BackupRestorePolicy.addressServerNote(value: value, current: nil, trusted: true), .SET)
        XCTAssertEqual(BackupRestorePolicy.addressServerHost(value: value, current: nil, trusted: true), "node.example.org")
        XCTAssertNil(BackupRestorePolicy.addressServerHost(value: value, current: nil, trusted: false))
        // Ersetzt: ebenfalls mit Hostnamen (wohin die Adressen ab jetzt gehen)
        XCTAssertEqual(BackupRestorePolicy.addressServerHost(value: "https://b.example", current: "https://a.example",
                                                             trusted: true), "b.example")
        XCTAssertNil(BackupRestorePolicy.addressServerHost(value: "https://b.example", current: "https://a.example",
                                                           trusted: false))
        XCTAssertNil(BackupRestorePolicy.addressServerHost(value: nil, current: "https://a.example", trusted: true))
        XCTAssertNil(BackupRestorePolicy.addressServerHost(value: "http://b.example", current: nil, trusted: true))
        XCTAssertNil(BackupRestorePolicy.addressServerHost(value: nil, current: nil, trusted: true))
    }

    /// Mit Passwort abgeschalteter Schutz wird genannt; ohne Passwort wird nichts abgeschaltet, also nichts genannt.
    func testAbgeschalteterSchutzNieStill() {
        func lowered(_ sys: Bool, _ sysNow: Bool, _ hide: Bool, _ hideNow: Bool, trusted: Bool, lock: Bool = false,
                     lockNow: Bool = false) -> BackupRestorePolicy.LoweredProtection {
            BackupRestorePolicy.loweredProtection(wantedSystemBackup: sys, currentSystemBackup: sysNow,
                                                  wantedHideAmounts: hide, currentHideAmounts: hideNow, trusted: trusted,
                                                  wantedAppLock: lock, currentAppLock: lockNow)
        }
        XCTAssertEqual(lowered(true, false, false, true, trusted: true),
                       BackupRestorePolicy.LoweredProtection(systemBackupOn: true, amountsShown: true))
        XCTAssertEqual(lowered(true, false, false, true, trusted: false), BackupRestorePolicy.LoweredProtection())
        XCTAssertFalse(lowered(true, true, false, false, trusted: true).any)
        XCTAssertFalse(lowered(false, true, true, false, trusted: true).any)
        // Portfolio-Sperre: mit Passwort ausgeschaltet → genannt; ohne Passwort bleibt sie an (appLockKept), nichts genannt
        XCTAssertEqual(lowered(false, false, true, true, trusted: true, lockNow: true),
                       BackupRestorePolicy.LoweredProtection(lockOff: true))
        XCTAssertTrue(lowered(false, false, true, true, trusted: true, lockNow: true).any)
        XCTAssertEqual(lowered(false, false, true, true, trusted: false, lockNow: true), BackupRestorePolicy.LoweredProtection())
        XCTAssertEqual(lowered(false, false, true, true, trusted: true, lock: true, lockNow: true),
                       BackupRestorePolicy.LoweredProtection())
    }

    /// Die Meldung nach dem Wiederherstellen nennt Hostnamen und abgeschalteten Schutz (`BackupSettingsPage.restoredText`).
    @MainActor
    func testMeldungNenntHostUndAbgeschaltetenSchutz() {
        var result = BackupManager.RestoreResult(watches: 1, alarms: 0, appLockKept: false)
        let plain = BackupSettingsPage.restoredText(result)
        result.addressServerNote = .SET
        result.addressServerHost = "node.example.org"
        result.lowered = BackupRestorePolicy.LoweredProtection(systemBackupOn: true, amountsShown: true, lockOff: true)
        let text = BackupSettingsPage.restoredText(result)
        XCTAssertTrue(text.contains("node.example.org"))
        XCTAssertTrue(text.contains(L("backup_restore_system_backup_on")))
        XCTAssertTrue(text.contains(L("backup_restore_amounts_shown")))
        XCTAssertTrue(text.contains(L("backup_restore_app_lock_off")))
        XCTAssertEqual(text.components(separatedBy: "\n").count, plain.components(separatedBy: "\n").count + 4)
        XCTAssertTrue(BackupSettingsPage.restoredAsDialog(result, text: text))
    }

    /// Doppelte Kennungen (S3-08): wie ungültige gezählt, nie still ersetzt (backup_duplicate_ids.json).
    func testDuplicateIdsCountAsInvalid() throws {
        let cases = TestSupport.list(try TestSupport.fixture("backup_duplicate_ids")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let ids = (c["ids"] as? [NSNumber] ?? []).map(\.int64Value)
            let got = BackupRestorePolicy.keepFirstIds(ids)
            XCTAssertEqual(got, c["keep"] as? [Bool] ?? [], name)
            XCTAssertEqual(got.filter { !$0 }.count, c["invalid"] as? Int, name)
            let unique = BackupRestorePolicy.uniqueIds((items: ids, invalid: 2), id: { $0 })
            XCTAssertEqual(unique.invalid, 2 + (c["invalid"] as? Int ?? 0), name)
        }
    }
}
