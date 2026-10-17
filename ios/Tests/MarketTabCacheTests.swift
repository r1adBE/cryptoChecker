import XCTest
@testable import CryptoChecker

/// Markt-Tab: Zwischenspeicher langsamer Daten, «Aktualisieren» mit Mindestabstand,
/// Reihenfolge der Abschnitte, Platz des Wirtschaftsdaten-Hinweises und Fear & Greed im
/// Widget — wie `CycleCachePolicyTest.kt`, `MarketRevealTest.kt`, `MacroCalendarTest.kt`
/// und `PulseWidgetMathTest.kt`.
final class MarketTabCacheTests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000
    private let minute = CycleCachePolicy.minute
    private let hour = CycleCachePolicy.hour

    func testSlowSourcesHaveLongerTtls() {
        XCTAssertEqual(CycleCachePolicy.altSeason, 3 * hour)
        XCTAssertEqual(CycleCachePolicy.fearGreed, hour)
        XCTAssertEqual(CycleCachePolicy.global, 30 * minute)
        XCTAssertEqual(CycleCachePolicy.history, 12 * hour)
        XCTAssertFalse(CycleCachePolicy.needsRefresh(savedAt: now - 2 * hour, now: now, ttl: CycleCachePolicy.altSeason, force: false))
        XCTAssertTrue(CycleCachePolicy.needsRefresh(savedAt: now - 3 * hour, now: now, ttl: CycleCachePolicy.altSeason, force: false))
    }

    func testManualReloadAtMostEveryFiveMinutes() {
        let floor = CycleCachePolicy.manualMinInterval
        XCTAssertEqual(floor, 5 * minute)
        let ttl = CycleCachePolicy.altSeason
        XCTAssertFalse(CycleCachePolicy.needsRefresh(savedAt: now - 4 * minute, now: now, ttl: ttl, force: true, minForce: floor))
        XCTAssertTrue(CycleCachePolicy.needsRefresh(savedAt: now - 5 * minute, now: now, ttl: ttl, force: true, minForce: floor))
        XCTAssertTrue(CycleCachePolicy.canManualRefresh(savedAt: nil, now: now, minInterval: floor))
        XCTAssertTrue(CycleCachePolicy.canManualRefresh(savedAt: now + minute, now: now, minInterval: floor))
        // Ohne Mindestabstand: erzwungen lädt immer
        XCTAssertTrue(CycleCachePolicy.needsRefresh(savedAt: now - 1000, now: now, ttl: ttl, force: true))
    }

    func testTabOrderNowContextData() {
        let order: [CycleRevealSlot] = [.pulse, .unusual, .headerContext, .fearGreed, .phase, .dominance, .halving,
                                        .headerData, .marketTotals, .gas, .coin]
        XCTAssertEqual(CycleRevealSlot.allCases, order)
    }

    func testMacroHintAtTopOnlyWithinTwoHours() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Zurich")!
        let cpiTime: Int64 = 1_791_980_000_000
        let events = [MacroEvent(type: .CPI, time: cpiTime)]
        func imminent(_ at: Int64) -> Bool {
            guard let hint = MacroCalendar.hint(events, now: at, calendar: calendar) else { return false }
            return MacroCalendar.isImminent(hint, now: at)
        }
        XCTAssertFalse(imminent(cpiTime - 3 * hour))
        XCTAssertTrue(imminent(cpiTime - 2 * hour))
        XCTAssertTrue(imminent(cpiTime))
        XCTAssertTrue(imminent(cpiTime + 2 * hour))
        XCTAssertFalse(imminent(cpiTime + 2 * hour + minute))
    }

    func testWidgetFearGreedOnlyWithin24Hours() {
        XCTAssertEqual(FearGreedShared.showable(.init(value: 72, time: now - hour), now: now), 72)
        XCTAssertEqual(FearGreedShared.showable(.init(value: 72, time: now - 24 * hour), now: now), 72)
        XCTAssertNil(FearGreedShared.showable(.init(value: 72, time: now - 24 * hour - 1), now: now))
        XCTAssertNil(FearGreedShared.showable(.init(value: 72, time: now + hour), now: now))
        XCTAssertNil(FearGreedShared.showable(.init(value: 140, time: now - hour), now: now))
        XCTAssertNil(FearGreedShared.showable(nil, now: now))
    }
}
