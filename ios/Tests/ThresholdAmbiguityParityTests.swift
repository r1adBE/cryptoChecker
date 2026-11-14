import XCTest
@testable import CryptoChecker

/// Mehrdeutige Zahl (`Parity/threshold.json`, «ambiguity»): «1.234» in DE bzw. «1,234» in US wird nie still als
/// Tausendertrennung gelesen; der Fehlertext nennt beide Schreibweisen. Gegenstück: Android `ThresholdAmbiguityParityTest.kt`.
final class ThresholdAmbiguityParityTests: XCTestCase {
    private typealias S = TestSupport

    func testAmbiguity() throws {
        let cases = S.list(try S.fixture("threshold")["ambiguity"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let text = c["text"] as? String ?? ""
            let separator: Character = (c["separator"] as? String)?.first ?? "."
            let hint = S.number(c["hint"])
            let actual = ThresholdParser.ambiguity(text, decimalSeparator: separator, priceHint: hint)
            guard let expected = c["expected"] as? [String: Any] else {
                XCTAssertNil(actual, "'\(text)' (\(separator))")
                continue
            }
            XCTAssertEqual(actual, ThresholdParser.Ambiguity(grouped: expected["grouped"] as? String ?? "",
                                                            decimal: expected["decimal"] as? String ?? ""), "'\(text)'")
            // Mehrdeutig heisst immer: parse liest nichts (nie still eine Lesart)
            XCTAssertNil(ThresholdParser.parse(text, decimalSeparator: separator, priceHint: hint))
            // Beide genannten Schreibweisen sind eindeutig
            if let actual {
                XCTAssertNil(ThresholdParser.ambiguity(actual.grouped, decimalSeparator: separator))
                XCTAssertNil(ThresholdParser.ambiguity(actual.decimal, decimalSeparator: separator))
            }
        }
    }
}
