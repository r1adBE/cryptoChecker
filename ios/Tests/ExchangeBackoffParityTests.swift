import XCTest
@testable import CryptoChecker

/// Pausen nach «zu viele Anfragen» — vor allem: eine unmögliche gespeicherte Pause (weiter als 6 h in der Zukunft)
/// zählt nirgends (`exchange_backoff.json`, geteilt mit Android). Die Fälle «merge» gibt es nur auf iOS.
/// Wie `ExchangeBackoffParityTest.kt`.
final class ExchangeBackoffParityTests: XCTestCase {
    private typealias S = TestSupport

    private func state(_ raw: Any?) -> ExchangeBackoff.State? {
        guard let s = raw as? [String: Any] else { return nil }
        return ExchangeBackoff.State(
            strikes: Int(S.number(s["strikes"]) ?? 0),
            pausedUntil: S.millis(s["pausedUntil"]),
            reason: (s["reason"] as? String).flatMap(RefreshFailure.init(rawValue:)),
            timeoutRuns: Int(S.number(s["timeoutRuns"]) ?? 0))
    }

    private func states(_ raw: Any?) -> [String: ExchangeBackoff.State] {
        (raw as? [String: Any] ?? [:]).compactMapValues { state($0) }
    }

    func testNextNachZuVielenAnfragen() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["next"]) {
            let result = ExchangeBackoff.next(
                state(c["state"]), outcome: .rateLimited, now: now,
                retryAfterMillis: S.number(c["retryAfterMillis"]).map { Int64($0) },
                banned: c["banned"] as? Bool ?? false, shared: c["shared"] as? Bool ?? false)
            XCTAssertEqual(result, state(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testGemeinsamePause() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["groupPause"]) {
            let result = ExchangeBackoff.groupPause(
                state(c["state"]), now: now, httpCode: Int(S.number(c["httpCode"]) ?? 0),
                retryAfterSeconds: S.number(c["retryAfterSeconds"]).map { Int64($0) })
            XCTAssertEqual(result, state(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testUebernommenePause() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["adoptedPause"]) {
            let result = ExchangeBackoff.adoptedPause(state(c["old"]), until: S.millis(c["until"]), now: now)
            XCTAssertEqual(result, state(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testZusammenfuehrenNurIos() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["merge"]) {
            let result = ExchangeBackoff.merge(ours: states(c["ours"]), initial: states(c["initial"]),
                                               stored: states(c["stored"]), now: now)
            XCTAssertEqual(result, states(c["expected"]), c["name"] as? String ?? "")
        }
    }
}
