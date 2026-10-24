import XCTest
@testable import CryptoChecker

/// «Seit Kauf» und «7 T» des Portfolio-Widgets in der Anzeigewährung aus dem Merkzettel der App
/// (`PortfolioDisplayFigures`): dieselbe Zahl wie die Gesamtkarte (`PortfolioCostFx.figures`), nie USDT × heutiger Kurs.
/// Wie Android `PortfolioDisplayFxTest`.
final class PortfolioDisplayFxTests: XCTestCase {

    private let day: Int64 = 86_400_000
    private let d0 = 20_000
    private let d1 = 20_100
    private let today = 20_200

    private func trade(_ id: Int64, _ coin: String, _ amount: Decimal, _ price: Decimal, _ epochDay: Int) -> PortfolioTx {
        PortfolioTx(id: id, coin: coin, type: .BUY, amountExact: amount, priceExact: price,
                    time: Int64(epochDay) * day + day / 2)
    }

    private var trades: [PortfolioTx] { [trade(1, "BTC", 1, 20_000, d0), trade(2, "SOL", 10, 100, d1)] }
    private let prices: [String: Double] = ["BTC": 30_000, "SOL": 150]
    /// USD → CHF nur am Kauftag von BTC (SOL ohne Devisenkurs: fehlt, «unvollständig»).
    private var rates: [Int: Decimal] { [d0: Decimal(string: "0.9")!] }

    func testUnrealizedMatchesTheCardFigures() throws {
        let summary = PortfolioCalculator.summarize(trades, prices: prices, method: .defaultMethod)
        let figures = PortfolioCostFx.figures(summary, currency: "CHF", currentRate: Decimal(string: "0.8")!,
                                              todayEpochDay: today, rates: rates, usdtUsd: [:])
        // Karte: BTC 30000 × 0.8 − 20000 × 0.9 = 24000 − 18000 = 6000 (+33.33 %), SOL ohne Kurs am Kauftag
        XCTAssertEqual(PortfolioDecimal.double(try XCTUnwrap(figures.unrealized)), 6000, accuracy: 1e-6)
        XCTAssertEqual(figures.fxMissingCoins, ["SOL"])
        let memo = try XCTUnwrap(PortfolioDisplayFigures.memo(currency: "chf", coinCosts: figures.coinCosts,
                                                              open: summary.open, dayFactor: nil, todayEpochDay: today))
        XCTAssertEqual(memo.currency, "CHF")
        XCTAssertEqual(Set(memo.costs.keys), ["BTC"])
        let widget = try XCTUnwrap(PortfolioDisplayFigures.unrealized(open: summary.open, currency: "CHF", currentRate: 0.8,
                                                                      memo: memo))
        XCTAssertEqual(widget.amount, 6000, accuracy: 1e-6)
        XCTAssertEqual(try XCTUnwrap(widget.percent), try XCTUnwrap(figures.unrealizedPercent), accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(widget.percent), 100.0 / 3.0, accuracy: 1e-9)
        XCTAssertEqual(widget.skipped, ["SOL"])
        // Später, Kurs gestiegen: mit dem aktuellen Wert nachgerechnet (Einstand zum Kurs der Erwerbstage bleibt)
        let later = PortfolioCalculator.summarize(trades, prices: ["BTC": 40_000, "SOL": 150], method: .defaultMethod)
        let laterWidget = try XCTUnwrap(PortfolioDisplayFigures.unrealized(open: later.open, currency: "CHF",
                                                                           currentRate: 0.8, memo: memo))
        XCTAssertEqual(laterWidget.amount, 40_000 * 0.8 - 18_000, accuracy: 1e-6)
        // Andere Währung bzw. ohne Merkzettel: kein Wert
        XCTAssertNil(PortfolioDisplayFigures.unrealized(open: summary.open, currency: "EUR", currentRate: 0.8, memo: memo))
        XCTAssertNil(PortfolioDisplayFigures.unrealized(open: summary.open, currency: "CHF", currentRate: 0.8, memo: nil))
    }

    func testChangedCoinIsNotRecomputedWithAnOldCost() {
        let summary = PortfolioCalculator.summarize(trades, prices: prices, method: .defaultMethod)
        let figures = PortfolioCostFx.figures(summary, currency: "CHF", currentRate: Decimal(string: "0.8")!,
                                              todayEpochDay: today, rates: rates, usdtUsd: [:])
        let memo = PortfolioDisplayFigures.memo(currency: "CHF", coinCosts: figures.coinCosts, open: summary.open,
                                                dayFactor: nil, todayEpochDay: today)
        // Seither BTC nachgekauft: Einstand USDT anders → BTC fehlt; kein Coin übrig → «—»
        let more = PortfolioCalculator.summarize(trades + [trade(3, "BTC", 1, 25_000, d1)], prices: prices,
                                                 method: .defaultMethod)
        XCTAssertNil(PortfolioDisplayFigures.unrealized(open: more.open, currency: "CHF", currentRate: 0.8, memo: memo))
    }

    func testWeekFactorsOnlyWhenTheMemoCoversTheWeek() throws {
        let summary = PortfolioCalculator.summarize(trades, prices: prices, method: .defaultMethod)
        let figures = PortfolioCostFx.figures(summary, currency: "CHF", currentRate: Decimal(string: "0.8")!,
                                              todayEpochDay: today, rates: rates, usdtUsd: [:])
        let t = today
        let memo = try XCTUnwrap(PortfolioDisplayFigures.memo(currency: "CHF", coinCosts: figures.coinCosts,
                                                              open: summary.open,
                                                              dayFactor: { 0.8 + Double(t - $0) * 0.001 },
                                                              todayEpochDay: today))
        XCTAssertEqual(memo.dayFactors.count, PortfolioDisplayFigures.memoDays)
        let fx = try XCTUnwrap(PortfolioDisplayFigures.periodFx(memo: memo, currency: "CHF", currentRate: 0.85,
                                                                todayEpochDay: today))
        XCTAssertEqual(fx(today - 7), 0.807, accuracy: 1e-12)
        XCTAssertEqual(fx(today), 0.85, accuracy: 1e-12)
        // Am nächsten UTC-Tag fehlt der Vortag: «7 T» in USDT
        XCTAssertNil(PortfolioDisplayFigures.periodFx(memo: memo, currency: "CHF", currentRate: 0.85, todayEpochDay: today + 1))
        XCTAssertNil(PortfolioDisplayFigures.periodFx(memo: memo, currency: "EUR", currentRate: 0.85, todayEpochDay: today))
        var gap = memo
        gap.dayFactors[today - 3] = nil
        XCTAssertNil(PortfolioDisplayFigures.periodFx(memo: gap, currency: "CHF", currentRate: 0.85, todayEpochDay: today))
        // Ohne Tagesfaktoren der App (Kurse laden noch): keine Faktoren gemerkt
        XCTAssertTrue(try XCTUnwrap(PortfolioDisplayFigures.memo(currency: "CHF", coinCosts: figures.coinCosts,
                                                                 open: summary.open, dayFactor: nil,
                                                                 todayEpochDay: today)).dayFactors.isEmpty)
        // Betrag aus der Rechnung mit Faktoren ist schon in der Anzeigewährung
        XCTAssertEqual(PortfolioDisplayFigures.periodChange(PortfolioChange(amount: 12.5, percent: 1.2, skipped: ["SOL"])),
                       PortfolioWidgetChange(amount: 12.5, percent: 1.2, skipped: ["SOL"]))
        XCTAssertNil(PortfolioDisplayFigures.periodChange(nil))
        // Merkzettel übersteht das Speichern im Stand (Codable)
        let data = try JSONEncoder().encode(memo)
        XCTAssertEqual(try JSONDecoder().decode(PortfolioDisplayFx.self, from: data), memo)
    }

    /// Gesamtsicht ohne Herkunftsteile (nach dem Umstellen von USDT rechnet die Karte erst mit Teilen neu): Einstand ≠ 0
    /// ohne Teile → «—» (`partsPending`), kein kurzer Hinweis «Kurs fehlt». Mit Teilen bzw. Einstand 0: rechnen. Wie
    /// Android `costFxFlow`.
    func testCostWithoutPartsIsPendingNotFxMissing() {
        // Ø ohne Herkunftsteile (`withParts: false`, die schnelle Rechnung vor der genauen)
        let without = PortfolioCalculator.summarize(trades, prices: prices, method: .AVERAGE, withParts: false)
        XCTAssertTrue(PortfolioCostFx.partsPending(without))
        let with = PortfolioCalculator.summarize(trades, prices: prices, method: .AVERAGE)
        XCTAssertFalse(PortfolioCostFx.partsPending(with))
        // Belohnung zum Wert 0: Einstand 0 braucht keine Teile
        let zero = PortfolioCalculator.summarize([trade(1, "BTC", 1, 0, d0)], prices: prices, method: .AVERAGE,
                                                 withParts: false)
        XCTAssertFalse(PortfolioCostFx.partsPending(zero))
        XCTAssertFalse(PortfolioCostFx.partsPending(PortfolioCalculator.summarize([], prices: prices)))
        for method in [CostMethod.FIFO, .FIFO_WALLET, .AVERAGE] {
            XCTAssertFalse(PortfolioCostFx.partsPending(PortfolioCalculator.summarize(trades, prices: prices, method: method)),
                           "\(method)")
        }
    }
}
