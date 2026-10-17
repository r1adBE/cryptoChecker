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

    func testShownTimeUsesPolledPairsWhenMixed() {
        let updates: [(id: Int64, time: Int64)] = [(1, 10_000), (2, 4_000), (3, 6_000)]
        // Ohne Strom: die jüngste
        XCTAssertEqual(10_000, LiveStatus.shownTime(updates: updates, liveIds: []))
        // Paar 1 live: die jüngste der abgefragten (3)
        XCTAssertEqual(6_000, LiveStatus.shownTime(updates: updates, liveIds: [1]))
        // Alle live: die jüngste
        XCTAssertEqual(10_000, LiveStatus.shownTime(updates: updates, liveIds: [1, 2, 3]))
        // Abgefragte nie aktualisiert: die jüngste
        XCTAssertEqual(10_000, LiveStatus.shownTime(updates: [(1, 10_000), (2, 0)], liveIds: [1]))
        XCTAssertEqual(0, LiveStatus.shownTime(updates: [], liveIds: [1]))
    }
}
