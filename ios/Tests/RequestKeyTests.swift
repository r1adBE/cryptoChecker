import XCTest
@testable import CryptoChecker

/// Vergleichsschlüssel ohne `timestamp` (`request_key.json`, geteilt mit Android). Wie `RequestKeyTest.kt`.
final class RequestKeyTests: XCTestCase {
    private typealias S = TestSupport

    func testParityCases() throws {
        let data = try S.fixture("request_key")
        for c in S.list(data["cases"]) {
            let url = c["url"] as? String ?? ""
            XCTAssertEqual(RequestKey.of(url), c["expected"] as? String, url)
        }
    }

    func testBingXStampedAddressesCompareEqual() throws {
        for market in [BingX(), BingXFutures()] as [Market] {
            let first = try XCTUnwrap(market.bulkTickersURL(call: 0))
            Thread.sleep(forTimeInterval: 0.002)
            let second = try XCTUnwrap(market.bulkTickersURL(call: 0))
            // Echte Adressen verschieden (Stempel), Schlüssel gleich: kein zweiter identischer Abruf
            XCTAssertNotEqual(first, second)
            XCTAssertEqual(RequestKey.of(first), RequestKey.of(second))
            XCTAssertTrue(LowMemoryBulk.isUnfiltered(market, pairIds: ["BTC-USDT"]))
        }
        let info = PairQuery("BTC", "USDT", "BTC-USDT", .none)
        let single = BingX().tickerAddress(call: 0, info: info)
        Thread.sleep(forTimeInterval: 0.002)
        XCTAssertEqual(RequestKey.of(single), RequestKey.of(BingX().tickerAddress(call: 0, info: info)))
        XCTAssertTrue(RequestKey.of(single).hasSuffix("symbol=BTC-USDT"))
    }
}
