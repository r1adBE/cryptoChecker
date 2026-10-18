import XCTest
@testable import CryptoChecker

/// Nebenzeile(n) des Widgets «Was gerade auffällt» (`PulseSideLayout`); Gegenstück in `PulseWidgetMathTest.kt`.
final class PulseSideLayoutTests: XCTestCase {
    /// Wie `ViewThatFits`: Fear & Greed 120 pt, Marktbreite 130 pt, beides mit « · » 260 pt;
    /// `twoLines` = die Höhe trägt eine zweite Nebenzeile.
    private func layout(width: Double, twoLines: Bool, fearGreed: Double? = 120, breadth: Double? = 130) -> PulseSideLayout? {
        PulseSideLayout.choose(hasFearGreed: fearGreed != nil, hasBreadth: breadth != nil, twoLines: twoLines) { layout in
            func fits(_ w: Double?) -> Bool { w.map { $0 <= width } ?? false }
            switch layout {
            case .oneLine: return fearGreed != nil && breadth != nil && 260 <= width
            case .twoLines: return fits(fearGreed) && fits(breadth)
            case .fearGreed: return fits(fearGreed)
            case .breadth: return fits(breadth)
            }
        }
    }

    func testWideShowsBothInOneLine() {
        XCTAssertEqual(layout(width: 300, twoLines: true), .oneLine)
        XCTAssertEqual(layout(width: 300, twoLines: false), .oneLine)
    }

    func testMediumStacksBreadthBelowFearGreed() {
        XCTAssertEqual(layout(width: 224, twoLines: true), .twoLines)
        // Marktbreite passt allein nicht: nie abgeschnitten, nur Fear & Greed
        XCTAssertEqual(layout(width: 224, twoLines: true, breadth: 230), .fearGreed)
    }

    func testSmallHeightKeepsTheOldFallback() {
        XCTAssertEqual(layout(width: 224, twoLines: false), .fearGreed)
        XCTAssertEqual(layout(width: 224, twoLines: false, fearGreed: 230), .breadth)
        XCTAssertNil(layout(width: 100, twoLines: false))
    }

    func testCandidatesMatchAndroidOrder() {
        XCTAssertEqual(PulseSideLayout.candidates(hasFearGreed: true, hasBreadth: true, twoLines: true),
                       [.oneLine, .twoLines, .fearGreed, .breadth])
        XCTAssertEqual(PulseSideLayout.candidates(hasFearGreed: true, hasBreadth: true, twoLines: false),
                       [.oneLine, .fearGreed, .breadth])
        XCTAssertEqual(PulseSideLayout.candidates(hasFearGreed: true, hasBreadth: false, twoLines: true), [.fearGreed])
        XCTAssertEqual(PulseSideLayout.candidates(hasFearGreed: false, hasBreadth: true, twoLines: true), [.breadth])
        XCTAssertTrue(PulseSideLayout.candidates(hasFearGreed: false, hasBreadth: false, twoLines: true).isEmpty)
        XCTAssertTrue(PulseSideLayout.twoLines.showsFearGreed && PulseSideLayout.twoLines.showsBreadth)
        XCTAssertFalse(PulseSideLayout.fearGreed.showsBreadth)
        XCTAssertFalse(PulseSideLayout.breadth.showsFearGreed)
    }
}
