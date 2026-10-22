import XCTest
@testable import CryptoChecker

/// Paar-Alarme aus der Sicherung (`Parity/backup_alarms.json`) — wie `BackupAlarmsParityTest.kt`: unbekannte
/// Alarmart oder Schwelle ohne endlichen Wert ist ungültig, nie «Kurs über …» mit fremder Schwelle.
final class BackupAlarmsParityTests: XCTestCase {
    func testAlarmsMatchFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("backup_alarms")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 6)
        for c in cases {
            let alarm = c["alarm"] as? [String: Any] ?? [:]
            let parsed = BackupManager.alarmFromJson(alarm)
            XCTAssertEqual(parsed != nil, c["valid"] as? Bool, c["name"] as? String ?? "")
            // Zeitraum nach dem Lesen (gekürzt, kein Überlauf)
            if let expected = (c["windowHours"] as? NSNumber)?.intValue {
                XCTAssertEqual(parsed?.windowHours, expected, c["name"] as? String ?? "")
            }
            // Seit Build 104: fallend ≥ 100 % ausgeschaltet übernommen
            if let expectedEnabled = c["expectedEnabled"] as? Bool {
                XCTAssertEqual(parsed?.enabled, expectedEnabled, c["name"] as? String ?? "")
            }
        }
    }
}
