import XCTest
@testable import CryptoChecker

/// Portfolio ausgeschaltet — gleiche Fälle wie `PortfolioOffTest.kt`.
final class PortfolioOffTests: XCTestCase {

    func testOnlyNewTransactionEnablesPortfolio() {
        XCTAssertTrue(PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: false))
        XCTAssertFalse(PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: true))
        XCTAssertFalse(PortfolioOff.enableOnSave(isNew: false, portfolioEnabled: false))
        XCTAssertFalse(PortfolioOff.enableOnSave(isNew: false, portfolioEnabled: true))
    }

    func testOnlyFirstNewTransactionAsksForTab() {
        XCTAssertTrue(PortfolioOff.askEnableOnSave(isNew: true, portfolioEnabled: false, hadTransactions: false))
        XCTAssertFalse(PortfolioOff.askEnableOnSave(isNew: true, portfolioEnabled: false, hadTransactions: true))
        XCTAssertFalse(PortfolioOff.askEnableOnSave(isNew: true, portfolioEnabled: true, hadTransactions: false))
        XCTAssertFalse(PortfolioOff.askEnableOnSave(isNew: false, portfolioEnabled: false, hadTransactions: false))
    }

    func testAddToPortfolioAlwaysOfferedExceptPreview() {
        XCTAssertTrue(PortfolioOff.offerAddToPortfolio(preview: false))
        XCTAssertFalse(PortfolioOff.offerAddToPortfolio(preview: true))
    }

    func testWidgetOffBeatsLockAndEmpty() {
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: false, locked: true, empty: true), .off)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: false, locked: false, empty: false), .off)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: true, locked: true, empty: true), .locked)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: true, locked: false, empty: true), .empty)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: true, locked: false, empty: false), .values)
    }

    func testWidgetOpensTabOrSettings() {
        XCTAssertEqual(PortfolioOff.widgetTarget(portfolioEnabled: true), .portfolioTab)
        XCTAssertEqual(PortfolioOff.widgetTarget(portfolioEnabled: false), .portfolioSettings)
    }
}
