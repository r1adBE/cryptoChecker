import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `widget_quantity.json` (Android: `WidgetQuantityParityTest.kt`).
final class WidgetQuantityFormatTests: XCTestCase {

    private typealias S = TestSupport

    func testCases() throws {
        let cases = S.list(try S.fixture("widget_quantity")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 30)
        for c in cases {
            let text = c["text"] as? String
            let decimal = c["decimal"] as? String ?? "."
            let grouping = c["grouping"] as? String ?? ","
            XCTAssertEqual(WidgetQuantityFormat.format(text, decimal: decimal, grouping: grouping),
                           c["expected"] as? String, "format '\(text ?? "nil")' (\(decimal) \(grouping))")
        }
    }

    func testFromDouble() throws {
        for c in S.list(try S.fixture("widget_quantity")["fromDouble"]) {
            let value = try XCTUnwrap(Double(c["value"] as? String ?? ""))
            XCTAssertEqual(WidgetQuantityFormat.format(DecimalText.plain(value), decimal: ".", grouping: ","),
                           c["expected"] as? String, "fromDouble \(c["value"] ?? "")")
        }
    }

    func testHiddenAndSpoken() {
        XCTAssertNil(WidgetQuantityFormat.visible("1.3", hidden: true))
        XCTAssertNil(WidgetQuantityFormat.visible(nil, hidden: false))
        XCTAssertEqual(WidgetQuantityFormat.spokenName("BTC", quantity: "1.3"), "1.3 BTC")
        XCTAssertEqual(WidgetQuantityFormat.spokenName("BTC", quantity: nil), "BTC")
    }

    /// Menge je Position aus dem Bestand (inkl. beobachteter Adressen), als Dezimaltext.
    func testTopPositionsCarryQuantity() {
        let values: [String: Double?] = ["BTC": 60_000, "ETH": 3_000, "XRP": nil]
        let top = PortfolioWidgetStore.topPositions(valuesUsd: values, totalUsd: 63_000, factor: 1, changes: [:],
                                                    amounts: ["BTC": 1.13, "ETH": 1.2e-5])
        XCTAssertEqual(top.positions.map(\.quantity), ["1.13", "0.000012"])
        let old = PortfolioWidgetStore.topPositions(valuesUsd: ["BTC": 1], totalUsd: 1, factor: 1, changes: [:])
        XCTAssertNil(old.positions.first?.quantity)
    }

    /// Ältere gespeicherte Positionen ohne `quantity` laden weiter; neue behalten die Menge.
    func testPositionDecodesWithoutQuantity() throws {
        let old = Data(#"{"symbol":"BTC","value":100,"sharePercent":50}"#.utf8)
        let decoded = try JSONDecoder().decode(PortfolioWidgetPosition.self, from: old)
        XCTAssertEqual(decoded.symbol, "BTC")
        XCTAssertNil(decoded.quantity)
        let fresh = PortfolioWidgetPosition(symbol: "ETH", value: 1, sharePercent: 1, change24hPercent: nil,
                                            quantity: "12.4567")
        let round = try JSONDecoder().decode(PortfolioWidgetPosition.self, from: JSONEncoder().encode(fresh))
        XCTAssertEqual(round.quantity, "12.4567")
    }
}
