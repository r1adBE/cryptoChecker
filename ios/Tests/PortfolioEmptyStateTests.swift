import XCTest
@testable import CryptoChecker

/// Leeres Portfolio: die Menge im Beispiel steht als Platzhalter und kommt aus dem Zahlenformat der App
/// (wie die Bestände). Wie `PortfolioEmptyStateTest.kt`.
final class PortfolioEmptyStateTests: XCTestCase {
    func testMengeWieBestaende() {
        XCTAssertEqual(PortfolioEmptyExample.amount, 0.01)
        XCTAssertEqual(PortfolioEmptyExample.amountText(), PriceFormat.amount(0.01))
    }

    func testBeispielEnthaeltFormatierteMenge() {
        let amount = PortfolioEmptyExample.amountText()
        let text = L("portfolio_empty_example_plain", amount)
        XCTAssertTrue(text.contains("\(amount) BTC"), text)
        XCTAssertFalse(text.contains("%"), text)
    }
}
