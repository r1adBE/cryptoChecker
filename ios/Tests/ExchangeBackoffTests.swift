import XCTest
@testable import CryptoChecker

/// Wie `ExchangeBackoffTest.kt`.
final class ExchangeBackoffTests: XCTestCase {

    private let now: Int64 = 1_000_000

    func testDelaysDoubleUpToFifteenMinutes() {
        XCTAssertEqual(ExchangeBackoff.delayMillis(1), 30_000)
        XCTAssertEqual(ExchangeBackoff.delayMillis(2), 60_000)
        XCTAssertEqual(ExchangeBackoff.delayMillis(3), 120_000)
        XCTAssertEqual(ExchangeBackoff.delayMillis(4), 240_000)
        XCTAssertEqual(ExchangeBackoff.delayMillis(5), 480_000)
        XCTAssertEqual(ExchangeBackoff.delayMillis(6), 900_000)
        XCTAssertEqual(ExchangeBackoff.delayMillis(60), 900_000)
    }

    func testRateLimitPausesAndGrows() throws {
        let first = try XCTUnwrap(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now))
        XCTAssertEqual(first.pausedUntil, now + 30_000)
        XCTAssertEqual(first.reason, .RATE_LIMIT)
        XCTAssertTrue(ExchangeBackoff.isPaused(first, now: now + 29_999))
        XCTAssertFalse(ExchangeBackoff.isPaused(first, now: now + 30_000))
        let second = try XCTUnwrap(ExchangeBackoff.next(first, outcome: .rateLimited, now: now + 30_000))
        XCTAssertEqual(second.pausedUntil, now + 30_000 + 60_000)
        XCTAssertEqual(second.strikes, 2)
    }

    func testRetryAfterIsHonouredAndCapped() {
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, retryAfterMillis: 90_000)?.pausedUntil, now + 90_000)
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, retryAfterMillis: 5_000)?.pausedUntil, now + 30_000)
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, retryAfterMillis: 3_600_000)?.pausedUntil, now + 900_000)
    }

    func testSuccessResets() {
        let paused = ExchangeBackoff.next(nil, outcome: .rateLimited, now: now)
        XCTAssertNil(ExchangeBackoff.next(paused, outcome: .success, now: now + 60_000))
    }

    func testRepeatedTimeoutsPause() throws {
        let once = try XCTUnwrap(ExchangeBackoff.next(nil, outcome: .timeout, now: now))
        XCTAssertFalse(ExchangeBackoff.isPaused(once, now: now))
        XCTAssertEqual(once.timeoutRuns, 1)
        let twice = try XCTUnwrap(ExchangeBackoff.next(once, outcome: .timeout, now: now + 60_000))
        XCTAssertTrue(ExchangeBackoff.isPaused(twice, now: now + 60_000))
        XCTAssertEqual(twice.reason, .TIMEOUT)
        XCTAssertEqual(twice.pausedUntil, now + 60_000 + 30_000)
        XCTAssertNil(ExchangeBackoff.next(once, outcome: .other, now: now))
    }

    func testOutcomeFromFailures() {
        XCTAssertEqual(ExchangeBackoff.outcome(failures: [.RATE_LIMIT], updated: 3), .rateLimited)
        XCTAssertEqual(ExchangeBackoff.outcome(failures: [.TIMEOUT], updated: 1), .success)
        XCTAssertEqual(ExchangeBackoff.outcome(failures: [], updated: 0), .success)
        XCTAssertEqual(ExchangeBackoff.outcome(failures: [.TIMEOUT, .TIMEOUT], updated: 0), .timeout)
        XCTAssertEqual(ExchangeBackoff.outcome(failures: [.TIMEOUT, .SERVER], updated: 0), .other)
    }

    func testRetryAfterParsing() {
        XCTAssertEqual(ExchangeBackoff.parseRetryAfterSeconds(" 30 ", now: now), 30)
        XCTAssertNil(ExchangeBackoff.parseRetryAfterSeconds(nil, now: now))
        XCTAssertNil(ExchangeBackoff.parseRetryAfterSeconds("soon", now: now))
        let base: Int64 = 1_791_000_000_000 // 2026-10-03T04:00:00Z (Samstag)
        XCTAssertEqual(ExchangeBackoff.parseRetryAfterSeconds("Sat, 3 Oct 2026 04:02:00 GMT", now: base), 120)
        XCTAssertEqual(ExchangeBackoff.retryAfterSuffix(30), " (retry-after 30 s)")
        XCTAssertEqual(ExchangeBackoff.retryAfterSuffix(nil), "")
        XCTAssertEqual(ExchangeBackoff.retryAfterMillis([nil, "HttpCode: 429 (retry-after 30 s)", "HTTP 429 (retry-after 45 s)"]), 45_000)
        XCTAssertNil(ExchangeBackoff.retryAfterMillis(["HttpCode: 500"]))
        XCTAssertEqual(RefreshReportLogic.classify("HTTP 429 (retry-after 30 s)"), .RATE_LIMIT)
    }

    func testEncodeRoundTrip() {
        let states: [String: ExchangeBackoff.State] = [
            "binance": .init(strikes: 2, pausedUntil: 123, reason: .RATE_LIMIT, timeoutRuns: 0),
            "kraken": .init(strikes: 0, pausedUntil: 0, reason: nil, timeoutRuns: 1),
        ]
        XCTAssertEqual(ExchangeBackoff.decode(ExchangeBackoff.encode(states)), states)
        XCTAssertEqual(ExchangeBackoff.decode("x|y;;"), [:])
        XCTAssertEqual(ExchangeBackoff.decode(nil), [:])
        // Gleiches Format wie Android
        XCTAssertEqual(ExchangeBackoff.encode(states), "binance|2|123|RATE_LIMIT|0;kraken|0|0||1")
    }

    func testAppStartTiming() {
        XCTAssertEqual(AppStartTiming.elapsed(processStart: 1_000, uiCreated: 1_150, firstFrame: 1_400), 400)
        XCTAssertEqual(AppStartTiming.elapsed(processStart: 1_000, uiCreated: 60_000, firstFrame: 60_300), 300)
        XCTAssertEqual(AppStartTiming.elapsed(processStart: nil, uiCreated: 100, firstFrame: 350), 250)
        XCTAssertNil(AppStartTiming.elapsed(processStart: nil, uiCreated: 100, firstFrame: 50))
        XCTAssertNil(AppStartTiming.elapsed(processStart: nil, uiCreated: 0, firstFrame: 120_000))
    }

    func testOfflineResumesOnceOnReconnect() {
        XCTAssertTrue(OfflineGate.resumeOnChange(previous: false, online: true))
        XCTAssertFalse(OfflineGate.resumeOnChange(previous: nil, online: true))
        XCTAssertFalse(OfflineGate.resumeOnChange(previous: true, online: true))
        XCTAssertFalse(OfflineGate.resumeOnChange(previous: true, online: false))
    }
}
