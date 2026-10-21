import XCTest
@testable import CryptoChecker

/// Längengrenze von Notizen in sichtbaren Zeichen (`Parity/text_limit.json`, seit Build 98): Swift zählt Graphem-Cluster
/// (`count`, `prefix`) — Android zählt seit Build 98 gleich (`TextLimit.kt`). Gegenstück: `TextLimitParityTest.kt`.
final class TextLimitParityTests: XCTestCase {
    private typealias S = TestSupport

    func testKuerztUndZaehltWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("text_limit")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let name = c["description"] as? String ?? "?"
            let text = try XCTUnwrap(c["text"] as? String, name)
            let max = Int(try XCTUnwrap(S.number(c["max"]), name))
            XCTAssertEqual(String(text.prefix(max)), c["expected"] as? String, name)
            XCTAssertEqual(text.count, Int(try XCTUnwrap(S.number(c["count"]), name)), name)
        }
    }

    /// Gespeicherte Notiz (`Watch.validNote`) kürzt mit derselben Grenze wie Android `WatchRepository.setNote`.
    func testValidNoteKuerztAufSichtbareZeichen() {
        let family = "👨‍👩‍👧‍👦"
        let note = String(repeating: "a", count: 119) + family + "b"
        XCTAssertEqual(Watch.validNote(note), String(repeating: "a", count: 119) + family)
    }
}
