import XCTest
@testable import CryptoChecker

/// Leitsatz des Pulse-Widgets (`PulseWidgetLead`); Gegenstück Android `PulseWidgetLeadTest`.
final class PulseWidgetLeadTests: XCTestCase {
    private func coin(_ symbol: String, _ change: Double) -> MoverCoin {
        MoverCoin(symbol: symbol, name: symbol, rank: nil, change24h: change)
    }

    private func report(_ btc: Double, _ eth: Double, _ sol: Double) -> PulseReport {
        CryptoPulse.evaluate(PulseInput(btc: btc, eth: eth, sol: sol, volumeRatio: 1.0, fearGreed: nil,
                                        fundingPercent: 0.01, ethGasGwei: nil))!
    }

    private var movers: MarketMoversResult {
        MarketMoversResult(gainers: [coin("SOL", 7.2), coin("ETH", 3.0)],
                           losers: [coin("DOGE", -6.4), coin("XRP", -2.0)])
    }

    func testBroadFallNamesTheStrongestLoser() {
        let choice = PulseWidgetLead.choose(report(-4, -5, -6), movers: movers)
        XCTAssertEqual(choice.kind, .down)
        XCTAssertEqual(choice.bottom?.symbol, "DOGE")
    }

    func testBroadRiseNamesTheStrongestGainer() {
        let choice = PulseWidgetLead.choose(report(4, 5, 6), movers: movers)
        XCTAssertEqual(choice.kind, .up)
        XCTAssertEqual(choice.top?.symbol, "SOL")
    }

    func testWithoutTheRankingTheCardSentenceStays() {
        XCTAssertEqual(PulseWidgetLead.choose(report(-4, -5, -6), movers: nil).kind, .card)
        let onlyGainers = MarketMoversResult(gainers: [coin("SOL", 1)], losers: [])
        XCTAssertEqual(PulseWidgetLead.choose(report(-4, -5, -6), movers: onlyGainers).kind, .card)
    }

    func testNormalAndNeutralPartsOfTheDetailAreDropped() {
        XCTAssertNil(PulseWidgetLead.notable(.volNormalFundNeutral))
        XCTAssertNil(PulseWidgetLead.notable(.fundNeutral))
        XCTAssertNil(PulseWidgetLead.notable(.volNormal))
        XCTAssertNil(PulseWidgetLead.notable(nil))
        XCTAssertEqual(PulseWidgetLead.notable(.volAboveFundNeutral), .volAbove)
        XCTAssertEqual(PulseWidgetLead.notable(.volNormalFundHigh), .fundHigh)
        XCTAssertEqual(PulseWidgetLead.notable(.volBelowFundNegative), .volBelowFundNegative)
    }
}
