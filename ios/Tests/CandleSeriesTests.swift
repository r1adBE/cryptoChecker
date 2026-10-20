import XCTest
@testable import CryptoChecker

/// Wie `CandleSeriesTest.kt` (Prüfung der Kerzenreihen; ohne den «Warum»-Teil).
final class CandleSeriesTests: XCTestCase {

    private let h = ActivityAnalyzer.hourMillis
    private var t0: Int64 { 1_700_000_000_000 - 1_700_000_000_000 % ActivityAnalyzer.hourMillis }
    /// Mitten in der laufenden (27.) Stunde der Reihe ab `t0`.
    private var now: Int64 { t0 + 26 * h + 30 * 60_000 }

    /// 27 Stundenkerzen ab `start`; Schluss steigt je Stunde um `step`, Umsatz `volume`.
    private func series(start: Int64? = nil, step: Double = 0.1, volume: Double = 100) -> [MarketCandle] {
        let first = start ?? t0
        return (0..<27).map { i in
            let close = 100 + Double(i) * step
            return MarketCandle(openTime: first + Int64(i) * h, open: close - step, high: close, low: close - step,
                                close: close, volume: volume)
        }
    }

    func testFreshSeriesIsOk() {
        XCTAssertEqual(CandleSeries.status(series(), now: now, tickerChange24h: 2), .ok)
        XCTAssertEqual(CandleSeries.status(series(), now: now, tickerChange24h: nil), .ok)
    }

    func testMissingOrTooShort() {
        XCTAssertEqual(CandleSeries.status(nil, now: now, tickerChange24h: nil), .missing)
        XCTAssertEqual(CandleSeries.status([], now: now, tickerChange24h: nil), .missing)
        XCTAssertEqual(CandleSeries.status(Array(series().prefix(1)), now: now, tickerChange24h: nil), .missing)
    }

    func testStaleAfterTwoHours() {
        let lastOpen = series().last!.openTime
        XCTAssertEqual(CandleSeries.status(series(), now: lastOpen + 2 * h, tickerChange24h: nil), .ok)
        XCTAssertEqual(CandleSeries.status(series(), now: lastOpen + 2 * h + 60_000, tickerChange24h: nil), .stale)
        // Delisteter Spot-Markt: Kerzen enden Monate vor «jetzt»
        let delisted = series(start: t0 - 400 * 24 * h)
        XCTAssertEqual(CandleSeries.status(delisted, now: now, tickerChange24h: 4.77), .stale)
        XCTAssertNil(CandleSeries.usable(delisted, now: now, tickerChange24h: 4.77))
    }

    func testFlatWhileTickerMoves() {
        let flat = series(step: 0)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: 4.77), .flat)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: -0.5), .flat)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: 0.01), .ok)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: nil), .ok)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: .nan), .ok)
    }

    func testNoVolumeAtAll() {
        XCTAssertEqual(CandleSeries.status(series(volume: 0), now: now, tickerChange24h: nil), .noVolume)
    }

    func testUsableReturnsTheSameCandles() {
        let candles = series()
        XCTAssertEqual(CandleSeries.usable(candles, now: now, tickerChange24h: 1), candles)
    }

    func testIsFlatTolerance() {
        XCTAssertTrue(CandleSeries.isFlat(series(step: 0)))
        XCTAssertFalse(CandleSeries.isFlat(series(step: 0.001)))
        XCTAssertFalse(CandleSeries.isFlat([]))
    }

    func testIsLivePerInterval() {
        let candles = series()
        let lastOpen = candles.last!.openTime
        XCTAssertTrue(CandleSeries.isLive(candles, intervalMillis: h, now: lastOpen + 24 * h))
        XCTAssertFalse(CandleSeries.isLive(candles, intervalMillis: h, now: lastOpen + 24 * h + 1))
        XCTAssertTrue(CandleSeries.isLive(candles, intervalMillis: 7 * 24 * h, now: lastOpen + 27 * 24 * h))
        XCTAssertFalse(CandleSeries.isLive(candles, intervalMillis: 7 * 24 * h, now: lastOpen + 29 * 24 * h))
        XCTAssertFalse(CandleSeries.isLive(nil, intervalMillis: h, now: now))
        XCTAssertFalse(CandleSeries.isLive([], intervalMillis: h, now: now))
    }

    func testChange24hPrefersTicker() {
        XCTAssertEqual(CandleSeries.change24h(ticker: 4.77, candles: 0), 4.77)
        XCTAssertEqual(CandleSeries.change24h(ticker: nil, candles: 1.5), 1.5)
        XCTAssertEqual(CandleSeries.change24h(ticker: .nan, candles: 1.5), 1.5)
        XCTAssertNil(CandleSeries.change24h(ticker: nil, candles: nil))
    }

    private struct W {
        let base: String
        let quote: String
        let spot: Bool
        let perp: Bool
        let id: Int
    }

    private func pick(_ items: [W], _ symbol: String) -> Int? {
        CandleSeries.pickWatch(items, symbol: symbol, base: { $0.base }, quote: { $0.quote },
                               isSpot: { $0.spot }, isPerpetual: { $0.perp })?.id
    }

    func testPickWatchSpotFirstThenPerpetual() {
        let perp = W(base: "XMR", quote: "USDT", spot: false, perp: true, id: 1)
        let spotEur = W(base: "XMR", quote: "EUR", spot: true, perp: false, id: 2)
        let spotUsdt = W(base: "xmr", quote: "USDT", spot: true, perp: false, id: 3)
        let quarterly = W(base: "XMR", quote: "USDT", spot: false, perp: false, id: 4)
        XCTAssertEqual(pick([perp, spotEur, spotUsdt, quarterly], "XMR"), 3)
        XCTAssertEqual(pick([perp, spotEur, quarterly], "XMR"), 2)
        XCTAssertEqual(pick([quarterly, perp], "xmr"), 1)
        XCTAssertEqual(pick([quarterly], "XMR"), 4)
        XCTAssertNil(pick([perp], "BTC"))
    }

    func testCoinbaseOlderWindowOnlyForFullPageAndMissingCandles() {
        let day = 24 * h
        let first = t0
        // 1 Jahr Tageskerzen: 300 geladen, 65 fehlen → Fenster mit 65 Kerzen direkt davor
        let window = CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 365, granularityMillis: day)
        XCTAssertEqual(window?.upperBound, first - day)
        XCTAssertEqual(window?.lowerBound, first - 65 * day)
        // Höchstens eine Seite
        XCTAssertEqual(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 1000,
                                                        granularityMillis: day)?.lowerBound, first - 300 * day)
        // Seite nicht voll (Paar jünger) oder genug Kerzen: keine weitere Anfrage
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 120, needed: 365, granularityMillis: day))
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 300, granularityMillis: day))
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 30, granularityMillis: day))
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 365, granularityMillis: 0))
    }

    func testMergeAscendingSortsAndDropsDuplicates() {
        let newer = Array(series().prefix(3))
        let older = Array(series(start: t0 - 2 * h).prefix(3)) // überlappt in t0 und t0 + h
        let merged = CandleSeries.mergeAscending(older: older, newer: newer)
        XCTAssertEqual(merged.map(\.openTime), [t0 - 2 * h, t0 - h, t0, t0 + h, t0 + 2 * h])
        // Bei gleichem Beginn gewinnt die neuere Seite
        XCTAssertEqual(merged[2], newer[0])
    }
}

/// Chart im Aktionsblatt — wie die Ergänzungen in `SheetChartTest.kt` (Tageskurse, Kurssprung,
/// Ziehen an den Rändern, Beschriftung, Achse, genähertes Volumen).
final class SheetChartRulesTests: XCTestCase {
    private let day: Int64 = 86_400_000

    private func candle(_ d: Int64, _ h: Int64, _ price: Double, volume: Double = 10,
                        quoteVolume: Double? = nil) -> MarketCandle {
        MarketCandle(openTime: d * day + h * 3_600_000, open: price, high: price, low: price, close: price,
                     volume: volume, quoteVolume: quoteVolume)
    }

    func testFxEachCandleWithTheRateOfItsDay() throws {
        let fallback = SheetCandleRequest(base: "BTC", quote: "USDT", convert: true)
        let rates: [Int: Double] = [10: 0.9, 13: 0.8]
        let candles = [candle(9, 0, 100), candle(10, 5, 100), candle(10, 23, 100), candle(12, 12, 100), candle(13, 1, 100)]
        // Kurs des Paars = umgerechneter Schluss: Tageskurse allein (Faktor 1)
        let plain = try XCTUnwrap(SheetChart.accept(candles, request: fallback, price: 80, dailyRates: rates))
        XCTAssertEqual(plain.map(\.close), [90, 90, 90, 90, 80])
        // Ohne Kurs des Paars nicht prüfbar; 50 % neben dem umgerechneten Schluss: anderer Token
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: nil, dailyRates: rates))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 120, dailyRates: rates))
        let anchored = try XCTUnwrap(SheetChart.accept(candles, request: fallback, price: 84, dailyRates: rates))
        XCTAssertEqual(anchored.last?.close ?? 0, 84, accuracy: 1e-9)
        XCTAssertEqual(anchored.first?.close ?? 0, 94.5, accuracy: 1e-9)
        XCTAssertEqual(SheetChart.fxMode(fallback, dailyRates: rates), .daily)
    }

    func testFallbackSameTokenCheckedThenScaled() throws {
        // Wie SheetChartTest.kt «accept usdt fallback - same token checked, then scaled to the pair price»
        let fallback = SheetCandleRequest(base: "BTC", quote: "USDT", convert: true)
        let candles = [candle(1, 0, 100), candle(2, 0, 98), candle(3, 0, 110)]
        let converted = try XCTUnwrap(SheetChart.accept(candles, request: fallback, price: 55, currentRate: 0.5))
        XCTAssertEqual(converted.last?.close ?? 0, 55, accuracy: 1e-9)
        XCTAssertEqual(converted.first?.close ?? 0, 50, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(SheetChart.accept(candles, request: fallback, price: 55, currentRate: 0.45)).last?.close ?? 0,
                       55, accuracy: 1e-9)
        // Reihe endet bei 110, Paar steht bei 55 (Kurs 1): anderer Token, kein Chart
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 55, currentRate: 1))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 55, currentRate: 0.3))
        // Ohne Devisenkurs bzw. ohne Kurs des Paars nicht prüfbar
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 55))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 55, currentRate: 0))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 55, currentRate: .nan))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: nil, currentRate: 0.5))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 0, currentRate: 0.5))
    }

    func testFallbackGapRuleAtTwentyFivePercent() {
        let fallback = SheetCandleRequest(base: "BTC", quote: "USDT", convert: true)
        let candles = [candle(1, 0, 100), candle(2, 0, 100)]
        XCTAssertNotNil(SheetChart.accept(candles, request: fallback, price: 99, currentRate: 0.8))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 101, currentRate: 0.8))
        XCTAssertNotNil(SheetChart.accept(candles, request: fallback, price: 61, currentRate: 0.8))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 59, currentRate: 0.8))
        // Mit Tageskursen zählt der Kurs des Tags der letzten Kerze, nicht der aktuelle
        XCTAssertNotNil(SheetChart.accept(candles, request: fallback, price: 80, dailyRates: [1: 0.5, 2: 0.8], currentRate: 0.5))
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 50, dailyRates: [1: 0.5, 2: 0.8], currentRate: 0.5))
    }

    func testSparklineOnlyForTheSameToken() {
        // Wie SheetChartTest.kt «sparkline - same token check»
        let closes: [Double] = [100, 102, 110]
        XCTAssertEqual(SheetChart.sparklineFor(closes, price: 111, quoteUsd: 1), closes)
        XCTAssertEqual(SheetChart.sparklineFor(closes, price: 100, quoteUsd: 1.1), closes)
        XCTAssertNil(SheetChart.sparklineFor(closes, price: 0.5, quoteUsd: 1))
        XCTAssertNil(SheetChart.sparklineFor(closes, price: nil, quoteUsd: 1))
        XCTAssertNil(SheetChart.sparklineFor(closes, price: 110, quoteUsd: nil))
        XCTAssertNil(SheetChart.sparklineFor(closes, price: 110, quoteUsd: .nan))
        XCTAssertNil(SheetChart.sparklineFor([110], price: 110, quoteUsd: 1))
        XCTAssertNil(SheetChart.sparklineFor(nil, price: 110, quoteUsd: 1))
        XCTAssertNil(SheetChart.sparklineFor([100, 0], price: 110, quoteUsd: 1))
    }

    func testTodayRateCaptionOnlyForOneCurrentRate() {
        let chf = SheetChart.todayRateCaption(.current, sourcePair: "BTC/USDT", quote: "chf")
        XCTAssertEqual(chf?.source, "USDT")
        XCTAssertEqual(chf?.target, "CHF")
        XCTAssertEqual(SheetChart.todayRateCaption(.current, sourcePair: nil, quote: "EUR")?.source, "USDT")
        // Tageskurse je Kerze oder Kerzen des Paars selbst: keine Zeile «mit heutigem Kurs» (wie Android)
        XCTAssertNil(SheetChart.todayRateCaption(.daily, sourcePair: "BTC/USDT", quote: "CHF"))
        XCTAssertNil(SheetChart.todayRateCaption(.none, sourcePair: "BTC/CHF", quote: "CHF"))
        XCTAssertNil(SheetChart.todayRateCaption(.current, sourcePair: "BTC/USDT", quote: " "))
    }

    /// Vermerk «USDT = 1 angenommen» nur bei Umrechnung aus der USDT-Reihe (wie Android).
    func testUsdtParityNoteOnlyForUsdtSeries() {
        XCTAssertTrue(SheetChart.usdtParityNote(.daily, sourcePair: "BTC/USDT"))
        XCTAssertTrue(SheetChart.usdtParityNote(.current, sourcePair: nil))
        XCTAssertFalse(SheetChart.usdtParityNote(.none, sourcePair: "BTC/CHF"))
        XCTAssertFalse(SheetChart.usdtParityNote(.daily, sourcePair: "BTC/USDC"))
    }

    func testFxWithoutDailyRatesUsesCurrentFactor() throws {
        let fallback = SheetCandleRequest(base: "BTC", quote: "USDT", convert: true)
        let candles = [candle(1, 0, 100), candle(2, 0, 110)]
        let converted = try XCTUnwrap(SheetChart.accept(candles, request: fallback, price: 55, dailyRates: [:],
                                                        currentRate: 0.5))
        XCTAssertEqual(converted.map(\.close), [50, 55])
        // Weder Tages- noch aktueller Devisenkurs: nicht prüfbar, kein Chart
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: 55, dailyRates: [:]))
        XCTAssertEqual(SheetChart.fxMode(fallback, dailyRates: [1: 0, 2: .nan]), .current)
        XCTAssertEqual(SheetChart.fxMode(SheetCandleRequest(base: "BTC", quote: "CHF", convert: false), dailyRates: [1: 0.9]), .none)
        XCTAssertNil(SheetChart.accept(candles, request: fallback, price: nil, dailyRates: nil))
        XCTAssertEqual(SheetChart.utcEpochDay(19 * day + 5), 19)
        XCTAssertEqual(SheetChart.utcEpochDay(-5), -1)
    }

    func testPriceJumpRule() {
        XCTAssertFalse(SheetChart.priceJumped(reference: 100, price: 102.99))
        XCTAssertTrue(SheetChart.priceJumped(reference: 100, price: 103.01))
        XCTAssertTrue(SheetChart.priceJumped(reference: 100, price: 96))
        XCTAssertFalse(SheetChart.priceJumped(reference: nil, price: 50))
        XCTAssertFalse(SheetChart.priceJumped(reference: 100, price: nil))
        XCTAssertFalse(SheetChart.priceJumped(reference: 100, price: 0))
    }

    func testScrubIndexClampedAtEdges() {
        let slot: CGFloat = 300 / 365
        XCTAssertEqual(SheetChart.scrubIndex(x: 0, plotLeft: 0, slot: slot, count: 365), 0)
        XCTAssertEqual(SheetChart.scrubIndex(x: -0.01, plotLeft: 0, slot: slot, count: 365), 0)
        XCTAssertEqual(SheetChart.scrubIndex(x: 300, plotLeft: 0, slot: slot, count: 365), 364)
        XCTAssertEqual(SheetChart.scrubIndex(x: 10_000, plotLeft: 0, slot: slot, count: 365), 364)
        XCTAssertNil(SheetChart.scrubIndex(x: .nan, plotLeft: 0, slot: slot, count: 365))
    }

    func testLabelTemplatesAndStamp() {
        XCTAssertEqual(PriceChartRange.day.sheetTimeTemplate, "EEEjm")
        XCTAssertEqual(PriceChartRange.week.sheetTimeTemplate, "EEEdMMMjm")
        XCTAssertEqual(PriceChartRange.month.sheetTimeTemplate, "EEEdMMM")
        XCTAssertEqual(PriceChartRange.year.sheetTimeTemplate, "dMMMy")
        XCTAssertEqual(SheetChart.stampMillis(lastOpenTime: 1_000, intervalMillis: 10_000, fetchedAt: 5_000), 5_000)
        XCTAssertEqual(SheetChart.stampMillis(lastOpenTime: 1_000, intervalMillis: 10_000, fetchedAt: 50_000), 11_000)
        XCTAssertEqual(SheetChart.stampMillis(lastOpenTime: 1_000, intervalMillis: 10_000, fetchedAt: 0), 11_000)
        let utc = TimeZone(identifier: "UTC") ?? .current
        XCTAssertEqual(SheetChart.stampTemplate(stamp: 10 * day + 1_000, now: 10 * day + 80_000_000, timeZone: utc), "jm")
        XCTAssertEqual(SheetChart.stampTemplate(stamp: 9 * day + 80_000_000, now: 10 * day + 1_000, timeZone: utc), "dMMMjm")
    }

    func testAxisMinimumSpan() throws {
        let peg = [
            MarketCandle(openTime: 0, open: 1.0001, high: 1.0001, low: 0.9998, close: 0.9998, volume: 1),
            MarketCandle(openTime: 3_600_000, open: 0.9998, high: 1.0002, low: 0.9998, close: 1.0002, volume: 1),
        ]
        let stable = try XCTUnwrap(SheetChart.axisLevels(peg, type: .candles, stable: true))
        XCTAssertEqual(stable.low, 0.995, accuracy: 1e-9)
        XCTAssertEqual(stable.high, 1.005, accuracy: 1e-9)
        let other = try XCTUnwrap(SheetChart.axisLevels(peg, type: .candles, stable: false))
        XCTAssertEqual(other.low, 0.999, accuracy: 1e-9)
        XCTAssertEqual(other.high, 1.001, accuracy: 1e-9)
        let wide = [candle(0, 0, 97), candle(0, 1, 111)]
        XCTAssertEqual(SheetChart.axisLevels(wide, type: .line, stable: true),
                       WidgetChartGeometry.levels(wide, type: .line))
    }

    func testVolumeApproximatedPassthrough() throws {
        XCTAssertTrue(SheetChart.isVolumeApproximated(nil))
        XCTAssertTrue(SheetChart.isVolumeApproximated(.nan))
        XCTAssertFalse(SheetChart.isVolumeApproximated(12))
        let candles = [candle(1, 0, 100, volume: 10), candle(2, 0, 110, volume: 10, quoteVolume: 2_000)]
        let daily = try XCTUnwrap(SheetChart.accept(candles, request: SheetCandleRequest(base: "BTC", quote: "USDT", convert: true),
                                                    price: 55, dailyRates: [1: 0.5]))
        XCTAssertEqual(daily.map(\.volumeApproximated), [true, false])
        XCTAssertEqual(daily.last?.quoteVolume ?? 0, 1_000, accuracy: 1e-9)
        let prepared = SheetChart.prepare(candles, type: .candles, range: .day, stable: false)
        XCTAssertNotNil(prepared.volumes)
        XCTAssertEqual(prepared.candles.count, 2)
    }
}
