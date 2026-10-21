import XCTest
@testable import CryptoChecker

/// Doppeltipp beim Anlegen eines Alarms (`AlarmCreateGuard`): nur ein neuer Eintrag.
final class AlarmCreateGuardTests: XCTestCase {
    func testZweiterTippBeimAnlegenWirdIgnoriert() {
        var guardState = AlarmCreateGuard()
        XCTAssertTrue(guardState.begin(creating: true))
        XCTAssertFalse(guardState.begin(creating: true))
        XCTAssertTrue(guardState.used)
    }

    func testBearbeitenBleibtUngebremst() {
        var guardState = AlarmCreateGuard()
        XCTAssertTrue(guardState.begin(creating: false))
        XCTAssertTrue(guardState.begin(creating: false))
        XCTAssertFalse(guardState.used)
    }
}
