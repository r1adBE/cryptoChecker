import XCTest
@testable import CryptoChecker

/// Wie `AlarmPulseTest.kt`.
final class AlarmPulseTests: XCTestCase {

    func testOnlyNewTriggerWhileOpenPulses() {
        // Erster Stand beim Öffnen: schon vorhandene Auslösungen pulsieren nicht
        XCTAssertFalse(AlarmPulse.isNew(previous: nil, current: 1_000))
        XCTAssertFalse(AlarmPulse.isNew(previous: nil, current: nil))
        // Neue Auslösung
        XCTAssertTrue(AlarmPulse.isNew(previous: 1_000, current: 2_000))
        XCTAssertTrue(AlarmPulse.isNew(previous: 0, current: 2_000))
        // Unverändert, zurückgesetzt oder noch nie
        XCTAssertFalse(AlarmPulse.isNew(previous: 2_000, current: 2_000))
        XCTAssertFalse(AlarmPulse.isNew(previous: 2_000, current: 0))
        XCTAssertFalse(AlarmPulse.isNew(previous: 0, current: 0))
        XCTAssertFalse(AlarmPulse.isNew(previous: 1_000, current: nil))
    }

    func testPulseIsShortAndSubtle() {
        XCTAssertTrue((0.1...0.25).contains(AlarmPulse.seconds))
        XCTAssertTrue(AlarmPulse.scale > 1 && AlarmPulse.scale <= 1.25)
    }
}
