import XCTest
@testable import CryptoChecker

/// Wie `LiveStatusTest.kt` (gleiche Fälle).
final class LiveStatusTests: XCTestCase {

    private let min: Int64 = 60_000

    func testPollingThresholdIsThreeBackgroundIntervalsWithFloor() {
        // Beide Modi: kein Vordergrund-Takt, also die Hintergrund-Regel
        XCTAssertEqual(45 * min, LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: 15))
        XCTAssertEqual(90 * min, LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: 30))
        XCTAssertEqual(180 * min, LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: 60))
        // Untergrenze 15 Min.
        XCTAssertEqual(15 * min, LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: 1))
    }

    func testLivePairStaleAfterTwoMinutes() {
        XCTAssertEqual(2 * min, LiveStatus.staleAfterMillis(onLiveSocket: true, pollingAfter: 45 * min))
        XCTAssertEqual(45 * min, LiveStatus.staleAfterMillis(onLiveSocket: false, pollingAfter: 45 * min))
        // 0 = nie veraltet (Vorschau im Aktionsblatt) bleibt 0
        XCTAssertEqual(0, LiveStatus.staleAfterMillis(onLiveSocket: true, pollingAfter: 0))
    }

    func testLiveOnlyWhenAllPairsOnSocketAndFresh() {
        XCTAssertTrue(LiveStatus.allLive(tradedIds: [1, 2], liveIds: [1, 2, 3], stale: 0))
        // Ein Paar abgefragt (Börse ohne WebSocket): kein «Live»
        XCTAssertFalse(LiveStatus.allLive(tradedIds: [1, 2], liveIds: [1], stale: 0))
        // Ein Live-Paar ohne Kurs seit über 2 Min.: kein «Live»
        XCTAssertFalse(LiveStatus.allLive(tradedIds: [1, 2], liveIds: [1, 2], stale: 1))
        // Keine Paare / kein Strom (Modus «Sparsam»)
        XCTAssertFalse(LiveStatus.allLive(tradedIds: [], liveIds: [1], stale: 0))
        XCTAssertFalse(LiveStatus.allLive(tradedIds: [1], liveIds: [], stale: 0))
    }

    func testShownTimeIsOldestShownPrice() {
        // Der älteste Kurs, nicht der jüngste
        XCTAssertEqual(4_000, LiveStatus.shownTime(updateTimes: [10_000, 4_000, 6_000]))
        // Nie aktualisierte Paare (0) zählen nicht
        XCTAssertEqual(10_000, LiveStatus.shownTime(updateTimes: [10_000, 0]))
        XCTAssertEqual(0, LiveStatus.shownTime(updateTimes: [0]))
        XCTAssertEqual(0, LiveStatus.shownTime(updateTimes: []))
    }
}
