import XCTest
@testable import CryptoChecker

/// Wie `PortfolioCalculatorTest.kt` und `PortfolioHistoryTest.kt` (scrubIndex).
final class PortfolioCalculatorTests: XCTestCase {

    private var nextId: Int64 = 1
    private let d = 1e-9

    private func buy(_ coin: String, _ amount: Double, _ price: Double?, _ time: Int64) -> PortfolioTx {
        defer { nextId += 1 }
        return PortfolioTx(id: nextId, coin: coin, type: .BUY, amount: amount, priceUsdt: price, time: time)
    }

    private func sell(_ coin: String, _ amount: Double, _ price: Double?, _ time: Int64) -> PortfolioTx {
        defer { nextId += 1 }
        return PortfolioTx(id: nextId, coin: coin, type: .SELL, amount: amount, priceUsdt: price, time: time)
    }

    private func value(_ v: Double?, file: StaticString = #filePath, line: UInt = #line) -> Double {
        guard let v else {
            XCTFail("nil", file: file, line: line)
            return .nan
        }
        return v
    }

    func testAverageCostOverTwoBuys() {
        let p = PortfolioCalculator.position("BTC", trades: [buy("BTC", 1, 100, 1), buy("BTC", 3, 200, 2)], currentPrice: 250)
        XCTAssertEqual(p.holdings, 4, accuracy: d)
        XCTAssertEqual(value(p.avgCost), 175, accuracy: d)
        XCTAssertEqual(value(p.costBasis), 700, accuracy: d)
        XCTAssertEqual(value(p.value), 1000, accuracy: d)
        XCTAssertEqual(value(p.unrealized), 300, accuracy: d)
        XCTAssertEqual(value(p.unrealizedPercent), 300.0 / 700.0 * 100, accuracy: d)
        XCTAssertFalse(p.priceMissing)
        XCTAssertFalse(p.oversold)
    }

    func testTradesAreProcessedInTimeOrder() {
        let trades = [buy("ETH", 1, 300, 30), sell("ETH", 1, 200, 20), buy("ETH", 1, 100, 10)]
        let p = PortfolioCalculator.position("ETH", trades: trades, currentPrice: 300)
        XCTAssertEqual(p.holdings, 1, accuracy: d)
        XCTAssertEqual(value(p.avgCost), 300, accuracy: d)
        XCTAssertEqual(p.realized, 100, accuracy: d)
        XCTAssertFalse(p.oversold)
    }

    func testSellKeepsAverageAndBooksRealized() {
        let trades = [buy("BTC", 2, 100, 1), buy("BTC", 2, 200, 2), sell("BTC", 1, 300, 3)]
        let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 150, method: .AVERAGE)
        XCTAssertEqual(p.holdings, 3, accuracy: d)
        XCTAssertEqual(value(p.avgCost), 150, accuracy: d)
        XCTAssertEqual(value(p.costBasis), 450, accuracy: d)
        XCTAssertEqual(p.realized, 150, accuracy: d)
        XCTAssertEqual(value(p.unrealized), 0, accuracy: d)
    }

    func testRealizedLossIsNegative() {
        let p = PortfolioCalculator.position("SOL", trades: [buy("SOL", 10, 50, 1), sell("SOL", 4, 40, 2)], currentPrice: 40)
        XCTAssertEqual(p.realized, -40, accuracy: d)
        XCTAssertEqual(p.holdings, 6, accuracy: d)
        XCTAssertEqual(value(p.unrealized), -60, accuracy: d)
    }

    func testBuyWithoutPriceMarksPriceMissing() {
        let p = PortfolioCalculator.position("BTC", trades: [buy("BTC", 1, nil, 1), buy("BTC", 1, 100, 2)], currentPrice: 120)
        XCTAssertEqual(p.holdings, 2, accuracy: d)
        XCTAssertEqual(value(p.avgCost), 100, accuracy: d)
        XCTAssertTrue(p.priceMissing)
        XCTAssertNil(p.costBasis)
        // «Seit Kauf» nur für den Teil mit Kaufpreis: 1 × 120 − 100, markiert «ohne 1 BTC ohne Kaufpreis» — wie Android
        XCTAssertEqual(value(p.unrealized), 20, accuracy: d)
        XCTAssertEqual(value(p.unrealizedPercent), 20, accuracy: d)
        XCTAssertTrue(p.unrealizedPartial)
        XCTAssertEqual(p.unpricedHoldingsExact, 1)
        XCTAssertEqual(value(p.value), 240, accuracy: d)
    }

    func testSellWhileCostMissingRealizesPricedShare() {
        // Gemischt: 1 ohne Preis + 1 zu 100, Teilverkauf 1 zu 300
        let trades = [buy("BTC", 1, nil, 1), buy("BTC", 1, 100, 2), sell("BTC", 1, 300, 3)]
        let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 300, method: .AVERAGE)
        XCTAssertEqual(p.holdings, 1, accuracy: d)
        // Hälfte des Verkaufs mit Kaufpreis: (300 − 100) × 0.5; die andere Hälfte unbekannt
        XCTAssertEqual(p.realized, 100, accuracy: d)
        XCTAssertEqual(p.realizedUnknownAmount, 0.5, accuracy: d)
        XCTAssertTrue(p.realizedIncomplete)
        XCTAssertTrue(p.priceMissing)
        XCTAssertEqual(value(p.avgCost), 100, accuracy: d)
    }

    func testMixedBuysThenPartialSellWithUnevenShares() {
        // 3 zu 100 + 1 ohne Preis; Verkauf 2 zu 150 → 1.5 mit Kaufpreis, 0.5 unbekannt
        let trades = [buy("ETH", 3, 100, 1), buy("ETH", 1, nil, 2), sell("ETH", 2, 150, 3)]
        let p = PortfolioCalculator.position("ETH", trades: trades, currentPrice: 150, method: .AVERAGE)
        XCTAssertEqual(p.holdings, 2, accuracy: d)
        XCTAssertEqual(p.realized, 75, accuracy: d)
        XCTAssertEqual(p.realizedUnknownAmount, 0.5, accuracy: d)
        XCTAssertNil(p.costBasis)
    }

    func testPricedOnlySellIsComplete() {
        let p = PortfolioCalculator.position("BTC", trades: [buy("BTC", 2, 100, 1), sell("BTC", 1, 150, 2)],
                                             currentPrice: 150)
        XCTAssertEqual(p.realized, 50, accuracy: d)
        XCTAssertFalse(p.realizedIncomplete)
    }

    func testSellWithoutPriceIsUnknown() {
        let p = PortfolioCalculator.position("BTC", trades: [buy("BTC", 2, 100, 1), sell("BTC", 1, nil, 2)],
                                             currentPrice: 100)
        XCTAssertEqual(p.realized, 0, accuracy: d)
        XCTAssertEqual(p.realizedUnknownAmount, 1, accuracy: d)
    }

    func testMissingCurrentPriceHidesValueAndPl() {
        let trades = [buy("XYZ", 1, 10, 1)]
        let p = PortfolioCalculator.position("XYZ", trades: trades, currentPrice: nil)
        XCTAssertNil(p.value)
        XCTAssertNil(p.unrealized)
        XCTAssertEqual(value(p.costBasis), 10, accuracy: d)
        let s = PortfolioCalculator.summarize(trades, prices: [:])
        XCTAssertEqual(s.missingCurrentPrices, ["XYZ"])
        XCTAssertNil(s.unrealized)
        XCTAssertEqual(value(s.invested), 10, accuracy: d)
        XCTAssertEqual(s.totalValue, 0, accuracy: d)
    }

    /// Widget-Stand: Zahl der Coins ohne Kurs («ohne 1 Coin»); ohne Angabe 0, nie negativ.
    func testWidgetSnapshotMissingPricesCount() throws {
        let minimal = #"{"total":1,"currency":"USD","updatedAt":5,"empty":false}"#
        let decoded = try JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: Data(minimal.utf8))
        XCTAssertNil(decoded.missingPrices)
        XCTAssertEqual(decoded.missingCount, 0)
        var snapshot = decoded
        snapshot.missingPrices = 2
        let again = try JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: JSONEncoder().encode(snapshot))
        XCTAssertEqual(again.missingCount, 2)
        snapshot.missingPrices = -1
        XCTAssertEqual(snapshot.missingCount, 0)
    }

    func testOversellIsClampedAndFlagged() {
        let p = PortfolioCalculator.position("BTC", trades: [buy("BTC", 1, 100, 1), sell("BTC", 3, 150, 2)], currentPrice: 200)
        XCTAssertTrue(p.oversold)
        XCTAssertEqual(p.holdings, 0, accuracy: d)
        XCTAssertFalse(p.isOpen)
        XCTAssertEqual(p.realized, 50, accuracy: d)
        XCTAssertNil(p.costBasis)
        XCTAssertEqual(value(p.value), 0, accuracy: d)
    }

    func testSellingEverythingIsNotOversold() {
        // 0.1 + 0.2 ≠ 0.3 in Double
        let trades = [buy("BTC", 0.1, 100, 1), buy("BTC", 0.2, 100, 2), sell("BTC", 0.3, 100, 3)]
        let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 100)
        XCTAssertFalse(p.oversold)
        XCTAssertFalse(p.isOpen)
    }

    func testBuyAfterFullSellStartsFreshAverage() {
        let trades = [buy("BTC", 1, 100, 1), sell("BTC", 1, 200, 2), buy("BTC", 1, 300, 3)]
        let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 300)
        XCTAssertEqual(value(p.avgCost), 300, accuracy: d)
        XCTAssertEqual(p.realized, 100, accuracy: d)
        XCTAssertEqual(value(p.unrealized), 0, accuracy: d)
    }

    func testUsdtIsWorthOne() {
        let p = PortfolioCalculator.position("usdt", trades: [buy("USDT", 500, 1, 1)], currentPrice: nil)
        XCTAssertEqual(p.coin, "USDT")
        XCTAssertEqual(value(p.currentPrice), 1, accuracy: d)
        XCTAssertEqual(value(p.value), 500, accuracy: d)
    }

    func testSummaryTotalsAndSorting() {
        let trades = [buy("BTC", 1, 100, 1), buy("ETH", 10, 10, 1), buy("DOGE", 100, 1, 1), sell("DOGE", 100, 2, 2)]
        let s = PortfolioCalculator.summarize(trades, prices: ["BTC": 150, "ETH": 30, "DOGE": 3])
        XCTAssertEqual(s.open.map(\.coin), ["ETH", "BTC"])
        XCTAssertEqual(s.closed.map(\.coin), ["DOGE"])
        XCTAssertEqual(s.totalValue, 450, accuracy: d)
        XCTAssertEqual(value(s.invested), 200, accuracy: d)
        XCTAssertEqual(value(s.unrealized), 250, accuracy: d)
        XCTAssertEqual(value(s.unrealizedPercent), 125, accuracy: d)
        XCTAssertEqual(s.realized, 100, accuracy: d)
        XCTAssertFalse(s.costMissing)
        XCTAssertTrue(s.missingCurrentPrices.isEmpty)
    }

    func testSummaryExcludesOnlyCoinWithoutCost() {
        // Ein Kauf ohne Preis nimmt nur diesen Coin aus dem Gewinn seit Kauf — wie Android
        let s = PortfolioCalculator.summarize([buy("BTC", 1, 100, 1), buy("ETH", 1, nil, 1)], prices: ["BTC": 200, "ETH": 50])
        XCTAssertTrue(s.costMissing)
        XCTAssertEqual(s.missingCostCoins, ["ETH"])
        XCTAssertEqual(value(s.invested), 100, accuracy: d)
        XCTAssertEqual(value(s.unrealized), 100, accuracy: d)
        XCTAssertEqual(value(s.unrealizedPercent), 100, accuracy: d)
        XCTAssertEqual(s.totalValue, 250, accuracy: d)
    }

    func testSummaryWithoutAnyCostHasNoGain() {
        let s = PortfolioCalculator.summarize([buy("ETH", 1, nil, 1)], prices: ["ETH": 50])
        XCTAssertNil(s.invested)
        XCTAssertNil(s.unrealized)
    }

    func testClosedAtBreakEvenStaysVisible() {
        let s = PortfolioCalculator.summarize([buy("BTC", 1, 100, 1), sell("BTC", 1, 100, 2)], prices: ["BTC": 120])
        XCTAssertTrue(s.open.isEmpty)
        XCTAssertEqual(s.closed.map(\.coin), ["BTC"])
        XCTAssertEqual(s.closed.first?.realized ?? .nan, 0, accuracy: d)
        XCTAssertFalse(s.isEmpty)
        XCTAssertFalse(s.realizedIncomplete)
        XCTAssertNil(s.invested)
    }

    func testFullSellWithUnpricedBuysAppearsInClosed() {
        let trades = [buy("SOL", 1, nil, 1), buy("SOL", 1, 10, 2), sell("SOL", 2, 20, 3)]
        let s = PortfolioCalculator.summarize(trades, prices: ["SOL": 25])
        XCTAssertTrue(s.open.isEmpty)
        XCTAssertEqual(s.closed.map(\.coin), ["SOL"])
        let p = s.closed.first
        XCTAssertEqual(p?.realized ?? .nan, 10, accuracy: d)
        XCTAssertEqual(p?.realizedUnknownAmount ?? .nan, 1, accuracy: d)
        XCTAssertTrue(p?.realizedIncomplete ?? false)
        XCTAssertTrue(s.realizedIncomplete)
        XCTAssertEqual(s.realized, 10, accuracy: d)
    }

    func testFullSellOfOnlyUnpricedBuysAppearsInClosed() {
        let s = PortfolioCalculator.summarize([buy("DOT", 5, nil, 1), sell("DOT", 5, 7, 2)], prices: [:])
        XCTAssertEqual(s.closed.map(\.coin), ["DOT"])
        XCTAssertEqual(s.closed.first?.realized ?? .nan, 0, accuracy: d)
        XCTAssertTrue(s.closed.first?.realizedIncomplete ?? false)
    }

    func testSellWithoutHoldingsIsNotClosed() {
        let s = PortfolioCalculator.summarize([sell("ADA", 1, 1, 1)], prices: [:])
        XCTAssertTrue(s.closed.isEmpty)
        XCTAssertTrue(s.isEmpty)
    }

    func testCoinsAreMatchedCaseInsensitive() {
        let s = PortfolioCalculator.summarize([buy("btc", 1, 100, 1), buy(" BTC ", 1, 200, 2)], prices: ["BTC": 150])
        XCTAssertEqual(s.open.count, 1)
        XCTAssertEqual(s.open.first?.holdings ?? 0, 2, accuracy: d)
        XCTAssertEqual(value(s.open.first?.avgCost), 150, accuracy: d)
    }

    // MARK: Ziehen über den Wertverlauf

    func testScrubIndexPicksTheNearestPoint() {
        // 5 Punkte auf 0…100 (Abstand 25), Rand 2
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 2, left: 2, width: 100, count: 5), 0)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 14, left: 2, width: 100, count: 5), 0)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 15, left: 2, width: 100, count: 5), 1)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 52, left: 2, width: 100, count: 5), 2)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 102, left: 2, width: 100, count: 5), 4)
    }

    func testScrubIndexClampsAndHandlesEdgeCases() {
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: -50, left: 2, width: 100, count: 5), 0)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 500, left: 2, width: 100, count: 5), 4)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 40, left: 2, width: 100, count: 1), 0)
        XCTAssertEqual(PortfolioHistory.scrubIndex(x: 40, left: 2, width: 0, count: 5), 0)
        XCTAssertNil(PortfolioHistory.scrubIndex(x: 40, left: 2, width: 100, count: 0))
        XCTAssertNil(PortfolioHistory.scrubIndex(x: .nan, left: 2, width: 100, count: 5))
    }
}

/// Verworfene Einträge beim Lesen von `portfolio.json` werden gezählt (die Datei gilt dann als nicht ganz
/// lesbar und wird vor dem Überschreiben gesichert).
final class PortfolioFileIntegrityTests: XCTestCase {
    func testDroppedEntriesAreCounted() throws {
        let json = #"{"transactions":[{"id":1,"coin":"BTC","type":"BUY","amountText":"1","time":1,"feeRow":false,"interest":false,"stakingMove":false},{"id":2,"coin":"","type":"BUY","amountText":"0","time":2,"feeRow":false,"interest":false,"stakingMove":false}],"nextId":3}"#
        let file = try JSONDecoder().decode(PortfolioFile.self, from: Data(json.utf8))
        XCTAssertEqual(file.transactions.count, 1)
        XCTAssertEqual(file.dropped, 1)
        XCTAssertEqual(PortfolioFile().dropped, 0)
    }

    /// «transactions» und «nextId» werden immer geschrieben: fehlt eines oder ist die Liste keine, ist die Datei unlesbar.
    func testListAndCounterAreRequired() {
        XCTAssertThrowsError(try JSONDecoder().decode(PortfolioFile.self, from: Data(#"{"nextId":1}"#.utf8)))
        XCTAssertThrowsError(try JSONDecoder().decode(PortfolioFile.self, from: Data(#"{"transactions":[]}"#.utf8)))
        XCTAssertThrowsError(try JSONDecoder().decode(PortfolioFile.self, from: Data(#"{"transactions":5,"nextId":1}"#.utf8)))
    }
}
