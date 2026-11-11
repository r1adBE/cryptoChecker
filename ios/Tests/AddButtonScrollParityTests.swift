import XCTest
@testable import CryptoChecker

/// Schwebender «+»-Knopf des Portfolios beim Scrollen (`Parity/add_button_scroll.json`): nach unten weg, nach oben
/// wieder da, am Anfang und am Ende immer sichtbar. Gegenstück: `AddButtonScrollParityTest.kt`.
final class AddButtonScrollParityTests: XCTestCase {
    private typealias S = TestSupport

    func testTravelMatches() throws {
        XCTAssertEqual(AddButtonScroll.travel, try XCTUnwrap(S.number(try S.fixture("add_button_scroll")["travel"])))
    }

    func testStepsMatchSharedCases() throws {
        let cases = S.list(try S.fixture("add_button_scroll")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            var state = AddButtonScroll.State()
            for (index, step) in S.list(c["steps"]).enumerated() {
                state = AddButtonScroll.next(state,
                                             delta: try XCTUnwrap(S.number(step["delta"]), name),
                                             atTop: try XCTUnwrap(step["atTop"] as? Bool, name),
                                             atEnd: try XCTUnwrap(step["atEnd"] as? Bool, name))
                XCTAssertEqual(state.visible, try XCTUnwrap(step["visible"] as? Bool, name), "\(name) · Schritt \(index + 1)")
            }
        }
    }
}
