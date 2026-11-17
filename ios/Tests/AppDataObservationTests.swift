import Observation
import os
import XCTest
@testable import CryptoChecker

/// `AppData` mit Observation (`@Observable`): Veröffentlicht wird über `revision` (`AppData.publish`), das die Ansichten
/// über `@AppDataEnv`/`@AppDataRef` mitlesen — wie früher `objectWillChange`. Geänderte Einstellungen und ein
/// ausdrückliches `publish()` melden sich bei jedem Leser von `revision`; ein Wert ohne Änderung der Revision nicht.
@MainActor
final class AppDataObservationTests: XCTestCase {

    /// true, sobald sich `revision` nach dem Lesen ändert (Beobachtung wie bei einer Ansicht).
    private func publishes(_ data: AppData, _ change: () -> Void) -> Bool {
        let fired = OSAllocatedUnfairLock(initialState: false)
        withObservationTracking { _ = data.revision } onChange: { fired.withLock { $0 = true } }
        change()
        return fired.withLock { $0 }
    }

    func testSettingsChangePublishes() {
        let data = AppData.shared
        let saved = data.settings
        defer { data.settings = saved }
        var changed = saved
        changed.watchlistNames.toggle()
        XCTAssertTrue(publishes(data) { data.settings = changed })
    }

    func testExplicitPublishReachesReaders() {
        let data = AppData.shared
        XCTAssertTrue(publishes(data) { data.publish() })
    }

    func testReadingAloneDoesNotPublish() {
        let data = AppData.shared
        XCTAssertFalse(publishes(data) { _ = data.settings; _ = data.favorites; _ = data.addTargetGroup })
    }
}
