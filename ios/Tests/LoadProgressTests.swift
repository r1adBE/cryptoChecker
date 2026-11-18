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
}
