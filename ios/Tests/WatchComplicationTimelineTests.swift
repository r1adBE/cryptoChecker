import XCTest
@testable import CryptoChecker

/// Zeitleiste der Preis-Komplikation (`WatchFreshness.complicationDates`): ein Eintrag jetzt, ein zweiter 1 ms
/// nach dem Veralten — nur wenn das vor dem nächsten Neuladen liegt; Neuladen nach dem begrenzten Intervall.
final class WatchComplicationTimelineTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var nowMillis: Int64 { Int64(now.timeIntervalSince1970 * 1000) }

    func testWithoutPriceOnlyNowAndReloadAfterTheInterval() {
        let plan = WatchFreshness.complicationDates(now: now, cachedTime: nil, intervalMinutes: 30)
        XCTAssertEqual(plan.dates, [now])
        XCTAssertEqual(plan.next, now.addingTimeInterval(30 * 60))
    }

    func testSecondEntryOneMillisecondAfterTheLimitWhenItComesBeforeTheReload() {
        // Veraltet nach 3 × 15 Min. = 45 Min.; Stand vor 40 Min. → veraltet in 5 Min., Neuladen in 15 Min.
        let time = nowMillis - 40 * 60_000
        let plan = WatchFreshness.complicationDates(now: now, cachedTime: time, intervalMinutes: 15)
        XCTAssertEqual(plan.dates.count, 2)
        XCTAssertEqual(plan.dates[0], now)
        XCTAssertEqual(plan.dates[1].timeIntervalSince1970, TimeInterval(time + 45 * 60_000 + 1) / 1000, accuracy: 0.0005)
        XCTAssertEqual(plan.next, now.addingTimeInterval(15 * 60))
    }

    func testNoSecondEntryWhenAlreadyOutdatedOrAfterTheReload() {
        // Schon veraltet: nichts mehr zu wechseln
        XCTAssertEqual(WatchFreshness.complicationDates(now: now, cachedTime: nowMillis - 60 * 60_000, intervalMinutes: 15).dates, [now])
        // Frisch: das Veralten liegt nach dem nächsten Neuladen (das zeichnet ohnehin neu)
        XCTAssertEqual(WatchFreshness.complicationDates(now: now, cachedTime: nowMillis, intervalMinutes: 15).dates, [now])
    }

    func testIntervalIsClampedLikeTheIphone() {
        let plan = WatchFreshness.complicationDates(now: now, cachedTime: nil, intervalMinutes: 1)
        XCTAssertEqual(plan.next, now.addingTimeInterval(TimeInterval(WatchInterval.clamp(1) * 60)))
    }
}
