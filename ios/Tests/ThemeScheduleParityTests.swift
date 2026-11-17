import XCTest
@testable import CryptoChecker

/// Darstellung «Nach Uhrzeit» als gemeinsame Testfälle (`theme_schedule.json`, Quelle `android/testdata/parity`) — wie
/// `ThemeScheduleParityTest.kt`: Zeitraum über Mitternacht, gleiche und ungültige Zeiten, Vorrang vor fest Hell/Dunkel
/// und der nächste Wechsel über die Sommerzeit.
final class ThemeScheduleParityTests: XCTestCase {
    private typealias S = TestSupport

    private func int(_ value: Any?) -> Int { Int(S.number(value) ?? -1) }

    func testIsDarkAt() throws {
        let cases = S.list(try S.fixture("theme_schedule")["isDark"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let dark = ThemeSchedule.isDarkAt(darkFrom: int(c["darkFrom"]), lightFrom: int(c["lightFrom"]),
                                              minuteOfDay: int(c["minute"]))
            XCTAssertEqual(dark, c["dark"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testForcedDark() throws {
        let cases = S.list(try S.fixture("theme_schedule")["forced"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let forced = ThemeSchedule.forcedDark(darkMode: c["darkMode"] as? Bool, scheduled: c["scheduled"] as? Bool ?? false,
                                                  darkFrom: int(c["darkFrom"]), lightFrom: int(c["lightFrom"]),
                                                  minuteOfDay: int(c["minute"]))
            XCTAssertEqual(forced, c["forced"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testNextSwitch() throws {
        let cases = S.list(try S.fixture("theme_schedule")["nextSwitch"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            var calendar = Calendar(identifier: .gregorian)
            let zone = c["zone"] as? String ?? ""
            calendar.timeZone = try XCTUnwrap(TimeZone(identifier: zone), "zone \(zone)")
            let nowMillis = Int64(S.number(c["nowMillis"]) ?? 0)
            XCTAssertEqual(nowMillis, S.millis(c["now"]), "\(name): now")
            let next = ThemeSchedule.nextSwitch(darkFrom: int(c["darkFrom"]), lightFrom: int(c["lightFrom"]),
                                                after: Date(timeIntervalSince1970: Double(nowMillis) / 1000),
                                                calendar: calendar)
            let want = S.number(c["nextMillis"]).map { Int64($0) }
            XCTAssertEqual(next.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }, want, name)
        }
    }

    func testWithoutScheduleNoSwitch() {
        XCTAssertNil(ThemeSchedule.nextSwitch(AppSettings(), after: Date()))
    }
}
