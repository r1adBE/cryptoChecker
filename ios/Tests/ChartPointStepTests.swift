import XCTest
@testable import CryptoChecker

/// Punkt für Punkt durch einen Chart (`ChartPointStep`, VoiceOver Wischen hoch/runter) — wie
/// `ChartPointStepTest.kt`.
final class ChartPointStepTests: XCTestCase {

    func testNextStartsAtFirstAndStopsAtLast() {
        XCTAssertEqual(ChartPointStep.next(nil, count: 3), 0)
        XCTAssertEqual(ChartPointStep.next(0, count: 3), 1)
        XCTAssertEqual(ChartPointStep.next(2, count: 3), 2)
    }

    func testPreviousStartsAtLastAndStopsAtFirst() {
        XCTAssertEqual(ChartPointStep.previous(nil, count: 3), 2)
        XCTAssertEqual(ChartPointStep.previous(2, count: 3), 1)
        XCTAssertEqual(ChartPointStep.previous(0, count: 3), 0)
    }

    func testEmptyOrShrunkSeries() {
        XCTAssertNil(ChartPointStep.next(nil, count: 0))
        XCTAssertNil(ChartPointStep.previous(4, count: 0))
        // Reihe kürzer geworden (neuer Zeitraum): bleibt innerhalb
        XCTAssertEqual(ChartPointStep.next(7, count: 2), 1)
        XCTAssertEqual(ChartPointStep.previous(7, count: 2), 1)
    }
}
