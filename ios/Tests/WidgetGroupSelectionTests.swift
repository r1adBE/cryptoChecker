import XCTest
@testable import CryptoChecker

/// Gruppe des Merkliste-Widgets — Gegenstück: WidgetGroupSelectionTest.kt (Android).
final class WidgetGroupSelectionTests: XCTestCase {

    private let groups: [String?] = [nil, "Majors", "Alts", "Alts"]

    func testAllStaysAll() {
        XCTAssertNil(WidgetGroupSelection.resolve(nil, groupNames: groups))
    }

    func testExistingGroupStays() {
        XCTAssertEqual(WidgetGroupSelection.resolve("Majors", groupNames: groups), "Majors")
    }

    func testDeletedGroupFallsBackToAll() {
        XCTAssertNil(WidgetGroupSelection.resolve("Memes", groupNames: groups))
        // Ohne Paare überhaupt: «Alle», kein Absturz
        XCTAssertNil(WidgetGroupSelection.resolve("Majors", groupNames: []))
    }

    func testBuiltInFiltersStay() {
        XCTAssertEqual(WidgetGroupSelection.resolve(WatchFilter.favorites, groupNames: groups), WatchFilter.favorites)
        XCTAssertEqual(WidgetGroupSelection.resolve(WatchFilter.tokens, groupNames: []), WatchFilter.tokens)
    }

    func testRenamedGroupFollowsTheNewName() {
        XCTAssertEqual(WidgetGroupSelection.resolve("Majors", groupNames: ["Large caps"],
                                                    renames: ["Majors": "Large caps"]), "Large caps")
        // Zweimal umbenannt
        XCTAssertEqual(WidgetGroupSelection.resolve("A", groupNames: ["C"], renames: ["A": "B", "B": "C"]), "C")
    }

    func testExistingGroupWinsOverAnOldRename() {
        XCTAssertEqual(WidgetGroupSelection.resolve("Majors", groupNames: ["Majors", "Large caps"],
                                                    renames: ["Majors": "Large caps"]), "Majors")
    }

    func testRenamedThenDeletedFallsBackToAll() {
        XCTAssertNil(WidgetGroupSelection.resolve("Majors", groupNames: ["Alts"], renames: ["Majors": "Large caps"]))
    }

    func testRenameLoopEndsWithAll() {
        XCTAssertNil(WidgetGroupSelection.resolve("A", groupNames: ["C"], renames: ["A": "B", "B": "A"]))
    }

    func testRenameBookkeeping() {
        var renames = WidgetGroupSelection.afterRename([:], old: "A", new: "B")
        XCTAssertEqual(renames, ["A": "B"])
        // B → C: auch Widgets mit «A» folgen
        renames = WidgetGroupSelection.afterRename(renames, old: "B", new: "C")
        XCTAssertEqual(renames, ["A": "C", "B": "C"])
        // Zurück auf A: A ist wieder ein vorhandener Name, kein alter
        renames = WidgetGroupSelection.afterRename(renames, old: "C", new: "A")
        XCTAssertEqual(renames, ["B": "A", "C": "A"])
        XCTAssertEqual(WidgetGroupSelection.afterRename(renames, old: "A", new: "A"), renames)
    }

    func testDeleteBookkeeping() {
        let renames = ["A": "C", "B": "C", "X": "Y"]
        XCTAssertEqual(WidgetGroupSelection.afterDelete(renames, name: "C"), ["X": "Y"])
        XCTAssertEqual(WidgetGroupSelection.afterDelete(renames, name: "X"), ["A": "C", "B": "C"])
    }

    func testNewConfigurationIsRecognised() {
        let now: Int64 = 1_700_000_000_000
        let first = WidgetGroupSelection.seen([:], key: "system|Majors", now: now)
        XCTAssertTrue(first.isNew)
        let again = WidgetGroupSelection.seen(first.seen, key: "system|Majors", now: now + 60_000)
        XCTAssertFalse(again.isNew)
        // Andere Gruppe gewählt: neu
        XCTAssertTrue(WidgetGroupSelection.seen(again.seen, key: "system|Alts", now: now + 120_000).isNew)
        // Lange nicht gezeichnet: wieder neu, alte Einträge fallen weg
        let later = WidgetGroupSelection.seen(again.seen, key: "system|Majors",
                                              now: now + WidgetGroupSelection.seenRetentionMillis + 60_000)
        XCTAssertTrue(later.isNew)
        XCTAssertEqual(later.seen.count, 1)
    }

    func testConfigKey() {
        XCTAssertEqual(WidgetGroupSelection.configKey(theme: "dark", group: nil), "dark|")
        XCTAssertNotEqual(WidgetGroupSelection.configKey(theme: "dark", group: "A"),
                          WidgetGroupSelection.configKey(theme: "light", group: "A"))
    }

    private func title(_ group: String?) -> String {
        WidgetGroupSelection.title(group, fallback: "Merkliste") { filter in
            if filter == WatchFilter.favorites { return "Favoriten" }
            if filter == WatchFilter.tokens { return "Token" }
            return "Alpha-Tokens"
        }
    }

    func testHeaderShowsTheSelectedGroup() {
        XCTAssertEqual(title(nil), "Merkliste")
        XCTAssertEqual(title(WatchFilter.favorites), "Favoriten")
        XCTAssertEqual(title(WatchFilter.tokens), "Token")
        XCTAssertEqual(title(WatchFilter.alpha), "Alpha-Tokens")
        XCTAssertEqual(title("TradFi"), "TradFi")
        XCTAssertEqual(title("Majors"), "Majors")
    }

    func testHeaderTruncatesLongNames() {
        // 24 Zeichen (längster Gruppenname) → 15 + «…» (wie Android)
        XCTAssertEqual(title("Langer Gruppenname Nr. 1"), "Langer Gruppenn…")
        XCTAssertEqual(title("1234567890123456"), "1234567890123456")
        XCTAssertEqual(title(String(repeating: "🚀", count: 20)), String(repeating: "🚀", count: 15) + "…")
    }

    /// Paar für `WidgetGroupSelection.pick`: Gruppe, Favorit, Alpha-Token.
    private struct P: Equatable {
        let name: String
        let group: String?
        var fav = false
        var alpha = false
    }

    private let pairs = [
        P(name: "BTC", group: nil, fav: true),
        P(name: "AIA", group: nil, alpha: true),
        P(name: "NVDA", group: "TradFi"),
        P(name: "ETH", group: "Majors"),
    ]

    private func pick(_ selection: String?, renames: [String: String] = [:]) -> (String?, [String]) {
        let r = WidgetGroupSelection.pick(selection, items: pairs, groupName: { $0.group }, favorite: { $0.fav },
                                          alpha: { $0.alpha }, renames: renames)
        return (r.group, r.items.map(\.name))
    }

    private func assertPick(_ selection: String?, _ group: String?, _ names: [String],
                            renames: [String: String] = [:], line: UInt = #line) {
        let r = pick(selection, renames: renames)
        XCTAssertEqual(r.0, group, line: line)
        XCTAssertEqual(r.1, names, line: line)
    }

    func testPickFiltersBySelection() {
        assertPick(nil, nil, ["BTC", "AIA", "NVDA", "ETH"])
        assertPick(WatchFilter.favorites, WatchFilter.favorites, ["BTC"])
        assertPick(WatchFilter.tokens, WatchFilter.tokens, ["BTC", "AIA", "ETH"])
        // «Alpha»: nur Alpha-Tokens
        assertPick(WatchFilter.alpha, WatchFilter.alpha, ["AIA"])
        assertPick("Majors", "Majors", ["ETH"])
        assertPick("TradFi", "TradFi", ["NVDA"])
    }

    func testPickFallsBackToAllInsteadOfAnEmptyWidget() {
        assertPick("Memes", nil, ["BTC", "AIA", "NVDA", "ETH"])
        assertPick("Large caps", "Majors", ["ETH"], renames: ["Large caps": "Majors"])
        // «Alpha» ohne Alpha-Tokens (Liste noch nicht geladen): alle, Titel «Merkliste»
        let noAlpha = WidgetGroupSelection.pick(WatchFilter.alpha, items: pairs, groupName: { $0.group },
                                                favorite: { $0.fav })
        XCTAssertNil(noAlpha.group)
        XCTAssertEqual(noAlpha.items, pairs)
    }

    func testOptionsFollowTheChipOrder() {
        XCTAssertEqual(WidgetGroupSelection.options(groupNames: pairs.map(\.group), alphaFlags: pairs.map(\.alpha)),
                       [nil, WatchFilter.favorites, WatchFilter.tokens, WatchFilter.alpha, "Majors", "TradFi"])
        XCTAssertEqual(WidgetGroupSelection.options(groupNames: ["Majors", "alts", nil], alphaFlags: [false, false, false]),
                       [nil, WatchFilter.favorites, "alts", "Majors"])
        XCTAssertEqual(WidgetGroupSelection.options(groupNames: [nil], alphaFlags: [true]),
                       [nil, WatchFilter.favorites])
    }
}
