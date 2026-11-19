import XCTest
@testable import CryptoChecker

/// Wie Android `LoadProgressTest`.
final class LoadProgressTests: XCTestCase {
    func testStartsSmallGrowsPerStepEndsFull() {
        var p = LoadProgress(total: 4)
        XCTAssertEqual(p.current, LoadProgress.start, accuracy: 1e-9)
        XCTAssertEqual(p.step(), 0.31, accuracy: 1e-9)
        _ = p.step()
        _ = p.step()
        XCTAssertEqual(p.step(), 1, accuracy: 1e-9)
        // Mehr Schritte als angekündigt: bleibt voll
        XCTAssertEqual(p.step(), 1, accuracy: 1e-9)
    }

    func testNoStepsCountsAsDone() {
        XCTAssertEqual(LoadProgress(total: 0).current, 1)
    }

    func testTallyCountsPartsJoiningWhileLoadingAndResetsWhenIdle() {
        var tally = LoadTally<String>()
        XCTAssertNil(tally.next([]))
        XCTAssertEqual(tally.next(["a", "b"]) ?? -1, LoadProgress.start, accuracy: 1e-9)
        // «a» fertig, «c» kommt dazu: 1 von 3
        XCTAssertEqual(tally.next(["b", "c"]) ?? -1, LoadProgress.shown(done: 1, total: 3), accuracy: 1e-9)
        XCTAssertEqual(tally.next(["c"]) ?? -1, LoadProgress.shown(done: 2, total: 3), accuracy: 1e-9)
        XCTAssertNil(tally.next([]))
        // Neues Laden zählt von vorn
        XCTAssertEqual(tally.next(["a"]) ?? -1, LoadProgress.start, accuracy: 1e-9)
    }
}
