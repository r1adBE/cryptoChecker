import XCTest
@testable import CryptoChecker

/// «zuletzt um …»: heute nur die Uhrzeit, an einem anderen Tag mit Datum (`MomentText`). Gegenstück: `MomentTextTest.kt`.
final class MomentTextTests: XCTestCase {
    private let zone = TimeZone(identifier: "Europe/Zurich")!

    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int, _ s: Int = 0) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let date = calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min, second: s))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    func testGleicherTagNachKalender() {
        let now = at(2026, 10, 12, 0, 30)
        XCTAssertTrue(MomentText.isSameDay(at(2026, 10, 12, 0, 5), now, timeZone: zone))
        // Vor Mitternacht = gestern, auch wenn nur 40 Minuten her
        XCTAssertFalse(MomentText.isSameDay(at(2026, 10, 11, 23, 50), now, timeZone: zone))
    }

    func testHeuteNurUhrzeitSonstMitDatum() {
        let now = at(2026, 10, 12, 18, 0)
        let de = Locale(identifier: "de_DE")
        XCTAssertEqual(MomentText.moment(at(2026, 10, 12, 14, 2, 11), now: now, timeZone: zone, locale: de), "14:02:11")
        let earlier = MomentText.moment(at(2026, 10, 9, 14, 2, 11), now: now, timeZone: zone, locale: de)
        XCTAssertTrue(earlier.contains("09.10.26"), earlier)
        XCTAssertTrue(earlier.contains("14:02"), earlier)
        XCTAssertFalse(earlier.contains("14:02:11"), earlier)
        XCTAssertEqual(MomentText.moment(0, now: now, timeZone: zone, locale: de), "—")
    }
}
