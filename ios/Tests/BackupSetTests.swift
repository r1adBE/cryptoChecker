import SwiftUI
import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus BackupAlarmsParityTests

/// Paar-Alarme aus der Sicherung (`Parity/backup_alarms.json`) — wie `BackupAlarmsParityTest`: unbekannte
/// Alarmart oder Schwelle ohne endlichen Wert ist ungültig, nie «Kurs über …» mit fremder Schwelle.
final class BackupAlarmsParityTests: XCTestCase {
    func testAlarmsMatchFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("backup_alarms")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 6)
        for c in cases {
            let alarm = c["alarm"] as? [String: Any] ?? [:]
            let parsed = try BackupManager.alarmFromJson(alarm)
            XCTAssertEqual(parsed != nil, c["valid"] as? Bool, c["name"] as? String ?? "")
            // Zeitraum nach dem Lesen (gekürzt, kein Überlauf)
            if let expected = (c["windowHours"] as? NSNumber)?.intValue {
                XCTAssertEqual(parsed?.windowHours, expected, c["name"] as? String ?? "")
            }
            // Fallend ≥ 100 % ausgeschaltet übernommen
            if let expectedEnabled = c["expectedEnabled"] as? Bool {
                XCTAssertEqual(parsed?.enabled, expectedEnabled, c["name"] as? String ?? "")
            }
        }
    }
}

// ---- aus BackupPasswordParityTests

/// Schwache/starke Passwörter und Versionen der Sicherung (`backup_passwords.json`); Gegenstück:
/// `BackupPasswordParityTest`.
final class BackupPasswordParityTests: XCTestCase {

    func testPasswordsMatchFixture() throws {
        let root = try TestSupport.fixture("backup_passwords")
        let weak = root["weak"] as? [String] ?? []
        let strong = root["strong"] as? [String] ?? []
        XCTAssertTrue(weak.count >= 20 && strong.count >= 6)
        for p in weak { XCTAssertTrue(BackupPasswordStrength.isWeak(p), p) }
        for p in strong { XCTAssertFalse(BackupPasswordStrength.isWeak(p), p) }
    }

    func testVersionsMatchFixture() throws {
        let root = try TestSupport.fixture("backup_passwords")
        for c in TestSupport.list(root["versions"]) {
            let name = c["name"] as? String ?? ""
            let raw: Any? = c["version"] is NSNull ? nil : c["version"]
            if let expected = c["result"] as? String {
                XCTAssertThrowsError(try BackupVersion.check(raw), name) { error in
                    switch error as? BackupVersion.Failure {
                    case .some(.newer): XCTAssertEqual(expected, "newer", name)
                    case .some(.invalid): XCTAssertEqual(expected, "invalid", name)
                    case .none: XCTFail("\(name): \(error)")
                    }
                }
            } else {
                let expected = Int(TestSupport.number(c["result"]) ?? -1)
                XCTAssertEqual(try BackupVersion.check(raw), expected, name)
            }
        }
    }
}

// ---- aus BackupSettingsParityTests

/// Einstellungen in der Sicherung hin und zurück (`Parity/backup_settings.json`) — wie Android
/// `BackupSettingsCodecTest`: alle Werte auf die Standardwerte gelesen (mit Passwort) und wieder geschrieben, gleich.
final class BackupSettingsParityTests: XCTestCase {

    /// Gleicher JSON-Wert: Zahlen nach Wert (30 = 30.0), Wahrheitswerte nur mit Wahrheitswerten, Objekte je Schlüssel.
    private func same(_ expected: Any?, _ actual: Any?) -> Bool {
        switch (expected, actual) {
        case (is NSNull, is NSNull): return true
        case let (e as String, a as String): return e == a
        case let (e as [String: Any], a as [String: Any]):
            return Set(e.keys) == Set(a.keys) && e.keys.allSatisfy { same(e[$0], a[$0]) }
        case let (e as NSNumber, a as NSNumber):
            let boolType = CFBooleanGetTypeID()
            let eBool = CFGetTypeID(e) == boolType
            let aBool = CFGetTypeID(a) == boolType || actual is Bool
            if eBool || aBool { return eBool == aBool && e.boolValue == a.boolValue }
            return e.doubleValue == a.doubleValue
        default: return false
        }
    }

    func testAllValuesComeBackUnchanged() throws {
        let data = try TestSupport.fixture("backup_settings")
        let settings = try XCTUnwrap(data["settings"] as? [String: Any])
        let excluded = Set(data["excludedOnIos"] as? [String] ?? [])
        let restored = try BackupManager.applySettings(settings, onto: AppSettings(), trusted: true)
        let written = BackupManager.settingsJson(restored)
        XCTAssertGreaterThanOrEqual(settings.count - excluded.count, 40)
        for (key, expected) in settings where !excluded.contains(key) {
            XCTAssertTrue(same(expected, written[key]), "\(key): \(expected) ≠ \(String(describing: written[key]))")
        }
    }

    func testUntrustedOnlyTowardsMoreProtection() throws {
        var current = AppSettings()
        current.hidePortfolioAmounts = true
        current.portfolioSystemBackup = false
        let less = TestSupport.settingsBackup(current, ["hidePortfolioAmounts": false, "portfolioSystemBackup": true])
        let untrusted = try BackupManager.applySettings(less, onto: current, trusted: false)
        XCTAssertTrue(untrusted.hidePortfolioAmounts)
        XCTAssertFalse(untrusted.portfolioSystemBackup)
        let trusted = try BackupManager.applySettings(less, onto: current, trusted: true)
        XCTAssertFalse(trusted.hidePortfolioAmounts)
        XCTAssertTrue(trusted.portfolioSystemBackup)
    }

    /// Wie Android `jedesGeschriebeneFeldIstPflicht`: Pflichtfelder = was geschrieben wird = Fixture; null nur dort,
    /// wo der Schreiber null schreibt.
    func testEveryWrittenFieldIsRequired() throws {
        let data = try TestSupport.fixture("backup_settings")
        let settings = try XCTUnwrap(data["settings"] as? [String: Any])
        let fields = Set(BackupManager.settingsFields.map(\.key))
        let written = BackupManager.settingsJson(AppSettings())
        XCTAssertEqual(Set(written.keys), fields)
        XCTAssertEqual(Set(settings.keys), fields)
        XCTAssertEqual(BackupManager.settingsFields.count, fields.count)
        try BackupManager.checkSettings(written)
        try BackupManager.checkSettings(settings)
        let nullable = Set(data["nullable"] as? [String] ?? [])
        for key in fields {
            var missing = settings
            missing.removeValue(forKey: key)
            XCTAssertThrowsError(try BackupManager.checkSettings(missing), "ohne \(key)")
            var null = settings
            null[key] = NSNull()
            if nullable.contains(key) {
                XCTAssertNoThrow(try BackupManager.checkSettings(null), key)
            } else {
                XCTAssertThrowsError(try BackupManager.checkSettings(null), "\(key) null")
            }
        }
    }

    /// Falsche Typen aus der Fixture machen die Sicherung ungültig (wie Android `falscherTypMachtDieSicherungUngueltig`).
    func testWrongTypeMakesTheBackupInvalid() throws {
        let data = try TestSupport.fixture("backup_settings")
        let settings = try XCTUnwrap(data["settings"] as? [String: Any])
        for c in TestSupport.list(data["invalid"]) {
            var changed = settings
            changed[c["key"] as? String ?? ""] = c["value"] ?? NSNull()
            XCTAssertThrowsError(try BackupManager.checkSettings(changed), c["name"] as? String ?? "")
        }
        var noAlerts = settings
        noAlerts["indicatorAlerts"] = [String: Any]()
        XCTAssertNoThrow(try BackupManager.checkSettings(noAlerts))
    }
}

// ---- aus BackupSettingsTextsTests

/// Gegenstück zu Android `BackupUnencryptedWarningTest` (Katalog statt res/values*) und Schriftgrösse der Seite
/// «Sichern & Wiederherstellen».
final class BackupSettingsTextsTests: XCTestCase {
    /// Einstellungen, die eine Sicherung ohne Passwort nicht schwächen darf — die Warnung nennt sie wörtlich.
    private static let labelKeys = [
        "settings_portfolio_lock",
        "portfolio_hide_amounts",
        "settings_address_server_title",
        "settings_portfolio_system_backup",
    ]

    /// Texte je Schlüssel und Sprache aus `Shared/Resources/Localizable.xcstrings` (gelesen neben dieser Quelle).
    private func catalog(file: StaticString = #filePath) throws -> [String: [String: String]] {
        let url = URL(fileURLWithPath: "\(file)").deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Shared/Resources/Localizable.xcstrings")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let strings = try XCTUnwrap(root["strings"] as? [String: Any])
        var result: [String: [String: String]] = [:]
        for (key, entry) in strings {
            let localizations = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            for (language, value) in localizations {
                let unit = (value as? [String: Any])?["stringUnit"] as? [String: Any]
                if let text = unit?["value"] as? String { result[key, default: [:]][language] = text }
            }
        }
        return result
    }

    func testUnencryptedWarningNamesSettingsInAllLanguages() throws {
        let texts = try catalog()
        let warnings = try XCTUnwrap(texts["backup_restore_unencrypted_warning"])
        XCTAssertEqual(warnings.count, 33)
        for (language, warning) in warnings {
            for key in Self.labelKeys {
                let label = try XCTUnwrap(texts[key]?[language], "\(language): \(key) fehlt")
                XCTAssertTrue(warning.lowercased().contains(label.lowercased()),
                              "\(language): Warnung nennt «\(label)» (\(key)) nicht wörtlich")
            }
        }
    }

    /// Hinweise nach dem Wiederherstellen nennen Sperre bzw. eigenen Server wie die Einstellung (wie Android).
    func testRestoreHintsNameSettingsInAllLanguages() throws {
        let texts = try catalog()
        let pairs = [("backup_restore_app_lock_kept", "settings_portfolio_lock"),
                     ("backup_restore_server_kept", "settings_address_server_title"),
                     ("backup_restore_server_public", "settings_address_server_title"),
                     ("backup_restore_server_changed", "settings_address_server_title")]
        for (hintKey, labelKey) in pairs {
            let hints = try XCTUnwrap(texts[hintKey], "\(hintKey) fehlt")
            XCTAssertEqual(hints.count, 33, hintKey)
            for (language, hint) in hints {
                let label = try XCTUnwrap(texts[labelKey]?[language], "\(language): \(labelKey) fehlt")
                XCTAssertTrue(hint.lowercased().contains(label.lowercased()),
                              "\(language): \(hintKey) nennt «\(label)» nicht wörtlich")
            }
        }
    }

    @MainActor
    func testFontScaleFollowsContentSize() {
        XCTAssertEqual(BackupSettingsPage.fontScale(.large), 1, accuracy: 0.001)
        XCTAssertGreaterThan(BackupSettingsPage.fontScale(.accessibilityExtraExtraExtraLarge), 1)
        // Kleinere Grade: höchstens 1 (die Grenze nutzt ohnehin max(1, Faktor))
        XCTAssertLessThanOrEqual(BackupSettingsPage.fontScale(.small), 1.001)
    }
}
