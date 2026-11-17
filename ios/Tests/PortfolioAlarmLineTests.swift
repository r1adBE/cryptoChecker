import XCTest
@testable import CryptoChecker

/// Portfolio-Alarm mit ungültiger Schwelle nennt «löschen und neu anlegen» (kein Editor), nicht «anpassen». Wie
/// Android `PortfolioAlarmInvalidHintTest.kt`.
final class PortfolioAlarmLineTests: XCTestCase {
    func testInvalidThresholdNamesDeleteAndRecreate() {
        XCTAssertEqual(PortfolioAlarmLine.key(invalid: true, repeating: true), "portfolio_alarm_threshold_invalid_recreate")
        XCTAssertEqual(PortfolioAlarmLine.key(invalid: true, repeating: false), "portfolio_alarm_threshold_invalid_recreate")
        XCTAssertNotEqual(PortfolioAlarmLine.key(invalid: true, repeating: false), "alarm_threshold_invalid_fix")
        XCTAssertEqual(PortfolioAlarmLine.key(invalid: false, repeating: true), "alarm_repeating")
        XCTAssertEqual(PortfolioAlarmLine.key(invalid: false, repeating: false), "alarm_once")
        XCTAssertNotEqual(L(PortfolioAlarmLine.key(invalid: true, repeating: false)), "portfolio_alarm_threshold_invalid_recreate",
                          "Text fehlt in Localizable")
    }
}
