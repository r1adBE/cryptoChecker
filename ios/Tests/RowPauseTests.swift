import XCTest
@testable import CryptoChecker

/// Börsen-Pause in der Zeile: gemeinsame Fälle `Parity/row_pause.json` (Gegenstück `RowPauseTest.kt`).
final class RowPauseTests: XCTestCase {
    private typealias S = TestSupport

    private func optionalMillis(_ value: Any?) -> Int64? {
        S.number(value).map { Int64($0) }
    }

    func testShownUntilParity() throws {
        let data = try S.fixture("row_pause")
        let now = S.millis(data["now"])
        for c in S.list(data["shownUntil"]) {
            let pausedUntil = optionalMillis(c["pausedInMillis"]).map { now + $0 }
            let expected = optionalMillis(c["expected"]).map { now + $0 }
            XCTAssertEqual(RowPause.shownUntil(disabled: c["disabled"] as? Bool ?? false, pausedUntil: pausedUntil, now: now),
                           expected, c["name"] as? String ?? "")
        }
    }

    func testPausesParity() throws {
        let data = try S.fixture("row_pause")
        for c in S.list(data["pauses"]) {
            var states: [String: ExchangeBackoff.State] = [:]
            for s in S.list(c["states"]) {
                states[s["key"] as? String ?? ""] = ExchangeBackoff.State(
                    strikes: Int(S.millis(s["strikes"])),
                    pausedUntil: S.millis(s["pausedUntil"]),
                    reason: (s["reason"] as? String).flatMap { RefreshFailure(rawValue: $0) },
                    timeoutRuns: Int(S.millis(s["timeoutRuns"])))
            }
            let expected = (c["expected"] as? [String: Any] ?? [:]).mapValues { S.millis($0) }
            XCTAssertEqual(RowPause.pauses(states), expected, c["name"] as? String ?? "")
        }
    }

    func testPauseFromBackoffIsShownUntilItEnds() throws {
        // Neue Pause nach «zu viele Anfragen»: die Zeile zeigt ihr Ende, danach wieder die normale Zeitzeile
        let now: Int64 = 1_700_000_000_000
        let state = try XCTUnwrap(ExchangeBackoff.next(nil, outcome: .rateLimited, now: now))
        let until = RowPause.pauses(["Binance": state])["Binance"]
        XCTAssertEqual(RowPause.shownUntil(disabled: false, pausedUntil: until, now: now), now + ExchangeBackoff.baseMillis)
        XCTAssertNil(RowPause.shownUntil(disabled: false, pausedUntil: until, now: now + ExchangeBackoff.baseMillis))
    }
}
