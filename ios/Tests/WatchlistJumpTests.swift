import XCTest
@testable import CryptoChecker

/// Wie `WatchJumpTest.kt` (Sprungknopf der Merkliste).
final class WatchlistJumpTests: XCTestCase {

    func testEligibleOnlyAboveThirtyAndNotSorting() {
        XCTAssertFalse(WatchlistJump.eligible(pairs: 30, sorting: false))
        XCTAssertTrue(WatchlistJump.eligible(pairs: 31, sorting: false))
        XCTAssertFalse(WatchlistJump.eligible(pairs: 100, sorting: true))
    }

    func testPointsDownInTheUpperHalf() {
        XCTAssertTrue(WatchlistJump.pointsDown(firstVisible: 0, lastVisible: 10, total: 50))
        XCTAssertTrue(WatchlistJump.pointsDown(firstVisible: 15, lastVisible: 25, total: 50))
        XCTAssertFalse(WatchlistJump.pointsDown(firstVisible: 20, lastVisible: 30, total: 50))
        XCTAssertFalse(WatchlistJump.pointsDown(firstVisible: 40, lastVisible: 49, total: 50))
    }

    func testAnimateOnlyShortDistancesWithoutReduceMotion() {
        XCTAssertTrue(WatchlistJump.animate(distance: -40, reduceMotion: false))
        XCTAssertFalse(WatchlistJump.animate(distance: 41, reduceMotion: false))
        XCTAssertFalse(WatchlistJump.animate(distance: 5, reduceMotion: true))
    }
}
