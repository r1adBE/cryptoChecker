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

/// Portfolio-Alarm (`PortfolioAlarmSheet`): legt immer neu an — ein zweiter Tipp auf «Speichern» bleibt ohne Wirkung.
final class PortfolioAlarmCreateGuardTests: XCTestCase {
    func testZweimalSpeichernGibtEinenAlarm() {
        var guardState = AlarmCreateGuard()
        var saved = 0
        for _ in 0..<2 where guardState.begin(creating: true) { saved += 1 }
        XCTAssertEqual(saved, 1)
    }
}
