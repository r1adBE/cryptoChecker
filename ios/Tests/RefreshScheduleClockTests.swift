import XCTest
@testable import CryptoChecker

/// Uhr vor- und zurückgestellt: iOS hält den absoluten Beginn der vorgemerkten Anfrage fest — liegt er weiter weg,
/// als `RefreshSchedule.nextBegin` je planen könnte, wird sie ersetzt; sonst bleibt sie stehen (ähnlich Android UPDATE: der laufende Takt bleibt).
final class RefreshScheduleClockTests: XCTestCase {
    private let m: Int64 = 60_000
    private let now: Int64 = 1_760_000_000_000

    func testPlannedBeginIsPlausible() {
        // Gerade geplant mit 30 min Intervall
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 30 * m, intervalMinutes: 30, now: now))
        // Knapp innerhalb des Spielraums
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 45 * m, intervalMinutes: 30, now: now))
        // Schon überfällig oder ohne Beginn: bleibt stehen
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now - 10 * m, intervalMinutes: 30, now: now))
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: nil, intervalMinutes: 30, now: now))
    }

    func testBeginHoursAwayAfterClockChangeIsReplaced() {
        XCTAssertTrue(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 45 * m + 1, intervalMinutes: 30, now: now))
        XCTAssertTrue(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 5 * 60 * m, intervalMinutes: 30, now: now))
    }

    func testNextBeginIsNeverImplausible() {
        for interval in [1, 15, 30, 60, 240, 24 * 60] {
            for last in [0, now - 3 * 60 * m, now - m, now, now + 60 * m] {
                for zone in [nil, 0, now - 11 * 60 * m, now] as [Int64?] {
                    let begin = RefreshSchedule.nextBegin(lastRunMillis: last, intervalMinutes: interval,
                                                          zoneLastRunMillis: zone, now: now)
                    let millis = Int64((begin.timeIntervalSince1970 * 1000).rounded())
                    XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: millis,
                                                                    intervalMinutes: interval, now: now))
                }
            }
        }
    }

    func testHugeIntervalDoesNotOverflow() {
        // Intervall aus alter/fremder Sicherung: `SafeMillis` begrenzt, kein Absturz
        let fiftyYears: Int64 = 50 * 365 * 24 * 60 * m
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + fiftyYears, intervalMinutes: Int.max, now: now))
        XCTAssertTrue(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: Int64.max, intervalMinutes: Int.max, now: now))
    }
}
