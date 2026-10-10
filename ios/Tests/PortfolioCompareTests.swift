import XCTest
@testable import CryptoChecker

/// Vergleich Währung gegen USDT in Prozent und Währungseffekt — wie `PortfolioCompareTest.kt`.
final class PortfolioCompareTests: XCTestCase {

    private let d = 1e-9

    private func points(_ values: [Double], from: Int = 100) -> [PortfolioHistoryPoint] {
        values.enumerated().map { PortfolioHistoryPoint(epochDay: from + $0.offset, value: $0.element) }
    }

    private func build(_ currency: [Double], _ usdt: [Double],
                       file: StaticString = #filePath, line: UInt = #line) throws -> PortfolioCompareSeries {
        try XCTUnwrap(PortfolioCompare.build(currency: points(currency), usdt: points(usdt)), file: file, line: line)
    }

    func testPercentSinceFirstPointAndCurrencyEffect() throws {
        // In USDT +12 %, in CHF nur +7 % → Währungseffekt −5 Prozentpunkte
        let c = try build([900, 920, 963], [1000, 1050, 1120])
        XCTAssertEqual(c.epochDays, [100, 101, 102])
        XCTAssertEqual(c.currency[0], 0, accuracy: d)
        XCTAssertEqual(c.usdt[0], 0, accuracy: d)
        XCTAssertEqual(c.usdt[1], 5, accuracy: d)
        XCTAssertEqual(c.usdt[2], 12, accuracy: d)
        XCTAssertEqual(c.currency[2], 7, accuracy: d)
        XCTAssertEqual(c.effect, -5, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(c.effect(at: 1)), 920.0 / 900.0 * 100 - 100 - 5, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(c.effect(at: 0)), 0, accuracy: d)
        XCTAssertNil(c.effect(at: 3))
        XCTAssertNil(c.effect(at: -1))
        XCTAssertEqual(c.lastIndex, 2)
    }

    func testEmptyStartUsesFirstPositivePointAsBase() throws {
        // «Seit 1. Kauf»: am Anfang noch nichts im Portfolio
        let c = try build([0, 0, 180, 216], [0, 0, 200, 220])
        XCTAssertEqual(c.epochDays, [102, 103])
        XCTAssertEqual(try XCTUnwrap(c.usdt.last), 10, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(c.currency.last), 20, accuracy: d)
        XCTAssertEqual(c.effect, 10, accuracy: d)
    }

    func testBaseNeedsBothSeriesPositive() {
        XCTAssertEqual(PortfolioCompare.baseIndex(currency: [1, 2, 3], usdt: [0, 2, 3]), 1)
        XCTAssertEqual(PortfolioCompare.baseIndex(currency: [-1, .nan, 3], usdt: [5, 5, 5]), 2)
        XCTAssertNil(PortfolioCompare.baseIndex(currency: [0, 0], usdt: [0, 0]))
        XCTAssertNil(PortfolioCompare.baseIndex(currency: [], usdt: []))
    }

    func testNoComparisonWithoutBaseOrEnoughPoints() {
        // Durchgehend leer
        XCTAssertNil(PortfolioCompare.build(currency: points([0, 0, 0]), usdt: points([0, 0, 0])))
        // Negativ (sollte nicht vorkommen) zählt nicht als Ausgangswert
        XCTAssertNil(PortfolioCompare.build(currency: points([-5, -3]), usdt: points([-5, -3])))
        // Erst der letzte Tag hat einen Wert: nur ein Punkt ab dem Ausgangspunkt
        XCTAssertNil(PortfolioCompare.build(currency: points([0, 0, 10]), usdt: points([0, 0, 11])))
        // Zu kurz
        XCTAssertNil(PortfolioCompare.build(currency: points([10]), usdt: points([11])))
        XCTAssertNil(PortfolioCompare.build(currency: [], usdt: []))
    }

    func testSeriesMustMatchDayByDay() {
        XCTAssertNil(PortfolioCompare.build(currency: points([1, 2, 3]), usdt: points([1, 2])))
        XCTAssertNil(PortfolioCompare.build(currency: points([1, 2], from: 100), usdt: points([1, 2], from: 101)))
    }

    func testValuesDroppingToZeroGiveMinusHundred() throws {
        let c = try build([50, 0], [40, 0])
        XCTAssertEqual(try XCTUnwrap(c.currency.last), -100, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(c.usdt.last), -100, accuracy: d)
        XCTAssertEqual(c.effect, 0, accuracy: d)
    }

    func testWithDailyFxRatesFlatUsdtShowsOnlyTheCurrencyEffect() throws {
        // 1000 USDT gleichbleibend, der Franken legt zu (0.80 → 0.76 CHF je USD)
        let series = PortfolioHistorySeries(points: points([1000, 1000, 1000, 1000, 1000]), skipped: [],
                                            change: 0, changePercent: 0, tradesInRange: false)
        let rates: [Int: Double] = [100: 0.80, 101: 0.79, 102: 0.78, 103: 0.77]
        let converted = PortfolioHistoryFx.convert(series, dailyRates: rates, currentRate: 0.76, todayEpochDay: 104)
        let c = try XCTUnwrap(PortfolioCompare.build(currency: converted.series.points, usdt: series.points))
        XCTAssertEqual(try XCTUnwrap(c.usdt.last), 0, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(c.currency.last), (0.76 / 0.80 - 1) * 100, accuracy: d)
        XCTAssertEqual(c.effect, (0.76 / 0.80 - 1) * 100, accuracy: d)
        // Der Wert in der Währung entspricht der Veränderung des konvertierten Verlaufs
        XCTAssertEqual(try XCTUnwrap(c.currency.last), try XCTUnwrap(converted.series.changePercent), accuracy: d)
    }

    func testBoundsCoverBothSeriesAndZero() throws {
        let up = try build([100, 110, 105], [100, 120, 130])
        XCTAssertEqual(PortfolioCompare.bounds(up).lo, 0, accuracy: d)
        XCTAssertEqual(PortfolioCompare.bounds(up).hi, 30, accuracy: d)
        let down = try build([100, 90], [100, 80])
        XCTAssertEqual(PortfolioCompare.bounds(down).lo, -20, accuracy: d)
        XCTAssertEqual(PortfolioCompare.bounds(down).hi, 0, accuracy: d)
    }

    func testEffectiveViewFallsBackToCurrency() {
        // Ohne unumgerechneten Verlauf (USD gewählt oder nur heutiger Kurs): immer in der Währung
        for view in PortfolioHistoryView.allCases {
            XCTAssertEqual(PortfolioCompare.effectiveView(view, usdtAvailable: false, compareAvailable: false), .currency)
            XCTAssertEqual(PortfolioCompare.effectiveView(view, usdtAvailable: false, compareAvailable: true), .currency)
        }
        XCTAssertEqual(PortfolioCompare.effectiveView(.usdt, usdtAvailable: true, compareAvailable: false), .usdt)
        XCTAssertEqual(PortfolioCompare.effectiveView(.compare, usdtAvailable: true, compareAvailable: false), .currency)
        XCTAssertEqual(PortfolioCompare.effectiveView(.compare, usdtAvailable: true, compareAvailable: true), .compare)
        XCTAssertEqual(PortfolioCompare.effectiveView(.currency, usdtAvailable: true, compareAvailable: true), .currency)
    }

    func testViewDecodesFromStoredSettings() throws {
        var s = AppSettings()
        XCTAssertEqual(s.portfolioHistoryView, .currency)
        s.portfolioHistoryView = .compare
        let back = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(back.portfolioHistoryView, .compare)
        // Unbekannter Wert (neuere Version): Standard in der Währung
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(s)) as? [String: Any])
        json["portfolioHistoryView"] = "bogus"
        let unknown = try JSONDecoder().decode(AppSettings.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(unknown.portfolioHistoryView, .currency)
    }
}
