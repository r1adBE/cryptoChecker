import XCTest
@testable import CryptoChecker

/// Wie `WhyFactorsTest.kt`: Faktorliste im «Warum?»-Blatt (Rang, neutrale zuletzt, nur mit Daten).
final class WhyFactorsTests: XCTestCase {

    private func report(_ reasons: [WhyReason], price: Double? = 100, high30d: Double? = nil) -> WhyReport {
        WhyReport(price: price, change1h: nil, change24h: nil, reasons: reasons, hasMarketData: true,
                  dataTime: 0, high30d: high30d)
    }

    private let volumeHigh = WhyReason(kind: .VOLUME_HIGH, tone: .warning, value: 3.4, strong: true)
    private let marketWide = WhyReason(kind: .MARKET_WIDE, tone: .up, value: 2.4, secondary: 4.0)
    private let volatilityHigh = WhyReason(kind: .VOLATILITY_HIGH, tone: .up, value: 2.1, secondary: 1.2)
    private let fundingBalanced = WhyReason(kind: .LEVERAGE_BALANCED, tone: .neutral, value: 0.01, secondary: 8.0)
    private let sentimentNeutral = WhyReason(kind: .SENTIMENT, tone: .neutral, value: 52, secondary: 3)

    func testOwnerExampleIsRankedStrongestFirstAndCappedAtFive() {
        let factors = WhyFactors.rank(report(
            [volumeHigh, marketWide, volatilityHigh, fundingBalanced, sentimentNeutral], high30d: 101.73))
        XCTAssertEqual(factors.map(\.kind), [.volume, .nearHigh, .market, .volatility, .openInterest])
        XCTAssertTrue(factors.allSatisfy { !$0.neutral })
        XCTAssertEqual(factors[0].direction, .up)
        XCTAssertEqual(factors[0].note, .volumeHigher)
        XCTAssertEqual(factors[1].note, .highBelow)
        XCTAssertEqual(factors[1].value, 1.7, accuracy: 0.01)
        XCTAssertEqual(factors[2].note, .marketPulls)
        XCTAssertEqual(factors[4].note, .oiUp)
    }

    func testNeutralFactorsComeLastInFixedOrder() {
        let factors = WhyFactors.rank(report([
            sentimentNeutral,
            WhyReason(kind: .VOLUME_NORMAL, tone: .neutral, value: 1.1),
            WhyReason(kind: .LEVERAGE_LONGS, tone: .warning, value: 0.08),
            WhyReason(kind: .MARKET_CALM, tone: .neutral, value: 0.3, secondary: 0.5),
        ]))
        XCTAssertEqual(factors.map(\.kind), [.funding, .volume, .market, .sentiment])
        XCTAssertFalse(factors[0].neutral)
        XCTAssertEqual(factors[0].note, .fundingLongs)
        XCTAssertTrue(factors.dropFirst().allSatisfy(\.neutral))
    }

    func testOnlyFactorsWithData() {
        let factors = WhyFactors.rank(report([WhyReason(kind: .LEVERAGE_BALANCED, tone: .neutral, value: 0)]))
        XCTAssertEqual(factors.map(\.kind), [.funding])
        XCTAssertEqual(factors[0].note, .fundingNeutral)
        XCTAssertTrue(WhyFactors.rank(report([])).isEmpty)
        XCTAssertTrue(WhyFactors.rank(report([], price: nil, high30d: 120)).isEmpty)
    }

    func testOpenInterestBelowFivePercentIsNeutral() {
        let small = WhyFactors.factors(report([WhyReason(kind: .LEVERAGE_BALANCED, tone: .neutral, value: 0, secondary: -3)]))
            .first { $0.kind == .openInterest }
        XCTAssertEqual(small?.neutral, true)
        XCTAssertEqual(small?.note, .oiFlat)
        XCTAssertEqual(small?.direction, WhyFactorDirection.none)
        let drop = WhyFactors.factors(report([WhyReason(kind: .LEVERAGE_SHORTS, tone: .warning, value: -0.07, secondary: -12)]))
            .first { $0.kind == .openInterest }
        XCTAssertEqual(drop?.neutral, false)
        XCTAssertEqual(drop?.note, .oiDown)
        XCTAssertEqual(drop?.direction, .down)
    }

    func testNearHighNotesAndThresholds() {
        func near(_ price: Double) -> WhyFactor? { WhyFactors.factors(report([], price: price, high30d: 100)).first }
        XCTAssertEqual(near(100)?.note, .highAt)
        // Kurs über dem Tageshoch (laufende Stunde neuer): auf dem Hoch, Abstand 0
        XCTAssertEqual(near(101)?.value, 0)
        XCTAssertEqual(near(101)?.strength ?? 0, 2, accuracy: 1e-9)
        XCTAssertEqual(near(95)?.neutral, false)
        XCTAssertEqual(near(94)?.neutral, true)
        XCTAssertNil(WhyFactors.distanceToHighPercent(price: 0, high: 100))
        XCTAssertNil(WhyFactors.distanceToHighPercent(price: 50, high: .nan))
    }

    func testMarketVariants() {
        func market(_ r: WhyReason) -> WhyFactor? { WhyFactors.factors(report([r])).first }
        let lags = market(WhyReason(kind: .MARKET_WIDE, tone: .down, value: -2, secondary: 0.4))
        XCTAssertEqual(lags?.note, .marketCoinLags)
        XCTAssertEqual(lags?.direction, .down)
        let alone = market(WhyReason(kind: .COIN_ONLY, tone: .up, value: 6, secondary: 0.2, strong: true))
        XCTAssertEqual(alone?.note, .marketCoinAlone)
        XCTAssertEqual(alone?.value, 0.2)
        XCTAssertEqual(alone?.strength ?? 0, 2, accuracy: 1e-9)
        let against = market(WhyReason(kind: .AGAINST_MARKET, tone: .down, value: -4, secondary: 2, strong: true))
        XCTAssertEqual(against?.note, .marketAgainst)
        XCTAssertEqual(against?.direction, .up)
        let leaderCalm = market(WhyReason(kind: .MARKET_LEADER, tone: .up, value: 0.8, secondary: 1))
        XCTAssertEqual(leaderCalm?.neutral, true)
        XCTAssertEqual(leaderCalm?.note, .marketLeaderCalm)
        let leaderMoves = market(WhyReason(kind: .MARKET_LEADER, tone: .down, value: -3, secondary: -2.5, strong: true))
        XCTAssertEqual(leaderMoves?.note, .marketLeaderMoves)
        XCTAssertEqual(leaderMoves?.direction, .down)
    }

    func testExtremeSentimentCountsAndGetsArrowOnBigChange() {
        let fear = WhyFactors.factors(report([WhyReason(kind: .SENTIMENT, tone: .warning, value: 12, secondary: -9)])).first
        XCTAssertEqual(fear?.neutral, false)
        XCTAssertEqual(fear?.direction, .down)
        XCTAssertEqual(WhyFactors.factors(report([sentimentNeutral])).first?.direction, WhyFactorDirection.none)
    }

    func testHigh30dNeedsThirtyRecentDays() {
        let day = ActivityAnalyzer.dayMillis
        let now = 100 * day + 5_000
        // In Schritten mit festen Typen (sonst braucht der Swift-Compiler zu lange)
        func candles(_ n: Int, endOpen: Int64) -> [MarketCandle] {
            var out: [MarketCandle] = []
            for i in 0..<n {
                let back: Int64 = Int64(n - 1 - i)
                let openTime: Int64 = endOpen - back * day
                let high: Double = i == 3 ? 50 : 20 + Double(i) * 0.1
                out.append(MarketCandle(openTime: openTime, open: 10, high: high, low: 9, close: 10, volume: 1))
            }
            return out
        }
        XCTAssertNil(ActivityAnalyzer.high30d(nil, now: now))
        XCTAssertNil(ActivityAnalyzer.high30d(candles(29, endOpen: 100 * day), now: now))
        // 31 Kerzen: die älteste (Index 0) zählt nicht mehr, Index 3 schon
        XCTAssertEqual(ActivityAnalyzer.high30d(candles(31, endOpen: 100 * day), now: now), 50)
        // Reihe endet vor drei Tagen: kein Hoch
        XCTAssertNil(ActivityAnalyzer.high30d(candles(31, endOpen: 97 * day), now: now))
    }
}
