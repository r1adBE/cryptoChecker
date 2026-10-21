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

    func testHugeRetryAfterIsClampedToOneDay() {
        let max = ExchangeBackoff.maxRetryAfterSeconds
        XCTAssertEqual(ExchangeBackoff.parseRetryAfterSeconds(String(Int64.max), now: now), max)
        XCTAssertEqual(ExchangeBackoff.parseRetryAfterSeconds("Fri, 31 Dec 9999 23:59:59 GMT", now: now), max)
        XCTAssertEqual(ExchangeBackoff.parseRetryAfterSeconds("-5", now: now), 0)
        // Gespeicherter Fehlertext mit riesiger Angabe: kein Überlauf-Absturz
        XCTAssertEqual(ExchangeBackoff.retryAfterMillis(["HTTP 429 (retry-after \(Int64.max) s)"]), max * 1000)
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

    // MARK: - Gemeinsame Binance-Pause

    func testBinanceHostsAndMarketsShareOneGroup() {
        for key in ["api.binance.com", "data-api.binance.vision", "fapi.binance.com", "dapi.binance.com",
                    "www.binance.com", "Binance", "BinanceFutures"] {
            XCTAssertEqual(ExchangeBackoff.sharedGroup(key), ExchangeBackoff.binanceGroup, key)
        }
        // Binance.US ist eine eigene Börse; andere Hosts ohne gemeinsame Pause
        XCTAssertNil(ExchangeBackoff.sharedGroup("api.binance.us"))
        XCTAssertNil(ExchangeBackoff.sharedGroup("Binance.US"))
        XCTAssertNil(ExchangeBackoff.sharedGroup("api.bybit.com"))
        XCTAssertTrue(ExchangeBackoff.isRateLimitCode(429))
        XCTAssertTrue(ExchangeBackoff.isRateLimitCode(418))
        XCTAssertFalse(ExchangeBackoff.isRateLimitCode(451))
    }

    func testGroupPauseHonoursRetryAfterBeyondTickerCap() {
        // Ohne Retry-After: Stufe 1 = 30 s
        let plain = ExchangeBackoff.groupPause(nil, now: now, httpCode: 429, retryAfterSeconds: nil)
        XCTAssertEqual(plain.pausedUntil, now + 30_000)
        XCTAssertEqual(plain.reason, .RATE_LIMIT)
        // Retry-After gilt (auch über 15 Min.), höchstens 6 h
        XCTAssertEqual(ExchangeBackoff.groupPause(nil, now: now, httpCode: 429, retryAfterSeconds: 3_600).pausedUntil,
                       now + 3_600_000)
        XCTAssertEqual(ExchangeBackoff.groupPause(nil, now: now, httpCode: 418, retryAfterSeconds: 999_999).pausedUntil,
                       now + ExchangeBackoff.groupMaxMillis)
        // 418 ohne Retry-After: mindestens 2 Min.
        XCTAssertEqual(ExchangeBackoff.groupPause(nil, now: now, httpCode: 418, retryAfterSeconds: nil).pausedUntil,
                       now + 120_000)
    }

    func testGroupPauseNeverShortensAndParallelHitsDoNotEscalate() {
        let first = ExchangeBackoff.groupPause(nil, now: now, httpCode: 429, retryAfterSeconds: 600)
        // Weitere 429 parallel gestarteter Anfragen während der Pause: Stufe bleibt, Ende bleibt
        let again = ExchangeBackoff.groupPause(first, now: now + 1_000, httpCode: 429, retryAfterSeconds: nil)
        XCTAssertEqual(again.strikes, 1)
        XCTAssertEqual(again.pausedUntil, first.pausedUntil)
        XCTAssertTrue(ExchangeBackoff.isPaused(again, now: now + 599_999))
    }

    func testGroupPauseEscalatesAfterPauseAndResetsWhenLongAgo() {
        let first = ExchangeBackoff.groupPause(nil, now: now, httpCode: 429, retryAfterSeconds: nil)
        let afterPause = first.pausedUntil + 1_000
        let second = ExchangeBackoff.groupPause(first, now: afterPause, httpCode: 429, retryAfterSeconds: nil)
        XCTAssertEqual(second.strikes, 2)
        XCTAssertEqual(second.pausedUntil, afterPause + 60_000)
        // Letzte Pause endete vor über 15 Min.: neu ab Stufe 1
        let later = second.pausedUntil + ExchangeBackoff.maxMillis + 1
        let fresh = ExchangeBackoff.groupPause(second, now: later, httpCode: 429, retryAfterSeconds: nil)
        XCTAssertEqual(fresh.strikes, 1)
        XCTAssertEqual(fresh.pausedUntil, later + 30_000)
    }

    func testTickerBanAndRetryAfterMatchCandles() {
        // 418 beim Ticker: mindestens 2 Min. wie bei Kerzen (groupPause), nicht 30 s
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, banned: true)?.pausedUntil, now + 120_000)
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, banned: true, shared: true)?.pausedUntil,
                       ExchangeBackoff.groupPause(nil, now: now, httpCode: 418, retryAfterSeconds: nil).pausedUntil)
        // Gemeinsame Gruppe (Binance): Retry-After bis 6 h wie bei Kerzen
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, retryAfterMillis: 3_600_000, shared: true)?.pausedUntil,
                       now + 3_600_000)
        XCTAssertEqual(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now, retryAfterMillis: 3_600_000, shared: true)?.pausedUntil,
                       ExchangeBackoff.groupPause(nil, now: now, httpCode: 429, retryAfterSeconds: 3_600).pausedUntil)
        // Laufende längere Pause wird nie verkürzt
        let running = ExchangeBackoff.State(strikes: 1, pausedUntil: now + 600_000, reason: .RATE_LIMIT, timeoutRuns: 0)
        XCTAssertEqual(ExchangeBackoff.next(running, outcome: .rateLimited, now: now)?.pausedUntil, now + 600_000)
        // 418 aus den Fehlertexten beider Plattformen erkannt
        XCTAssertTrue(ExchangeBackoff.banned([nil, "HttpCode: 418 (retry-after 120 s)"]))
        XCTAssertTrue(ExchangeBackoff.banned(["HTTP 418"]))
        XCTAssertFalse(ExchangeBackoff.banned(["HttpCode: 429", nil]))
    }

    // MARK: Zusammenführen mit dem inzwischen gespeicherten Stand (anderer Prozess)

    func testMergeKeepsUntouchedKeysFromStore() {
        let a = ExchangeBackoff.State(strikes: 1, pausedUntil: 1_000, reason: .RATE_LIMIT)
        let b = ExchangeBackoff.State(strikes: 2, pausedUntil: 5_000, reason: .TIMEOUT)
        // Dieser Lauf ändert nichts an «b»; ein anderer Prozess hat «b» inzwischen gemerkt → bleibt
        let merged = ExchangeBackoff.merge(ours: ["a": a], initial: ["a": a], stored: ["a": a, "b": b], now: 0)
        XCTAssertEqual(merged["b"], b)
        XCTAssertEqual(merged["a"], a)
    }

    func testMergeKeepsLaterPauseWhenBothChanged() {
        let mine = ExchangeBackoff.State(strikes: 1, pausedUntil: 2_000, reason: .RATE_LIMIT)
        let theirs = ExchangeBackoff.State(strikes: 3, pausedUntil: 9_000, reason: .RATE_LIMIT)
        XCTAssertEqual(ExchangeBackoff.merge(ours: ["x": mine], initial: [:], stored: ["x": theirs], now: 0)["x"], theirs)
        let earlier = ExchangeBackoff.State(strikes: 1, pausedUntil: 1_000, reason: .TIMEOUT)
        XCTAssertEqual(ExchangeBackoff.merge(ours: ["x": mine], initial: [:], stored: ["x": earlier], now: 0)["x"], mine)
    }

    func testMergeClearsOnlyUnchangedPause() {
        let old = ExchangeBackoff.State(strikes: 1, pausedUntil: 1_000, reason: .RATE_LIMIT)
        // Erfolg bei uns, gespeichert noch der Stand vom Start → aufheben
        XCTAssertNil(ExchangeBackoff.merge(ours: [:], initial: ["x": old], stored: ["x": old], now: 0)["x"])
        // Inzwischen neu pausiert (anderer Prozess) → bleibt
        let fresh = ExchangeBackoff.State(strikes: 2, pausedUntil: 8_000, reason: .RATE_LIMIT)
        XCTAssertEqual(ExchangeBackoff.merge(ours: [:], initial: ["x": old], stored: ["x": fresh], now: 0)["x"], fresh)
    }
}
