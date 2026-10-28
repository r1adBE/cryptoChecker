import XCTest
@testable import CryptoChecker

/// Teilung und Multiplikation einmal auf 34 Stellen gerundet (`Parity/portfolio_decimal.json`): `divide`, `multiply` und
/// `divide(_:_:by:)` (Produkt exakt) — auch dort, wo 38 Stellen und dann 34 in der letzten Stelle abwichen.
/// Gegenstück: `PortfolioDecimalParityTest.kt`.
final class PortfolioDecimalParityTests: XCTestCase {
    private typealias S = TestSupport

    private let posix = Locale(identifier: "en_US_POSIX")

    private func dec(_ value: Any?) throws -> Decimal {
        try XCTUnwrap(Decimal(string: value as? String ?? "", locale: posix), "\(String(describing: value))")
    }

    /// Ohne Exponent und Nullen am Ende (wie `toPlainString` nach `stripTrailingZeros`, Android).
    private func plain(_ value: Decimal) -> String {
        if value == 0 { return "0" }
        var v = value
        NSDecimalCompact(&v)
        return NSDecimalString(&v, posix)
    }

    func testDivide() throws {
        let cases = S.list(try S.fixture("portfolio_decimal")["divide"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let q = PortfolioDecimal.divide(try dec(c["a"]), try dec(c["b"]))
            XCTAssertEqual(plain(q), c["expected"] as? String, "\(c)")
        }
    }

    func testMultiply() throws {
        let cases = S.list(try S.fixture("portfolio_decimal")["multiply"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let p = PortfolioDecimal.multiply(try dec(c["a"]), try dec(c["b"]))
            XCTAssertEqual(plain(p), c["expected"] as? String, "\(c)")
        }
    }

    func testDivideProduct() throws {
        let cases = S.list(try S.fixture("portfolio_decimal")["divideProduct"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let q = PortfolioDecimal.divide(try dec(c["a"]), try dec(c["b"]), by: try dec(c["c"]))
            XCTAssertEqual(plain(q), c["expected"] as? String, "\(c)")
        }
    }

    /// Jede Gruppe enthält Fälle, in denen doppeltes Runden abwiche.
    func testDoubleRoundingCovered() throws {
        let fixture = try S.fixture("portfolio_decimal")
        for section in ["divide", "multiply", "divideProduct"] {
            XCTAssertTrue(S.list(fixture[section]).contains { $0["doubleRounding"] as? Bool == true }, section)
        }
        // Produkte, die ein einfaches `a * b` mit `Decimal` anders ergäbe (Geldpfade nutzen `multiply`)
        XCTAssertTrue(S.list(fixture["multiply"]).contains { $0["plainDecimalDiffers"] as? Bool == true })
    }
}
