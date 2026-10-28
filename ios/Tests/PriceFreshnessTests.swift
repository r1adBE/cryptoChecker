import XCTest
@testable import CryptoChecker

/// Frische des Kurses: gemeinsame Fälle `Parity/price_freshness.json` (Gegenstück
/// `PriceFreshnessTest.kt`) und die Regeln für ein Paar.
final class PriceFreshnessTests: XCTestCase {
    private typealias S = TestSupport

    private func optionalMillis(_ value: Any?) -> Int64? {
        S.number(value).map { Int64($0) }
    }

    func testFreshnessParity() throws {
        let data = try S.fixture("price_freshness")
        for c in S.list(data["freshness"]) {
            XCTAssertEqual(PriceFreshness.freshnessTime(lastUpdate: S.millis(c["lastUpdate"]),
                                                        exchangeTime: optionalMillis(c["exchangeTime"])),
                           S.millis(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testStaleParity() throws {
        let data = try S.fixture("price_freshness")
        let now = S.millis(data["now"])
        for c in S.list(data["stale"]) {
            let lastUpdate = optionalMillis(c["lastUpdateAge"]).map { now - $0 } ?? 0
            let exchangeTime = optionalMillis(c["exchangeTimeAge"]).map { now - $0 }
            let expected = (c["expected"] as? String).flatMap(PriceFreshness.StaleReason.init(rawValue:))
            XCTAssertEqual(PriceFreshness.staleReason(lastUpdate: lastUpdate, exchangeTime: exchangeTime, now: now,
                                                      staleAfter: S.millis(c["staleAfter"])),
                           expected, c["name"] as? String ?? "")
        }
    }

    func testUnchangedParity() throws {
        let data = try S.fixture("price_freshness")
        let now = S.millis(data["now"])
        for c in S.list(data["unchanged"]) {
            let changedAt = optionalMillis(c["priceChangedAtAge"]).map { now - $0 } ?? 0
            let lastUpdate = optionalMillis(c["lastUpdateAge"]).map { now - $0 } ?? 0
            let expected = optionalMillis(c["expected"]).map { now - $0 }
            XCTAssertEqual(PriceFreshness.unchangedSince(exchangeTime: optionalMillis(c["exchangeTime"]),
                                                         priceChangedAt: changedAt, lastUpdate: lastUpdate,
                                                         now: now, outdatedAfter: S.millis(c["outdatedAfter"])),
                           expected, c["name"] as? String ?? "")
        }
    }

    func testNextParity() throws {
        let data = try S.fixture("price_freshness")
        for c in S.list(data["next"]) {
            XCTAssertEqual(PriceFreshness.nextPriceChangedAt(previousPrice: S.number(c["previousPrice"]),
                                                             previousChangedAt: S.millis(c["previousChangedAt"]),
                                                             price: S.number(c["price"]) ?? 0,
                                                             time: S.millis(c["time"])),
                           S.millis(c["expected"]), c["name"] as? String ?? "")
        }
    }

    private let now: Int64 = 1_700_000_000_000

    private func watch(exchangeTime: Int64? = nil, changedAgo: Int64 = 2 * 3_600_000, disabled: Bool = false,
                       error: String? = nil, price: Double? = 62_000) -> Watch {
        var w = Watch(id: 1, exchangeKey: "Kraken", exchangeName: "Kraken", baseAsset: "BTC", quoteAsset: "USD")
        w.lastPrice = price
        w.lastUpdate = now - 10_000
        w.lastError = error
        w.disabled = disabled
        w.priceChangedAt = now - changedAgo
        w.exchangeTime = exchangeTime
        return w
    }

    func testWatchWithoutExchangeTimeShowsUnchanged() {
        XCTAssertEqual(PriceFreshness.unchangedSince(watch(), now: now, outdatedAfter: 120_000), now - 2 * 3_600_000)
        XCTAssertEqual(PriceFreshness.freshnessTime(watch()), now - 10_000)
    }

    func testWatchNeverUnchangedWhenDisabledFailedWithoutPriceOrWithExchangeTime() {
        XCTAssertNil(PriceFreshness.unchangedSince(watch(disabled: true), now: now, outdatedAfter: 120_000))
        XCTAssertNil(PriceFreshness.unchangedSince(watch(error: "timeout"), now: now, outdatedAfter: 120_000))
        XCTAssertNil(PriceFreshness.unchangedSince(watch(price: nil), now: now, outdatedAfter: 120_000))
        XCTAssertNil(PriceFreshness.unchangedSince(watch(exchangeTime: now - 5_000), now: now, outdatedAfter: 120_000))
        XCTAssertEqual(PriceFreshness.freshnessTime(watch(exchangeTime: now - 5_000)), now - 5_000)
    }

    func testStaleFollowsTheAlarmBlock() {
        // Eben abgefragt, letzter Handel laut Börse 3 h her: veraltet wie die Alarmsperre
        let old = watch(exchangeTime: now - 3 * 3_600_000)
        XCTAssertEqual(PriceFreshness.staleReason(old, now: now, staleAfter: 120_000), .trade)
        XCTAssertTrue(PriceFreshness.isStale(old, now: now, staleAfter: 120_000))
        XCTAssertTrue(AlarmFreshness.blocksAlarms(old.exchangeTime, markFallback: false, now: now))
        // Ruhiges Paar (Handel vor 1 h) bzw. ohne Zeit der Börse: frisch
        XCTAssertFalse(PriceFreshness.isStale(watch(exchangeTime: now - 3_600_000), now: now, staleAfter: 120_000))
        XCTAssertFalse(PriceFreshness.isStale(watch(), now: now, staleAfter: 120_000))
        // Deaktiviert nie; noch nie abgefragt immer
        XCTAssertFalse(PriceFreshness.isStale(watch(exchangeTime: now - 3 * 3_600_000, disabled: true),
                                              now: now, staleAfter: 120_000))
        var never = watch()
        never.lastUpdate = 0
        XCTAssertTrue(PriceFreshness.isStale(never, now: now, staleAfter: 120_000))
    }

    func testLimitIsAtLeastOneHourOrTheOutdatedLimit() {
        XCTAssertEqual(PriceFreshness.unchangedAfterMillis(outdatedAfter: 120_000), 3_600_000)
        XCTAssertEqual(PriceFreshness.unchangedAfterMillis(outdatedAfter: 2_700_000), 3_600_000)
        XCTAssertEqual(PriceFreshness.unchangedAfterMillis(outdatedAfter: 10_800_000), 10_800_000)
    }

    /// Frische unbekannt (0) und ohne Börsenzeit (Feld nicht geschrieben); gesetzte Werte bleiben erhalten.
    func testWatchDecodingDefaultsAndRoundTrip() throws {
        let minimal = #"{"id":1,"exchangeKey":"Kraken","baseAsset":"BTC","quoteAsset":"USD","lastPrice":1.5,"lastUpdate":5,"exchangeName":"Kraken","contractType":0,"sortOrder":0,"notificationEnabled":true,"ttsEnabled":false,"notifiedAt":0,"favorite":false,"disabled":false,"priceChangedAt":0}"#
        let decoded = try JSONDecoder().decode(Watch.self, from: Data(minimal.utf8))
        XCTAssertEqual(decoded.priceChangedAt, 0)
        XCTAssertNil(decoded.exchangeTime)

        var w = decoded
        w.priceChangedAt = 4
        w.exchangeTime = 3
        let again = try JSONDecoder().decode(Watch.self, from: JSONEncoder().encode(w))
        XCTAssertEqual(again.priceChangedAt, 4)
        XCTAssertEqual(again.exchangeTime, 3)
    }
}
