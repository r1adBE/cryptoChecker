import XCTest
@testable import CryptoChecker

/// Wie `PortfolioInsightsTest.kt`: Aufteilung, Grösste Bewegungen, Beträge verbergen.
final class PortfolioInsightsTests: XCTestCase {

    private let d = 1e-9

    private func position(_ coin: String, _ value: Double?) -> CoinPosition {
        CoinPosition(coin: coin, holdings: 1, avgCost: nil, costBasis: nil, currentPrice: value, value: value,
                     unrealized: nil, unrealizedPercent: nil, realized: 0, priceMissing: false, oversold: false,
                     tradeCount: 1)
    }

    func testAllocationTopFourAndOthers() {
        let open = [position("BTC", 500), position("ETH", 200), position("SOL", 100),
                    position("ADA", 100), position("DOT", 60), position("XRP", 40)]
        let slices = PortfolioInsights.allocation(open)
        XCTAssertEqual(slices.map(\.coin), ["BTC", "ETH", "ADA", "SOL", nil])
        XCTAssertEqual(slices[0].sharePercent, 50, accuracy: d)
        XCTAssertEqual(slices[4].valueUsd, 100, accuracy: d)
        XCTAssertEqual(slices[4].sharePercent, 10, accuracy: d)
        XCTAssertTrue(slices[4].isOther)
    }

    func testAllocationNoOthersAndSkipsUnpriced() {
        let slices = PortfolioInsights.allocation([position("BTC", 300), position("ETH", nil), position("SOL", 100)])
        XCTAssertEqual(slices.map(\.coin), ["BTC", "SOL"])
        XCTAssertEqual(slices[0].sharePercent, 75, accuracy: d)
        XCTAssertTrue(PortfolioInsights.allocation([position("ETH", nil), position("X", 0)]).isEmpty)
    }

    func testValueChange() {
        XCTAssertEqual(PortfolioInsights.valueChange(110, percent: 10) ?? .nan, 10, accuracy: d)
        XCTAssertEqual(PortfolioInsights.valueChange(90, percent: -10) ?? .nan, -10, accuracy: d)
        XCTAssertNil(PortfolioInsights.valueChange(90, percent: -100))
    }

    func testMoversByAbsoluteValueChange() {
        let open = [position("BTC", 1000), position("ETH", 500), position("SOL", 50), position("ADA", 200), position("DOT", 80)]
        let changes = ["BTC": 1.0, "ETH": -10.0, "SOL": 50.0, "ADA": 0.0, "XRP": 99.0]
        let movers = PortfolioInsights.movers(open, changes: changes)
        XCTAssertEqual(movers.map(\.coin), ["ETH", "SOL", "BTC"])
        XCTAssertEqual(movers[0].changeUsd, 500 - 500 / 0.9, accuracy: d)
        XCTAssertEqual(movers[0].changePercent, -10, accuracy: d)
    }

    func testMask() {
        XCTAssertEqual(PortfolioInsights.mask("1’234.00 USDT", hidden: true), "•••")
        XCTAssertEqual(PortfolioInsights.mask("1’234.00 USDT", hidden: false), "1’234.00 USDT")
    }
}

/// Wie `PortfolioAlarmLogicTest.kt`: Wert über/unter Betrag, Veränderung ±x %.
final class PortfolioAlarmLogicTests: XCTestCase {

    private let start: Int64 = 1_700_000_000_000

    private func alarm(_ kind: PortfolioAlarmKind, _ threshold: Double, currency: String? = "CHF") -> PortfolioAlarm {
        PortfolioAlarm(id: 1, kind: kind, threshold: threshold, currency: currency, repeating: true)
    }

    private func reading(_ total: Double, change: Double? = nil, currency: String = "CHF", usdt: Double? = nil) -> PortfolioReading {
        PortfolioReading(total: total, currency: currency, totalUsdt: usdt ?? total * 1.1, changePercent: change, empty: false)
    }

    /// Wie PortfolioAlarmRunner: Rearm → referenceAt 0, Fire → referenceAt = Zeit (einmalige schalten ab).
    private func run(_ start: PortfolioAlarm, _ readings: [PortfolioReading], repeating: Bool = true, cooldown: Int = 0) -> Int {
        var a = start
        var fired = 0
        for (i, r) in readings.enumerated() {
            let now = self.start + Int64(i) * 60_000
            switch PortfolioAlarmLogic.decide(a, r, now: now, cooldownMinutes: cooldown) {
            case .nothing: break
            case .rearm: a.referenceAt = 0
            case .fire:
                fired += 1
                a.referenceAt = now
                a.lastTriggeredAt = now
                a.enabled = PortfolioAlarmLogic.enabledAfterFire(repeating: repeating)
            }
        }
        return fired
    }

    func testValueAboveFiresOnceAndRearmsWithHysteresis() {
        let values: [Double] = [49_000, 50_100, 50_500, 49_950, 50_200, 49_000, 50_300]
        XCTAssertEqual(run(alarm(.VALUE_ABOVE, 50_000), values.map { reading($0) }), 2)
    }

    func testValueBelowAndOnce() {
        let values: [Double] = [12_000, 9_900, 11_000, 9_000]
        XCTAssertEqual(run(alarm(.VALUE_BELOW, 10_000), values.map { reading($0) }), 2)
        XCTAssertEqual(run(alarm(.VALUE_BELOW, 10_000), values.map { reading($0) }, repeating: false), 1)
    }

    func testCurrencyMatching() {
        let r = reading(45_000, currency: "CHF", usdt: 52_000)
        XCTAssertEqual(PortfolioAlarmLogic.total(in: "chf", r), 45_000)
        XCTAssertEqual(PortfolioAlarmLogic.total(in: "USD", r), 52_000)
        XCTAssertNil(PortfolioAlarmLogic.total(in: "EUR", r))
        XCTAssertEqual(PortfolioAlarmLogic.decide(alarm(.VALUE_ABOVE, 1, currency: "EUR"), r, now: start, cooldownMinutes: 0), .nothing)
    }

    func testChangeUpAndDown() {
        let changes: [Double] = [1, 5.2, 6, -2, -5.5, -7, 0, 5]
        XCTAssertEqual(run(alarm(.CHANGE_UP, 5, currency: nil), changes.map { reading(1000, change: $0) }), 2)
        XCTAssertEqual(run(alarm(.CHANGE_DOWN, 5, currency: nil), changes.map { reading(1000, change: $0) }), 1)
        XCTAssertEqual(run(alarm(.CHANGE_UP, 5, currency: nil), [reading(1000)]), 0)
    }

    func testCooldown() {
        let values: [Double] = [101, 90, 101]
        XCTAssertEqual(run(alarm(.VALUE_ABOVE, 100), values.map { reading($0) }), 2)
        XCTAssertEqual(run(alarm(.VALUE_ABOVE, 100), values.map { reading($0) }, cooldown: 5), 1)
    }

    func testValidThreshold() {
        XCTAssertTrue(PortfolioAlarmLogic.isValidThreshold(.VALUE_ABOVE, 1_000_000))
        XCTAssertFalse(PortfolioAlarmLogic.isValidThreshold(.CHANGE_UP, 1_500))
        XCTAssertFalse(PortfolioAlarmLogic.isValidThreshold(.CHANGE_DOWN, 0))
        XCTAssertFalse(PortfolioAlarmLogic.isValidThreshold(.VALUE_BELOW, nil))
    }
}
