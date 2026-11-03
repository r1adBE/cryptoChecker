import XCTest
@testable import CryptoChecker

/// Übersicht von «Paar hinzufügen» (`Parity/explorer_browse.json`): Markt-Chips, «Zuletzt genutzte Börsen», «Beliebt»,
/// Zeichen der Börsen und Börsen zum Suchtext. Gegenstück: `ExplorerBrowseParityTest.kt`.
final class ExplorerBrowseParityTests: XCTestCase {
    private typealias S = TestSupport

    private func strings(_ value: Any?) -> [String] { value as? [String] ?? [] }

    func testMarktChipsZeigenIhreArt() throws {
        let cases = S.list(try S.fixture("explorer_browse")["markets"])
        XCTAssertEqual(cases.compactMap { $0["filter"] as? String }, ExplorerMarketFilter.allCases.map(\.rawValue))
        for c in cases {
            let filter = try XCTUnwrap(ExplorerMarketFilter(rawValue: try XCTUnwrap(c["filter"] as? String)))
            let shown = ExchangeCategory.allCases.filter { filter.allows($0) }.map(\.rawValue)
            XCTAssertEqual(shown, strings(c["shows"]), filter.rawValue)
        }
    }

    func testZuletztGenutzteBoersen() throws {
        let data = try S.fixture("explorer_browse")
        XCTAssertEqual(S.number(data["recentMax"]).map { Int($0) }, ExplorerRecentExchanges.max)
        for c in S.list(data["recent"]) {
            let add = try XCTUnwrap(c["add"] as? String)
            XCTAssertEqual(ExplorerRecentExchanges.add(strings(c["list"]), add), strings(c["expected"]), "\(c)")
        }
        for c in S.list(data["recentDecode"]) {
            let decoded = ExplorerRecentExchanges.decode(c["text"] as? String)
            XCTAssertEqual(decoded, strings(c["expected"]), "\(c)")
            XCTAssertEqual(ExplorerRecentExchanges.decode(ExplorerRecentExchanges.encode(decoded)), decoded)
        }
    }

    func testBeliebtePaare() throws {
        let data = try S.fixture("explorer_browse")
        XCTAssertEqual(S.number(data["popularCount"]).map { Int($0) }, ExplorerPopularPairs.count)
        XCTAssertEqual(strings(data["popularExtra"]), ExplorerPopularPairs.extra)
        for c in S.list(data["popular"]) {
            let available = (c["available"] as? [String]).map { Set($0) }
            let actual = ExplorerPopularPairs.pick(starter: strings(c["starter"]), fallback: strings(c["fallback"]),
                                                   available: available)
            XCTAssertEqual(actual, strings(c["expected"]), "\(c)")
        }
    }

    func testZeichenDerBoersen() throws {
        for c in S.list(try S.fixture("explorer_browse")["marks"]) {
            let name = try XCTUnwrap(c["name"] as? String)
            XCTAssertEqual(ExplorerExchangeMark.initials(name), c["initials"] as? String, name)
            XCTAssertEqual(ExplorerExchangeMark.colorKey(name), c["colorKey"] as? String, name)
        }
    }

    func testBoersenZumSuchtext() throws {
        let data = try S.fixture("explorer_browse")
        XCTAssertEqual(S.number(data["exchangeSearchLimit"]).map { Int($0) }, ExplorerExchangeSearch.limit)
        for c in S.list(data["exchangeSearch"]) {
            let query = try XCTUnwrap(c["query"] as? String)
            let actual = ExplorerExchangeSearch.find(query, strings(c["names"])) { $0 }
            XCTAssertEqual(actual, strings(c["expected"]), query)
        }
    }
}
