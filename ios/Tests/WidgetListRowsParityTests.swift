import XCTest
@testable import CryptoChecker

/// Zeilen des Merklisten-Widgets bei grosser Schrift (`widget_list_rows.json`, Quelle `android/testdata/parity`) —
/// wie `WidgetListRowsParityTest.kt`.
final class WidgetListRowsParityTests: XCTestCase {
    private typealias S = TestSupport

    func testRowsFor() throws {
        let cases = S.list(try S.fixture("widget_list_rows")["rowsFor"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let height = S.number(c["height"]) ?? -1
            let scale = S.number(c["scale"]) ?? -1
            let baseRows = Int(S.number(c["baseRows"]) ?? -1)
            let want = Int(S.number(c["rows"]) ?? -1)
            XCTAssertEqual(WidgetRows.rowsFor(height: height, scale: scale, baseRows: baseRows), want,
                           c["name"] as? String ?? "")
        }
    }

    func testFewerRowsOnlyWithLargerText() {
        XCTAssertEqual(WidgetRows.rowsFor(height: 112, scale: 1, baseRows: WidgetRows.mediumListRows), 4)
        XCTAssertEqual(WidgetRows.rowsFor(height: 308, scale: 0.8, baseRows: WidgetRows.maxListRows), 10)
        XCTAssertLessThan(WidgetRows.rowsFor(height: 308, scale: 1.3, baseRows: WidgetRows.maxListRows), 10)
        XCTAssertEqual(WidgetRows.rowsFor(height: 308, scale: 1.3, baseRows: 10),
                       WidgetRows.rowsFor(height: 308, scale: 3, baseRows: 10))
    }
}
