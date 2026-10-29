import XCTest
@testable import CryptoChecker

/// Frist beiseitegelegter und ersetzter Dateien (`Parity/aside_retention.json`): gleiche Auswahl wie Android
/// (`AsideRetentionParityTest.kt`).
final class AsideRetentionTests: XCTestCase {
    private typealias S = TestSupport

    private func millis(_ value: Any?) -> [String: Int64] {
        ((value as? [String: Any]) ?? [:]).compactMapValues { ($0 as? NSNumber)?.int64Value }
    }

    func testFristWieGemeinsameFaelle() throws {
        let fixture = try S.fixture("aside_retention")
        XCTAssertEqual((fixture["maxAgeMillis"] as? NSNumber)?.int64Value, AsideRetention.maxAgeMillis)
        let cases = S.list(fixture["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let selection = AsideRetention.select(
                copies: try XCTUnwrap(c["copies"] as? [String], name),
                seen: millis(c["seen"]),
                now: try XCTUnwrap((c["now"] as? NSNumber)?.int64Value, name),
                allowed: try XCTUnwrap(c["allowed"] as? Bool, name))
            XCTAssertEqual(selection.delete, c["delete"] as? [String], name)
            XCTAssertEqual(selection.seen, millis(c["seenAfter"]), name)
        }
    }
}
