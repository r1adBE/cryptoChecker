import XCTest
@testable import CryptoChecker

/// Zeile über der Merkliste (`Parity/alarm_reliability.json`): gelesener Zustand → ein Satz und Farbe.
/// Gegenstück: `AlarmReliabilityParityTest.kt`.
final class AlarmReliabilityTests: XCTestCase {
    private typealias S = TestSupport

    private func bool(_ m: [String: Any], _ key: String, _ fallback: Bool) -> Bool { m[key] as? Bool ?? fallback }

    private func input(_ m: [String: Any]) throws -> AlarmReadiness.Input {
        var input = AlarmReadiness.Input(
            platform: (m["platform"] as? String) == "ios" ? .ios : .android,
            notifications: try XCTUnwrap(AlarmReadiness.Access(rawValue: m["notifications"] as? String ?? "")),
            now: S.millis(m["now"]),
            minuteOfDay: Int(S.number(m["minuteOfDay"]) ?? 0))
        input.lastBackgroundCheck = S.number(m["lastBackgroundCheck"]).map { Int64($0) }
        // Gespeicherte Läufe («at;trigger;result;dauer»), falls der Fall sie nennt (seit Build 93)
        if let lines = m["runs"] as? [String] {
            let runs = CheckRunLog.decode(lines.joined(separator: "\n"))
            input.lastBackgroundCheck = CheckRunLog.lastBackgroundCheck(runs)
            input.lastFailedBackgroundCheck = CheckRunLog.lastFailedBackgroundCheck(runs)
        }
        input.storageNoKey = bool(m, "storageNoKey", false)
        input.hasAlarms = bool(m, "hasAlarms", false)
        input.channel = (m["channel"] as? String).flatMap { AlarmReadiness.Channel(rawValue: $0) }
        input.batteryUnrestricted = bool(m, "batteryUnrestricted", false)
        input.backgroundRestricted = bool(m, "backgroundRestricted", false)
        input.backgroundRefresh = bool(m, "backgroundRefresh", true)
        return input
    }

    func testStaleLimitMatches() throws {
        let fixture = try S.fixture("alarm_reliability")
        XCTAssertEqual(AlarmReliability.staleMillis(.android), S.millis(fixture["staleMillis"]))
        XCTAssertEqual(AlarmReliability.staleMillis(.ios), S.millis(fixture["staleMillisIos"]))
        // iOS: dieselbe Grenze wie die Seite «Damit Alarme ankommen»
        XCTAssertEqual(AlarmReliability.staleMillis(.ios), AlarmReadiness.staleCheckMillis)
    }

    func testStatus() throws {
        let cases = S.list(try S.fixture("alarm_reliability")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        // Jeder Satz kommt im gemeinsamen Testfall vor
        XCTAssertEqual(Set(AlarmReliability.Kind.allCases.map(\.rawValue)), Set(cases.compactMap { $0["kind"] as? String }))
        for c in cases {
            let name = c["name"] as? String ?? ""
            let m = c["input"] as? [String: Any] ?? [:]
            let actual = AlarmReliability.status(try input(m), oemVendorKnown: bool(m, "oem", false))
            XCTAssertEqual(actual.kind.rawValue, c["kind"] as? String, name)
            XCTAssertEqual(actual.warn, c["warn"] as? Bool, name)
        }
    }
}
