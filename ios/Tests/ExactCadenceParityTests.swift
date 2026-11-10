import XCTest
@testable import CryptoChecker

/// «Pünktlich prüfen» im gewählten Intervall und Sperrfenster: gemeinsame Fälle mit Android
/// (`ExactCadenceParityTest.kt`, `exact_cadence.json`). iOS nutzt davon nur `shouldSkip` (Aufgabe `bgTask`).
final class ExactCadenceParityTests: XCTestCase {
    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }
    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? 0 }

    func testIntervalMatchesFixture() throws {
        let data = try TestSupport.fixture("exact_cadence")
        let now = int64(data["now"])
        let cases = TestSupport.list(data["interval"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let interval = BackgroundSchedule.exactIntervalMillis(backgroundIntervalMinutes: int(c["minutes"]))
            XCTAssertEqual(interval, int64(c["expected"]), "\(c["minutes"] ?? "")")
            XCTAssertEqual(BackgroundSchedule.nextExactAt(now: now, intervalMillis: interval), now + interval)
        }
    }

    func testNeedsArmMatchesFixture() throws {
        let data = try TestSupport.fixture("exact_cadence")
        let now = int64(data["now"])
        for c in TestSupport.list(data["needsArm"]) {
            let interval = BackgroundSchedule.exactIntervalMillis(backgroundIntervalMinutes: int(c["minutes"]))
            XCTAssertEqual(BackgroundSchedule.needsArm(armedFor: now + int64(c["armedIn"]), now: now, intervalMillis: interval),
                           c["expected"] as? Bool, "\(c["name"] ?? "")")
        }
    }

    func testSkipWindowMatchesFixture() throws {
        let data = try TestSupport.fixture("exact_cadence")
        let now = int64(data["now"])
        let cases = TestSupport.list(data["skip"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let trigger = try XCTUnwrap(CheckTrigger(rawValue: c["trigger"] as? String ?? ""))
            let window = BackgroundSchedule.dedupeWindowMillis(
                trigger: trigger, exactArmed: c["exactArmed"] as? Bool ?? false,
                intervalMillis: BackgroundSchedule.exactIntervalMillis(backgroundIntervalMinutes: int(c["minutes"])))
            let last: Int64 = (c["age"] as? NSNumber).map { now - $0.int64Value } ?? 0
            XCTAssertEqual(BackgroundSchedule.shouldSkip(trigger: trigger, lastFinishedAt: last, now: now, windowMillis: window),
                           c["expected"] as? Bool, "\(c["name"] ?? "")")
        }
    }
}
