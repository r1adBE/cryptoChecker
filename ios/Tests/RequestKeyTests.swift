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
        for exchange in [BingX(), BingXFutures()] as [Exchange] {
            let first = try XCTUnwrap(exchange.bulkTickersURL(call: 0))
            // Dieselbe Abfrage eine Millisekunde später (fester Stempel statt Warten auf die Uhr)
            let second = try restamped(first)
            // Echte Adressen verschieden (Stempel), Schlüssel gleich: kein zweiter identischer Abruf
            XCTAssertNotEqual(first, second)
            XCTAssertEqual(RequestKey.of(first), RequestKey.of(second))
            XCTAssertTrue(LowMemoryBulk.isUnfiltered(exchange, pairIds: ["BTC-USDT"]))
        }
        let info = PairQuery("BTC", "USDT", "BTC-USDT", .none)
        let single = BingX().tickerAddress(call: 0, info: info)
        XCTAssertEqual(RequestKey.of(single), RequestKey.of(try restamped(single)))
        XCTAssertTrue(RequestKey.of(single).hasSuffix("symbol=BTC-USDT"))
    }

    /// `url` mit dem Stempel `timestamp` + 1 ms (die Adresse muss einen tragen).
    private func restamped(_ url: String) throws -> String {
        let range = try XCTUnwrap(url.range(of: "timestamp=[0-9]+", options: .regularExpression),
                                  "kein timestamp in \(url)")
        let value = try XCTUnwrap(Int64(url[range].dropFirst("timestamp=".count)))
        return url.replacingCharacters(in: range, with: "timestamp=\(value + 1)")
    }
}
