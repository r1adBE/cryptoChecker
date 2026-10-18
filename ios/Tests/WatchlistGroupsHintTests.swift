import XCTest
@testable import CryptoChecker

/// G-14: Gruppen-Hinweis erst ab einigen Paaren — wie `WatchlistGroupsHintTest.kt`.
final class WatchlistGroupsHintTests: XCTestCase {

    private func hint(pairs: Int, groups: [String] = [], favorites: Bool = false,
                      searching: Bool = false, quickView: QuickView? = nil, onboarded: Bool = true) -> Bool {
        WatchlistGroupChips.showsHint(groups: groups, selected: nil, hasFavorites: favorites, hasActivity: false,
                                      quickView: quickView, searching: searching, sorting: false, pairCount: pairs,
                                      onboarded: onboarded)
    }

    func testHintOnlyFromFivePairs() {
        XCTAssertFalse(hint(pairs: 0))
        XCTAssertFalse(hint(pairs: WatchlistGroupChips.hintMinPairs - 1))
        XCTAssertTrue(hint(pairs: WatchlistGroupChips.hintMinPairs))
    }

    func testChipsInsteadOfHintWithGroupOrFavorite() {
        // Gruppe oder Favorit: Chips statt Hinweis, auch mit wenigen Paaren
        XCTAssertTrue(WatchlistGroupChips.showsChips(groups: ["DeFi"], selected: nil, hasFavorites: false))
        XCTAssertTrue(WatchlistGroupChips.showsChips(groups: [], selected: nil, hasFavorites: true))
        XCTAssertFalse(hint(pairs: 10, groups: ["DeFi"]))
        XCTAssertFalse(hint(pairs: 10, favorites: true))
    }

    func testNoHintWhileSearchingOrInQuickView() {
        XCTAssertFalse(hint(pairs: 10, searching: true))
        XCTAssertFalse(hint(pairs: 10, quickView: .stale))
    }

    func testNoHintBeforeFirstUseIsOver() {
        // Erster Bildschirm nur für Kurse und den ersten Alarm (WatchlistOnboarding)
        XCTAssertFalse(hint(pairs: 10, onboarded: false))
        XCTAssertTrue(hint(pairs: 10, onboarded: true))
    }
}
