import XCTest
@testable import CryptoChecker

/// Markt-Tab: «Einordnung» und «Daten» beginnen immer zugeklappt, auch beim ersten Besuch;
/// Gewähltes gilt für die Sitzung; «Jetzt» nie zu — wie `MarketSectionsTest.kt`.
final class MarketSectionsTests: XCTestCase {

    func testCollapsibleSectionsStartCollapsed() {
        XCTAssertFalse(MarketSections.expanded(.context, sessionChoice: nil))
        XCTAssertFalse(MarketSections.expanded(.data, sessionChoice: nil))
    }

    func testSessionChoiceWins() {
        XCTAssertTrue(MarketSections.expanded(.context, sessionChoice: true))
        XCTAssertTrue(MarketSections.expanded(.data, sessionChoice: true))
        XCTAssertFalse(MarketSections.expanded(.data, sessionChoice: false))
    }

    func testNowNeverCollapses() {
        XCTAssertFalse(MarketSection.now.collapsible)
        XCTAssertTrue(MarketSection.context.collapsible)
        XCTAssertTrue(MarketSection.data.collapsible)
        for choice in [nil, true, false] as [Bool?] {
            XCTAssertTrue(MarketSections.expanded(.now, sessionChoice: choice))
        }
    }

    func testSummaryJoinsPresentParts() {
        XCTAssertEqual(MarketSections.summary(["Gier 72", "Neutral"]), "Gier 72 · Neutral")
        XCTAssertEqual(MarketSections.summary([nil, " Neutral "]), "Neutral")
        XCTAssertEqual(MarketSections.summary(["Gier 72", "", "  "]), "Gier 72")
        XCTAssertEqual(MarketSections.summary([nil, nil]), "")
        XCTAssertEqual(MarketSections.summary([]), "")
    }
}
