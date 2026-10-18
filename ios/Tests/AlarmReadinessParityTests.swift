import XCTest
@testable import CryptoChecker

/// Seite «Damit Alarme ankommen» (`Parity/alarm_delivery.json`): gelesener Zustand → Zeilen mit Zustand, Text und
/// Knopf. Gegenstück: `AlarmReadinessParityTest.kt`.
final class AlarmReadinessParityTests: XCTestCase {
    private typealias S = TestSupport

    private func bool(_ m: [String: Any], _ key: String, _ fallback: Bool) -> Bool { m[key] as? Bool ?? fallback }

    private func optionalBool(_ value: Any?) -> Bool? {
        guard let value, !(value is NSNull) else { return nil }
        return value as? Bool
    }

    private func input(_ m: [String: Any]) throws -> AlarmReadiness.Input {
        var input = AlarmReadiness.Input(
            platform: (m["platform"] as? String) == "ios" ? .ios : .android,
            notifications: try XCTUnwrap(AlarmReadiness.Access(rawValue: m["notifications"] as? String ?? "")),
            now: S.millis(m["now"]),
            minuteOfDay: Int(S.number(m["minuteOfDay"]) ?? 0))
        input.quietEnabled = bool(m, "quietEnabled", false)
        input.quietStart = S.number(m["quietStart"]).map { Int($0) } ?? QuietHours.defaultStart
        input.quietEnd = S.number(m["quietEnd"]).map { Int($0) } ?? QuietHours.defaultEnd
        input.lastBackgroundCheck = S.number(m["lastBackgroundCheck"]).map { Int64($0) }
        input.hasAlarms = bool(m, "hasAlarms", false)
        input.channel = (m["channel"] as? String).flatMap { AlarmReadiness.Channel(rawValue: $0) }
        input.exactEnabled = bool(m, "exactEnabled", false)
        input.exactPermitted = bool(m, "exactPermitted", true)
        input.batteryUnrestricted = bool(m, "batteryUnrestricted", false)
        input.backgroundRestricted = bool(m, "backgroundRestricted", false)
        input.systemDnd = optionalBool(m["systemDnd"])
        input.alertEnabled = bool(m, "alertEnabled", true)
        input.soundEnabled = bool(m, "soundEnabled", true)
        input.wantsSound = bool(m, "wantsSound", true)
        input.timeSensitive = optionalBool(m["timeSensitive"])
        input.backgroundRefresh = bool(m, "backgroundRefresh", true)
        input.lowPower = bool(m, "lowPower", false)
        return input
    }

    func testStaleLimitMatches() throws {
        XCTAssertEqual(AlarmReadiness.staleCheckMillis, S.millis(try S.fixture("alarm_delivery")["staleCheckMillis"]))
    }

    func testRows() throws {
        let cases = S.list(try S.fixture("alarm_delivery")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let actual = AlarmReadiness.rows(try input(c["input"] as? [String: Any] ?? [:]))
                .map { [$0.row.rawValue, $0.state.rawValue, $0.detail.rawValue, $0.action.rawValue] }
            let expected = (c["expected"] as? [[String]]) ?? []
            XCTAssertEqual(actual, expected, name)
        }
    }

    func testOkRowsHaveNoButton() throws {
        for c in S.list(try S.fixture("alarm_delivery")["cases"]) {
            for item in AlarmReadiness.rows(try input(c["input"] as? [String: Any] ?? [:])) where item.state == .ok {
                XCTAssertEqual(item.action, .none)
            }
        }
    }

    func testWarningSummary() {
        var good = AlarmReadiness.Input(platform: .ios, notifications: .granted, now: 10, minuteOfDay: 600)
        good.lastBackgroundCheck = 5
        good.timeSensitive = true
        XCTAssertFalse(AlarmReadiness.hasWarning(AlarmReadiness.rows(good)))
        let bad = AlarmReadiness.Input(platform: .ios, notifications: .denied, now: 10, minuteOfDay: 600)
        XCTAssertTrue(AlarmReadiness.hasWarning(AlarmReadiness.rows(bad)))
    }

    func testBackgroundAlarmKinds() throws {
        let cases = S.list(try S.fixture("alarm_delivery")["backgroundAlarms"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let k = c["kinds"] as? [String: Any] ?? [:]
            var kinds = AlarmReadiness.AlarmKinds()
            kinds.pairAlarm = bool(k, "pairAlarm", false)
            kinds.portfolioEnabled = bool(k, "portfolioEnabled", false)
            kinds.portfolioAlarm = bool(k, "portfolioAlarm", false)
            kinds.gasEthTenths = S.number(k["gasEthTenths"]).map { Int($0) } ?? 0
            kinds.gasBtc = S.number(k["gasBtc"]).map { Int($0) } ?? 0
            kinds.depeg = bool(k, "depeg", false)
            kinds.fearGreedBelow = S.number(k["fearGreedBelow"]).map { Int($0) } ?? 0
            kinds.fearGreedAbove = S.number(k["fearGreedAbove"]).map { Int($0) } ?? 0
            kinds.indicatorAlert = bool(k, "indicatorAlert", false)
            XCTAssertEqual(AlarmReadiness.hasBackgroundAlarms(kinds), c["expected"] as? Bool, c["name"] as? String ?? "")
        }
    }
}
