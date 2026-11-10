import XCTest
@testable import CryptoChecker

/// Kleine Uhr-Komplikationen (rund, Ecke, Zeile): veraltete Werte zeigen das Alter knapp («BTC 47m»), wie Android
/// der Kurztitel (`TimeDifferenceStyle.SHORT_SINGLE_UNIT`). Grenze wie `WatchFreshness.isOutdated` (45 Min.).
final class WatchShortAgeTests: XCTestCase {
    private let en = Locale(identifier: "en_US")
    private let now: Int64 = 1_800_000_000_000
    private let minute: Int64 = 60_000

    func testFreshValueKeepsTitleAndLine() {
        let time = now - 10 * minute
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: time, now: now, locale: en), "BTC")
        XCTAssertEqual(WatchFreshness.inlineLine("BTC 84,450", time: time, now: now, locale: en), "BTC 84,450")
        // Genau 45 Min. ist noch nicht veraltet
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: now - 45 * minute, now: now, locale: en), "BTC")
    }

    func testOutdatedValueShowsAgeInOneUnit() {
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: now - 47 * minute, now: now, locale: en), "BTC 47m")
        XCTAssertEqual(WatchFreshness.shortTitle("USD", time: now - 3 * 60 * minute - 5 * minute, now: now, locale: en),
                       "USD 3h")
        XCTAssertEqual(WatchFreshness.inlineLine("CHF 12,345.67", time: now - 2 * 24 * 60 * minute, now: now, locale: en),
                       "CHF 12,345.67 · 2d")
    }

    func testNeverLoadedOrJustNowHasNoAge() {
        XCTAssertEqual(WatchFreshness.shortTitle("BTC", time: 0, now: now, locale: en), "BTC")
        XCTAssertNil(WatchFreshness.shortAge(now - 30_000, now: now, locale: en))
        XCTAssertEqual(WatchFreshness.shortAge(now - 47 * minute, now: now, locale: en), "47m")
    }
}
