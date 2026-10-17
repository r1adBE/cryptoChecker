import XCTest
@testable import CryptoChecker

/// Uhrzeit im Kopf des Merkliste-Widgets und gespeicherte Kurse aus demselben Stand —
/// Gegenstück: ListWidgetDataVersionTest.kt (Android).
final class WidgetDataFreshnessTests: XCTestCase {

    func testTimeNewerThanWrittenPricesIsBehind() {
        // App hat «Stand 14:55» gesetzt, die Kurse liegen noch vom vorigen Durchlauf auf der Platte
        XCTAssertTrue(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_000 - 60_000))
    }

    func testPricesWrittenAfterTimeAreCurrent() {
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_050))
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_000))
    }

    func testWidgetOwnRefreshWritesFileThenTime() {
        // Widget-Aktualisierung: erst Datei, Millisekunden später die Uhrzeit — kein Warten
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_000 - 30))
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000,
                                                      writtenAt: 1_000_000 - WidgetDataFreshness.toleranceMillis))
    }

    func testUnknownNeverBehind() {
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 0, writtenAt: 500))
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 0))
    }

    // MARK: Takt (`WidgetCadence`, nur iOS)

    /// Normal etwa alle 30 Minuten; Kurse werden beim geplanten Neuladen sicher geholt.
    func testCadenceIsAboutThirtyMinutes() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let next = WidgetCadence.nextReload(now: now, wantsQuickRetry: false, lastQuickRetryAt: nil)
        XCTAssertEqual(next.date.timeIntervalSince(now), 30 * 60)
        XCTAssertFalse(next.quickRetry)
        XCTAssertLessThan(WidgetCadence.staleAfterMillis, Int64(WidgetCadence.reloadMinutes) * 60_000)
        XCTAssertGreaterThanOrEqual(WidgetCadence.retryAfterMillis, 20 * 60_000)
    }

    /// Höchstens ein früher Neuversuch je Takt — nicht mehr jede Minute.
    func testAtMostOneQuickRetryPerCadence() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let first = WidgetCadence.nextReload(now: now, wantsQuickRetry: true, lastQuickRetryAt: nil)
        XCTAssertTrue(first.quickRetry)
        XCTAssertEqual(first.date.timeIntervalSince(now), 60)
        // Eine Minute später wieder gewünscht: normaler Takt
        let again = now.addingTimeInterval(60)
        let second = WidgetCadence.nextReload(now: again, wantsQuickRetry: true, lastQuickRetryAt: now)
        XCTAssertFalse(second.quickRetry)
        XCTAssertEqual(second.date.timeIntervalSince(again), 30 * 60)
        // Ein Takt später darf es wieder einen geben; Uhr zurückgestellt ebenso
        XCTAssertTrue(WidgetCadence.nextReload(now: now.addingTimeInterval(30 * 60), wantsQuickRetry: true,
                                               lastQuickRetryAt: now).quickRetry)
        XCTAssertTrue(WidgetCadence.nextReload(now: now.addingTimeInterval(-60), wantsQuickRetry: true,
                                               lastQuickRetryAt: now).quickRetry)
    }
}
