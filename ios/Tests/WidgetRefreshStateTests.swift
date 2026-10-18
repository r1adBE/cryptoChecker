import XCTest
@testable import CryptoChecker

/// Wie `WidgetRefreshStateTest.kt`.
final class WidgetRefreshStateTests: XCTestCase {

    private let now: Int64 = 1_000_000_000
    private let window = RefreshDebounce.windowMillis

    func testTapWhileRunningDoesNothing() {
        XCTAssertEqual(WidgetRefreshState.onTap(running: true, lastFinishedAt: now - 2_000, now: now), .running)
        XCTAssertEqual(WidgetRefreshState.onTap(running: true, lastFinishedAt: 0, now: now), .running)
    }

    func testTapWithinWindowIsBlockedUntilWindowEnd() {
        XCTAssertEqual(WidgetRefreshState.onTap(running: false, lastFinishedAt: now - 4_000, now: now),
                       .blocked(until: now - 4_000 + window))
        XCTAssertEqual(WidgetRefreshState.onTap(running: false, lastFinishedAt: now, now: now), .blocked(until: now + window))
    }

    func testTapAfterWindowOrWithoutRefreshStarts() {
        XCTAssertEqual(WidgetRefreshState.onTap(running: false, lastFinishedAt: 0, now: now), .start)
        XCTAssertEqual(WidgetRefreshState.onTap(running: false, lastFinishedAt: now - window, now: now), .start)
        // Uhr zurückgestellt: letzte Aktualisierung «in der Zukunft» sperrt nicht
        XCTAssertEqual(WidgetRefreshState.onTap(running: false, lastFinishedAt: now + 5_000, now: now), .start)
    }

    func testCountdownRightAfterRefreshWithoutTap() {
        // Eben fertig, nie getippt: Countdown bis zum Ende des Fensters
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: 0, lastFinishedAt: now - 2_000, runningSince: 0, now: now),
                       now - 2_000 + window)
        // Fenster vorbei bzw. noch nie aktualisiert: Knopf
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: 0, lastFinishedAt: now - window, runningSince: 0, now: now), 0)
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: 0, lastFinishedAt: 0, runningSince: 0, now: now), 0)
        // Uhr zurückgestellt (letzte «in der Zukunft»): Knopf
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: 0, lastFinishedAt: now + 5_000, runningSince: 0, now: now), 0)
    }

    func testLaterOfStoredAndWindowEnd() {
        let last = now - 4_000
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: last + window - 3_000, lastFinishedAt: last, runningSince: 0, now: now),
                       last + window)
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: now + 13_000, lastFinishedAt: last, runningSince: 0, now: now),
                       now + 13_000)
        // Gespeichertes Ende allein (z. B. nach einer Aktualisierung ohne Kurs) gilt weiter
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: now + 6_000, lastFinishedAt: now - 60_000, runningSince: 0, now: now),
                       now + 6_000)
        // Ungültiges gespeichertes Ende (Uhr verstellt) verdrängt das gültige nicht
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: now + window + 5_000, lastFinishedAt: last, runningSince: 0, now: now),
                       last + window)
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: now - 1, lastFinishedAt: now - 60_000, runningSince: 0, now: now), 0)
    }

    func testNoCountdownWhileNewRefreshRuns() {
        let last = now - 4_000
        // Nach der letzten fertigen begonnen: läuft — Knopf statt Countdown
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: now + 9_000, lastFinishedAt: last, runningSince: now - 1_000, now: now), 0)
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: 0, lastFinishedAt: 0, runningSince: now - 1_000, now: now), 0)
        // Zeichnen am Ende der Aktualisierung (Zeit schon gesetzt, Merker noch gehalten): Countdown beginnt
        XCTAssertEqual(WidgetRefreshState.blockedUntil(stored: 0, lastFinishedAt: now, runningSince: now - 3_000, now: now),
                       now + window)
    }

    func testRemainingMillisWithinWindowOnly() {
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: 0, now: now), 0)
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: now + 10_000, now: now), 10_000)
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: now + window, now: now), window)
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: now + 1, now: now), 1)
        // Ende erreicht oder vorbei: Knopf wieder zeigen
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: now, now: now), 0)
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: now - 1, now: now), 0)
        // Uhr zurückgestellt: nie länger als ein Fenster
        XCTAssertEqual(WidgetRefreshState.remainingMillis(blockedUntil: now + window + 1, now: now), 0)
    }

    func testRemainingSecondsRoundUp() {
        XCTAssertEqual(WidgetRefreshState.remainingSeconds(blockedUntil: now + window, now: now), 15)
        XCTAssertEqual(WidgetRefreshState.remainingSeconds(blockedUntil: now + 14_200, now: now), 15)
        XCTAssertEqual(WidgetRefreshState.remainingSeconds(blockedUntil: now + 1, now: now), 1)
        XCTAssertEqual(WidgetRefreshState.remainingSeconds(blockedUntil: now, now: now), 0)
    }

    func testBlockedStateMatchesAppCountdown() {
        let last = now - 3_300
        guard case let .blocked(until) = WidgetRefreshState.onTap(running: false, lastFinishedAt: last, now: now) else {
            return XCTFail("gesperrt erwartet")
        }
        for t in [now, now + 700, now + 5_000, now + 11_699, now + 11_700, now + 20_000] {
            XCTAssertEqual(WidgetRefreshState.remainingSeconds(blockedUntil: until, now: t),
                           RefreshDebounce.remainingSeconds(lastFinishedAt: last, now: t))
        }
    }

    func testUnlockEntryOnlyWhileBlocked() {
        XCTAssertEqual(WidgetRefreshState.unlockAt(blockedUntil: now + 9_000, now: now), now + 9_000)
        XCTAssertNil(WidgetRefreshState.unlockAt(blockedUntil: now, now: now))
        XCTAssertNil(WidgetRefreshState.unlockAt(blockedUntil: 0, now: now))
        XCTAssertNil(WidgetRefreshState.unlockAt(blockedUntil: now + window + 1, now: now))
    }
}
