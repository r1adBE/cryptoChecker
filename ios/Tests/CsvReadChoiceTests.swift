import XCTest
@testable import CryptoChecker

/// Seit Build 103 (Review FUN-01): «Importieren» nicht während des Neu-Lesens und nur mit der Vorschau der aktuellen
/// Lesart (`csv_import.json` › «confirmGate»). Gegenstück: `CsvReadChoiceParityTest.kt`.
final class CsvReadChoiceTests: XCTestCase {
    private typealias S = TestSupport

    private func choice(_ raw: Any?) -> CsvReadChoice? {
        guard let o = raw as? [String: Any] else { return nil }
        return CsvReadChoice(zone: o["zone"] as? String,
                             dateOrder: (o["dateOrder"] as? String).flatMap(CsvDateOrder.init(rawValue:)),
                             decimalMark: (o["decimalMark"] as? String)?.first)
    }

    func testKnopfWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("csv_import")["confirmGate"])
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let chosen = try XCTUnwrap(choice(c["chosen"]), name)
            let actual = CsvReadChoice.canConfirm(rereading: c["rereading"] as? Bool ?? false,
                                                  builtWith: choice(c["builtWith"]), chosen: chosen)
            XCTAssertEqual(actual, c["expected"] as? Bool, name)
        }
    }
}
