import XCTest
@testable import CryptoChecker

/// Gemeinsame Fälle für Chart im Aktionsblatt und Mini-Chart (`Parity/sheet_chart.json`) — wie
/// `SheetChartParityTest.kt`: gleicher Token der Ausweich-Reihe (25 %, mit Devisenkurs).
final class SheetChartParityTests: XCTestCase {

    private func near(_ label: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTFail("\(label): expected value missing") }
        guard let actual else { return XCTFail("\(label): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), max(abs(expected) * 1e-9, 1e-9), label)
    }

    func testAccept() throws {
        let cases = TestSupport.list(try TestSupport.fixture("sheet_chart")["accept"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let candles: [MarketCandle] = (c["candles"] as? [Any] ?? []).compactMap { raw in
                guard let pair = raw as? [Any], pair.count == 2,
                      let day = TestSupport.number(pair[0]), let price = TestSupport.number(pair[1]) else { return nil }
                return MarketCandle(openTime: Int64(day) * 86_400_000, open: price, high: price, low: price,
                                    close: price, volume: 0, quoteVolume: nil)
            }
            var rates: [Int: Double]? = nil
            if let raw = c["dailyRates"] as? [String: Any] {
                var out: [Int: Double] = [:]
                for (k, v) in raw { if let d = Int(k), let n = TestSupport.number(v) { out[d] = n } }
                rates = out
            }
            let request = SheetCandleRequest(base: "BTC", quote: "USDT", convert: c["convert"] as? Bool ?? false)
            let result = SheetChart.accept(candles, request: request, price: TestSupport.number(c["price"]),
                                           dailyRates: rates, currentRate: TestSupport.number(c["currentRate"]))
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(result, "\(name): expected no chart")
                continue
            }
            guard let result else {
                XCTFail("\(name): expected a chart")
                continue
            }
            near("\(name) first", TestSupport.number(e["first"]), result.first?.close)
            near("\(name) last", TestSupport.number(e["last"]), result.last?.close)
        }
    }

    func testWidget() throws {
        let cases = TestSupport.list(try TestSupport.fixture("sheet_chart")["widget"])
        XCTAssertGreaterThanOrEqual(cases.count, 6)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let candles: [MarketCandle] = (c["candles"] as? [Any] ?? []).compactMap { raw in
                guard let pair = raw as? [Any], pair.count == 2,
                      let day = TestSupport.number(pair[0]), let price = TestSupport.number(pair[1]) else { return nil }
                return MarketCandle(openTime: Int64(day) * 86_400_000, open: price, high: price, low: price,
                                    close: price, volume: 0, quoteVolume: nil)
            }
            let result = WidgetChartGeometry.pairCandles(candles, price: TestSupport.number(c["price"]))
            XCTAssertEqual(result != nil, c["shown"] as? Bool ?? false, name)
        }
    }

    func testSparkline() throws {
        let cases = TestSupport.list(try TestSupport.fixture("sheet_chart")["sparkline"])
        XCTAssertGreaterThanOrEqual(cases.count, 6)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let closes = (c["closes"] as? [Any] ?? []).compactMap { TestSupport.number($0) }
            let result = SheetChart.sparklineFor(closes, price: TestSupport.number(c["price"]),
                                                 quoteUsd: TestSupport.number(c["quoteUsd"]))
            XCTAssertEqual(result != nil, c["shown"] as? Bool ?? false, name)
            if let result { XCTAssertEqual(result, closes, name) }
        }
    }
}
