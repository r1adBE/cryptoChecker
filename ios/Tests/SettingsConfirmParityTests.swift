import XCTest
@testable import CryptoChecker

/// Rückfragen in Einstellungen › Portfolio: gemeinsame Fälle mit Android (`SettingsConfirmParityTest.kt`,
/// `settings_confirm.json`).
final class SettingsConfirmParityTests: XCTestCase {
    func testCostMethodMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("settings_confirm")["costMethod"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let current = try XCTUnwrap(CostMethod(rawValue: c["current"] as? String ?? ""))
            let chosen = try XCTUnwrap(CostMethod(rawValue: c["chosen"] as? String ?? ""))
            XCTAssertEqual(SettingsConfirm.costMethod(current: current, chosen: chosen), c["expected"] as? Bool,
                           "\(current) → \(chosen)")
        }
    }

    func testSystemBackupMatchesFixture() throws {
        for c in TestSupport.list(try TestSupport.fixture("settings_confirm")["systemBackup"]) {
            XCTAssertEqual(SettingsConfirm.systemBackup(enabling: c["enabling"] as? Bool ?? false), c["expected"] as? Bool)
        }
    }
}
