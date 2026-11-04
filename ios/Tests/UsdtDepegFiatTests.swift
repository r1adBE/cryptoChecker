import XCTest
@testable import CryptoChecker

/// Fiat-Werte aus der USDT-Reihe mit USDT/USD je Tag (Review 194): BTC in USDT flach, USDT/USD 1.00 → 0.90,
/// CHF je USD 0.90 — in CHF −10 %. Wie `UsdtDepegFiatTest.kt`.
final class UsdtDepegFiatTests: XCTestCase {

    private let day: Int64 = 86_400_000
    private func candle(_ at: Int64, _ price: Double) -> MarketCandle {
        MarketCandle(openTime: at, open: price, high: price, low: price, close: price, volume: 0, quoteVolume: nil)
    }
    private let fallback = SheetCandleRequest(base: "BTC", quote: "USDT", convert: true)
    private var flat: [MarketCandle] { [candle(20 * day, 100_000), candle(21 * day, 100_000)] }

    func testDayChangeFromUsdtSeriesIncludesTheDepeg() throws {
        let ref = DayReference(open: 100_000, lastClose: 100_000)
        XCTAssertEqual(try XCTUnwrap(DayChange.fromUsdtSeries(81_000, ref, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9,
                                                              usdtUsd: 0.9, usdtUsdAtOpen: 1.0)), -10, accuracy: 1e-9)
        XCTAssertFalse(DayChange.usdtSeriesApproximate(usdtUsd: 0.9, usdtUsdAtOpen: 1.0))
        // Ohne USDT/USD: 0 % (Näherung, markiert)
        XCTAssertEqual(try XCTUnwrap(DayChange.fromUsdtSeries(81_000, ref, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9)),
                       0, accuracy: 1e-9)
        XCTAssertTrue(DayChange.usdtSeriesApproximate(usdtUsd: nil, usdtUsdAtOpen: 1.0))
        // Kein Schluss zur Eröffnung, live 0.90: beide Enden 0.90 → 0 % statt erfundener −10 %
        XCTAssertFalse(DayChange.usdtSeriesApproximate(usdtUsd: 0.9, usdtUsdAtOpen: nil))
        XCTAssertTrue(DayChange.usdtSeriesHistoryMissing(usdtUsd: 0.9, usdtUsdAtOpen: nil))
        XCTAssertEqual(try XCTUnwrap(DayChange.fromUsdtSeries(81_000, ref, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9,
                                                              usdtUsd: 0.9)), 0, accuracy: 1e-9)
        // Schluss da, aber kein Live-Kurs: beide Enden 1 → 0 % statt +11 %
        XCTAssertTrue(DayChange.usdtSeriesApproximate(usdtUsd: nil, usdtUsdAtOpen: 0.9))
        XCTAssertEqual(try XCTUnwrap(DayChange.fromUsdtSeries(90_000, ref, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9,
                                                              usdtUsdAtOpen: 0.9)), 0, accuracy: 1e-9)
        // Ohne Devisenkurs zur Eröffnung weiterhin kein Wert
        XCTAssertNil(DayChange.fromUsdtSeries(81_000, ref, usdToQuote: 0.9, usdToQuoteAtOpen: nil,
                                              usdtUsd: 0.9, usdtUsdAtOpen: 1.0))
    }

    func testChartUsesTheUsdtCloseOfEachDay() throws {
        let usdt = UsdtUsdRates(live: 0.9, daily: [20: 1.0])
        let rates: [Int: Double] = [20: 0.9, 21: 0.9]
        let daily = try XCTUnwrap(SheetChart.accept(flat, request: fallback, price: 81_000, dailyRates: rates,
                                                    usdt: usdt, todayEpochDay: 21))
        XCTAssertEqual(daily[0].close, 90_000, accuracy: 1e-6)
        XCTAssertEqual(daily[1].close, 81_000, accuracy: 1e-6)
        XCTAssertEqual(try XCTUnwrap(SheetChart.changePercent(start: daily[0].close, value: daily[1].close)), -10,
                       accuracy: 1e-9)
        // Ein Devisenkurs für alle Tage: USDT/USD trotzdem je Tag
        let current = try XCTUnwrap(SheetChart.accept(flat, request: fallback, price: 81_000, currentRate: 0.9,
                                                      usdt: usdt, todayEpochDay: 21))
        XCTAssertEqual(current[0].close, 90_000, accuracy: 1e-6)
        XCTAssertEqual(current[1].close, 81_000, accuracy: 1e-6)
        XCTAssertFalse(SheetChart.usdtApproximate(daily, usdt: usdt, todayEpochDay: 21))
    }

    func testMissingHistoryNeverInventsAStep() throws {
        let rates: [Int: Double] = [20: 0.9, 21: 0.9]
        // Review 195: nur Merkliste (keine gespeicherten Schlüsse), live 0.90, BTC flach in USDT → keine Stufe
        let noClose = UsdtUsdRates(live: 0.9)
        let chart = try XCTUnwrap(SheetChart.accept(flat, request: fallback, price: 81_000, dailyRates: rates,
                                                    usdt: noClose, todayEpochDay: 21))
        XCTAssertEqual(chart[0].close, 81_000, accuracy: 1e-6)
        XCTAssertEqual(chart[1].close, 81_000, accuracy: 1e-6)
        let series = SheetChart.usdtSeries(chart, usdt: noClose, todayEpochDay: 21)
        XCTAssertFalse(series.approximate)
        XCTAssertTrue(series.historyMissing)
        XCTAssertTrue(SheetChart.usdtHistoryNote(.daily, sourcePair: "BTC/USDT", historyMissing: series.historyMissing))
        XCTAssertFalse(SheetChart.usdtParityNote(.daily, sourcePair: "BTC/USDT", usdtApproximate: series.approximate))
        // Mit einem Devisenkurs ebenso
        let current = try XCTUnwrap(SheetChart.accept(flat, request: fallback, price: 81_000, currentRate: 0.9,
                                                      usdt: noClose, todayEpochDay: 21))
        XCTAssertEqual(current[0].close, current[1].close, accuracy: 1e-6)
        // Schluss für Tag 20, aber kein Live-Kurs: ganze Reihe 1, Vermerk «USDT = 1»
        let noLive = SheetChart.usdtSeries(flat, usdt: UsdtUsdRates(live: nil, daily: [20: 1.0]), todayEpochDay: 21)
        XCTAssertEqual(noLive.rates, [1, 1])
        XCTAssertTrue(noLive.approximate)
        XCTAssertFalse(noLive.historyMissing)
        // Gar nichts: immer Vermerk «USDT = 1»
        XCTAssertTrue(SheetChart.usdtApproximate(flat, usdt: nil, todayEpochDay: 21))
        // Echter Einbruch: Schluss 0.40 gespeichert und live 0.40 → beide Tage 0.40, keine Stufe
        let crash = UsdtUsdRates(live: 0.4, daily: [20: 0.4])
        let crashed = try XCTUnwrap(SheetChart.accept(flat, request: fallback, price: 36_000, dailyRates: rates,
                                                      usdt: crash, todayEpochDay: 21))
        XCTAssertEqual(crashed[0].close, 36_000, accuracy: 1e-6)
        XCTAssertEqual(crashed[1].close, 36_000, accuracy: 1e-6)
        XCTAssertFalse(SheetChart.usdtSeries(crashed, usdt: crash, todayEpochDay: 21).historyMissing)
    }

    func testSparklineCheckDividesByUsdtUsd() {
        let closes: [Double] = [100, 100]
        XCTAssertNotNil(SheetChart.sparklineFor(closes, price: 56, quoteUsd: 1.25, usdtUsd: 0.7))
        XCTAssertNil(SheetChart.sparklineFor(closes, price: 56, quoteUsd: 1.25))
        // USDT-Quote: Faktor enthält USDT/USD schon
        XCTAssertNotNil(SheetChart.sparklineFor(closes, price: 100, quoteUsd: 0.7, usdtUsd: 0.7))
    }
}
