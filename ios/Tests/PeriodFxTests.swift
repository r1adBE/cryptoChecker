import XCTest
@testable import CryptoChecker

/// «7 T» im Kopf in der Anzeigewährung = Gewinn/Verlust der umgerechneten Kurve (gleiche Tageskurse) — wie
/// `PortfolioHistoryFxTest.weekHeaderMatchesConvertedCurve` (Android).
final class PeriodFxTests: XCTestCase {

    func testWeekHeaderMatchesConvertedCurve() throws {
        let today = 20_000
        func t(_ day: Int) -> Int64 { Int64(day) * 86_400_000 + 43_200_000 }
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: t(19_980)),
            PortfolioTx(id: 2, coin: "BTC", type: .BUY, amount: 0.5, priceUsdt: 110, time: t(19_996)),
        ]
        var btc: [Int: Double] = [:]
        for day in 19_970...19_999 { btc[day] = day <= 19_993 ? 100 : 105 }
        let closes = ["BTC": btc]
        let live = ["BTC": 120.0]
        var fxRates: [Int: Double] = [:]
        for day in 19_960...19_999 { fxRates[day] = day <= 19_993 ? 0.9 : 0.95 }
        let fx = PortfolioHistoryFx.rateFunction(dailyRates: fxRates, currentRate: 1.25, todayEpochDay: today)

        let usdt = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: live, closes: closes,
                                                            baseDay: today - PortfolioPeriodChange.days))
        XCTAssertEqual(usdt.amount, 180 - 100 - 55, accuracy: 1e-9)
        let header = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: live, closes: closes,
                                                              baseDay: today - PortfolioPeriodChange.days, fx: fx))
        // 1.5 × 120 × 1.25 − 1 × 100 × 0.9 − 0.5 × 110 × 0.95
        XCTAssertEqual(header.amount, 225 - 90 - 52.25, accuracy: 1e-9)
        let gainPart: Double = 225 - 90 - 52.25
        let basePart: Double = 90 + 52.25
        let expectedPercent: Double = gainPart / basePart * 100
        XCTAssertEqual(try XCTUnwrap(header.percent), expectedPercent, accuracy: 1e-9)

        let series = PortfolioHistory.build(trades: trades, closes: closes, livePrices: live, range: .week,
                                            todayEpochDay: today)
        let curve = PortfolioHistoryFx.convert(series, dailyRates: fxRates, currentRate: 1.25, todayEpochDay: today).series
        let pnl = try XCTUnwrap(curve.pnlChange)
        XCTAssertEqual(pnl, header.amount, accuracy: 1e-9)
        // Karte zeigt Beträge × heutiger Kurs: Gegenwert so, dass genau die Zahl der Kurve erscheint
        XCTAssertEqual(PortfolioHistoryFx.forCard(header, currentRate: 1.25).amount * 1.25, pnl, accuracy: 1e-9)
    }
}
