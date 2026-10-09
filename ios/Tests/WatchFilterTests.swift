import XCTest
@testable import CryptoChecker

/// «Alle», «FAV» und Gruppen — wie `WatchFilterTest.kt`.
final class WatchFilterTests: XCTestCase {
    func testAllFavoritesAndGroups() {
        XCTAssertTrue(WatchFilter.matches(nil, groupName: "Alts", favorite: false))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.favorites, groupName: "Alts", favorite: true))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.favorites, groupName: nil, favorite: true))
        XCTAssertFalse(WatchFilter.matches(WatchFilter.favorites, groupName: "Alts", favorite: false))
        XCTAssertTrue(WatchFilter.matches("Alts", groupName: "Alts", favorite: false))
        XCTAssertFalse(WatchFilter.matches("Alts", groupName: "Majors", favorite: true))
        // Eine Gruppe, die «FAV» heisst, ist nicht der Favoriten-Filter
        XCTAssertFalse(WatchFilter.isFavorites("FAV"))
        XCTAssertTrue(WatchFilter.matches("FAV", groupName: "FAV", favorite: false))
        // Gleicher gespeicherter Wert wie Android (Sicherungen behalten die Auswahl)
        XCTAssertEqual(WatchFilter.favorites, "\u{1}FAV")
    }
}
