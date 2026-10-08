import XCTest
@testable import CryptoChecker

/// Wie `NotTradedTest.kt` (ohne den «Warum»-Teil).
final class NotTradedTests: XCTestCase {

    private func watch(_ id: Int64, _ error: String?) -> Watch {
        Watch(id: id, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT", lastError: error)
    }

    func testMarkerOnlyExactText() {
        XCTAssertTrue(NotTraded.isMarker(NotTraded.marker))
        XCTAssertFalse(NotTraded.isMarker(nil))
        XCTAssertFalse(NotTraded.isMarker("NETWORK_OFFLINE"))
        XCTAssertFalse(NotTraded.isMarker("wird an der börse nicht mehr gehandelt"))
    }

    func testShownChangeHiddenForNotTraded() {
        XCTAssertNil(NotTraded.shownChange24h(lastError: NotTraded.marker, change24h: 13.9))
        XCTAssertEqual(NotTraded.shownChange24h(lastError: nil, change24h: 13.9), 13.9)
        XCTAssertEqual(NotTraded.shownChange24h(lastError: "NETWORK_OFFLINE", change24h: -2), -2)
        XCTAssertNil(NotTraded.shownChange24h(lastError: nil, change24h: nil))
        var w = watch(1, NotTraded.marker)
        w.change24h = 5
        XCTAssertTrue(w.isNotTraded)
        XCTAssertNil(w.shownChange24h)
    }

    func testIdsAndWithoutIds() {
        let items = [watch(1, nil), watch(2, NotTraded.marker), watch(3, "x"), watch(4, NotTraded.marker)]
        let ids = NotTraded.ids(items)
        XCTAssertEqual(ids, [2, 4])
        let signals: [Int64: String] = [1: "a", 2: "b", 3: "c"]
        XCTAssertEqual(NotTraded.withoutIds(signals, ids), [1: "a", 3: "c"])
        XCTAssertEqual(NotTraded.withoutIds([1: "a"], ids), [1: "a"])
        XCTAssertEqual(NotTraded.withoutIds(signals, []), signals)
    }
}
