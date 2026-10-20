import XCTest
@testable import CryptoChecker

/// «1 h später» (`Parity/alarm_snooze.json`, gleich in `AlarmSnoozeParityTest.kt`), die Kategorien der
/// Alarm-Mitteilung und das Ziel «Anpassen» (wie `AlarmEditTargetTest.kt`).
final class AlarmSnoozeTests: XCTestCase {
    private typealias S = TestSupport

    func testSnoozeLastsOneHour() throws {
        let data = try S.fixture("alarm_snooze")
        XCTAssertEqual(AlarmSnooze.snoozeMillis, Int64(S.number(data["snoozeMillis"]) ?? 0))
        for c in S.list(data["until"]) {
            XCTAssertEqual(AlarmSnooze.snoozeUntil(now: Int64(S.number(c["now"]) ?? 0)),
                           Int64(S.number(c["until"]) ?? -1), "\(c)")
        }
    }

    func testIsSnoozed() throws {
        for c in S.list(try S.fixture("alarm_snooze")["isSnoozed"]) {
            let until = S.number(c["until"]).map { Int64($0) }
            XCTAssertEqual(AlarmSnooze.isSnoozed(until: until, now: Int64(S.number(c["now"]) ?? 0)),
                           c["snoozed"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testPruneKeepsRunningPauses() throws {
        let c = try XCTUnwrap(try S.fixture("alarm_snooze")["prune"] as? [String: Any])
        let raw = try XCTUnwrap(c["all"] as? [String: Any])
        var all: [Int64: Int64] = [:]
        for (key, value) in raw { all[Int64(key) ?? -1] = Int64(S.number(value) ?? 0) }
        let kept = AlarmSnooze.prune(all, now: Int64(S.number(c["now"]) ?? 0))
        let expected = Set((c["kept"] as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } })
        XCTAssertEqual(Set(kept.keys), expected)
    }

    func testOfferedOnlyWherePauseWorks() throws {
        let cases = S.list(try S.fixture("alarm_snooze")["offeredFor"])
        // Jede Alarm-Art muss im gemeinsamen Testfall vorkommen, damit eine neue Art nicht unbemerkt bleibt
        XCTAssertEqual(Set(AlarmCondition.allCases.map(\.rawValue)), Set(cases.compactMap { $0["condition"] as? String }))
        for c in cases {
            let condition = try XCTUnwrap(AlarmCondition(rawValue: c["condition"] as? String ?? ""))
            let repeating = try XCTUnwrap(c["repeating"] as? Bool)
            XCTAssertEqual(AlarmSnooze.offeredFor(condition, repeating: repeating), c["offered"] as? Bool,
                           "\(c["name"] as? String ?? "") (\(condition.rawValue), repeating=\(repeating))")
        }
    }

    func testRearmsOnlyPriceLevels() throws {
        let cases = S.list(try S.fixture("alarm_snooze")["rearmsOnSnooze"])
        XCTAssertEqual(Set(AlarmCondition.allCases.map(\.rawValue)), Set(cases.compactMap { $0["condition"] as? String }))
        for c in cases {
            let condition = try XCTUnwrap(AlarmCondition(rawValue: c["condition"] as? String ?? ""))
            let rearms = try XCTUnwrap(c["rearms"] as? Bool)
            XCTAssertEqual(AlarmSnooze.rearmsOnSnooze(condition), rearms, condition.rawValue)
            // Wer wieder scharf gestellt wird, bekommt den Knopf auch bei «einmal»
            if rearms { XCTAssertTrue(AlarmSnooze.offeredFor(condition, repeating: false), condition.rawValue) }
        }
    }

    /// Speichern im App-Group-Speicher: Pause gilt sofort, abgelaufene fallen beim nächsten Tippen weg.
    func testStoreKeepsOnlyRunningPauses() throws {
        let store = try XCTUnwrap(UserDefaults(suiteName: "AlarmSnoozeTests"))
        store.removePersistentDomain(forName: "AlarmSnoozeTests")
        defer { store.removePersistentDomain(forName: "AlarmSnoozeTests") }
        AlarmSnooze.snooze(7, now: 1_000, store: store)
        XCTAssertEqual(AlarmSnooze.until(7, store: store), 3_601_000)
        XCTAssertTrue(AlarmSnooze.isSnoozed(until: AlarmSnooze.until(7, store: store), now: 2_000))
        AlarmSnooze.snooze(8, now: 4_000_000, store: store)
        XCTAssertNil(AlarmSnooze.until(7, store: store), "abgelaufen und aufgeräumt")
        XCTAssertEqual(AlarmSnooze.until(8, store: store), 7_600_000)
    }

    /// «1 h später» und «Was bewegt sich mit?» je nach Alarm und Paar — wie Android.
    func testPairAlarmCategory() {
        XCTAssertEqual(Notifier.pairAlarmCategory(traded: true, snooze: true), Notifier.categoryAlarmWhySnooze)
        XCTAssertEqual(Notifier.pairAlarmCategory(traded: true, snooze: false), Notifier.categoryAlarmWhy)
        XCTAssertEqual(Notifier.pairAlarmCategory(traded: false, snooze: true), Notifier.categoryAlarmSnooze)
        XCTAssertEqual(Notifier.pairAlarmCategory(traded: false, snooze: false), Notifier.categoryAlarmPair)
        let categories = Notifier.pairAlarmCategories()
        XCTAssertEqual(Set(categories.map(\.identifier)).count, 4)
        // «Anpassen» immer als letzter Knopf, «1 h später» nur in den snooze-Kategorien
        for category in categories {
            XCTAssertEqual(category.actions.last?.identifier, Notifier.actionEdit)
            let snoozes = category.actions.contains { $0.identifier == Notifier.actionSnooze }
            XCTAssertEqual(snoozes, category.identifier.hasSuffix("snooze"), category.identifier)
        }
    }

    func testAlarmEditTarget() throws {
        let target = AlarmEditTarget.of(watchId: 12, alarmId: 345)
        XCTAssertEqual(target, "alarm/12/345")
        let parsed = try XCTUnwrap(AlarmEditTarget.parse(target))
        XCTAssertEqual(parsed.watchId, 12)
        XCTAssertEqual(parsed.alarmId, 345)
        for broken in [nil, "alarms", "why/12", "alarm/12", "alarm/12/x", "alarm/0/5", "alarm/12/5/7"] {
            XCTAssertNil(AlarmEditTarget.parse(broken), broken ?? "nil")
        }
    }
}
