import XCTest
@testable import CryptoChecker

/// Wie `WhyConfidenceTest.kt`: «Sicherheit» der wahrscheinlichen Gründe im «Warum»-Blatt.
final class WhyConfidenceTests: XCTestCase {

    private func r(_ kind: WhyReasonKind, _ value: Double = 0, _ secondary: Double? = nil,
                   strong: Bool = false) -> WhyReason {
        WhyReason(kind: kind, tone: .neutral, value: value, secondary: secondary, strong: strong)
    }

    func testNoDataNoConfidence() {
        XCTAssertNil(WhySummary.confidence([], hasMarketData: true))
        XCTAssertNil(WhySummary.confidence([r(.COIN_ONLY, 5, 0.1, strong: true)], hasMarketData: false))
        XCTAssertNil(WhySummary.confidence([r(.SENTIMENT, 50)], hasMarketData: true))
    }

    func testHighWithThreeStrongAgreeingAndFullData() {
        let c = WhySummary.confidence([
            r(.COIN_ONLY, 8, 0.3, strong: true),
            r(.VOLUME_HIGH, 4, strong: true),
            r(.VOLATILITY_HIGH, 3.5, 2, strong: true),
            r(.LEVERAGE_BALANCED, 0.01),
            r(.SENTIMENT, 60),
        ], hasMarketData: true)
        XCTAssertEqual(c?.level, .high)
        XCTAssertEqual(c?.agreeing, 3)
        XCTAssertEqual(c?.total, 4)
        XCTAssertEqual(c?.partialData, false)
    }

    func testMediumWhenAgreeingButNotEnoughStrong() {
        let c = WhySummary.confidence([
            r(.MARKET_WIDE, 2, 2.5),
            r(.VOLUME_HIGH, 2.2),
            r(.VOLATILITY_NORMAL, 1, 0.2),
        ], hasMarketData: true)
        XCTAssertEqual(c?.level, .medium)
        XCTAssertEqual(c?.agreeing, 2)
        XCTAssertEqual(c?.total, 3)
    }

    func testThinVolumeBlocksHigh() {
        let c = WhySummary.confidence([
            r(.AGAINST_MARKET, -6, 2, strong: true),
            r(.VOLUME_LOW, 0.4),
            r(.VOLATILITY_HIGH, 4, -3, strong: true),
            r(.LEVERAGE_SHORTS, -0.2, nil, strong: true),
        ], hasMarketData: true)
        XCTAssertEqual(c?.agreeing, 3)
        XCTAssertEqual(c?.level, .medium)
    }

    func testLowWithOneWeakHint() {
        let c = WhySummary.confidence([
            r(.MARKET_WIDE, 1.8, 0.4),
            r(.VOLUME_NORMAL, 1),
            r(.VOLATILITY_NORMAL, 0.5, 0.1),
        ], hasMarketData: true)
        XCTAssertEqual(c?.agreeing, 1)
        XCTAssertEqual(c?.level, .low)
    }

    func testLowWithPartialData() {
        let noMarket = WhySummary.confidence([
            r(.VOLUME_HIGH, 5, strong: true),
            r(.VOLATILITY_HIGH, 4, 3, strong: true),
            r(.LEVERAGE_LONGS, 0.2, nil, strong: true),
        ], hasMarketData: true)
        XCTAssertEqual(noMarket?.partialData, true)
        XCTAssertEqual(noMarket?.level, .low)

        let two = WhySummary.confidence([
            r(.COIN_ONLY, 7, 0.1, strong: true),
            r(.LEVERAGE_LONGS, 0.2, nil, strong: true),
        ], hasMarketData: true)
        XCTAssertEqual(two?.partialData, true)
        XCTAssertEqual(two?.agreeing, 2)
        XCTAssertEqual(two?.total, 2)
        XCTAssertEqual(two?.level, .low)
    }

    func testCalmAgreesWithQuietHints() {
        let c = WhySummary.confidence([
            r(.MARKET_CALM, 0.3, 0.5),
            r(.VOLUME_NORMAL, 1),
            r(.VOLATILITY_NORMAL, 0.6, 0.1),
            r(.LEVERAGE_BALANCED, 0.01),
        ], hasMarketData: true)
        XCTAssertEqual(c?.agreeing, 4)
        XCTAssertEqual(c?.level, .high)

        let mixed = WhySummary.confidence([
            r(.MARKET_CALM, 0.3, 0.5),
            r(.VOLUME_NORMAL, 1),
            r(.VOLATILITY_HIGH, 2.5, 1),
            r(.LEVERAGE_BALANCED, 0.01),
        ], hasMarketData: true)
        XCTAssertEqual(mixed?.agreeing, 3)
        XCTAssertEqual(mixed?.level, .medium)
    }

    func testBitcoinLeaderCountsWhenMoving() {
        let c = WhySummary.confidence([
            r(.MARKET_LEADER, 4, 3, strong: true),
            r(.VOLUME_HIGH, 3.2, strong: true),
            r(.VOLATILITY_HIGH, 3.1, 1.5, strong: true),
        ], hasMarketData: true)
        XCTAssertEqual(c?.agreeing, 3)
        XCTAssertEqual(c?.total, 3)
        XCTAssertEqual(c?.level, .high)
    }
}
