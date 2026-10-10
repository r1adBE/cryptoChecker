import XCTest
@testable import CryptoChecker

/// Wie `PortfolioStablesTest.kt`, `PricePlausibilityTest.kt` und `PortfolioHistoryFxTest.kt`:
/// eine Stablecoin-Regel, 25-%-Prüfung für Verlauf/Stichtag, Verlauf mit Devisen-Tageskursen.
final class PortfolioNumbersTests: XCTestCase {

    private var nextId: Int64 = 1
    private let d = 1e-9
    private let utc = TimeZone(identifier: "UTC")!
    private let today = LocalDay(2026, 10, 6).epochDay

    private func buy(_ coin: String, _ amount: Double, _ time: Int64) -> PortfolioTx {
        defer { nextId += 1 }
        return PortfolioTx(id: nextId, coin: coin, type: .BUY, amount: amount, priceUsdt: 1, time: time)
    }

    private func noon(_ day: Int) -> Int64 { Int64(day) * 86_400_000 + 12 * 3_600_000 }

    private func build(_ trades: [PortfolioTx], _ closes: [String: [Int: Double]],
                       _ live: [String: Double]) -> PortfolioHistorySeries {
        PortfolioHistory.build(trades: trades, closes: closes, livePrices: live, range: .week,
                               todayEpochDay: today,
                               dayEndMillis: { PortfolioHistory.dayEndMillis($0, timeZone: self.utc) })
    }

    // MARK: Stablecoins

    func testOneListEverywhere() {
        XCTAssertEqual(PortfolioStables.coins, CurrencyConversion.usdStables)
        XCTAssertEqual(CutoffExport.stables, CurrencyConversion.usdStables)
        XCTAssertEqual(PortfolioHistory.stables, CurrencyConversion.usdStables)
        XCTAssertTrue(PortfolioHistory.isStable("usde"))
        XCTAssertTrue(CutoffExport.isStable(" PYUSD "))
        XCTAssertFalse(PortfolioStables.isStable("BTC"))
    }

    func testUsdtAlwaysOneOtherStablesMarketElseOne() {
        XCTAssertEqual(PortfolioStables.price("USDT", market: 1.0004), 1)
        XCTAssertEqual(PortfolioStables.price("usdt", market: nil), 1)
        XCTAssertEqual(PortfolioStables.price("USDC", market: 0.99), 0.99)
        XCTAssertEqual(PortfolioStables.price("USDC", market: nil), 1)
        XCTAssertEqual(PortfolioStables.price("DAI", market: .nan), 1)
        XCTAssertEqual(PortfolioStables.price("DAI", market: 0), 1)
        XCTAssertEqual(PortfolioStables.price("BTC", market: 50), 50)
        XCTAssertNil(PortfolioStables.price("BTC", market: nil))
        XCTAssertFalse(PortfolioStables.needsQuote("USDT"))
        XCTAssertTrue(PortfolioStables.needsQuote("USDC"))
    }

    func testOpenBookUsesMarketPriceOfStable() {
        let trades = [buy("USDC", 100, 1), buy("DAI", 10, 1), buy("USDT", 5, 1)]
        let s = PortfolioCalculator.summarize(trades, prices: ["USDC": 0.99])
        XCTAssertEqual(s.open.first { $0.coin == "USDC" }?.value ?? .nan, 99, accuracy: d)
        XCTAssertEqual(s.open.first { $0.coin == "DAI" }?.value ?? .nan, 10, accuracy: d)
        XCTAssertEqual(s.open.first { $0.coin == "USDT" }?.value ?? .nan, 5, accuracy: d)
        XCTAssertEqual(s.totalValue, 114, accuracy: d)
        XCTAssertTrue(s.missingCurrentPrices.isEmpty)
    }

    func testHistoryUsesStableClosesWithFallbackOne() {
        let trades = [buy("USDC", 100, noon(today - 3)), buy("DAI", 10, noon(today - 3))]
        let s = build(trades, ["USDC": [today - 2: 0.98, today - 1: 0.99]], ["USDC": 0.995])
        XCTAssertTrue(s.skipped.isEmpty)
        let values = s.points.map(\.value)
        XCTAssertEqual(values.count, 4)
        for (got, want) in zip(values, [110, 108, 109, 109.5]) { XCTAssertEqual(got, want, accuracy: 1e-6) }
    }

    func testCutoffRowsUseStableCloseElseOne() {
        let rows = CutoffExport.rows([CutoffHolding(coin: "USDC", amount: 10), CutoffHolding(coin: "DAI", amount: 5),
                                      CutoffHolding(coin: "USDT", amount: 2)],
                                     prices: ["USDC": 0.97, "USDT": 1.2], fxRate: 1)
        XCTAssertEqual(rows[0].priceUsdt ?? .nan, 0.97, accuracy: d)
        XCTAssertEqual(rows[0].valueUsdt ?? .nan, 9.7, accuracy: d)
        XCTAssertEqual(rows[1].priceUsdt ?? .nan, 1, accuracy: d)
        XCTAssertFalse(rows[1].noPrice)
        XCTAssertEqual(rows[2].priceUsdt ?? .nan, 1, accuracy: d)
    }

    // MARK: Plausibilität

    func testSameThresholdAsDayChange() {
        XCTAssertEqual(PricePlausibility.maxGap, DayChange.maxPriceGap)
    }

    func testMatchesWithinTwentyFivePercent() {
        XCTAssertTrue(PricePlausibility.matches(125, reference: 100))
        XCTAssertTrue(PricePlausibility.matches(75, reference: 100))
        XCTAssertFalse(PricePlausibility.matches(125.1, reference: 100))
        XCTAssertFalse(PricePlausibility.matches(74.9, reference: 100))
        XCTAssertTrue(PricePlausibility.matches(3, reference: nil))
        XCTAssertTrue(PricePlausibility.matches(3, reference: .nan))
        XCTAssertFalse(PricePlausibility.matches(nil, reference: 100))
        XCTAssertFalse(PricePlausibility.matches(-1, reference: 100))
    }

    func testClosesMatchLiveUsesLatestClose() {
        let closes: [(day: Int, close: Double)] = [(day: 1, close: 10), (day: 2, close: 100)]
        XCTAssertTrue(PricePlausibility.closesMatchLive(closes, live: 110))
        XCTAssertFalse(PricePlausibility.closesMatchLive(closes, live: 10))
        XCTAssertTrue(PricePlausibility.closesMatchLive(closes, live: nil))
        XCTAssertTrue(PricePlausibility.closesMatchLive([], live: 5))
    }

    func testCutoffSourceCheck() {
        XCTAssertTrue(PricePlausibility.acceptSourceClose(5, trusted: true, sourceLatest: nil, current: 900))
        XCTAssertTrue(PricePlausibility.acceptSourceClose(5, trusted: false, sourceLatest: 1.1, current: 1))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, trusted: false, sourceLatest: 40, current: 1))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, trusted: false, sourceLatest: nil, current: 1))
        XCTAssertTrue(PricePlausibility.acceptSourceClose(5, trusted: false, sourceLatest: nil, current: nil))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(nil, trusted: true, sourceLatest: nil, current: nil))
    }

    func testHistoryDropsImplausibleSeries() {
        let trades = [buy("BTC", 1, noon(today - 2)), buy("FOO", 10, noon(today - 2))]
        var btc: [Int: Double] = [:]
        var foo: [Int: Double] = [:]
        for i in 0...5 {
            btc[today - i] = 100
            foo[today - i] = 50
        }
        let s = build(trades, ["BTC": btc, "FOO": foo], ["BTC": 101, "FOO": 1])
        XCTAssertEqual(s.skipped, ["FOO"])
        XCTAssertEqual(s.points.first?.value ?? .nan, 100, accuracy: d)
        XCTAssertEqual(s.points.last?.value ?? .nan, 101, accuracy: d)
    }

    func testHistoryStableWithImplausibleClosesFallsBackToOne() {
        let s = build([buy("USDC", 10, noon(today - 1))], ["USDC": [today - 1: 7, today: 7]], ["USDC": 1])
        XCTAssertTrue(s.skipped.isEmpty)
        XCTAssertEqual(s.points.map(\.value), [10, 10])
    }

    // MARK: Devisen-Tageskurse

    private func day(_ iso: String) -> Int { LocalDay(iso: iso)!.epochDay }

    private var flatUsd: PortfolioHistorySeries {
        PortfolioHistorySeries(
            points: ["2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06"]
                .map { PortfolioHistoryPoint(epochDay: day($0), value: 1000) },
            skipped: [], change: 0, changePercent: 0, tradesInRange: false)
    }

    private var rates: [Int: Double] {
        [day("2026-10-01"): 0.79, day("2026-10-02"): 0.80, day("2026-10-05"): 0.81]
    }

    func testWeekendsCarryPreviousBusinessDay() {
        let sorted = rates.sorted { $0.key < $1.key }.map { (day: $0.key, rate: $0.value) }
        XCTAssertEqual(PortfolioHistoryFx.rateOn(sorted, day: day("2026-10-03")), 0.80)
        XCTAssertEqual(PortfolioHistoryFx.rateOn(sorted, day: day("2026-10-04")), 0.80)
        XCTAssertEqual(PortfolioHistoryFx.rateOn(sorted, day: day("2026-10-05")), 0.81)
        XCTAssertNil(PortfolioHistoryFx.rateOn(sorted, day: day("2026-09-30")))
    }

    func testFlatUsdShowsCurrencyMove() {
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: rates, currentRate: 0.82, todayEpochDay: today)
        XCTAssertFalse(r.approximate)
        for (got, want) in zip(r.series.points.map(\.value), [800.0, 800, 800, 810, 820]) {
            XCTAssertEqual(got, want, accuracy: 1e-6)
        }
        XCTAssertEqual(r.series.change ?? .nan, 20, accuracy: 1e-6)
        XCTAssertEqual(r.series.changePercent ?? .nan, 2.5, accuracy: 1e-6)
    }

    func testMissingSeriesFallsBackToTodayAndFlags() {
        for missing in [nil, [:]] as [[Int: Double]?] {
            let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: missing, currentRate: 0.82, todayEpochDay: today)
            XCTAssertTrue(r.approximate)
            XCTAssertTrue(r.series.points.allSatisfy { abs($0.value - 820) < 1e-6 })
            XCTAssertEqual(r.series.change ?? .nan, 0, accuracy: 1e-6)
        }
    }

    func testDayBeforeFirstRateUsesFirst() {
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: [day("2026-10-05"): 0.81], currentRate: 0.82,
                                           todayEpochDay: today)
        XCTAssertEqual(r.series.points.first?.value ?? .nan, 810, accuracy: 1e-6)
    }

    func testRatesByDayParsesAndDerivesBgn() {
        let byDate: [String: [String: Double]] = [
            "2025-12-31": ["BGN": 1.70, "EUR": 0.86],
            "2026-01-02": ["EUR": 0.85],
            "kaputt": ["BGN": 1.0],
            "2026-01-05": ["BGN": -1.0],
        ]
        let bgn = PortfolioHistoryFx.ratesByDay("bgn", byDate: byDate)
        XCTAssertEqual(bgn[day("2025-12-31")] ?? .nan, 1.70, accuracy: d)
        XCTAssertEqual(bgn[day("2026-01-02")] ?? .nan, 0.85 * 1.95583, accuracy: d)
        XCTAssertNil(bgn[day("2026-01-05")])
        XCTAssertEqual(PortfolioHistoryFx.ratesByDay("EUR", byDate: byDate).count, 2)
        XCTAssertEqual(PortfolioHistoryFx.requestCurrencies("BGN"), ["BGN", "EUR"])
        XCTAssertEqual(PortfolioHistoryFx.requestCurrencies(" chf "), ["CHF"])
    }

    func testRequestStartsLeadDaysEarlier() {
        let range = PortfolioHistoryFx.requestRange(days: 30, today: LocalDay(2026, 10, 6))
        XCTAssertEqual(range.from, LocalDay(2026, 8, 30))
        XCTAssertEqual(range.to, LocalDay(2026, 10, 6))
    }
}
