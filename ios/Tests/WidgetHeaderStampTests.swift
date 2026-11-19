import XCTest
@testable import CryptoChecker

/// Wie `WidgetHeaderStampTest.kt`.
final class WidgetHeaderStampTests: XCTestCase {

    func testDurationOnlyForTheSameData() {
        let stamp = WidgetHeaderStamp.Stamp.finished(dataAt: 5_000, startedAt: 10_000, now: 10_840)
        XCTAssertEqual(840, stamp.duration(for: 5_000))
        XCTAssertEqual(10_840, stamp.finishedAt)
        // Neuere Daten aus der App: ohne Dauer
        XCTAssertEqual(0, stamp.duration(for: 6_000))
        XCTAssertEqual(0, stamp.duration(for: 0))
    }

    func testClockSetBackGivesNoNegativeDuration() {
        XCTAssertEqual(0, WidgetHeaderStamp.Stamp.finished(dataAt: 1, startedAt: 2_000, now: 1_000).durationMillis)
    }

    func testEmptyStampHasNoDuration() {
        XCTAssertEqual(0, WidgetHeaderStamp.Stamp().duration(for: 0))
    }
}
