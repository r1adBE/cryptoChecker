import XCTest
@testable import CryptoChecker

/// Ziel der Glocke im Merklisten-Kopf — gleiche Fälle wie `AlarmBellRouteTest.kt`.
final class AlarmBellRouteTests: XCTestCase {

    func testWithAlarmsAlwaysOverview() {
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 1, watchCount: 0), .overview)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 1, watchCount: 1), .overview)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 7, watchCount: 12), .overview)
    }

    func testNotLoadedYetStaysOnOverview() {
        // Früher Tipp vor dem ersten Stand der Daten: nie ein falscher Sprung
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: nil, watchCount: 0), .overview)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: nil, watchCount: 3), .overview)
    }

    func testWithoutAlarmsLeadsToCreatingOne() {
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 1), .editor)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 2), .pickPair)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 40), .pickPair)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 0), .addCoin)
    }
}
