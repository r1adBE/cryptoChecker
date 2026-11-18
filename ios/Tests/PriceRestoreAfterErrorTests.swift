import XCTest
@testable import CryptoChecker

/// Kurs-Mitteilung nach einem Aussetzer (wie Android `PriceUpdateRule` mit `refresh`): Unter der Schwelle wird eine
/// noch stehende Mitteilung mit dem neuen Kurs nachgezogen, der Bezug bleibt; ohne Aussetzer bleibt sie, wie sie ist.
final class PriceRestoreAfterErrorTests: XCTestCase {
    private func watch(error: String?) -> Watch {
        var w = Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        w.notificationEnabled = true
        w.notifiedPrice = 100
        w.lastError = error
        return w
    }

    private func run(_ w: Watch, price: Double) -> (PriceRefresher.Outcome, [Watch], [Int64]) {
        var settings = AppSettings()
        settings.priceNotifications = true
        settings.notificationChangePercent = 5
        var updated = w
        updated.lastPrice = price
        updated.lastError = nil
        var outcome = PriceRefresher.Outcome()
        var cancel: [Int64] = []
        var posts: [Watch] = []
        PriceRefresher.priceEffects(watch: w, updated: updated, price: price, now: 1_000, settings: settings,
                                    speechAllowed: false, spokeAlarm: false, live: false,
                                    outcome: &outcome, cancelIds: &cancel, pricePosts: &posts)
        return (outcome, posts, cancel)
    }

    func testBelowThresholdAfterErrorRefreshesOnlyIfShown() {
        let (outcome, posts, cancel) = run(watch(error: "timeout"), price: 102)
        XCTAssertEqual(posts.map(\.id), [1])
        XCTAssertEqual(posts.first?.lastPrice, 102)
        XCTAssertEqual(outcome.priceRefreshOnly, [1])
        XCTAssertNil(outcome.notified[1], "Bezug bleibt")
        XCTAssertTrue(cancel.isEmpty)
    }

    func testBelowThresholdWithoutErrorLeavesNotification() {
        let (outcome, posts, _) = run(watch(error: nil), price: 102)
        XCTAssertTrue(posts.isEmpty)
        XCTAssertTrue(outcome.priceRefreshOnly.isEmpty)
    }

    func testAboveThresholdAfterErrorIsANormalPost() {
        let (outcome, posts, _) = run(watch(error: "timeout"), price: 110)
        XCTAssertEqual(posts.map(\.id), [1])
        XCTAssertTrue(outcome.priceRefreshOnly.isEmpty)
        XCTAssertEqual(outcome.notified[1]?.0, 110)
    }

    func testStillShownCountsSingleAndOverflow() {
        typealias E = PriceDeliveredState.Entry
        let state = PriceDeliveredState(entries: [
            E(identifier: "price-1", date: Date(timeIntervalSince1970: 10), title: "BTC", userInfo: [:]),
            E(identifier: Notifier.priceOverflowId, date: Date(timeIntervalSince1970: 5), title: "+1",
              userInfo: [Notifier.userInfoOverflowIds: [NSNumber(value: 7)], Notifier.userInfoOverflowLines: ["SOL"]])
        ])
        XCTAssertTrue(state.stillShown(1))
        XCTAssertTrue(state.stillShown(7))
        XCTAssertFalse(state.stillShown(2))
    }
}
