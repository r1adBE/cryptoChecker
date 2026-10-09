import XCTest
@testable import CryptoChecker

/// Herkunft und Alter in den Zeilen des Markt-Tabs — wie `DataFreshnessTest.kt`.
final class DataFreshnessTests: XCTestCase {

    private let minute: Int64 = 60_000
    private let hour: Int64 = 3_600_000

    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    private func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Int64 {
        let date = calendar.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    func testJustNowUnderOneMinuteAndInFuture() {
        let now = at(2026, 10, 7, 14, 5)
        XCTAssertEqual(DataFreshness.age(savedAt: now, now: now, calendar: calendar), .justNow)
        XCTAssertEqual(DataFreshness.age(savedAt: now - minute + 1, now: now, calendar: calendar), .justNow)
        // Uhr verstellt: Zeitpunkt in der Zukunft
        XCTAssertEqual(DataFreshness.age(savedAt: now + 5 * minute, now: now, calendar: calendar), .justNow)
    }

    func testMinutesUnderOneHour() {
        let now = at(2026, 10, 7, 14, 5)
        XCTAssertEqual(DataFreshness.age(savedAt: now - minute, now: now, calendar: calendar), .minutes(1))
        XCTAssertEqual(DataFreshness.age(savedAt: now - 3 * minute - 59_000, now: now, calendar: calendar), .minutes(3))
        XCTAssertEqual(DataFreshness.age(savedAt: now - hour + 1, now: now, calendar: calendar), .minutes(59))
        // Kurz nach Mitternacht: noch Minuten, nicht Datum
        XCTAssertEqual(DataFreshness.age(savedAt: at(2026, 10, 6, 23, 50), now: at(2026, 10, 7, 0, 10), calendar: calendar),
                       .minutes(20))
    }

    func testTodayFromOneHourElseDate() {
        let now = at(2026, 10, 7, 14, 5)
        let twoAm = at(2026, 10, 7, 2, 0)
        XCTAssertEqual(DataFreshness.age(savedAt: now - hour, now: now, calendar: calendar), .today(now - hour))
        XCTAssertEqual(DataFreshness.age(savedAt: twoAm, now: now, calendar: calendar), .today(twoAm))
        let yesterday = at(2026, 10, 6, 23, 59)
        XCTAssertEqual(DataFreshness.age(savedAt: yesterday, now: now, calendar: calendar), .date(yesterday))
    }

    func testStaleAfterThreeTtl() {
        let now = at(2026, 10, 7, 14, 5)
        let ttl = CycleCachePolicy.global
        XCTAssertFalse(DataFreshness.isStale(savedAt: now - 3 * ttl, now: now, ttl: ttl))
        XCTAssertTrue(DataFreshness.isStale(savedAt: now - 3 * ttl - 1, now: now, ttl: ttl))
        XCTAssertFalse(DataFreshness.isStale(savedAt: now + hour, now: now, ttl: ttl))
        XCTAssertFalse(DataFreshness.isStale(savedAt: 0, now: now, ttl: 0))
        // Gas (1 Min.): nach 3 Min. veraltet
        XCTAssertTrue(DataFreshness.isStale(savedAt: now - 4 * minute, now: now, ttl: CycleCachePolicy.gas))
    }

    func testProviderNames() {
        XCTAssertEqual(DataFreshness.providers("Binance", nil, " ", "Coin Metrics", "Binance"), "Binance, Coin Metrics")
        XCTAssertNil(DataFreshness.providers(nil, ""))
        XCTAssertEqual(DataFreshness.candleProvider(host: "data-api.binance.vision"), "Binance")
        XCTAssertEqual(DataFreshness.candleProvider(host: "fapi.binance.com"), "Binance")
        XCTAssertEqual(DataFreshness.candleProvider(host: "api.binance.us"), "Binance.US")
        XCTAssertEqual(DataFreshness.candleProvider(host: "api.exchange.coinbase.com"), "Coinbase")
        XCTAssertEqual(DataFreshness.siteName("https://ethereum-rpc.publicnode.com"), "publicnode.com")
        XCTAssertEqual(DataFreshness.siteName("https://eth.llamarpc.com/"), "llamarpc.com")
        XCTAssertEqual(DataFreshness.siteName("https://cloudflare-eth.com"), "cloudflare-eth.com")
        XCTAssertEqual(DataFreshness.siteName("https://mempool.space/api/v1/fees/recommended"), "mempool.space")
    }
}
