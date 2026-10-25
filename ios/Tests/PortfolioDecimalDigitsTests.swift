import XCTest
@testable import CryptoChecker

/// `PortfolioDecimal.mantissaDigits` zählt die Ziffern der Mantisse ohne Text — gleich wie die Länge von
/// `NSDecimalString` der Mantisse; `roundSignificant` rundet damit genau wie über den Text.
final class PortfolioDecimalDigitsTests: XCTestCase {
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Ziffern der Mantisse über den Text (Mantisse mit Exponent 0, ohne Nullen am Ende).
    private static func textDigits(_ value: Decimal) -> Int {
        var compact = value
        NSDecimalCompact(&compact)
        var mantissa = compact.significand
        return NSDecimalString(&mantissa, posix).filter(\.isNumber).count
    }

    /// `roundSignificant` mit der Zählung über den Text (Referenz).
    private static func roundByText(_ value: Decimal, digits: Int = PortfolioDecimal.significantDigits) -> Decimal {
        guard value != 0, !value.isNaN, value.isFinite else { return value }
        var compact = value
        NSDecimalCompact(&compact)
        let excess = textDigits(compact) - digits
        guard excess > 0 else { return compact }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &compact, -(Int(compact.exponent) + excess), .bankers)
        return rounded
    }

    private static func dec(_ text: String) -> Decimal {
        Decimal(string: text, locale: posix) ?? .nan
    }

    func testDigitsAtPowersOfTen() {
        for k in 1...38 {
            let nines = PortfolioDecimalDigitsTests.dec(String(repeating: "9", count: k))
            let power = PortfolioDecimalDigitsTests.dec("1" + String(repeating: "0", count: k))
            var compactPower = power
            NSDecimalCompact(&compactPower)
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(nines), k, "10^\(k) − 1")
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(nines), PortfolioDecimalDigitsTests.textDigits(nines))
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(compactPower), 1, "10^\(k) kompakt")
        }
        let largest = PortfolioDecimalDigitsTests.dec("340282366920938463463374607431768211455")
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(largest), PortfolioDecimalDigitsTests.textDigits(largest))
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(0), 0)
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(1), 1)
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(-12_345), 5)
    }

    func testDivisionsRoundLikeTextCount() {
        var state: UInt64 = 41
        func next(_ bound: Int) -> Int {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Int((state >> 33) % UInt64(bound))
        }
        for _ in 0..<2_000 {
            let a = Decimal(sign: .plus, exponent: -next(19), significand: Decimal(next(1_000_000_000) + 1))
            let b = Decimal(sign: .plus, exponent: -next(9), significand: Decimal(next(99_999) + 1))
            let product = a * b
            let quotient = a / b
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(quotient), PortfolioDecimalDigitsTests.textDigits(quotient))
            let expected = PortfolioDecimalDigitsTests.roundByText(quotient)
            XCTAssertEqual(PortfolioDecimal.roundSignificant(quotient), expected)
            XCTAssertEqual(PortfolioDecimal.roundSignificant(product), PortfolioDecimalDigitsTests.roundByText(product))
            let short = PortfolioDecimal.roundSignificant(quotient, digits: 8)
            XCTAssertEqual(short, PortfolioDecimalDigitsTests.roundByText(quotient, digits: 8))
            let negative = -quotient
            let expectedNegative = PortfolioDecimalDigitsTests.roundByText(negative)
            XCTAssertEqual(PortfolioDecimal.roundSignificant(negative), expectedNegative)
        }
    }
}
