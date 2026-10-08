import XCTest
@testable import CryptoChecker

/// Wie `PortfolioWidgetSeriesTest.kt`: Stundenverlauf des Portfolio-Widgets, insbesondere
/// die Abdeckungsregel (eine Stunde zählt nur, wenn Coins mit Kurs ≥ 80 % des Werts ausmachen).
final class PortfolioWidgetSeriesTests: XCTestCase {

    private let h = PortfolioWidgetSeries.hourMillis
    private var now: Int64 { 1_000 * h + 17 * 60_000 }
    private let stables: Set<String> = ["USDT", "USDC"]

    /// Stundenkurse der letzten 24 h: Kurs = f(Stunden zurück).
    private func series(_ f: (Int) -> Double) -> [PortfolioTimedPrice] {
        stride(from: 24, through: 1, by: -1).map { k in PortfolioTimedPrice(at: now - Int64(k) * h, price: f(k)) }
    }

    func testHourlyClosesGetTimesBackFromFetch() {
        let timed = PortfolioWidgetSeries.fromHourlyCloses([1, 2, .nan, 4], fetchedAt: now)
        XCTAssertEqual(timed.map(\.at), [now - 3 * h, now - 2 * h, now])
        XCTAssertEqual(timed.map(\.price), [1, 2, 4])
        XCTAssertTrue(PortfolioWidgetSeries.fromHourlyCloses([1], fetchedAt: 0).isEmpty)
        let candles = PortfolioWidgetSeries.fromCandles([(open: now - 2 * h, close: 5), (open: now - 10 * 60_000, close: 6)],
                                                        fetchedAt: now)
        XCTAssertEqual(candles.map(\.at), [now - h, now])
    }

    func testMergeKeepsNewestPerHourAndPrunes() throws {
        let stored: [String: [PortfolioTimedPrice]] = [
            "btc": [PortfolioTimedPrice(at: now - 30 * h, price: 1), PortfolioTimedPrice(at: now - 5 * h - 10, price: 2)],
            "ETH": [PortfolioTimedPrice(at: now + h, price: 9)],
        ]
        let hourStart = (now - 5 * h - 10) / h * h
        let fresh: [String: [PortfolioTimedPrice]] = [
            "BTC": [PortfolioTimedPrice(at: hourStart + 1, price: 3), PortfolioTimedPrice(at: now, price: 4)],
        ]
        let merged = PortfolioWidgetSeries.merge(stored: stored, fresh: fresh, now: now)
        XCTAssertEqual(Set(merged.keys), ["BTC"])
        let btc = try XCTUnwrap(merged["BTC"])
        XCTAssertEqual(btc.count, 2)
        XCTAssertEqual(btc.map(\.at), btc.map(\.at).sorted())
        XCTAssertEqual(btc.map(\.price), [2, 4])
    }

    func testPriceAtUsesLatestWithinTolerance() {
        let prices = [PortfolioTimedPrice(at: now - 3 * h, price: 1), PortfolioTimedPrice(at: now - h, price: 2)]
        XCTAssertEqual(PortfolioWidgetSeries.price(prices, at: now), 2)
        XCTAssertEqual(PortfolioWidgetSeries.price(prices, at: now - 2 * h), 1)
        XCTAssertNil(PortfolioWidgetSeries.price(prices, at: now - h - 1))
        XCTAssertNil(PortfolioWidgetSeries.price(prices, at: now - 4 * h))
        XCTAssertNil(PortfolioWidgetSeries.price(nil, at: now))
    }

    func testHourlySeriesSumsHoldingsTimesCloses() throws {
        let prices = ["BTC": series { 100 + Double(24 - $0) }, "ETH": series { _ in 10 }]
        let points = PortfolioWidgetSeries.hourly(holdings: ["BTC": 1, "ETH": 2, "USDT": 50],
                                                  current: ["BTC": 130, "ETH": 10, "USDT": 1],
                                                  prices: prices, now: now, factor: 2, stables: stables)
        XCTAssertEqual(points.count, 25)
        XCTAssertEqual(points.first?.at, now - 24 * h)
        XCTAssertEqual(points.last?.at, now)
        // 24 h zurück: BTC 100, ETH 2 × 10, USDT 50 → 170 × 2
        XCTAssertEqual(try XCTUnwrap(points.first).value, 340, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(points.last).value, (130 + 20 + 50) * 2, accuracy: 1e-9)
        XCTAssertTrue(PortfolioWidgetSeries.drawable(points))
        XCTAssertTrue(PortfolioWidgetSeries.coversDay(points))
    }

    func testCoverageBelowEightyPercentDropsHours() throws {
        // BTC hat Kurse, macht aber nur 70 % des Werts aus
        let current: [String: Double] = ["BTC": 100, "XYZ": 1]
        let points = PortfolioWidgetSeries.hourly(holdings: ["BTC": 0.7, "XYZ": 30], current: current,
                                                  prices: ["BTC": series { _ in 100 }], now: now, factor: 1,
                                                  stables: stables)
        XCTAssertEqual(points.map(\.at), [now])
        XCTAssertFalse(PortfolioWidgetSeries.drawable(points))
        // Genau 80 %: zählt; der Rest geht flach mit dem aktuellen Kurs ein
        let ok = PortfolioWidgetSeries.hourly(holdings: ["BTC": 0.8, "XYZ": 20], current: current,
                                              prices: ["BTC": series { _ in 50 }], now: now, factor: 1, stables: stables)
        XCTAssertEqual(ok.count, 25)
        XCTAssertEqual(try XCTUnwrap(ok.first).value, 0.8 * 50 + 20, accuracy: 1e-9)
    }

    func testFewHoursAreNotDrawable() {
        let prices = ["BTC": stride(from: 4, through: 1, by: -1).map { PortfolioTimedPrice(at: now - Int64($0) * h, price: 100) }]
        let points = PortfolioWidgetSeries.hourly(holdings: ["BTC": 1], current: ["BTC": 100], prices: prices, now: now,
                                                  factor: 1, stables: stables)
        XCTAssertEqual(points.count, 5)
        XCTAssertFalse(PortfolioWidgetSeries.drawable(points))
        XCTAssertNil(PortfolioWidgetSeries.change(points))
        XCTAssertTrue(PortfolioWidgetSeries.hourly(holdings: [:], current: [:], prices: prices, now: now, factor: 1,
                                                   stables: stables).isEmpty)
    }

    func testChangeOverTheDay() throws {
        let points = stride(from: 24, through: 0, by: -1).map { k in
            PortfolioWidgetPoint(at: now - Int64(k) * h, value: k == 24 ? 200 : 190)
        }
        let change = try XCTUnwrap(PortfolioWidgetSeries.change(points))
        XCTAssertEqual(change.amount, -10, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(change.percent), -5, accuracy: 1e-9)
        let short = stride(from: 10, through: 0, by: -1).map { PortfolioWidgetPoint(at: now - Int64($0) * h, value: 1) }
        XCTAssertNil(PortfolioWidgetSeries.change(short))
        XCTAssertEqual(PortfolioWidgetSeries.direction(-1), -1)
        XCTAssertEqual(PortfolioWidgetSeries.direction(0.004), 0)
        XCTAssertEqual(PortfolioWidgetSeries.direction(0.01), 1)
    }

    func testCoinChangesAgainstPriceADayAgo() throws {
        let prices = ["BTC": series { $0 == 24 ? 100 : 105 }, "ETH": [PortfolioTimedPrice(at: now - 2 * h, price: 5)]]
        let changes = PortfolioWidgetSeries.coinChanges(current: ["BTC": 110, "ETH": 6], prices: prices, now: now)
        XCTAssertEqual(try XCTUnwrap(changes["BTC"]), 10, accuracy: 1e-9)
        XCTAssertNil(changes["ETH"])
    }

    func testHourlySinceDayStartAndChange() throws {
        let dayStart = now / h * h - 5 * h // fünf volle Stunden vor der laufenden
        let prices = ["BTC": (0...6).map { PortfolioTimedPrice(at: dayStart - h + Int64($0) * h, price: 100 + Double($0)) }]
        let points = PortfolioWidgetSeries.hourlySince(holdings: ["BTC": 2], current: ["BTC": 110], prices: prices,
                                                       dayStart: dayStart, now: now, factor: 1, stables: stables)
        XCTAssertEqual(points.first?.at, dayStart)
        XCTAssertEqual(points.count, 7)
        XCTAssertEqual(points.last?.at, now)
        XCTAssertEqual(try XCTUnwrap(points.first).value, 2 * 101, accuracy: 1e-9)
        let change = try XCTUnwrap(PortfolioWidgetSeries.changeSince(points, dayStart: dayStart))
        XCTAssertEqual(change.amount, 2 * 110 - 2 * 101, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(change.percent), (110.0 / 101.0 - 1) * 100, accuracy: 1e-9)
        let late = ["BTC": [PortfolioTimedPrice(at: dayStart + 2 * h, price: 105)]]
        let partial = PortfolioWidgetSeries.hourlySince(holdings: ["BTC": 2], current: ["BTC": 110], prices: late,
                                                        dayStart: dayStart, now: now, factor: 1, stables: stables)
        XCTAssertNil(PortfolioWidgetSeries.changeSince(partial, dayStart: dayStart))
        let since = PortfolioWidgetSeries.coinChangesSince(current: ["BTC": 110], prices: prices, dayStart: dayStart)
        XCTAssertEqual(try XCTUnwrap(since["BTC"]), (110.0 / 101.0 - 1) * 100, accuracy: 1e-9)
        XCTAssertTrue(PortfolioWidgetSeries.coinChangesSince(current: ["BTC": 110], prices: late, dayStart: dayStart).isEmpty)
    }
}
