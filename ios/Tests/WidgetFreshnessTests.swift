import XCTest
@testable import CryptoChecker

/// Frische in den Widgets (`WidgetFreshness`); Gegenstück `WidgetFreshnessTest.kt`.
final class WidgetFreshnessTests: XCTestCase {
    private let now: Int64 = 1_760_000_000_000
    private let hour: Int64 = 3_600_000
    private var after: Int64 { WidgetFreshness.afterMillis(backgroundIntervalMinutes: 15) } // 45 Min.

    private func watch(lastUpdateAgo: Int64 = 60_000, changedAgo: Int64 = 2 * 3_600_000, exchangeTime: Int64? = nil,
                       disabled: Bool = false, error: String? = nil) -> Watch {
        var w = Watch(id: 1, marketKey: "kraken", marketName: "Kraken", baseAsset: "BTC", quoteAsset: "EUR")
        w.lastPrice = 62_000
        w.lastUpdate = now - lastUpdateAgo
        w.lastError = error
        w.disabled = disabled
        w.priceChangedAt = now - changedAgo
        w.exchangeTime = exchangeTime
        return w
    }

    func testWidgetsUseThePollingLimitNeverTheLiveLimit() {
        XCTAssertEqual(WidgetFreshness.afterMillis(backgroundIntervalMinutes: 15), 45 * 60_000)
        XCTAssertEqual(WidgetFreshness.afterMillis(backgroundIntervalMinutes: 1), 15 * 60_000)
        XCTAssertEqual(WidgetFreshness.afterMillis(backgroundIntervalMinutes: 60), 180 * 60_000)
    }

    func testUnchangedPriceShowsSinceAndItsTime() {
        XCTAssertEqual(WidgetFreshness.unchangedSince(watch(), now: now, afterMillis: after), now - 2 * hour)
        XCTAssertEqual(WidgetFreshness.priceTime(watch(), now: now, afterMillis: after), now - 2 * hour)
    }

    func testRecentChangeIsFreshWithFetchTime() {
        let w = watch(changedAgo: 30 * 60_000)
        XCTAssertNil(WidgetFreshness.unchangedSince(w, now: now, afterMillis: after))
        XCTAssertEqual(WidgetFreshness.priceTime(w, now: now, afterMillis: after), now - 60_000)
    }

    func testExchangeTimeWinsAndNeverUnchanged() {
        let w = watch(exchangeTime: now - 5 * 60_000)
        XCTAssertNil(WidgetFreshness.unchangedSince(w, now: now, afterMillis: after))
        XCTAssertEqual(WidgetFreshness.priceTime(w, now: now, afterMillis: after), now - 5 * 60_000)
    }

    func testOutdatedDisabledOrFailedPairsShowNoUnchangedHint() {
        XCTAssertNil(WidgetFreshness.unchangedSince(watch(lastUpdateAgo: hour), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.unchangedSince(watch(disabled: true), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.unchangedSince(watch(error: "timeout"), now: now, afterMillis: after))
    }

    func testOutdatedLikeTheRowAndTheAlarmBlock() {
        // Abruf zu alt: Zeit des Abrufs
        XCTAssertEqual(WidgetFreshness.outdatedTime(watch(lastUpdateAgo: hour), now: now, afterMillis: after), now - hour)
        // Abruf frisch, letzter Handel 3 h her: Zeit des Handels; ruhiges Paar (1 h) nicht
        XCTAssertEqual(WidgetFreshness.outdatedTime(watch(exchangeTime: now - 3 * hour), now: now, afterMillis: after),
                       now - 3 * hour)
        XCTAssertNil(WidgetFreshness.outdatedTime(watch(exchangeTime: now - hour), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.outdatedTime(watch(), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.outdatedTime(watch(lastUpdateAgo: hour, disabled: true), now: now, afterMillis: after))
        // Wecker: Handel so verschoben, dass er mit derselben Grenze genau nach 2 h wechselt
        let w = watch(exchangeTime: now - hour)
        XCTAssertEqual(WidgetFreshness.outdatedTimes(w, afterMillis: after), [w.lastUpdate, now - hour + 2 * hour - after])
        XCTAssertEqual(OutdatedRule.nextChangeAt([now - hour + 2 * hour - after], now: now, afterMillis: after),
                       now + hour + 1)
    }
}
