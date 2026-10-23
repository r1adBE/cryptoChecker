import XCTest
@testable import CryptoChecker

/// Review FUN3-02/UX3-06: «fällt um ≥ 100 %» lässt sich per Schalter nicht einschalten
/// (`Parity/alarm_toggle.json`) — wie `AlarmToggleParityTest.kt`.
final class AlarmToggleParityTests: XCTestCase {
    func testToggleMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("alarm_toggle")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let condition = try XCTUnwrap(AlarmCondition(rawValue: c["condition"] as? String ?? ""), name)
            let threshold = (c["threshold"] as? NSNumber)?.doubleValue ?? .nan
            XCTAssertEqual(BackupManager.toggleAllowed(condition, threshold, enable: c["enable"] as? Bool ?? false),
                           c["allowed"] as? Bool, name)
        }
    }

    /// Schalter (Karte, Übersicht) und Schreibweg nutzen dieselbe Regel.
    func testSwitchesAndWritePathUseTheRule() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App")
        func text(_ path: String) throws -> String {
            try String(contentsOf: app.appendingPathComponent(path), encoding: .utf8)
        }
        XCTAssertTrue(try text("Features/Alarms/AlarmCard.swift").contains("enabled: BackupManager.toggleAllowed("))
        XCTAssertTrue(try text("Features/Alarms/AlarmsOverviewScreen.swift").contains("enabled: BackupManager.toggleAllowed("))
        let alarms = try text("Services/AppData+Alarms.swift")
        let toggle = alarms.components(separatedBy: "func setAlarmEnabled(").last ?? ""
        XCTAssertTrue(toggle.components(separatedBy: "mutate(").first?.contains("BackupManager.toggleAllowed(") == true)
    }
}
