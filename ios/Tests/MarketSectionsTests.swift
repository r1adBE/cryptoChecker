import XCTest
@testable import CryptoChecker

/// Markt-Tab: drei Register, Start mit «Jetzt», Teile je Register, Ladezeile, Wischen,
/// Register des Wirtschaftsdaten-Hinweises — wie `MarketSectionsTest`.
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
        XCTAssertTrue(MarketSections.loading(.now, revealed: [.pulse]))
        XCTAssertFalse(MarketSections.loading(.now, revealed: [.pulse, .unusual]))
        let all = Set(CycleRevealSlot.allCases)
        XCTAssertTrue(MarketSections.loading(.data, revealed: all.subtracting([.coin])))
        XCTAssertFalse(MarketSections.loading(.data, revealed: all))
        // Je Register für sich: «Daten» fertig, auch wenn «Einordnung» noch lädt
        let dataDone = all.subtracting([.halving])
        XCTAssertFalse(MarketSections.loading(.data, revealed: dataDone))
        XCTAssertTrue(MarketSections.loading(.context, revealed: dataDone))
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

    func testSubpageFromRoute() {
        XCTAssertEqual(MarketSections.subpage("CONTEXT"), .context)
        XCTAssertEqual(MarketSections.subpage("DATA"), .data)
        // «Jetzt» steht im Tab selbst; Unbekanntes: «Einordnung»
        XCTAssertEqual(MarketSections.subpage("NOW"), .context)
        XCTAssertEqual(MarketSections.subpage(nil), .context)
        XCTAssertEqual(MarketSections.subpage("x"), .context)
    }

    /// Jede Kennzahl hat eine Zeile «hoch heisst …, tief heisst …», dazu Hebel,
    /// Funding und Altcoins in den Info-Blättern — übersetzt und kurz. Wie `IndicatorReadingTest.kt`.
    func testIndicatorReadings() {
        for id in IndicatorId.allCases {
            let text = IndicatorText.reading(id)
            XCTAssertFalse(text.isEmpty || text.hasPrefix("ind_read_"), "\(id)")
            XCTAssertLessThanOrEqual(text.count, 160, "\(id)")
        }
        XCTAssertEqual(MarketInfoTopic.pulse.readingKeys, ["ind_read_leverage"])
        XCTAssertEqual(MarketInfoTopic.unusual.readingKeys, ["ind_read_funding"])
        XCTAssertEqual(MarketInfoTopic.altSeason.readingKeys, ["ind_read_alt_season"])
        for key in MarketInfoTopic.pulse.readingKeys + MarketInfoTopic.unusual.readingKeys + MarketInfoTopic.altSeason.readingKeys {
            XCTAssertNotEqual(L(key), key)
        }
    }
}
