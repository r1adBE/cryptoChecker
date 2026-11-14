import XCTest
@testable import CryptoChecker

/// Morgen-Mitteilung «Wirtschaftstermine» in der Zone der Termine (`Parity/macro_notify.json`): 08:00 Ortszeit,
/// spätestens 08:00 New York, nie vor 05:00 – so kommen CPI, PPI, Arbeitsmarkt und PCE (08:30 New York) auch in
/// Chicago, Denver und Los Angeles in die Mitteilung. Gegenstück: Android `MacroCalendarNotifyParityTest`.
final class MacroNotifyParityTests: XCTestCase {

    private func t(_ iso: String) throws -> Int64 { try XCTUnwrap(MacroCalendar.parseInstant(iso)) }

    private func calendar(_ zone: String) throws -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: zone))
        return calendar
    }

    /// Mittag des Ortstags `day` («2026-10-14») in der Zone von `calendar`.
    private func noon(_ day: String, _ calendar: Calendar) throws -> Date {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        return try XCTUnwrap(calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)))
    }

    func testZeitpunktJeOrtstag() throws {
        let fixture = try TestSupport.fixture("macro_notify")
        let cases = try XCTUnwrap(fixture["notifyAt"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let zone = try XCTUnwrap(c["zone"] as? String)
            let day = try XCTUnwrap(c["day"] as? String)
            let cal = try calendar(zone)
            let at = try XCTUnwrap(MacroCalendar.notifyAt(day: try noon(day, cal), calendar: cal))
            XCTAssertEqual(Int64(at.timeIntervalSince1970 * 1000), try t(XCTUnwrap(c["expected"] as? String)), "\(zone) \(day)")
        }
    }

    func testTermineDerMitteilung() throws {
        let fixture = try TestSupport.fixture("macro_notify")
        for c in try XCTUnwrap(fixture["events"] as? [[String: Any]]) {
            let cal = try calendar(try XCTUnwrap(c["zone"] as? String))
            let day = try noon(try XCTUnwrap(c["day"] as? String), cal)
            let events = try XCTUnwrap(c["events"] as? [String]).map { MacroEvent(type: .CPI, time: try t($0)) }
            let expected = try XCTUnwrap(c["expected"] as? [String]).map { try t($0) }
            XCTAssertEqual(MacroCalendar.eventsOnDay(events, day: day, calendar: cal).map(\.time), expected,
                           c["note"] as? String ?? "")
        }
    }

    /// Nach einem Wechsel von Zeitzone oder Uhr wird neu geplant (Text und Zeitpunkt in der neuen Zone).
    func testZonenwechselPlantNeu() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let planning = try String(contentsOf: root.appendingPathComponent("App/Services/BackgroundRefresh+Planning.swift"),
                                  encoding: .utf8)
        XCTAssertTrue(planning.contains("MacroNotifications.clockChanged()"))
        let macro = try String(contentsOf: root.appendingPathComponent("App/Services/MacroNotifications.swift"), encoding: .utf8)
        XCTAssertTrue(macro.contains("let notifyAt = MacroCalendar.notifyAt(day: day, calendar: calendar),"))
        XCTAssertTrue(macro.contains("refresh(settings: SharedStorage.loadSettings())"))
    }
}
