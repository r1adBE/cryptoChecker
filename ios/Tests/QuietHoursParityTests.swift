import XCTest
@testable import CryptoChecker

/// Nachtruhe als gemeinsame Testfälle (`quiet_hours.json`, Quelle `android/testdata/parity`) — wie
/// `QuietHoursParityTest.kt`: Grenzen, Zeitraum über Mitternacht, Beginn = Ende, ungültige Zeiten und die Minute des
/// Tages an den Sommerzeit-Wechseln (Europe/Zurich, America/New_York).
final class QuietHoursParityTests: XCTestCase {
    private typealias S = TestSupport

    private func calendar(_ zone: Any?) throws -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        let identifier = zone as? String ?? ""
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: identifier), "zone \(identifier)")
        return calendar
    }

    private func date(_ millis: Any?) -> Date {
        Date(timeIntervalSince1970: (S.number(millis) ?? 0) / 1000)
    }

    func testIsQuietAndHoldsAlarms() throws {
        let cases = S.list(try S.fixture("quiet_hours")["isQuiet"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let enabled = c["enabled"] as? Bool ?? false
            let start = Int(S.number(c["start"]) ?? 0)
            let end = Int(S.number(c["end"]) ?? 0)
            let minute = Int(S.number(c["minute"]) ?? 0)
            let want = c["quiet"] as? Bool
            let quiet = QuietHours.isQuiet(enabled: enabled, start: start, end: end, minuteOfDay: minute)
            XCTAssertEqual(quiet, want, name)
            XCTAssertEqual(QuietHours.holdsAlarms(enabled: enabled, start: start, end: end, minuteOfDay: minute), want,
                           "\(name) (holdsAlarms)")
        }
    }

    func testMinuteOfDayAcrossDaylightSavingChanges() throws {
        for c in S.list(try S.fixture("quiet_hours")["minuteOfDay"]) {
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(Int64(S.number(c["millis"]) ?? 0), S.millis(c["utc"]), "\(name): utc")
            let minute = QuietHours.minuteOfDay(date(c["millis"]), calendar: try calendar(c["zone"]))
            XCTAssertEqual(minute, Int(S.number(c["minute"]) ?? -1), name)
        }
    }

    func testQuietAtInstantInZone() throws {
        for c in S.list(try S.fixture("quiet_hours")["quietAt"]) {
            let minute = QuietHours.minuteOfDay(date(c["millis"]), calendar: try calendar(c["zone"]))
            let quiet = QuietHours.isQuiet(enabled: true, start: Int(S.number(c["start"]) ?? 0),
                                           end: Int(S.number(c["end"]) ?? 0), minuteOfDay: minute)
            XCTAssertEqual(quiet, c["quiet"] as? Bool, c["name"] as? String ?? "")
        }
    }
}
