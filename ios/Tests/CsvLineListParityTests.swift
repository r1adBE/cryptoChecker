import XCTest
@testable import CryptoChecker

/// Zeilennummern der CSV-Vorschau: gemeinsame Fälle mit Android (`CsvLineListParityTest.kt`, `csv_line_list.json`).
final class CsvLineListParityTests: XCTestCase {
    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? -1 }
    private func ints(_ value: Any?) -> [Int] { ((value as? [Any]) ?? []).map { int($0) } }

    func testOfMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("csv_line_list")["of"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = "\(c["name"] ?? "")"
            let r = CsvLineList.of(ints(c["lines"]), shown: int(c["shown"]))
            XCTAssertEqual(r.count, int(c["count"]), name)
            XCTAssertEqual(r.shown, ints(c["first"]), name)
            XCTAssertEqual(r.more, int(c["more"]), name)
        }
    }

    func testMoreAfterMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("csv_line_list")["moreAfter"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(CsvLineList.moreAfter(total: int(c["total"]), shown: int(c["shown"])), int(c["expected"]),
                           "\(c["name"] ?? "")")
        }
    }

    func testDefaultShowsFiveNumbers() {
        XCTAssertEqual(CsvLineList.shownDefault, 5)
        XCTAssertEqual(CsvLineList.of(Array(1...9)).shown, [1, 2, 3, 4, 5])
    }
}
