import XCTest
@testable import CryptoChecker

/// «Kurz gesagt» im Widget «Was gerade auffällt» (`PulseWidgetPlain`); Gegenstück `PulseWidgetPlainTest.kt`.
final class PulseWidgetPlainTests: XCTestCase {

    private let now: Int64 = 1_750_000_000_000
    private let minute: Int64 = 60_000

    func testSentencesOnlyWholeDroppedFromTheEnd() {
        // Rahmen: 8 oben + Titel 16 + 4 + Hinweis 30 = 58; je Satz 4 Abstand + Höhe
        let heights: [CGFloat] = [36, 36, 54]
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 196, title: 16, footnote: 30, sentenceHeights: heights), 3)
        // Ein pt weniger: der dritte Satz passt nicht ganz → weg, nicht abgeschnitten
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 195, title: 16, footnote: 30, sentenceHeights: heights), 2)
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 98, title: 16, footnote: 30, sentenceHeights: heights), 1)
        // Nicht einmal ein Satz: der Block entfällt
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 97, title: 16, footnote: 30, sentenceHeights: heights), 0)
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 500, title: 16, footnote: 30, sentenceHeights: []), 0)
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 0, title: 16, footnote: 30, sentenceHeights: heights), 0)
    }

    func testLongSecondSentenceDoesNotUnlockShorterThird() {
        // Reihenfolge bleibt: passt Satz 2 nicht, entfällt auch Satz 3 (keine Lücke)
        XCTAssertEqual(PulseWidgetPlain.sentenceCount(available: 140, title: 16, footnote: 30, sentenceHeights: [20, 90, 10]), 1)
    }

    func testCandidateCountsFromAllToNone() {
        XCTAssertEqual(PulseWidgetPlain.candidateCounts(2), [2, 1, 0])
        XCTAssertEqual(PulseWidgetPlain.candidateCounts(5), [3, 2, 1, 0])
        XCTAssertEqual(PulseWidgetPlain.candidateCounts(0), [0])
    }

    func testMediumFirstSentenceOnlyWhenItFitsTheLeadLines() {
        XCTAssertTrue(PulseWidgetPlain.sentenceAsLead(sentenceLines: 2, leadLines: 2))
        XCTAssertTrue(PulseWidgetPlain.sentenceAsLead(sentenceLines: 1, leadLines: 2))
        XCTAssertFalse(PulseWidgetPlain.sentenceAsLead(sentenceLines: 3, leadLines: 2))
        XCTAssertFalse(PulseWidgetPlain.sentenceAsLead(sentenceLines: 2, leadLines: 1))
        XCTAssertFalse(PulseWidgetPlain.sentenceAsLead(sentenceLines: nil, leadLines: 2))
        XCTAssertFalse(PulseWidgetPlain.sentenceAsLead(sentenceLines: 0, leadLines: 2))
    }

    func testInputsUseTheAppFreshnessRule() {
        let fresh = MarketPlainSummary.evaluate(PulseWidgetPlain.input(
            now: now, pulseMarketChange24h: 2.4, pulseSavedAt: now - 2 * minute,
            fearGreed: 72, fearGreedSavedAt: now - 30 * minute))
        XCTAssertEqual(fresh.direction?.kind, .upSlight)
        XCTAssertEqual(fresh.mood?.kind, .greed)
        // Widget hat «Heute auffällig» nicht (wie Android): nie ein dritter Satz
        XCTAssertNil(fresh.standout)
        XCTAssertEqual(MarketPlainSummaryText.sentences(fresh).count, 2)

        // Puls veraltet nach 3 × 5 Min., Fear & Greed nach 3 × 1 h — dann kein Satz (keine Vermutung)
        let stale = MarketPlainSummary.evaluate(PulseWidgetPlain.input(
            now: now, pulseMarketChange24h: 2.4, pulseSavedAt: now - 16 * minute,
            fearGreed: 72, fearGreedSavedAt: now - 181 * minute))
        XCTAssertTrue(stale.isEmpty)
        XCTAssertTrue(MarketPlainSummaryText.sentences(stale).isEmpty)
        // Ohne Stand: kein Satz
        XCTAssertTrue(MarketPlainSummary.evaluate(PulseWidgetPlain.input(
            now: now, pulseMarketChange24h: 2.4, pulseSavedAt: nil, fearGreed: 72, fearGreedSavedAt: nil)).isEmpty)
    }

    func testExpiryIsTheEarliestMomentAShownSentenceWouldBeStale() {
        let input = PulseWidgetPlain.input(now: now, pulseMarketChange24h: -4, pulseSavedAt: now - 2 * minute,
                                           fearGreed: 20, fearGreedSavedAt: now - 30 * minute)
        // Puls: gespeichert vor 2 Min., veraltet nach 15 Min. → in 13 Min. (+1 ms, «älter als»)
        XCTAssertEqual(PulseWidgetPlain.expiresAt(input, MarketPlainSummary.evaluate(input)), now + 13 * minute + 1)

        // Nur Fear & Greed: gespeichert vor 30 Min., veraltet nach 3 h
        let moodOnly = PulseWidgetPlain.input(now: now, pulseMarketChange24h: nil, pulseSavedAt: nil,
                                              fearGreed: 20, fearGreedSavedAt: now - 30 * minute)
        XCTAssertEqual(PulseWidgetPlain.expiresAt(moodOnly, MarketPlainSummary.evaluate(moodOnly)), now + 150 * minute + 1)

        // Kein Satz: kein weiterer Eintrag
        let none = PulseWidgetPlain.input(now: now, pulseMarketChange24h: nil, pulseSavedAt: nil,
                                          fearGreed: nil, fearGreedSavedAt: nil)
        XCTAssertNil(PulseWidgetPlain.expiresAt(none, MarketPlainSummary.evaluate(none)))
    }

    func testSourcesOnlyOfShownSentencesInTheirOrder() {
        let both = MarketPlainSummary.evaluate(PulseWidgetPlain.input(
            now: now, pulseMarketChange24h: 2.4, pulseSavedAt: now - 2 * minute,
            fearGreed: 72, fearGreedSavedAt: now - 30 * minute))
        XCTAssertEqual(PulseWidgetPlain.sources(both, shownCount: 2), "CoinGecko, alternative.me")
        // Nur der erste Satz passt: nur seine Quelle
        XCTAssertEqual(PulseWidgetPlain.sources(both, shownCount: 1), "CoinGecko")
        XCTAssertNil(PulseWidgetPlain.sources(both, shownCount: 0))
        // Nur Stimmung
        let moodOnly = MarketPlainSummary.evaluate(PulseWidgetPlain.input(
            now: now, pulseMarketChange24h: nil, pulseSavedAt: nil, fearGreed: 72, fearGreedSavedAt: now - 30 * minute))
        XCTAssertEqual(PulseWidgetPlain.sources(moodOnly, shownCount: 3), "alternative.me")
    }
}
