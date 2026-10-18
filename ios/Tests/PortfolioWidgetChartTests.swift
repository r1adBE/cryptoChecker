import XCTest
@testable import CryptoChecker

/// Wie `PortfolioWidgetChartTest.kt`: Anzeige, Zeitraum und Chart des Portfolio-Widgets
/// (`PortfolioWidgetChart`, `PortfolioWidgetChartGeometry`, `PortfolioWidgetDailyStore.merge`).
final class PortfolioWidgetChartTests: XCTestCase {

    private let d = 1e-9
    private let zone = TimeZone(identifier: "Europe/Zurich")!
    private let today = LocalDay(2026, 10, 11).epochDay
    private let hour: Int64 = 3_600_000

    private func start(_ day: Int) -> Int64 { PortfolioWidgetChart.dayStart(day, timeZone: zone) }

    private func dayEnd(_ day: Int) -> Int64 { PortfolioHistory.dayEndMillis(day, timeZone: zone) }

    private func snapshot(total: Double = 900, totalUsdt: Double? = 1000, currency: String = "CHF",
                          change: Double? = 9, percent: Double? = 1, history: [PortfolioWidgetPoint] = [],
                          daily: [PortfolioHistoryPoint] = [],
                          periodChanges: [String: PortfolioWidgetChange]? = nil) -> PortfolioWidgetSnapshot {
        PortfolioWidgetSnapshot(total: total, changeAmount: change, changePercent: percent, currency: currency,
                                updatedAt: start(today) + 12 * hour, empty: false, totalUsdt: totalUsdt,
                                positions: [PortfolioWidgetPosition(symbol: "BTC", value: 900, sharePercent: 100,
                                                                    change24hPercent: 1)],
                                history: history, daily: daily.map(PortfolioWidgetDay.init),
                                periodChanges: periodChanges)
    }

    /// Stundenwerte, einer je Stunde bis Mittag.
    private func hourly(_ values: [Double]) -> [PortfolioWidgetPoint] {
        let end = start(today) + 12 * hour
        return values.enumerated().map { i, v in
            PortfolioWidgetPoint(at: end - Int64(values.count - 1 - i) * hour, value: v)
        }
    }

    /// Tagesverlauf der letzten `count` Tage bis `last`, Wert = 1000 + Index.
    private func daily(_ count: Int, last: Int? = nil) -> [PortfolioHistoryPoint] {
        let end = last ?? today
        return (0..<count).map { (i: Int) -> PortfolioHistoryPoint in PortfolioHistoryPoint(epochDay: end - (count - 1 - i), value: 1000 + Double(i)) }
    }

    // MARK: Anzeige

    func testDefaultDisplayIsConversionUnlessUsd() {
        XCTAssertEqual(PortfolioWidgetDisplay.defaultFor(currency: "CHF"), .conversion)
        // USD: USDT zum Marktkurs USDT/USD wie jede Fiat-Währung
        XCTAssertEqual(PortfolioWidgetDisplay.defaultFor(currency: "USD"), .conversion)
        XCTAssertEqual(PortfolioWidgetDisplay.defaultFor(currency: "USDT"), .usdt)
        XCTAssertEqual(PortfolioWidgetDisplay.defaultFor(currency: "USDC"), .usdt)
    }

    func testEffectiveDisplayCollapsesForUsdAndOldSnapshots() {
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.conversionUsdt, currency: "USDT", hasUsdt: true), .usdt)
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.conversion, currency: "usdc", hasUsdt: true), .usdt)
        // USD fällt nicht mehr zusammen (USDT zum Marktkurs USDT/USD)
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.conversion, currency: "USD", hasUsdt: true), .conversion)
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.conversionUsdt, currency: "USD", hasUsdt: true),
                       .conversionUsdt)
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.usdt, currency: "CHF", hasUsdt: false), .conversion)
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.conversionUsdt, currency: "CHF", hasUsdt: true),
                       .conversionUsdt)
    }

    func testUsdtDisplayDividesAmountsByTheSnapshotRate() {
        let s = PortfolioWidgetChart.displayed(snapshot(history: hourly([900, 891])), display: .usdt)
        XCTAssertEqual(s.currency, "USDT")
        XCTAssertEqual(s.total, 1000, accuracy: d)
        XCTAssertEqual(s.changeAmount ?? .nan, 10, accuracy: d)
        XCTAssertEqual(s.changePercent ?? .nan, 1, accuracy: d)
        XCTAssertEqual(s.topPositions.first?.value ?? .nan, 1000, accuracy: d)
        XCTAssertEqual(s.valueHistory.map(\.value), [1000, 990])
        XCTAssertEqual(PortfolioWidgetChart.fxFactor(s) ?? .nan, 1, accuracy: d)
        XCTAssertEqual(PortfolioWidgetChart.displayed(snapshot(), display: .conversion).currency, "CHF")
    }

    // MARK: Devisenkurs des Stands und Umrechnung beim Zeichnen

    func testFxRateFallsBackToTheRememberedRate() {
        let now: Int64 = 1_000_000_000
        let memo = PortfolioFxMemo(currency: "CHF", rate: 0.9, fetchedAt: now - hour)
        // Frischer Kurs geht vor
        XCTAssertEqual(PortfolioWidgetChart.fxRate(for: "CHF", fresh: 0.91, memo: memo, now: now) ?? .nan, 0.91, accuracy: d)
        // Ohne frischen Kurs (kein Netz, frischer Prozess): der gemerkte statt USD
        XCTAssertEqual(PortfolioWidgetChart.fxRate(for: "chf", fresh: nil, memo: memo, now: now) ?? .nan, 0.9, accuracy: d)
        XCTAssertEqual(PortfolioWidgetChart.fxRate(for: "CHF", fresh: Double.nan, memo: memo, now: now) ?? .nan, 0.9, accuracy: d)
        // Andere Währung, zu alt, aus der Zukunft: keiner
        var old = memo
        old.fetchedAt = now - 25 * hour
        var future = memo
        future.fetchedAt = now + hour
        XCTAssertNil(PortfolioWidgetChart.fxRate(for: "EUR", fresh: nil, memo: memo, now: now))
        XCTAssertNil(PortfolioWidgetChart.fxRate(for: "CHF", fresh: nil, memo: old, now: now))
        XCTAssertNil(PortfolioWidgetChart.fxRate(for: "CHF", fresh: nil, memo: future, now: now))
        XCTAssertNil(PortfolioWidgetChart.fxRate(for: "CHF", fresh: nil, memo: nil, now: now))
        // USD braucht keinen Kurs
        XCTAssertEqual(PortfolioWidgetChart.fxRate(for: "USD", fresh: nil, memo: nil, now: now) ?? .nan, 1, accuracy: d)
    }

    func testOnlyARateOfTheCurrencyIsRemembered() {
        let now: Int64 = 5_000
        XCTAssertEqual(PortfolioWidgetChart.fxMemoToSave(currency: "chf", fresh: 0.9, cachedForCurrency: 0.9,
                                                         fetchedAt: 4_000, now: now),
                       PortfolioFxMemo(currency: "CHF", rate: 0.9, fetchedAt: 4_000))
        XCTAssertEqual(PortfolioWidgetChart.fxMemoToSave(currency: "CHF", fresh: 0.9, cachedForCurrency: 0.9,
                                                         fetchedAt: nil, now: now)?.fetchedAt, now)
        // Kurs der vorigen Währung kurz nach dem Umstellen (FxRateSource kennt einen anderen bzw. keinen)
        XCTAssertNil(PortfolioWidgetChart.fxMemoToSave(currency: "CHF", fresh: 1.08, cachedForCurrency: 0.9,
                                                       fetchedAt: 4_000, now: now))
        XCTAssertNil(PortfolioWidgetChart.fxMemoToSave(currency: "CHF", fresh: 0.9, cachedForCurrency: nil,
                                                       fetchedAt: nil, now: now))
        XCTAssertNil(PortfolioWidgetChart.fxMemoToSave(currency: "CHF", fresh: nil, cachedForCurrency: 0.9,
                                                       fetchedAt: nil, now: now))
        XCTAssertNil(PortfolioWidgetChart.fxMemoToSave(currency: "USD", fresh: 1, cachedForCurrency: 1,
                                                       fetchedAt: nil, now: now))
    }

    func testUsdSnapshotIsRebasedToTheConversionCurrency() {
        // Ohne Devisenkurs aufgenommen (in USDT): Gesamtwert = USDT-Wert
        var usd = snapshot(total: 1000, totalUsdt: 1000, currency: "USDT", change: 10, history: hourly([1000, 990]))
        usd.positions = [PortfolioWidgetPosition(symbol: "BTC", value: 1000, sharePercent: 100, change24hPercent: 1,
                                                 pnlAmount: 10)]
        // Bis zum Umrechnen zeigt «Umrechnung» USDT
        XCTAssertEqual(PortfolioWidgetChart.effectiveDisplay(.conversion, currency: usd.currency, hasUsdt: true), .usdt)
        let chf = PortfolioWidgetChart.rebased(usd, currency: "CHF", rate: 0.9)
        XCTAssertEqual(chf.currency, "CHF")
        XCTAssertEqual(chf.total, 900, accuracy: d)
        XCTAssertEqual(chf.changeAmount ?? .nan, 9, accuracy: d)
        XCTAssertEqual(chf.topPositions.first?.value ?? .nan, 900, accuracy: d)
        XCTAssertEqual(chf.topPositions.first?.pnlAmount ?? .nan, 9, accuracy: d)
        XCTAssertEqual(chf.valueHistory.map(\.value), [900, 891])
        XCTAssertEqual(chf.totalUsdt ?? .nan, 1000, accuracy: d)
        // Anzeige → Werte und Währung
        let conversion = PortfolioWidgetChart.effectiveDisplay(.conversion, currency: chf.currency, hasUsdt: true)
        XCTAssertEqual(conversion, .conversion)
        XCTAssertEqual(PortfolioWidgetChart.displayed(chf, display: conversion).currency, "CHF")
        XCTAssertEqual(PortfolioWidgetChart.displayed(chf, display: conversion).total, 900, accuracy: d)
        let withUsdt = PortfolioWidgetChart.effectiveDisplay(.conversionUsdt, currency: chf.currency, hasUsdt: true)
        XCTAssertEqual(withUsdt, .conversionUsdt)
        XCTAssertEqual(PortfolioWidgetChart.displayed(chf, display: withUsdt).currency, "CHF")
        let usdt = PortfolioWidgetChart.displayed(
            chf, display: PortfolioWidgetChart.effectiveDisplay(.usdt, currency: chf.currency, hasUsdt: true))
        XCTAssertEqual(usdt.currency, "USDT")
        XCTAssertEqual(usdt.total, 1000, accuracy: d)
        XCTAssertEqual(usdt.changeAmount ?? .nan, 10, accuracy: d)
    }

    func testRebaseSwitchesCurrencyAndKeepsMatchingSnapshots() {
        // Vor dem Währungswechsel aufgenommen (CHF, Faktor 0.9) → EUR 0.85
        let eur = PortfolioWidgetChart.rebased(snapshot(), currency: "EUR", rate: 0.85)
        XCTAssertEqual(eur.currency, "EUR")
        XCTAssertEqual(eur.total, 850, accuracy: d)
        XCTAssertEqual(eur.changeAmount ?? .nan, 8.5, accuracy: d)
        // Gleiche Währung, ohne Kurs oder ohne USDT-Wert: unverändert
        XCTAssertEqual(PortfolioWidgetChart.rebased(snapshot(), currency: "chf", rate: 0.5), snapshot())
        XCTAssertEqual(PortfolioWidgetChart.rebased(snapshot(), currency: "EUR", rate: nil), snapshot())
        XCTAssertEqual(PortfolioWidgetChart.rebased(snapshot(totalUsdt: nil), currency: "EUR", rate: 0.85),
                       snapshot(totalUsdt: nil))
        // Zurück auf USD: Kurs 1
        XCTAssertEqual(PortfolioWidgetChart.rebased(snapshot(), currency: "USD", rate: 1).total, 1000, accuracy: d)
    }

    // MARK: Tageskurse und Tagesverlauf

    func testStaleClosesAreNotUsable() {
        let todayUtc = 20_000
        let closes: [String: [Int: Double]] = [
            "BTC": [todayUtc - 1: 100],
            "ETH": [todayUtc - 2: 10],
            "SOL": [todayUtc: .nan],
        ]
        XCTAssertEqual(Set(PortfolioWidgetChart.usableCloses(closes, todayUtcDay: todayUtc).keys), ["BTC"])
    }

    func testDailyDataBuildsYearAndPeriodChangesOfTodaysHoldings() {
        let trades = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 50, time: start(today - 500))]
        var btc: [Int: Double] = [:]
        for day in (today - 400)..<today {
            btc[day] = day == today - 7 ? 90 : (day == today - 30 ? 80 : 100)
        }
        let data = PortfolioWidgetChart.dailyData(trades: trades, closes: ["BTC": btc], ranges: [:],
                                                  holdings: ["BTC": 1], current: ["BTC": 110],
                                                  todayEpochDay: today, todayUtcDay: today,
                                                  dayEndMillis: { self.dayEnd($0) })
        XCTAssertEqual(data.points.count, 366)
        XCTAssertEqual(data.points.last?.epochDay, today)
        XCTAssertEqual(data.points.last?.value ?? .nan, 110, accuracy: d)
        XCTAssertEqual(data.changes[7]?.amount ?? .nan, 20, accuracy: d)
        XCTAssertEqual(data.changes[7]?.percent ?? .nan, 20.0 / 90.0 * 100, accuracy: 1e-6)
        XCTAssertEqual(data.changes[30]?.amount ?? .nan, 30, accuracy: d)
        XCTAssertEqual(data.changes[365]?.amount ?? .nan, 10, accuracy: d)
    }

    func testCoinWithoutUsableClosesMeansNoDailyData() {
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 50, time: start(today - 500)),
            PortfolioTx(id: 2, coin: "ETH", type: .BUY, amount: 1, priceUsdt: 50, time: start(today - 500)),
        ]
        var btc: [Int: Double] = [:]
        var eth: [Int: Double] = [:]
        for day in (today - 400)..<today { btc[day] = 100 }
        for day in (today - 400)..<(today - 5) { eth[day] = 10 }
        let data = PortfolioWidgetChart.dailyData(trades: trades, closes: ["BTC": btc, "ETH": eth], ranges: [:],
                                                  holdings: ["BTC": 1, "ETH": 1], current: ["BTC": 100, "ETH": 10],
                                                  todayEpochDay: today, todayUtcDay: today,
                                                  dayEndMillis: { self.dayEnd($0) })
        XCTAssertTrue(data.points.isEmpty)
        // Zahl wie «Gewinn 7 T» der App: die übrigen Coins zählen, ETH steht unter «ohne …» (M-3: dieselbe Zahl)
        XCTAssertEqual(data.changes[7]?.amount ?? .nan, 0, accuracy: 1e-9)
        XCTAssertEqual(data.changes[7]?.skipped, ["ETH"])
    }

    func testDailyBarsTakeTheRangeLikeThePortfolioTab() {
        let points = daily(366)
        let week = PortfolioWidgetChart.dailyBars(points, range: .week, type: .line, todayEpochDay: today, timeZone: zone)
        XCTAssertEqual(week.count, 8)
        XCTAssertEqual(week.first?.start, start(today - 7))
        let month = PortfolioWidgetChart.dailyBars(points, range: .month, type: .candles, todayEpochDay: today,
                                                   timeZone: zone, factor: 0.5)
        XCTAssertEqual(month.count, 31)
        XCTAssertEqual(month.first?.open ?? .nan, points[points.count - 32].value * 0.5, accuracy: d)
        XCTAssertEqual(month.last?.close ?? .nan, points[points.count - 1].value * 0.5, accuracy: d)
        XCTAssertEqual(month.last?.end, start(today + 1))
    }

    func testYearCandlesAreWeekly() {
        let bars = PortfolioWidgetChart.dailyBars(daily(366), range: .year, type: .candles, todayEpochDay: today,
                                                  timeZone: zone)
        XCTAssertTrue((53...54).contains(bars.count))
        for i in bars.indices.dropLast() { XCTAssertEqual(bars[i].end, bars[i + 1].start) }
    }

    func testOutdatedDailySeriesGivesNoBars() {
        XCTAssertTrue(PortfolioWidgetChart.dailyBars(daily(366, last: today - 2), range: .week, type: .line,
                                                     todayEpochDay: today, timeZone: zone).isEmpty)
        XCTAssertEqual(PortfolioWidgetChart.dailyBars(daily(366, last: today - 1), range: .week, type: .line,
                                                      todayEpochDay: today, timeZone: zone).count, 8)
    }

    // MARK: Stundenverlauf

    func testHourlyCandlesPairNeighbours() {
        let bars = PortfolioWidgetChart.hourlyBars(hourly([10, 12, 11]), type: .candles)
        XCTAssertEqual(bars.count, 2)
        XCTAssertEqual(bars[0].open, 10)
        XCTAssertEqual(bars[0].high, 12)
        XCTAssertEqual(bars[0].low, 10)
        XCTAssertEqual(bars[0].close, 12)
        XCTAssertFalse(bars[1].up)
        XCTAssertEqual(PortfolioWidgetChart.hourlyBars(hourly([10, 12, 11]), type: .line).count, 3)
        XCTAssertTrue(PortfolioWidgetChart.hourlyBars(hourly([10, 12]), type: .candles).isEmpty)
    }

    // MARK: Plan

    func testPlanUsesDailyDataAndPeriodChange() {
        let s = snapshot(daily: daily(366), periodChanges: ["7": PortfolioWidgetChange(amount: 20, percent: 2)])
        let plan = PortfolioWidgetChart.plan(s, requested: .week, type: .candles, todayEpochDay: today, timeZone: zone)
        XCTAssertFalse(plan.fallback)
        XCTAssertEqual(plan.range, .week)
        XCTAssertEqual(plan.type, .candles)
        XCTAssertEqual(plan.grid, .week)
        XCTAssertEqual(plan.changeAmount ?? .nan, 18, accuracy: d)
        XCTAssertEqual(plan.changePercent ?? .nan, 2, accuracy: d)
        XCTAssertEqual(plan.bars.count, 8)
    }

    func testPlanFallsBackToHourlyLineWithoutDailyData() {
        let points = hourly([10, 11, 12, 11, 13, 12, 14])
        let s = snapshot(history: points, periodChanges: ["30": PortfolioWidgetChange(amount: 5, percent: 1)])
        let plan = PortfolioWidgetChart.plan(s, requested: .month, type: .candles, todayEpochDay: today, timeZone: zone)
        XCTAssertTrue(plan.fallback)
        XCTAssertEqual(plan.range, .day)
        XCTAssertEqual(plan.requested, .month)
        XCTAssertEqual(plan.type, .line)
        XCTAssertEqual(plan.bars.count, points.count)
        XCTAssertEqual(plan.grid, .day)
        XCTAssertEqual(plan.changeAmount ?? .nan, 9, accuracy: d)
    }

    func testPlanDayNeedsEnoughHourlyPoints() {
        let few = snapshot(history: hourly([10, 11]))
        XCTAssertFalse(PortfolioWidgetChart.plan(few, requested: .day, type: .line, todayEpochDay: today).hasChart)
        let axis = (from: start(today), to: start(today + 1))
        let plan = PortfolioWidgetChart.plan(few, requested: .day, type: .line, todayEpochDay: today, dayAxis: axis)
        XCTAssertTrue(plan.hasChart)
        XCTAssertEqual(plan.axisFrom, axis.from)
        XCTAssertEqual(plan.axisTo, axis.to)
    }

    func testPercentTextCarriesTheSignWithoutArrow() {
        let us = Locale(identifier: "en_US")
        let up = PortfolioWidgetChart.plan(snapshot(change: 9, percent: 2.314), requested: .day, type: .line,
                                           todayEpochDay: today)
        XCTAssertEqual(PortfolioWidgetChart.percentText(up, locale: us), BidiText.ltr("+2.31%"))
        XCTAssertEqual(PortfolioWidgetChart.direction(up), 1)
        let down = PortfolioWidgetChart.plan(snapshot(change: -9, percent: -0.5), requested: .day, type: .line,
                                             todayEpochDay: today)
        XCTAssertEqual(PortfolioWidgetChart.percentText(down, locale: us), BidiText.ltr("\u{2212}0.50%"))
        let none = PortfolioWidgetChart.plan(snapshot(change: nil, percent: nil), requested: .day, type: .line,
                                             todayEpochDay: today)
        XCTAssertNil(PortfolioWidgetChart.percentText(none))
    }

    // MARK: Geometrie

    private func geometry(_ bars: [PortfolioChartBar], type: PriceChartType = .line,
                          axis: (Int64, Int64)? = nil) -> PortfolioWidgetChartGeometry? {
        PortfolioWidgetChartGeometry(bars: bars, type: type, axisFrom: axis?.0, axisTo: axis?.1,
                                     width: 100, height: 50, inset: 0, topInset: 10, bottomInset: 0)
    }

    func testCalmPortfolioGetsMinimumAxisSpan() throws {
        // 1000 → 1000.5: Spanne unter 0.2 % der Mitte → Achse 0.2 % hoch, um die Mitte gelegt (wie Android)
        let geo = try XCTUnwrap(geometry([.point(0, 1000), .point(10, 1000.5)]))
        XCTAssertEqual(geo.high, 1000.5)
        XCTAssertEqual(geo.axisLevels.high - geo.axisLevels.low, 1000.25 * 0.002, accuracy: 1e-9)
        for (y, expected) in zip(geo.levelYs, [10.0, 30.0, 50.0]) { XCTAssertEqual(y, expected, accuracy: 1e-6) }
        XCTAssertTrue(geo.y(1000) > 30 && geo.y(1000) < 50)
        XCTAssertTrue(geo.y(1000.5) > 10 && geo.y(1000.5) < 30)
    }

    func testWidgetAxisLevelsHaveMinimumSpanLikeTheSheet() throws {
        func candle(_ t: Int64, _ o: Double, _ h: Double, _ l: Double, _ c: Double) -> MarketCandle {
            MarketCandle(openTime: t, open: o, high: h, low: l, close: c, volume: 0, quoteVolume: nil)
        }
        // Ruhige Reihe: Spanne 0.04 < 0.2 % von 100 → ±0.1 um die Mitte (kein «Absturz»)
        let calm = [candle(0, 100, 100.01, 99.99, 100), candle(3_600_000, 100, 100.02, 99.98, 100.01)]
        let axis = try XCTUnwrap(WidgetChartGeometry.axisLevels(calm, type: .candles))
        XCTAssertEqual(axis.mid, 100, accuracy: 1e-9)
        XCTAssertEqual(axis.high - axis.low, 0.2, accuracy: 1e-9)
        // Stablecoin: 1 % der Mitte
        let stable = try XCTUnwrap(WidgetChartGeometry.axisLevels(calm, type: .candles, stable: true))
        XCTAssertEqual(stable.high - stable.low, 1, accuracy: 1e-9)
        // Bewegte Reihe bleibt unverändert
        let moving = [candle(0, 100, 110, 90, 105), candle(3_600_000, 105, 120, 100, 118)]
        XCTAssertEqual(WidgetChartGeometry.axisLevels(moving, type: .candles), WidgetChartGeometry.levels(moving, type: .candles))
    }

    func testLineFollowsTimeAndMarksTheHigh() throws {
        let geo = try XCTUnwrap(geometry([.point(0, 1), .point(10, 3), .point(20, 2)]))
        XCTAssertEqual(geo.linePoints.map { $0.x }, [0, 50, 100])
        XCTAssertEqual(geo.highIndex, 1)
        XCTAssertEqual(geo.highPoint.y, 10, accuracy: 1e-9)
        XCTAssertEqual(geo.y(1), 50, accuracy: 1e-9)
        XCTAssertEqual(geo.levelYs, [10, 30, 50])
        XCTAssertTrue(geo.up)
        XCTAssertNil(geometry([.point(0, 1)]))
    }

    func testFixedAxisLeavesTheRestOfTheDayEmpty() throws {
        let geo = try XCTUnwrap(geometry([.point(0, 1), .point(20, 2)], axis: (0, 40)))
        XCTAssertEqual(geo.linePoints.last?.x ?? .nan, 50, accuracy: 1e-9)
    }

    func testCandlesSitInTheMiddleOfTheirTime() throws {
        let bars = [PortfolioChartBar(start: 0, end: 10, open: 1, high: 2, low: 0.5, close: 1.5),
                    PortfolioChartBar(start: 10, end: 20, open: 1.5, high: 4, low: 1, close: 1.2)]
        let geo = try XCTUnwrap(geometry(bars, type: .candles))
        let candles = geo.candles()
        XCTAssertEqual(candles[0].x, 25, accuracy: 1e-9)
        XCTAssertEqual(candles[0].bodyWidth, 50 * 0.65, accuracy: 1e-9)
        XCTAssertEqual(geo.highIndex, 1)
        XCTAssertEqual(geo.highPoint.x, 75, accuracy: 1e-9)
        XCTAssertFalse(candles[1].up)
    }

    func testHighLabelStaysBesideTheMarkerAndInside() {
        XCTAssertEqual(PortfolioWidgetChartGeometry.labelLeft(markerX: 10, labelWidth: 30, left: 0, right: 100, gap: 5), 15)
        XCTAssertEqual(PortfolioWidgetChartGeometry.labelLeft(markerX: 90, labelWidth: 30, left: 0, right: 100, gap: 5), 55)
        XCTAssertEqual(PortfolioWidgetChartGeometry.labelLeft(markerX: 20, labelWidth: 90, left: 0, right: 100, gap: 5), 10)
    }

    // MARK: Tageskurse in der App Group

    func testDailyStoreMergeReplacesCoinsAndDropsOldDays() {
        let todayUtc = 20_000
        let stored: [String: PortfolioWidgetDailyCoin] = [
            "BTC": PortfolioWidgetDailyCoin(closes: [todayUtc - 1: 1], ranges: nil),
            "OLD": PortfolioWidgetDailyCoin(closes: [todayUtc - 500: 1], ranges: nil),
            "ETH": PortfolioWidgetDailyCoin(closes: [todayUtc - 1: 5], ranges: nil),
        ]
        let merged = PortfolioWidgetDailyStore.merge(
            stored, closes: ["BTC": [todayUtc - 450: 2, todayUtc: 3]],
            ranges: ["BTC": [todayUtc: PortfolioDayRange(open: 2, high: 4, low: 1)]], todayUtcDay: todayUtc)
        XCTAssertEqual(merged["BTC"]?.closes, [todayUtc: 3])
        XCTAssertEqual(merged["BTC"]?.ranges?[todayUtc], PortfolioDayRange(open: 2, high: 4, low: 1))
        XCTAssertEqual(merged["ETH"]?.closes, [todayUtc - 1: 5])
        XCTAssertNil(merged["OLD"])
    }

    // MARK: Ansichten (wie PortfolioWidgetViewsTest.kt)

    func testViewDefaultsToChartForNewAndExistingWidgets() {
        XCTAssertEqual(PortfolioWidgetMode.standard, .chart)
        XCTAssertEqual(PortfolioWidgetMode.from(nil), .chart)
        XCTAssertEqual(PortfolioWidgetMode.from("unknown"), .chart)
        XCTAssertEqual(PortfolioWidgetMode.from("list"), .list)
        XCTAssertEqual(PortfolioWidgetMode.from("overview"), .overview)
        XCTAssertTrue(PortfolioWidgetMode.chart.usesChartType)
        XCTAssertFalse(PortfolioWidgetMode.overview.usesChartType)
        XCTAssertFalse(PortfolioWidgetMode.list.usesChartType)
        XCTAssertTrue(PortfolioWidgetMode.chart.usesRange)
        XCTAssertTrue(PortfolioWidgetMode.overview.usesRange)
        XCTAssertFalse(PortfolioWidgetMode.list.usesRange)
    }

    func testOldSnapshotWithoutUnrealizedStillDecodes() throws {
        let json = #"{"total":900,"changeAmount":9,"changePercent":1,"currency":"CHF","updatedAt":1,"empty":false}"#
        let s = try JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: Data(json.utf8))
        XCTAssertNil(s.unrealized)
        XCTAssertNil(s.unrealizedDisplay)
        XCTAssertNil(s.weekDisplay)
        XCTAssertNil(s.displayFx)
        XCTAssertTrue(s.topPositions.isEmpty)
        // Gesamt unbekannt, Heute bleibt
        let figures = PortfolioWidgetViews.figures(s)
        XCTAssertNil(figures[2].amount)
        XCTAssertEqual(figures[0].amount ?? .nan, 9, accuracy: d)
    }

    /// In der Anzeigewährung wie die Gesamtkarte: «Seit Kauf» und «7 T» aus den gespeicherten Werten der Anzeigewährung,
    /// nie USDT × Kurs des Stands (der Einstand gilt zum Kurs der Erwerbstage). Wie Android
    /// `figuresInDisplayCurrencyUseStoredDisplayValues`.
    func testFiguresInDisplayCurrencyUseStoredDisplayValues() {
        var s = snapshot(periodChanges: ["7": PortfolioWidgetChange(amount: 100, percent: 2)])
        s.unrealized = PortfolioWidgetChange(amount: -50, percent: -5)
        s.unrealizedDisplay = PortfolioWidgetChange(amount: -80, percent: -8.5, skipped: ["SOL"])
        s.weekDisplay = PortfolioWidgetChange(amount: 95, percent: 2.1)
        let figures = PortfolioWidgetViews.figures(s)
        XCTAssertEqual(figures.map(\.kind), [.today, .week, .total])
        XCTAssertEqual(figures[0].amount ?? .nan, 9, accuracy: d)
        XCTAssertEqual(figures[0].percent ?? .nan, 1, accuracy: d)
        // 7 T und Gesamt wie gespeichert (nicht 100 × 0.9 bzw. −50 × 0.9)
        XCTAssertEqual(figures[1].amount ?? .nan, 95, accuracy: d)
        XCTAssertEqual(figures[1].percent ?? .nan, 2.1, accuracy: d)
        XCTAssertNil(figures[1].unit)
        XCTAssertEqual(figures[2].amount ?? .nan, -80, accuracy: d)
        XCTAssertEqual(figures[2].percent ?? .nan, -8.5, accuracy: d)
        // SOL fehlt (kein Devisenkurs am Kauftag): «unvollständig»
        XCTAssertTrue(figures[2].incomplete)
        // «Nur USDT»: Werte in USDT
        let usdt = PortfolioWidgetViews.figures(PortfolioWidgetChart.inUsdt(s))
        XCTAssertEqual(usdt[0].amount ?? .nan, 10, accuracy: d)
        XCTAssertEqual(usdt[1].amount ?? .nan, 100, accuracy: d)
        XCTAssertNil(usdt[1].unit)
        XCTAssertEqual(usdt[2].amount ?? .nan, -50, accuracy: d)
    }

    /// Ohne Werte der Anzeigewährung: «Seit Kauf» «—», «7 T» in USDT mit Einheit «USDT». Wie Android
    /// `figuresWithoutDisplayValuesNeverScaleUsdt`.
    func testFiguresWithoutDisplayValuesNeverScaleUsdt() {
        var s = snapshot(periodChanges: ["7": PortfolioWidgetChange(amount: 100, percent: 2)])
        s.unrealized = PortfolioWidgetChange(amount: -50, percent: -5)
        let figures = PortfolioWidgetViews.figures(s)
        XCTAssertEqual(figures[1].amount ?? .nan, 100, accuracy: d)
        XCTAssertEqual(figures[1].percent ?? .nan, 2, accuracy: d)
        XCTAssertEqual(figures[1].unit, "USDT")
        XCTAssertNil(figures[2].amount)
        XCTAssertNil(figures[2].percent)
        XCTAssertFalse(figures[2].incomplete)
        // Andere Währung eingestellt (umgerechnet beim Zeichnen): Werte der alten Währung gelten nicht
        var shown = snapshot()
        shown.unrealizedDisplay = PortfolioWidgetChange(amount: -80, percent: -8.5)
        shown.weekDisplay = PortfolioWidgetChange(amount: 95, percent: 2.1)
        let rebased = PortfolioWidgetChart.rebased(shown, currency: "EUR", rate: 0.95)
        XCTAssertNil(rebased.unrealizedDisplay)
        XCTAssertNil(rebased.weekDisplay)
    }

    func testMissingFiguresAreUnknown() {
        let figures = PortfolioWidgetViews.figures(snapshot(change: nil, percent: 3))
        for f in figures {
            XCTAssertNil(f.amount)
            XCTAssertNil(f.percent)
        }
    }

    func testListSortsByValueThenSymbol() {
        func p(_ symbol: String, _ value: Double) -> PortfolioWidgetPosition {
            PortfolioWidgetPosition(symbol: symbol, value: value, sharePercent: 0, change24hPercent: nil)
        }
        let sorted = PortfolioWidgetViews.sortedByValue([p("ETH", 200), p("BTC", 600), p("ADA", 200), p("BAD", .nan)])
        XCTAssertEqual(sorted.map(\.symbol), ["BTC", "ADA", "ETH"])
    }

    func testListRowsKeepFooterOnlyWhenItCostsNoRow() {
        func rows(_ available: Double?, _ count: Int) -> [Int] {
            let r = PortfolioWidgetViews.listRows(available: available, row: 30, footer: 18, count: count)
            return [r.rows, r.footer ? 1 : 0]
        }
        XCTAssertEqual(rows(100, 5), [3, 0])
        XCTAssertEqual(rows(100, 2), [2, 1])
        XCTAssertEqual(rows(110, 5), [3, 1])
        XCTAssertEqual(rows(nil, 7), [3, 1])
        XCTAssertEqual(rows(50, 0), [0, 1])
        XCTAssertEqual(rows(10, 4), [0, 0])
        XCTAssertGreaterThan(PortfolioWidgetViews.rowHeight(wide: false), PortfolioWidgetViews.rowHeight(wide: true))
        XCTAssertEqual(PortfolioWidgetViews.rowHeight(wide: true), 28, accuracy: d)
    }

    func testListTotalFooterHasPriorityOverRows() {
        let total = PortfolioWidgetViews.listTotalHeight()
        let convertedPart: Double = (13 + 11) * 1.2
        let expectedTotal: Double = convertedPart + 2 + 8 + 1
        XCTAssertEqual(total, expectedTotal, accuracy: d)
        XCTAssertGreaterThan(total, PortfolioWidgetViews.rowHeight(wide: true))
        // Breit einzeilig wie die breiten Zeilen (Build 46): niedriger als zweizeilig
        XCTAssertLessThan(PortfolioWidgetViews.listTotalHeight(wide: true), total)
        // Platz für Kopf (16 + 6) und Total, aber keine Zeile: keine Zeilen, Stand nur wenn Platz
        let tight = PortfolioWidgetViews.listRowsBelowTotal(height: 22 + total + 2, header: 16, gap: 6, row: 30,
                                                            footer: 18, count: 5)
        XCTAssertEqual(tight.rows, 0)
        // Zu klein für das Total: nie negativ
        XCTAssertEqual(PortfolioWidgetViews.listRowsBelowTotal(height: 10, header: 16, gap: 6, row: 30, footer: 18,
                                                               count: 5).rows, 0)
        // Sonst wie listRows mit der Höhe ohne Kopf und Total
        let r = PortfolioWidgetViews.listRowsBelowTotal(height: 22 + total + 110, header: 16, gap: 6, row: 30,
                                                        footer: 18, count: 5)
        XCTAssertEqual([r.rows, r.footer ? 1 : 0], [3, 1])
        // «Stand …» bleibt immer: knapp zu wenig für Zeile + Fusszeile → keine Zeile, Fusszeile da
        let almostHeight: Double = 22 + total + 30 + 18 - 1
        let almost = PortfolioWidgetViews.listRowsBelowTotal(height: almostHeight, header: 16, gap: 6,
                                                             row: 30, footer: 18, count: 5)
        XCTAssertEqual([almost.rows, almost.footer ? 1 : 0], [0, 1])
        XCTAssertTrue(tight.footer)
    }

    func testListTotalChangeIsTheSnapshotGainWithAmount() {
        let up = PortfolioWidgetViews.listTotalChange(snapshot(change: 9, percent: 1.61))
        XCTAssertEqual(up?.amount ?? 0, 9, accuracy: d)
        XCTAssertEqual(up?.percent ?? 0, 1.61, accuracy: d)
        XCTAssertEqual(up?.direction, 1)
        XCTAssertEqual(PortfolioWidgetViews.listTotalChange(snapshot(change: -3, percent: -0.4))?.direction, -1)
        // Richtung nach dem Betrag (0 = grau)
        XCTAssertEqual(PortfolioWidgetViews.listTotalChange(snapshot(change: 0.001, percent: 0.001))?.direction, 0)
        // Ohne Prozent (Basis ~0): nur der Betrag
        XCTAssertNil(PortfolioWidgetViews.listTotalChange(snapshot(change: 9, percent: nil))?.percent)
        XCTAssertNotNil(PortfolioWidgetViews.listTotalChange(snapshot(change: 9, percent: nil)))
        XCTAssertNil(PortfolioWidgetViews.listTotalChange(snapshot(change: nil, percent: 1)))
        XCTAssertNil(PortfolioWidgetViews.listTotalChange(snapshot(change: .nan, percent: 1)))
    }

    func testRowPnlAndTextsPutAmountFirst() throws {
        let p = PortfolioWidgetPosition(symbol: "BTC", value: 83_385.24, sharePercent: 88.9, change24hPercent: 1,
                                        quantity: "1.3", pnlAmount: 1_250, pnlPercent: 1.52)
        let pnl = try XCTUnwrap(PortfolioWidgetViews.rowPnl(p))
        XCTAssertEqual(pnl.direction, 1)
        let texts = PortfolioWidgetViews.pnlTexts(pnl, currency: "CHF", hidden: false)
        XCTAssertEqual(texts.count, 2)
        XCTAssertTrue(texts[0].hasPrefix(texts[1] + " · "))
        // Beträge verborgen: Betrag «•••», Prozent bleibt
        let hidden = PortfolioWidgetViews.pnlTexts(pnl, currency: "CHF", hidden: true)
        XCTAssertEqual(hidden.last, PortfolioInsights.hidden)
        XCTAssertTrue(hidden[0].hasPrefix(PortfolioInsights.hidden + " · "))
        // Verlust: −1; unbekannt (älterer Stand): nil → «—»
        var loss = p
        loss.pnlAmount = -5
        XCTAssertEqual(PortfolioWidgetViews.rowPnl(loss)?.direction, -1)
        var unknown = p
        unknown.pnlAmount = nil
        XCTAssertNil(PortfolioWidgetViews.rowPnl(unknown))
        // Ohne Prozent nur der Betrag
        var noPercent = p
        noPercent.pnlPercent = nil
        XCTAssertEqual(PortfolioWidgetViews.pnlTexts(try XCTUnwrap(PortfolioWidgetViews.rowPnl(noPercent)),
                                                     currency: "CHF", hidden: false).count, 1)
    }

    func testOldSnapshotWithoutPnlStillDecodes() throws {
        // Älterer Stand ohne Gewinn/Verlust-Felder: lädt, Felder nil
        let json = """
        {"total":900,"currency":"CHF","updatedAt":1,"empty":false,
         "positions":[{"symbol":"BTC","value":900,"sharePercent":100,"change24hPercent":1}]}
        """
        let s = try JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: Data(json.utf8))
        XCTAssertNil(s.coinPnl)
        XCTAssertNil(s.positions?.first?.pnlAmount)
        // Neuer Stand: Werte bleiben beim Speichern erhalten
        var fresh = snapshot()
        fresh.coinPnl = ["BTC": PortfolioWidgetChange(amount: 12, percent: 1.3)]
        fresh.positions = [PortfolioWidgetPosition(symbol: "BTC", value: 900, sharePercent: 100, change24hPercent: 1,
                                                   pnlAmount: 10.8, pnlPercent: 1.3)]
        let back = try JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: JSONEncoder().encode(fresh))
        XCTAssertEqual(back, fresh)
        // Andere %-Basis: auch Gewinn/Verlust «—»
        let shown = back.shown(basis: .LOCAL_DAY, now: back.updatedAt)
        XCTAssertNil(shown.positions?.first?.pnlAmount)
        XCTAssertNil(shown.coinPnl)
    }

    func testMaxPositionsAllowsLongerList() {
        let values: [String: Double?] = Dictionary(uniqueKeysWithValues: (1...15).map { (i: Int) -> (String, Double?) in ("C\(i)", Optional(Double(i))) })
        let top = PortfolioWidgetStore.topPositions(valuesUsd: values, totalUsd: 120, factor: 1, changes: [:])
        XCTAssertEqual(top.positions.count, PortfolioWidgetStore.maxPositions)
        XCTAssertEqual(top.positions.first?.symbol, "C15")
        XCTAssertEqual(top.others, 15 - PortfolioWidgetStore.maxPositions)
    }

    // MARK: Währung (wie WidgetQuoteTextTest.kt)

    func testQuoteSplitFindsCurrencyAfterNumber() {
        let a = WidgetQuoteText.split("67’000.00 USDT", quote: "USDT")
        XCTAssertEqual(a.before, "67’000.00 ")
        XCTAssertEqual(a.quote, "USDT")
        XCTAssertEqual(a.after, "")
        let b = WidgetQuoteText.split("−968.40 CHF · 24h", quote: "CHF")
        XCTAssertEqual(b.before, "−968.40 ")
        XCTAssertEqual(b.quote, "CHF")
        XCTAssertEqual(b.after, " · 24h")
        let c = WidgetQuoteText.split("1 USDT 2 USD", quote: "USD")
        XCTAssertEqual(c.before, "1 USDT 2 ")
        XCTAssertEqual(c.quote, "USD")
        let rtl = WidgetQuoteText.split("\u{2066}+12.00 CHF\u{2069}", quote: "CHF")
        XCTAssertEqual(rtl.quote, "CHF")
        XCTAssertEqual(rtl.after, "\u{2069}")
    }

    func testQuoteSplitIgnoresHiddenAndWords() {
        XCTAssertNil(WidgetQuoteText.split("•••", quote: "CHF").quote)
        XCTAssertNil(WidgetQuoteText.split("—", quote: "USDT").quote)
        XCTAssertNil(WidgetQuoteText.split("USDT", quote: "USDT").quote)
        XCTAssertNil(WidgetQuoteText.split("12.00 USDTX", quote: "USDT").quote)
        XCTAssertNil(WidgetQuoteText.split("12.00 USDT", quote: "").quote)
        XCTAssertEqual(WidgetQuoteText.split("•••", quote: "CHF").before, "•••")
    }

    func testQuoteSizeKeepsMinimum() {
        XCTAssertEqual(WidgetQuoteText.quoteSize(for: 28), 11.76, accuracy: 1e-9)
        XCTAssertEqual(WidgetQuoteText.quoteSize(for: 14), 11, accuracy: 1e-9)
        XCTAssertEqual(WidgetQuoteText.quoteSize(for: 8), 8, accuracy: 1e-9)
    }

    // MARK: Betragszeile und Kennzahlen als Text (keine Pillen)

    func testChangeLineDropsPeriodThenAmountKeepsPercent() {
        let list = PortfolioWidgetViews.changeLineCandidates(amount: "−31’997.28 CHF", percent: "−27.85%", period: "1J")
        XCTAssertEqual(list, [
            PortfolioChangeLine(amount: "−31’997.28 CHF", percent: "−27.85%", period: "1J"),
            PortfolioChangeLine(amount: "−31’997.28 CHF", percent: "−27.85%", period: nil),
            PortfolioChangeLine(amount: nil, percent: "−27.85%", period: "1J"),
            PortfolioChangeLine(amount: nil, percent: "−27.85%", period: nil),
        ])
        // Ohne Prozent: Betrag mit und ohne Zeitraum; leerer Zeitraum zählt nicht
        XCTAssertEqual(PortfolioWidgetViews.changeLineCandidates(amount: "+12.00 CHF", percent: nil, period: "24h"), [
            PortfolioChangeLine(amount: "+12.00 CHF", percent: nil, period: "24h"),
            PortfolioChangeLine(amount: "+12.00 CHF", percent: nil, period: nil),
        ])
        XCTAssertEqual(PortfolioWidgetViews.changeLineCandidates(amount: "0.00 CHF", percent: "0.00%", period: "").count, 2)
        XCTAssertEqual(PortfolioWidgetViews.changeLineSizes, [12, 11])
    }

    func testFigureDirectionFollowsAmountThenPercent() {
        XCTAssertEqual(PortfolioWidgetViews.figureDirection(PortfolioFigure(kind: .total, amount: 120, percent: 424.47)), 1)
        XCTAssertEqual(PortfolioWidgetViews.figureDirection(PortfolioFigure(kind: .today, amount: nil, percent: -0.85)), -1)
        XCTAssertEqual(PortfolioWidgetViews.figureDirection(PortfolioFigure(kind: .week, amount: 0, percent: 0)), 0)
        XCTAssertNil(PortfolioWidgetViews.figureDirection(PortfolioFigure(kind: .week, amount: 5, percent: nil)))
    }

    // MARK: «Heute ohne …» (wie PortfolioWidgetViewsTest.kt)

    private func plan(_ range: PortfolioWidgetRange, change: Double? = 9) -> PortfolioChartPlan {
        PortfolioChartPlan(range: range, requested: range, fallback: false, type: .line, bars: [], grid: .day,
                           axisFrom: nil, axisTo: nil, changeAmount: change, changePercent: 1)
    }

    func testTodaySkippedOnlyWhereTodayIsShown() {
        var s = snapshot()
        s.todaySkipped = ["SOL", "ADA", "SOL", ""]
        XCTAssertEqual(PortfolioWidgetViews.todaySkipped(s, plan: plan(.day), overview: false), ["ADA", "SOL"])
        // 7 T zeigt nicht «Heute»; die Übersicht hat «Heute» immer in den Kennzahlen
        XCTAssertEqual(PortfolioWidgetViews.todaySkipped(s, plan: plan(.week), overview: false), [])
        XCTAssertEqual(PortfolioWidgetViews.todaySkipped(s, plan: plan(.week), overview: true), ["ADA", "SOL"])
    }

    /// M-3: «7 T ohne …» im Widget wie «Gewinn 7 T» der App.
    func testPeriodSkippedNamesTheCoinsOfTheVisiblePeriodNumber() {
        let s = snapshot(periodChanges: ["7": PortfolioWidgetChange(amount: 100, percent: 2, skipped: ["SOL", "ADA", "SOL", ""]),
                                         "30": PortfolioWidgetChange(amount: 50, percent: 1)])
        XCTAssertEqual(PortfolioWidgetViews.periodSkipped(s, plan: plan(.day), overview: true)?.coins, ["ADA", "SOL"])
        XCTAssertEqual(PortfolioWidgetViews.periodSkipped(s, plan: plan(.day), overview: true)?.range, .week)
        XCTAssertEqual(PortfolioWidgetViews.periodSkipped(s, plan: plan(.week), overview: false)?.coins, ["ADA", "SOL"])
        // 30 T ohne fehlende Coins, 24 h ohne Tages-Zahl, Zeitraum ohne Betrag: kein Hinweis
        XCTAssertNil(PortfolioWidgetViews.periodSkipped(s, plan: plan(.month), overview: false))
        XCTAssertNil(PortfolioWidgetViews.periodSkipped(s, plan: plan(.day), overview: false))
        XCTAssertNil(PortfolioWidgetViews.periodSkipped(s, plan: plan(.week, change: nil), overview: false))
    }

    func testTodaySkippedEmptyWithoutTodayNumber() {
        // %-Basis veraltet (Betrag «—») oder keine Coins ausgelassen
        var noChange = snapshot(change: nil)
        noChange.todaySkipped = ["SOL"]
        XCTAssertEqual(PortfolioWidgetViews.todaySkipped(noChange, plan: plan(.day, change: nil), overview: true), [])
        XCTAssertEqual(PortfolioWidgetViews.todaySkipped(snapshot(), plan: plan(.day), overview: true), [])
    }

    /// Faktor USDT → Währung des Stands: Devisenkurs × USDT/USD, auch für USD; USD-Stablecoins bleiben. Wie Android.
    func testUsdtFactorUsesMarketPrice() {
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "CHF", fx: 0.9, usdtLive: 0.998), 0.9 * 0.998, accuracy: 1e-12)
        // Ohne Messung bzw. ausserhalb 0.05…3: USDT = 1
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "CHF", fx: 0.9, usdtLive: nil), 0.9, accuracy: 1e-12)
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "CHF", fx: 0.9, usdtLive: 5), 0.9, accuracy: 1e-12)
        // Bestätigter Einbruch (die Messung speichert ihn nur bestätigt): Marktkurs statt 1
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "CHF", fx: 0.9, usdtLive: 0.3), 0.9 * 0.3, accuracy: 1e-12)
        // USD: USDT zum Marktkurs (0.97 → 1000 USDT = 970 USD); ohne Messung 1
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "USD", fx: 1, usdtLive: 0.97), 0.97, accuracy: 1e-12)
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "USD", fx: 1, usdtLive: nil), 1, accuracy: 1e-12)
        // USD-Stablecoin als Anzeige: unverändert (Widget zeigt dort USDT)
        XCTAssertEqual(PortfolioWidgetChart.usdtFactor(currency: "USDC", fx: 1, usdtLive: 0.97), 1, accuracy: 1e-12)
    }
}
