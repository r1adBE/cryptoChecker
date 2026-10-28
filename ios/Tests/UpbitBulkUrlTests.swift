import XCTest
@testable import CryptoChecker

/// Upbit: viele Paare auf mehrere gefilterte URLs verteilt (sonst HTTP 414) — wie `UpbitBulkUrlTest.kt`.
final class UpbitBulkUrlTests: XCTestCase {

    private let many: [String] = (1...400).map { String(format: "KRW-C%03d", $0) }

    func testLongListsAreSplit() throws {
        let exchange = Upbit()
        let count = exchange.bulkTickersRequestCount(pairIds: many)
        XCTAssertGreaterThan(count, 1)
        var ids = Set<String>()
        for i in 0..<count {
            let url = try XCTUnwrap(exchange.bulkTickersURL(call: i, pairIds: many))
            XCTAssertLessThan(url.count, 2000)
            XCTAssertTrue(url.hasPrefix("https://api.upbit.com/v1/ticker?markets="))
            let list = url.components(separatedBy: "markets=").last ?? ""
            ids.formUnion(list.split(separator: ",").map(String.init))
        }
        XCTAssertEqual(ids, Set(many))
        XCTAssertNil(exchange.bulkTickersURL(call: count, pairIds: many))
        // Wenige Paare: eine Anfrage wie bisher
        XCTAssertEqual(exchange.bulkTickersRequestCount(pairIds: ["KRW-ETH", "KRW-BTC"]), 1)
        XCTAssertEqual(exchange.bulkTickersURL(call: 0, pairIds: ["KRW-ETH", "KRW-BTC"]),
                       "https://api.upbit.com/v1/ticker?markets=KRW-BTC,KRW-ETH")
        // Bithumb (ohne ungefilterten Ersatz) bleibt bei einer Anfrage
        XCTAssertEqual(Bithumb().bulkTickersRequestCount(pairIds: many), 1)
    }
}
