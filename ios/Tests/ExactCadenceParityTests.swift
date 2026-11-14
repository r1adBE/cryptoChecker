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

    /// Fenster unter dem Wecker: Intervall minus Toleranz, nie unter dem gewöhnlichen Fenster (wie Android).
    func testWindowWithToleranceMatchesFixture() throws {
        let data = try TestSupport.fixture("exact_cadence")
        XCTAssertEqual(int64(data["toleranceMillis"]), BackgroundSchedule.dedupeToleranceMillis)
        let cases = TestSupport.list(data["window"])
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for c in cases {
            let trigger = try XCTUnwrap(CheckTrigger(rawValue: c["trigger"] as? String ?? ""))
            let window = BackgroundSchedule.dedupeWindowMillis(
                trigger: trigger, exactArmed: c["exactArmed"] as? Bool ?? false,
                intervalMillis: BackgroundSchedule.exactIntervalMillis(backgroundIntervalMinutes: int(c["minutes"])))
            XCTAssertEqual(window, int64(c["expected"]), "\(c["name"] ?? "")")
            XCTAssertGreaterThanOrEqual(window, BackgroundSchedule.dedupeWindowMillis, "\(c["name"] ?? "")")
        }
        XCTAssertEqual(BackgroundSchedule.dedupeWindowMillis(trigger: .worker, exactArmed: true, intervalMillis: 6 * 60_000),
                       BackgroundSchedule.dedupeWindowMillis)
    }

    func testSkipWindowMatchesFixture() throws {
        let data = try TestSupport.fixture("exact_cadence")
        let now = int64(data["now"])
        let cases = TestSupport.list(data["skip"])
        XCTAssertGreaterThanOrEqual(cases.count, 13)
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

    // MARK: Volle Prüfung und Zeitpunkt von `lastAlarmCheckAt` (`full_check.json`, wie Android `FullCheckParityTest`)

    func testFullCheckMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("full_check")["isFullCheck"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            XCTAssertEqual(BackgroundSchedule.isFullCheck(attempted: int(c["attempted"]), failed: int(c["failed"]),
                                                          notTraded: int(c["notTraded"]), cutByBudget: int(c["cutByBudget"])),
                           c["expected"] as? Bool, "\(c)")
        }
    }

    /// Uhrzeit der Aktualisierung: ein Kurs kam oder alle Paare sind nicht gehandelt bzw. stillgelegt.
    func testRefreshedMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("full_check")["refreshed"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            XCTAssertEqual(BackgroundSchedule.refreshed(attempted: int(c["attempted"]), failed: int(c["failed"]),
                                                        notTraded: int(c["notTraded"])),
                           c["expected"] as? Bool, "\(c)")
        }
    }

    func testMarksAlarmCheckMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("full_check")["marksAlarmCheck"])
        XCTAssertEqual(cases.count, 8)
        for c in cases {
            let actual = BackgroundSchedule.marksAlarmCheck(
                fullCheck: try XCTUnwrap(c["fullCheck"] as? Bool),
                deferredWaiting: try XCTUnwrap(c["deferredWaiting"] as? Bool),
                deferredChecked: try XCTUnwrap(c["deferredChecked"] as? Bool))
            XCTAssertEqual(actual, c["expected"] as? Bool, "\(c)")
        }
    }

    /// Die Hintergrund-Aufgabe vermerkt über diese Regel: gleich nach den Kursalarmen nur ohne wartende Alarme mit
    /// Zusatzdaten, sonst erst nach deren Prüfung.
    func testBackgroundTaskMarksThroughTheRule() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/BackgroundRefresh.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        XCTAssertEqual(source.components(separatedBy: "SharedStorage.lastAlarmCheckAt = EpochTime.nowMillis").count - 1, 2)
        XCTAssertEqual(source.components(separatedBy: "if BackgroundSchedule.marksAlarmCheck(").count - 1, 2)
        let deferred = try XCTUnwrap(source.range(of: "await seams.checkDeferred(outcome.deferredAlarms, settings, deadline)"))
        let second = try XCTUnwrap(source.range(of: "deferredWaiting: true,"))
        XCTAssertLessThan(deferred.lowerBound, second.lowerBound)
    }
}
