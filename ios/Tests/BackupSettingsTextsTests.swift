import SwiftUI
import XCTest
@testable import CryptoChecker

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
        XCTAssertEqual(warnings.count, 31)
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
            XCTAssertEqual(hints.count, 31, hintKey)
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
