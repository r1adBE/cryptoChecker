@testable import CryptoChecker
import XCTest

/// Wie Android `LogoPassSummaryTest`.
final class LogoPassSummaryTests: XCTestCase {
    func testRoundTrip() {
        let pass = LogoPassSummary(at: 1_700_000_000_000, added: 12, failed: 3, paused: 0, open: 284, unsaved: 1)
        XCTAssertEqual(pass.encode(), "1700000000000;12;3;0;284;1")
        XCTAssertEqual(LogoPassSummary.decode(pass.encode()), pass)
    }

    func testUnreadableIsNil() {
        XCTAssertNil(LogoPassSummary.decode(nil))
        XCTAssertNil(LogoPassSummary.decode("1;2;3"))
        XCTAssertNil(LogoPassSummary.decode("x;1;2;3;4;5"))
        XCTAssertEqual(LogoPassSummary.decode("1;-5;0;0;0;0")?.added, 0)
    }

    func testOnlyForbiddenLeavesOpen() {
        XCTAssertTrue(CoinLogoRetry.leavesOpen(httpCode: 403))
        XCTAssertFalse(CoinLogoRetry.leavesOpen(httpCode: 404))
        XCTAssertFalse(CoinLogoRetry.leavesOpen(httpCode: 500))
    }
}
