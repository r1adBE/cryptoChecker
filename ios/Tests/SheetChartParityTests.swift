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
            // Tag darf gebrochen sein (Stundenkerzen: 20.5 = 12:00 UTC am Tag 20)
            let candles: [MarketCandle] = (c["candles"] as? [Any] ?? []).compactMap { raw in
                guard let pair = raw as? [Any], pair.count == 2,
                      let day = TestSupport.number(pair[0]), let price = TestSupport.number(pair[1]) else { return nil }
                return MarketCandle(openTime: Int64(day * 86_400_000), open: price, high: price, low: price,
                                    close: price, volume: 0, quoteVolume: nil)
            }
            let rates = byDay(c["dailyRates"])
            let request = SheetCandleRequest(base: "BTC", quote: "USDT", convert: c["convert"] as? Bool ?? false)
            let live = TestSupport.number(c["usdtLive"])
            let daily = byDay(c["usdtDaily"])
            let usdt: UsdtUsdRates? = live != nil || daily != nil ? UsdtUsdRates(live: live, daily: daily ?? [:]) : nil
            let today = TestSupport.number(c["today"]).map { Int($0) }
                ?? SheetChart.utcEpochDay(candles.last?.openTime ?? 0)
            let result = SheetChart.accept(candles, request: request, price: TestSupport.number(c["price"]),
                                           dailyRates: rates, currentRate: TestSupport.number(c["currentRate"]),
                                           usdt: usdt, todayEpochDay: today)
            if let assumed = c["usdtAssumed"] as? Bool {
                if let result {
                    let note = SheetChart.usdtParityNote(
                        SheetChart.fxMode(request, dailyRates: rates), sourcePair: "BTC/USDT",
                        usdtApproximate: SheetChart.usdtApproximate(result, usdt: usdt, todayEpochDay: today))
                    XCTAssertEqual(note, assumed, "\(name): note «USDT = 1 assumed»")
                } else {
                    XCTFail("\(name): note needs a chart")
                }
            }
            if let missing = c["usdtHistoryMissing"] as? Bool {
                if let result {
                    let note = SheetChart.usdtHistoryNote(
                        SheetChart.fxMode(request, dailyRates: rates), sourcePair: "BTC/USDT",
                        historyMissing: SheetChart.usdtSeries(result, usdt: usdt, todayEpochDay: today).historyMissing)
                    XCTAssertEqual(note, missing, "\(name): note «USDT/USD history not available»")
                } else {
                    XCTFail("\(name): note needs a chart")
                }
            }
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

    private func byDay(_ raw: Any?) -> [Int: Double]? {
        guard let raw = raw as? [String: Any] else { return nil }
        var out: [Int: Double] = [:]
        for (k, v) in raw { if let d = Int(k), let n = TestSupport.number(v) { out[d] = n } }
        return out
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
                                                 quoteUsd: TestSupport.number(c["quoteUsd"]),
                                                 usdtUsd: TestSupport.number(c["usdtUsd"]))
            XCTAssertEqual(result != nil, c["shown"] as? Bool ?? false, name)
            if let result { XCTAssertEqual(result, closes, name) }
        }
    }
}
