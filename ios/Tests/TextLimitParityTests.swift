import XCTest
@testable import CryptoChecker

/// Längengrenze von Notizen in sichtbaren Zeichen (`Parity/text_limit.json`): Swift zählt Graphem-Cluster
/// (`count`, `prefix`) — Android zählt gleich (`TextLimit.kt`). Gegenstück: `TextLimitParityTest.kt`.
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

    /// Gruppenname, Börse/Wallet und Notiz säubern wie Eingabe und Sicherung — wie Android.
    func testSaeubertFelderWieGemeinsameTestfaelle() throws {
        let fields = S.list(try S.fixture("text_limit")["fields"])
        XCTAssertGreaterThanOrEqual(fields.count, 8)
        for c in fields {
            let name = c["description"] as? String ?? "?"
            let text = try XCTUnwrap(c["text"] as? String, name)
            let actual: String?
            switch c["field"] as? String {
            case "group": actual = Watch.validGroupName(text)
            case "exchange": actual = PortfolioTx.cleanExchange(text)
            case "note": actual = Watch.validNote(text)
            default: XCTFail("unbekanntes Feld: \(name)"); continue
            }
            XCTAssertEqual(actual, c["expected"] as? String, name)
        }
    }

    /// Gespeicherte Notiz (`Watch.validNote`) kürzt mit derselben Grenze wie Android `WatchRepository.setNote`.
    func testValidNoteKuerztAufSichtbareZeichen() {
        let family = "👨‍👩‍👧‍👦"
        let note = String(repeating: "a", count: 119) + family + "b"
        XCTAssertEqual(Watch.validNote(note), String(repeating: "a", count: 119) + family)
    }
}
