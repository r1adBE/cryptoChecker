import XCTest
@testable import CryptoChecker

/// Kleine Uhr-Komplikationen (rund, Ecke, Zeile): veraltete Werte zeigen das Alter knapp («BTC 47m»), wie Android
/// der Kurztitel (`TimeDifferenceStyle.SHORT_SINGLE_UNIT`). Grenze wie `WatchFreshness.isOutdated` (3 × Intervall: 45 Min. bei 15).
final class WatchShortAgeTests: XCTestCase {
    private let en = Locale(identifier: "en_US")
    private let now: Int64 = 1_800_000_000_000
    private let minute: Int64 = 60_000

    func testFreshValueKeepsTitleAndLine() {
        let time = now - 10 * minute
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: time, now: now, intervalMinutes: 15, locale: en), "BTC")
        XCTAssertEqual(WatchFreshness.inlineLine("BTC 84,450", time: time, now: now, intervalMinutes: 15, locale: en), "BTC 84,450")
        // Genau 45 Min. ist noch nicht veraltet
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: now - 45 * minute, now: now, intervalMinutes: 15, locale: en), "BTC")
    }

    func testOutdatedValueShowsAgeInOneUnit() {
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: now - 47 * minute, now: now, intervalMinutes: 15, locale: en), "BTC 47m")
        XCTAssertEqual(WatchFreshness.shortTitle("USD", time: now - 3 * 60 * minute - 5 * minute, now: now, intervalMinutes: 15, locale: en),
                       "USD 3h")
        XCTAssertEqual(WatchFreshness.inlineLine("CHF 12,345.67", time: now - 2 * 24 * 60 * minute, now: now, intervalMinutes: 15, locale: en),
                       "CHF 12,345.67 · 2d")
    }

    /// Intervall 60 auf dem iPhone: veraltet erst nach 3 Std. (wie Merkliste und Widgets), nicht schon nach 45 Min.
    func testLimitFollowsPhoneInterval() {
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: now - 47 * minute, now: now, intervalMinutes: 60, locale: en),
                       "BTC")
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: now - 181 * minute, now: now, intervalMinutes: 60, locale: en),
                       "BTC 3h")
        XCTAssertEqual(WatchFreshness.inlineLine("BTC 84,450", time: now - 91 * minute, now: now, intervalMinutes: 30,
                                                 locale: en), "BTC 84,450 · 1h")
    }

    func testNeverLoadedOrJustNowHasNoAge() {
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: 0, now: now, intervalMinutes: 15, locale: en), "BTC")
        XCTAssertNil(WatchFreshness.shortAge(now - 30_000, now: now, locale: en))
        XCTAssertEqual(WatchFreshness.shortAge(now - 47 * minute, now: now, locale: en), "47m")
    }
}
