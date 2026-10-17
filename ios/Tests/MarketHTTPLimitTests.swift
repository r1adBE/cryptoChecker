import XCTest
@testable import CryptoChecker

/// Höchstgrösse von Antworten (wie Android `HttpBodyLimitTest.kt`).
final class MarketHTTPLimitTests: XCTestCase {

    func testSingleSessionUsesSmallLimit() {
        XCTAssertNoThrow(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes, session: MarketHTTP.session))
        XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes + 1, session: MarketHTTP.session)) {
            XCTAssertEqual($0.localizedDescription, ResponseTooLargeError.message)
        }
    }

    func testListSessionsUseLargeLimit() {
        for session in [MarketHTTP.bulkSession, MarketHTTP.tickerBulkSession] {
            XCTAssertNoThrow(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes + 1, session: session))
            XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxBulkBodyBytes + 1, session: session))
        }
        XCTAssertEqual(MarketHTTP.maxBodyBytes, 2 * 1024 * 1024)
        XCTAssertEqual(MarketHTTP.maxBulkBodyBytes, 16 * 1024 * 1024)
    }

    /// Der feste Text bleibt beim Speichern erhalten (`StoredError`).
    func testMessageIsKeptWhenStored() {
        XCTAssertEqual(StoredError.sanitize(ResponseTooLargeError().localizedDescription), ResponseTooLargeError.message)
    }
}
