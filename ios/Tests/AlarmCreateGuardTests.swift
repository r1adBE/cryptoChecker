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

    /// Bearbeiten sperrt ebenfalls; «Abbrechen» in der Rückfrage gibt wieder frei.
    func testBearbeitenSperrtBisAbbrechen() {
        var guardState = AlarmCreateGuard()
        XCTAssertTrue(guardState.begin(creating: false))
        XCTAssertFalse(guardState.begin(creating: false))
        guardState.reset()
        XCTAssertTrue(guardState.begin(creating: false))
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
