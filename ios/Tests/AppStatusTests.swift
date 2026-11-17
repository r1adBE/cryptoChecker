import Observation
import XCTest
@testable import CryptoChecker

/// Netz, App-Start, «wird nicht gespeichert» und Hinweis-Zähler als eigenes beobachtbares Objekt (`AppStatus`,
/// `AppData.status`): Ein Wechsel meldet sich nur bei den Ansichten, die den Wert lesen — `AppData.revision`
/// (Veröffentlichung, `AppData.publish`) bleibt gleich, die Merkliste und die übrigen Bildschirme rechnen ihren `body`
/// nicht neu.
@MainActor
final class AppStatusTests: XCTestCase {

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
        let appRevision = data.revision

        status.online = !savedOnline
        status.appStartMillis = (savedStart ?? 0) + 1
        status.watchlistSavePending = !savedPending
        data.watchlistLockedHints &+= 1
        XCTAssertEqual(data.revision, appRevision, "AppData darf bei Statuswechseln nicht veröffentlichen")
        // Gelesen wird weiter über AppData
        XCTAssertEqual(data.online, !savedOnline)
        XCTAssertEqual(data.appStartMillis, (savedStart ?? 0) + 1)
        XCTAssertEqual(data.watchlistSavePending, !savedPending)
        XCTAssertEqual(data.watchlistLockedHints, savedHints &+ 1)
    }

    /// Auch Börsen-Pausen, %-Stempel, Aktivität und wartendes Portfolio-Speichern.
    func testMovedValuesDoNotPublishOnAppData() {
        let data = AppData.shared
        let savedPauses = data.exchangePauses
        let savedStamp = data.changeStamp
        let savedReports = data.activityReports
        let savedPortfolio = data.portfolioSavePending
        defer {
            data.exchangePauses = savedPauses
            data.changeStamp = savedStamp
            data.activityReports = savedReports
            data.portfolioSavePending = savedPortfolio
        }
        let appRevision = data.revision

        data.exchangePauses["test"] = 42
        data.changeStamp = nil
        data.activityReports[-1] = nil
        data.portfolioSavePending = !savedPortfolio
        XCTAssertEqual(data.revision, appRevision, "AppData darf bei diesen Werten nicht veröffentlichen")
        XCTAssertEqual(data.status.exchangePauses["test"], 42)
        XCTAssertNil(data.status.changeStamp)
        XCTAssertEqual(data.status.portfolioSavePending, !savedPortfolio)
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
