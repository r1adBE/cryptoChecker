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

    /// Unter 10⁻¹²⁸ (nur iOS, `Decimal` hat 8 Bit Exponent): weniger Stellen, die letzte bei 10⁻¹²⁸, bis 0 — nie ein
    /// übergelaufener Exponent (früher −130 → +126). Muster aus `PortfolioCostPartPoolTests`: Produkt vieler Anteile.
    func testUnderflowShrinksToZero() throws {
        let tiny = try dec("5E-65")
        XCTAssertEqual(PortfolioDecimal.multiply(tiny, try dec("2E-64")), try dec("1E-128"))
        XCTAssertEqual(PortfolioDecimal.multiply(tiny, tiny), 0)
        XCTAssertEqual(PortfolioDecimal.multiply(try dec("1.234567E-100"), try dec("1E-25")), try dec("1.235E-125"))
        XCTAssertEqual(PortfolioDecimal.divide(1, try dec("3E127")), try dec("3E-128"))
        XCTAssertEqual(PortfolioDecimal.divide(try dec("1E-100"), try dec("1E60")), 0)
        var rng = PerformanceRandom(seed: 7)
        var product: Decimal = 1
        for _ in 0..<700 {
            let total = Decimal(1_000 + rng.nextInt(7))
            let share = PortfolioDecimal.divide(total - Decimal(1 + rng.nextInt(999)), total)
            let next = PortfolioDecimal.multiply(product, share)
            XCTAssertTrue(next >= 0 && next <= product, "\(product) × \(share) = \(next)")
            product = next
        }
        XCTAssertEqual(product, 0)
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
