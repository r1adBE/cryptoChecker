import Combine
import Observation
import XCTest
@testable import CryptoChecker

/// Netz, App-Start, «wird nicht gespeichert» und Hinweis-Zähler als eigenes beobachtbares Objekt (`AppStatus`,
/// `AppData.status`): Ein Wechsel meldet sich nur bei den Ansichten, die den Wert lesen — `AppData.objectWillChange`
/// bleibt still, die Merkliste und die übrigen Bildschirme rechnen ihren `body` nicht neu.
@MainActor
final class AppStatusTests: XCTestCase {

    /// Zähler für `objectWillChange` (auf dem Haupt-Thread).
    private final class Counter {
        var count = 0
    }

    func testStatusChangesDoNotPublishOnAppData() {
        let data = AppData.shared
        let status = data.status
        let savedOnline = status.online
        let savedStart = status.appStartMillis
        let savedPending = status.watchlistSavePending
        let savedHints = status.watchlistLockedHints
        defer {
            status.online = savedOnline
            status.appStartMillis = savedStart
            status.watchlistSavePending = savedPending
            status.watchlistLockedHints = savedHints
        }
        let appChanges = Counter()
        var watchers: [AnyCancellable] = []
        data.objectWillChange.sink { _ in appChanges.count += 1 }.store(in: &watchers)

        status.online = !savedOnline
        status.appStartMillis = (savedStart ?? 0) + 1
        status.watchlistSavePending = !savedPending
        data.watchlistLockedHints &+= 1
        XCTAssertEqual(appChanges.count, 0, "AppData darf bei Statuswechseln nicht veröffentlichen")
        // Gelesen wird weiter über AppData
        XCTAssertEqual(data.online, !savedOnline)
        XCTAssertEqual(data.appStartMillis, (savedStart ?? 0) + 1)
        XCTAssertEqual(data.watchlistSavePending, !savedPending)
        XCTAssertEqual(data.watchlistLockedHints, savedHints &+ 1)
        watchers.removeAll()
    }

    func testReadersOfAValueAreNotified() {
        let status = AppStatus(online: true, appStartMillis: nil)
        let onlineChanged = expectation(description: "online")
        withObservationTracking {
            _ = status.online
        } onChange: {
            onlineChanged.fulfill()
        }
        status.online = false
        wait(for: [onlineChanged], timeout: 1)
    }

    func testOtherValuesDoNotNotifyReadersOfOnline() {
        let status = AppStatus(online: true, appStartMillis: nil)
        let notified = expectation(description: "andere Werte bei Lesern von online")
        notified.isInverted = true
        withObservationTracking {
            _ = status.online
        } onChange: {
            notified.fulfill()
        }
        status.watchlistLockedHints += 1
        status.watchlistSavePending = true
        status.appStartMillis = 1
        wait(for: [notified], timeout: 0.2)
    }
}
