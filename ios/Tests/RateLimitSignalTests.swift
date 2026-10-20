import XCTest
@testable import CryptoChecker

/// Rate-Limit ohne HTTP 429/418 (Kraken, Bybit, OKX, BingX, bitbank). Fixture `rate_limit_signal.json`, wie `RateLimitSignalTest.kt`.
final class RateLimitSignalTests: XCTestCase {
    private typealias S = TestSupport

    func testFixtureStatusPassedOnAndClassifiedError() throws {
        let data = try S.fixture("rate_limit_signal")
        for c in S.list(data["httpCode"]) {
            let status = Int(S.number(c["status"]) ?? 0)
            let body = c["body"] as? String
            let expected = S.number(c["code"]).map { Int($0) }
            XCTAssertEqual(RateLimitSignal.httpCode(status: status, body: body), expected, "httpCode: \(status) \(body ?? "nil")")
        }
        for c in S.list(data["classify"]) {
            let error = c["error"] as? String ?? ""
            XCTAssertEqual(RefreshReportLogic.classify(error).rawValue, c["failure"] as? String, "classify: \(error)")
        }
    }

    func testLongOkBodiesAreNotScannedErrorBodiesOnlyTheirStart() {
        let long = String(repeating: "x", count: RateLimitSignal.maxOkBody) + "Too many requests"
        XCTAssertNil(RateLimitSignal.httpCode(status: 200, body: long))
        XCTAssertNil(RateLimitSignal.httpCode(status: 403, body: long))
        XCTAssertEqual(RateLimitSignal.httpCode(status: 403, body: "Too many requests" + long), 429)
        XCTAssertTrue(RateLimitSignal.inOkBody("{\"code\":\"50011\",\"msg\":\"\"}"))
        XCTAssertFalse(RateLimitSignal.inOkBody("{\"code\":\"0\",\"data\":[]}"))
    }

    func testRateLimitTextStopsFurtherSingles() {
        XCTAssertTrue(SingleRateGate.isRateLimit("EGeneral:Too many requests"))
        XCTAssertFalse(SingleRateGate.isRateLimit("EQuery:Unknown asset pair"))
    }
}
