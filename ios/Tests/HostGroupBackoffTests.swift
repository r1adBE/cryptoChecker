import XCTest
@testable import CryptoChecker

/// Spot und Futures auf demselben Host teilen die Pause nach «zu viele Anfragen» (`host_group_backoff.json`, geteilt
/// mit Android). Wie `HostGroupBackoffTest.kt`.
final class HostGroupBackoffTests: XCTestCase {
    private typealias S = TestSupport

    private func state(_ raw: Any?) -> ExchangeBackoff.State {
        let s = raw as? [String: Any] ?? [:]
        return ExchangeBackoff.State(
            strikes: Int(S.number(s["strikes"]) ?? 0),
            pausedUntil: S.millis(s["pausedUntil"]),
            reason: (s["reason"] as? String).flatMap(RefreshFailure.init(rawValue:)),
            timeoutRuns: Int(S.number(s["timeoutRuns"]) ?? 0))
    }

    private func states(_ raw: Any?) -> [String: ExchangeBackoff.State] {
        (raw as? [String: Any] ?? [:]).mapValues { state($0) }
    }

    func testHostGroupsMatchFixture() throws {
        let data = try S.fixture("host_group_backoff")
        XCTAssertEqual(data["hostGroups"] as? [String: String], ExchangeBackoff.hostGroups)
    }

    func testParityCases() throws {
        let data = try S.fixture("host_group_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["cases"]) {
            XCTAssertEqual(ExchangeBackoff.withHostGroupPauses(states(c["states"]), now: now), states(c["expected"]),
                           c["name"] as? String ?? "")
        }
    }

    func testRateLimitAtBingXAlsoPausesBingXFutures() throws {
        let now: Int64 = 5_000_000
        let after429 = try XCTUnwrap(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now))
        let stored = ExchangeBackoff.withHostGroupPauses(["BingX": after429], now: now)
        // Nächster Durchlauf: beide Teile übersprungen
        XCTAssertTrue(ExchangeBackoff.isPaused(stored["BingX"], now: now + 1))
        XCTAssertTrue(ExchangeBackoff.isPaused(stored["BingXFutures"], now: now + 1))
        XCTAssertEqual(stored["BingXFutures"]?.reason, .RATE_LIMIT)
        // Andere Börse unberührt
        XCTAssertFalse(ExchangeBackoff.isPaused(stored["Bybit"], now: now + 1))
    }
}
