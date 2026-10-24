import XCTest
@testable import CryptoChecker

/// Gruppen-Hinweis immer, wenn keine Chips da sind — gleiche Fälle wie `WatchlistGroupsHintTest.kt`.
final class WatchlistGroupsHintTests: XCTestCase {

    private func hint(groups: [String] = [], favorites: Bool = false, searching: Bool = false,
                      sorting: Bool = false, quickView: QuickView? = nil) -> Bool {
        WatchlistGroupChips.showsHint(groups: groups, selected: nil, hasFavorites: favorites, hasActivity: false,
                                      quickView: quickView, searching: searching, sorting: sorting)
    }

    func testHintWithoutGroupsFromFirstStart() {
        // Ohne Gruppen und Favoriten immer der Hinweis — auch mit 0–4 Paaren und ohne Alarm
        // (die Regel kennt weder Paarzahl noch ersten Gebrauch)
        XCTAssertTrue(hint())
    }

    func testChipsInsteadOfHintWithGroupOrFavorite() {
        XCTAssertTrue(WatchlistGroupChips.showsChips(groups: ["DeFi"], selected: nil, hasFavorites: false))
        XCTAssertTrue(WatchlistGroupChips.showsChips(groups: [], selected: nil, hasFavorites: true))
        XCTAssertFalse(hint(groups: ["DeFi"]))
        XCTAssertFalse(hint(favorites: true))
    }

    func testNoHintWhileSearchingSortingOrInQuickView() {
        XCTAssertFalse(hint(searching: true))
        XCTAssertFalse(hint(sorting: true))
        XCTAssertFalse(hint(quickView: .stale))
    }
}
