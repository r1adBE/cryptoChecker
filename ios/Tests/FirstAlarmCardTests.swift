import XCTest
@testable import CryptoChecker

/// Karte «Ersten Alarm setzen» — gleiche Fälle wie `FirstAlarmCardTest.kt`.
final class FirstAlarmCardTests: XCTestCase {

    func testShownWithPairsAndNoAlarm() {
        XCTAssertTrue(FirstAlarmCard.shouldShow(alarmCount: 0, watchCount: 1, dismissed: false, busy: false))
        XCTAssertTrue(FirstAlarmCard.shouldShow(alarmCount: 0, watchCount: 25, dismissed: false, busy: false))
    }

    func testHiddenOnceAnAlarmExistsOrBeforeLoading() {
        XCTAssertFalse(FirstAlarmCard.shouldShow(alarmCount: 1, watchCount: 3, dismissed: false, busy: false))
        XCTAssertFalse(FirstAlarmCard.shouldShow(alarmCount: nil, watchCount: 3, dismissed: false, busy: false))
    }

    func testHiddenWithoutPairsAfterDismissingOrWhileBusy() {
        XCTAssertFalse(FirstAlarmCard.shouldShow(alarmCount: 0, watchCount: 0, dismissed: false, busy: false))
        XCTAssertFalse(FirstAlarmCard.shouldShow(alarmCount: 0, watchCount: 3, dismissed: true, busy: false))
        XCTAssertFalse(FirstAlarmCard.shouldShow(alarmCount: 0, watchCount: 3, dismissed: false, busy: true))
    }
}
