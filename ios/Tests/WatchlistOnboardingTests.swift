import XCTest
@testable import CryptoChecker

/// Merkliste nach der Start-Auswahl schrittweise — gleiche Fälle wie `WatchlistOnboardingTest.kt`.
final class WatchlistOnboardingTests: XCTestCase {

    func testFirstUseHidesPulseAndGestureHint() {
        let v = WatchlistOnboarding.visible(alarmCount: 0, usageDays: 1)
        XCTAssertFalse(v.pulse)
        XCTAssertFalse(v.gestureHint)
    }

    func testAlarmsNotLoadedYetCountAsNone() {
        let v = WatchlistOnboarding.visible(alarmCount: nil, usageDays: 0)
        XCTAssertFalse(v.pulse)
        XCTAssertFalse(v.gestureHint)
    }

    func testFirstAlarmShowsEverything() {
        let v = WatchlistOnboarding.visible(alarmCount: 1, usageDays: 0)
        XCTAssertTrue(v.pulse)
        XCTAssertTrue(v.gestureHint)
    }

    func testThreeDifferentDaysShowEverythingWithoutAnAlarm() {
        XCTAssertFalse(WatchlistOnboarding.visible(alarmCount: 0, usageDays: WatchlistOnboarding.unlockDays - 1).pulse)
        let v = WatchlistOnboarding.visible(alarmCount: 0, usageDays: WatchlistOnboarding.unlockDays)
        XCTAssertTrue(v.pulse)
        XCTAssertTrue(v.gestureHint)
    }

    func testUsageDaysCountEachLaterDayOnce() {
        let first = UsageDays(count: 0, lastDay: nil).next(today: 100)
        XCTAssertEqual(first, UsageDays(count: 1, lastDay: 100))
        // Derselbe Tag zählt nicht nochmals
        XCTAssertEqual(first.next(today: 100), first)
        XCTAssertEqual(first.next(today: 101), UsageDays(count: 2, lastDay: 101))
        // Lücke von mehreren Tagen zählt als ein Tag
        XCTAssertEqual(first.next(today: 101).next(today: 110), UsageDays(count: 3, lastDay: 110))
    }

    func testUsageDaysIgnoreAClockSetBack() {
        XCTAssertEqual(UsageDays(count: 2, lastDay: 101).next(today: 99), UsageDays(count: 2, lastDay: 99))
        XCTAssertEqual(UsageDays(count: UsageDays.maxCount, lastDay: 5).next(today: 6),
                       UsageDays(count: UsageDays.maxCount, lastDay: 6))
    }

    /// Ältere Einstellungen ohne die Felder: noch kein Tag gezählt; gespeicherte Werte bleiben erhalten.
    func testSettingsDecodeUsageDays() throws {
        let old = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(old.usageDays, 0)
        XCTAssertNil(old.usageLastDay)
        var s = AppSettings()
        s.usageDays = 2
        s.usageLastDay = 20_000
        let back = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(back.usageDays, 2)
        XCTAssertEqual(back.usageLastDay, 20_000)
    }
}
