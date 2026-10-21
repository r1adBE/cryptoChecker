import XCTest
@testable import CryptoChecker

/// Einstellungen in der Sicherung hin und zurück (`Parity/backup_settings.json`, seit Build 102) — wie Android
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
        let restored = BackupManager.applySettings(settings, onto: AppSettings(), trusted: true)
        let written = BackupManager.settingsJson(restored)
        XCTAssertGreaterThanOrEqual(settings.count - excluded.count, 40)
        for (key, expected) in settings where !excluded.contains(key) {
            XCTAssertTrue(same(expected, written[key]), "\(key): \(expected) ≠ \(String(describing: written[key]))")
        }
    }

    func testUntrustedOnlyTowardsMoreProtection() {
        var current = AppSettings()
        current.hidePortfolioAmounts = true
        current.portfolioSystemBackup = false
        let less: [String: Any] = ["hidePortfolioAmounts": false, "portfolioSystemBackup": true]
        let untrusted = BackupManager.applySettings(less, onto: current, trusted: false)
        XCTAssertTrue(untrusted.hidePortfolioAmounts)
        XCTAssertFalse(untrusted.portfolioSystemBackup)
        let trusted = BackupManager.applySettings(less, onto: current, trusted: true)
        XCTAssertFalse(trusted.hidePortfolioAmounts)
        XCTAssertTrue(trusted.portfolioSystemBackup)
    }
}
