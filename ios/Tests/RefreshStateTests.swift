import Combine
import Observation
import os
import XCTest
@testable import CryptoChecker

/// Zähler für `objectWillChange` (auf dem Haupt-Thread).
@MainActor
private final class WillChangeCounter {
    var count = 0
}

/// Status der Aktualisierung als eigenes Objekt (`RefreshState`, `AppData.refreshState`): Änderungen veröffentlichen
/// nur dort — `AppData.objectWillChange` bleibt still, die Merkliste rechnet ihren `body` nicht neu. Je Zeile
/// beobachtbare Kästchen: ein Wechsel meldet sich nur bei der Zeile seines Paars.
@MainActor
final class RefreshStateTests: XCTestCase {

    func testStatusChangesPublishOnRefreshStateNotOnAppData() {
        let data = AppData.shared
        let state = data.refreshState
        let savedRefreshing = state.refreshing
        let savedIds = state.refreshingWatchIds
        let savedMillis = state.lastRefreshMillis
        let savedReport = state.lastRefreshReport
        let savedExchanges = state.liveExchanges
        let savedLive = state.liveWatchIds
        defer {
            state.refreshing = savedRefreshing
            state.refreshingWatchIds = savedIds
            state.lastRefreshMillis = savedMillis
            state.lastRefreshReport = savedReport
            state.liveExchanges = savedExchanges
            state.liveWatchIds = savedLive
        }
        let appChanges = WillChangeCounter()
        let stateChanges = WillChangeCounter()
        var watchers: [AnyCancellable] = []
        data.objectWillChange.sink { _ in appChanges.count += 1 }.store(in: &watchers)
        state.objectWillChange.sink { _ in stateChanges.count += 1 }.store(in: &watchers)

        state.refreshing = !savedRefreshing
        XCTAssertEqual(stateChanges.count, 1, "refreshing")
        state.refreshingWatchIds.insert(-9_001)
        XCTAssertEqual(stateChanges.count, 2, "refreshingWatchIds")
        state.lastRefreshMillis = savedMillis + 1
        XCTAssertEqual(stateChanges.count, 3, "lastRefreshMillis")
        state.lastRefreshReport = nil
        XCTAssertEqual(stateChanges.count, 4, "lastRefreshReport")
        state.liveExchanges = ["Test"]
        XCTAssertEqual(stateChanges.count, 5, "liveExchanges")
        state.liveWatchIds.insert(-9_002)
        XCTAssertEqual(stateChanges.count, 6, "liveWatchIds")
        XCTAssertEqual(appChanges.count, 0, "AppData darf bei Statusänderungen nicht veröffentlichen")
        // Gelesen wird der Live-Stand weiter über AppData (Frische-Schwelle je Paar)
        XCTAssertEqual(data.staleAfterMillis(for: -9_002),
                       LiveStatus.staleAfterMillis(onLiveSocket: true, pollingAfter: data.staleAfterMillis))
        watchers.removeAll()
    }

    func testRowValuesFollowTheSets() {
        let state = RefreshState()
        XCTAssertFalse(state.rowLoading(1, priceMissing: true))
        XCTAssertFalse(state.rowOnLive(1))
        state.refreshingWatchIds.insert(1)
        XCTAssertTrue(state.rowLoading(1, priceMissing: false))
        XCTAssertFalse(state.rowLoading(2, priceMissing: false))
        // Volle Aktualisierung: nur Paare noch ohne Kurs zeigen «wird aktualisiert»
        state.refreshing = true
        XCTAssertTrue(state.rowLoading(2, priceMissing: true))
        XCTAssertFalse(state.rowLoading(2, priceMissing: false))
        state.refreshing = false
        state.refreshingWatchIds.remove(1)
        XCTAssertFalse(state.rowLoading(1, priceMissing: true))
        // Kästchen, das erst nach dem Eintrag entsteht, übernimmt den Stand
        state.liveWatchIds = [3]
        XCTAssertTrue(state.rowOnLive(3))
        state.liveWatchIds = []
        XCTAssertFalse(state.rowOnLive(3))
    }

    /// Liest wie eine Zeile (`WatchlistLiveRow`) und meldet, ob `change` diese Zeile neu zeichnen liesse.
    private func rowNotified(_ state: RefreshState, id: Int64, priceMissing: Bool,
                             _ change: () -> Void) -> Bool {
        let fired = OSAllocatedUnfairLock(initialState: false)
        withObservationTracking {
            _ = state.rowLoading(id, priceMissing: priceMissing)
            _ = state.rowOnLive(id)
        } onChange: {
            fired.withLock { $0 = true }
        }
        change()
        return fired.withLock { $0 }
    }

    func testOnlyTheAffectedRowIsNotified() {
        let state = RefreshState()
        let otherRefreshing = rowNotified(state, id: 1, priceMissing: false) { _ = state.refreshingWatchIds.insert(2) }
        XCTAssertFalse(otherRefreshing, "anderes Paar einzeln aktualisiert")
        let otherLive = rowNotified(state, id: 1, priceMissing: false) { _ = state.liveWatchIds.insert(2) }
        XCTAssertFalse(otherLive, "anderes Paar am Live-Strom")
        let fullWithPrice = rowNotified(state, id: 1, priceMissing: false) { state.refreshing = true }
        XCTAssertFalse(fullWithPrice, "volle Aktualisierung bei einer Zeile mit Kurs")
        state.refreshing = false
        let duration = rowNotified(state, id: 1, priceMissing: false) { state.lastRefreshMillis = 1_200 }
        XCTAssertFalse(duration, "Dauer der Aktualisierung")
        let fullWithoutPrice = rowNotified(state, id: 1, priceMissing: true) { state.refreshing = true }
        XCTAssertTrue(fullWithoutPrice, "volle Aktualisierung bei einer Zeile ohne Kurs")
        state.refreshing = false
        let ownRefreshing = rowNotified(state, id: 1, priceMissing: false) { _ = state.refreshingWatchIds.insert(1) }
        XCTAssertTrue(ownRefreshing, "eigenes Paar einzeln aktualisiert")
        let ownLive = rowNotified(state, id: 1, priceMissing: false) { _ = state.liveWatchIds.insert(1) }
        XCTAssertTrue(ownLive, "eigenes Paar am Live-Strom")
    }
}
