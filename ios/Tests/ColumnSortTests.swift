import XCTest
@testable import CryptoChecker

/// Wie `ColumnSortTest.kt`.
final class ColumnSortTests: XCTestCase {
    private struct Row { let name: String; let fav: Bool; let chf: Double?; let change: Double? }

    private let rows = [
        Row(name: "ETH/USDT", fav: false, chf: 2064, change: -0.02),
        Row(name: "btc/USDT", fav: true, chf: 68643, change: 0),
        Row(name: "SOL/CHF", fav: false, chf: 118.4, change: 3.85),
        Row(name: "XRP/USDT", fav: false, chf: nil, change: nil),
        Row(name: "ADA/USDT", fav: true, chf: 0.5, change: 1.2),
    ]

    private func names(_ sort: ColumnSort?) -> [String] {
        ColumnSort.apply(rows, sort, favorite: { $0.fav }, name: { $0.name }, value: { $0.chf }, change: { $0.change })
            .map(\.name)
    }

    func testCycle() {
        let name = ColumnSort.next(nil, tapped: .NAME)
        XCTAssertEqual(name, ColumnSort(key: .NAME, descending: false))
        XCTAssertEqual(ColumnSort.next(name, tapped: .NAME), ColumnSort(key: .NAME, descending: true))
        XCTAssertNil(ColumnSort.next(ColumnSort(key: .NAME, descending: true), tapped: .NAME))
        let price = ColumnSort.next(name, tapped: .PRICE)
        XCTAssertEqual(price, ColumnSort(key: .PRICE, descending: true))
        XCTAssertEqual(ColumnSort.next(price, tapped: .PRICE), ColumnSort(key: .PRICE, descending: false))
        XCTAssertNil(ColumnSort.next(ColumnSort(key: .PRICE, descending: false), tapped: .PRICE))
    }

    func testOwnOrderWithoutSort() {
        XCTAssertEqual(names(nil), rows.map(\.name))
    }

    func testFavoritesStayOnTopAndUnknownLast() {
        XCTAssertEqual(names(ColumnSort(key: .PRICE, descending: true)), ["btc/USDT", "ADA/USDT", "ETH/USDT", "SOL/CHF", "XRP/USDT"])
        XCTAssertEqual(names(ColumnSort(key: .PRICE, descending: false)), ["ADA/USDT", "btc/USDT", "SOL/CHF", "ETH/USDT", "XRP/USDT"])
        XCTAssertEqual(names(ColumnSort(key: .CHANGE, descending: true)), ["ADA/USDT", "btc/USDT", "SOL/CHF", "ETH/USDT", "XRP/USDT"])
    }

    func testNameIgnoresCase() {
        XCTAssertEqual(names(ColumnSort(key: .NAME, descending: false)), ["ADA/USDT", "btc/USDT", "ETH/USDT", "SOL/CHF", "XRP/USDT"])
    }

    func testEncodeDecode() {
        for key in SortKey.allCases {
            for d in [true, false] {
                let s = ColumnSort(key: key, descending: d)
                XCTAssertEqual(ColumnSort.decode(s.encoded), s)
            }
        }
        XCTAssertNil(ColumnSort.decode(nil))
        XCTAssertNil(ColumnSort.decode("VOLUME_DESC"))
        XCTAssertNil(ColumnSort.decode("PRICE"))
    }
}
