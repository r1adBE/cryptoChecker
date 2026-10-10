import XCTest
@testable import CryptoChecker

/// Markt-Tab: drei Register, Start mit «Jetzt», Teile je Register, Ladezeile, Wischen,
/// Register des Wirtschaftsdaten-Hinweises — wie `MarketSectionsTest.kt`.
final class MarketSectionsTests: XCTestCase {

    func testStartsWithNow() {
        XCTAssertEqual(MarketSections.defaultSection, .now)
    }

    func testSlotsBelongToTheirRegister() {
        XCTAssertEqual(MarketSections.of(.pulse), .now)
        XCTAssertEqual(MarketSections.of(.unusual), .now)
        XCTAssertEqual(MarketSections.of(.fearGreed), .context)
        XCTAssertEqual(MarketSections.of(.halving), .context)
        XCTAssertEqual(MarketSections.of(.marketTotals), .data)
        XCTAssertEqual(MarketSections.of(.coin), .data)
        // Jedes Register hat zusammenhängende Teile in der Reihenfolge der Register
        let order = CycleRevealSlot.allCases.map { MarketSections.of($0).rawValue }
        XCTAssertEqual(order, order.sorted())
    }

    func testLoadingRowUntilLastSlotOfRegister() {
        XCTAssertEqual(MarketSections.lastSlot(.now), .unusual)
        XCTAssertEqual(MarketSections.lastSlot(.context), .halving)
        XCTAssertEqual(MarketSections.lastSlot(.data), .coin)
        XCTAssertTrue(MarketSections.loading(.now, revealed: 1))
        XCTAssertFalse(MarketSections.loading(.now, revealed: 2))
        XCTAssertTrue(MarketSections.loading(.data, revealed: CycleReveal.count - 1))
        XCTAssertFalse(MarketSections.loading(.data, revealed: CycleReveal.count))
    }

    func testSwipeMovesToNeighbour() {
        // Nach links wischen: nächstes Register; nach rechts: voriges; am Rand nichts
        XCTAssertEqual(MarketSections.swipeTarget(.now, dx: -100, threshold: 50, rtl: false), .context)
        XCTAssertEqual(MarketSections.swipeTarget(.context, dx: 100, threshold: 50, rtl: false), .now)
        XCTAssertNil(MarketSections.swipeTarget(.now, dx: 100, threshold: 50, rtl: false))
        XCTAssertNil(MarketSections.swipeTarget(.data, dx: -100, threshold: 50, rtl: false))
        // Zu kurz
        XCTAssertNil(MarketSections.swipeTarget(.now, dx: -40, threshold: 50, rtl: false))
        // Rechts-nach-links: umgekehrt
        XCTAssertEqual(MarketSections.swipeTarget(.now, dx: 100, threshold: 50, rtl: true), .context)
        XCTAssertNil(MarketSections.swipeTarget(.now, dx: -100, threshold: 50, rtl: true))
    }

    func testMacroHintRegister() {
        XCTAssertEqual(MarketSections.macroSection(imminent: true), .now)
        XCTAssertEqual(MarketSections.macroSection(imminent: false), .data)
    }
}
