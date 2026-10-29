import XCTest
@testable import CryptoChecker

/// Portfolio-Beträge mit Vorzeichen (`Parity/signed_amount.json`): unter einem halben Cent ohne Vorzeichen (nie
/// «-0.00»), echtes Minus, Steuer-Summen nur bei Verlust mit Vorzeichen, Vorzeichen und Zahl als
/// links-nach-rechts-Insel. Gegenstück: `SignedAmountParityTest.kt`.
final class SignedAmountParityTests: XCTestCase {
    private typealias S = TestSupport

    private func number(_ magnitude: Double) -> String {
        PriceFormat.valueWithCurrency(magnitude, "").trimmingCharacters(in: .whitespaces)
    }

    func testFaelleWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("signed_amount")["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let kind = c["kind"] as? String ?? ""
            let value = try XCTUnwrap(S.number(c["value"]))
            let magnitude = try XCTUnwrap(S.number(c["magnitude"]))
            let sign = c["sign"] as? String ?? ""
            let unit = c["unit"] as? String ?? ""
            let actual: String
            let expected: String
            switch kind {
            case "money":
                actual = PortfolioFormat.signedMoney(value, unit: unit)
                expected = BidiText.ltr(sign + number(magnitude)) + " " + unit
            case "value":
                actual = PortfolioHistoryCard.signedValue(value, unit: unit)
                expected = BidiText.ltr(sign + PriceFormat.valueWithCurrency(magnitude, unit))
            default:
                actual = PortfolioChartRenderer.signedAxisValue(value)
                expected = (c["zero"] as? Bool) == true ? "0" : BidiText.ltr(sign + PriceFormat.price(magnitude))
            }
            XCTAssertEqual(actual, expected, "\(kind) \(value)")
            XCTAssertFalse(actual.contains("-"), "\(kind) \(value)")
        }
    }
}
