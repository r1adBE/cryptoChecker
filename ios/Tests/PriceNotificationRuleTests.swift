import XCTest
@testable import CryptoChecker

/// «Alle an» nur nach den Einstellungen — gleich wie Android `PriceNotificationRuleTest`.
final class PriceNotificationRuleTests: XCTestCase {
    private func show(master: Bool = true, pair: Bool = true, disabled: Bool = false, notTraded: Bool = false,
                      price: Double? = 100) -> Bool {
        PriceNotificationRule.showNow(masterOn: master, pairOn: pair, disabled: disabled, notTraded: notTraded, price: price)
    }

    func testShowsOnlyWithMasterPairActiveTradedAndPrice() {
        XCTAssertTrue(show())
        XCTAssertFalse(show(master: false))
        XCTAssertFalse(show(pair: false))
        XCTAssertFalse(show(disabled: true))
        XCTAssertFalse(show(notTraded: true))
        XCTAssertFalse(show(price: nil))
        XCTAssertFalse(show(price: 0))
        XCTAssertFalse(show(price: .nan))
    }

    func testReferenceOnlyWhenShown() {
        XCTAssertEqual(PriceNotificationRule.referenceAfterEnable(shown: true, price: 42), 42)
        XCTAssertNil(PriceNotificationRule.referenceAfterEnable(shown: false, price: 42))
    }

    /// Kurs-Mitteilungen nur beim Übergang an → aus entfernen, nicht bei jeder Einstellungs-Änderung.
    func testSwitchedOffOnlyOnTransition() {
        XCTAssertTrue(PriceNotificationRule.switchedOff(old: true, new: false))
        XCTAssertFalse(PriceNotificationRule.switchedOff(old: false, new: false))
        XCTAssertFalse(PriceNotificationRule.switchedOff(old: true, new: true))
        XCTAssertFalse(PriceNotificationRule.switchedOff(old: false, new: true))
    }
}
