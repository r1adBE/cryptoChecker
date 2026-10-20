import XCTest
@testable import CryptoChecker

/// Alarm-Protokoll (`Parity/alarm_log.json`): Ergebnis je beobachteter Prüfung, Melde-Ablauf, Zusammenfassen im
/// Ringpuffer und Dateiformat. Gegenstück: `AlarmLogParityTest.kt`.
final class AlarmLogParityTests: XCTestCase {
    private typealias S = TestSupport

    private func flag(_ m: [String: Any], _ key: String) -> Bool { m[key] as? Bool ?? false }

    private func optionalBool(_ value: Any?) -> Bool? {
        guard let value, !(value is NSNull) else { return nil }
        return value as? Bool
    }

    func testCodesMatch() throws {
        let codes = try XCTUnwrap(try S.fixture("alarm_log")["codes"] as? [String: String])
        XCTAssertEqual(codes.count, AlarmLogOutcome.allCases.count)
        for outcome in AlarmLogOutcome.allCases {
            XCTAssertEqual(codes[String(describing: outcome)], outcome.rawValue, "\(outcome)")
        }
    }

    func testOutcomeFromTrace() throws {
        for c in S.list(try S.fixture("alarm_log")["outcome"]) {
            let t = c["trace"] as? [String: Any] ?? [:]
            let trace = AlarmLogTrace(
                noPrice: flag(t, "noPrice"), lowLiquidity: flag(t, "lowLiquidity"), markPrice: flag(t, "markPrice"),
                stale: flag(t, "stale"), noRate: flag(t, "noRate"), fxStale: flag(t, "fxStale"),
                fxUnsure: flag(t, "fxUnsure"), due: flag(t, "due"),
                coolingDown: flag(t, "coolingDown"), waitingRearm: flag(t, "waitingRearm"),
                quietHours: flag(t, "quietHours"), snoozed: flag(t, "snoozed"),
                notificationsOff: flag(t, "notificationsOff"),
                retryBlocked: flag(t, "retryBlocked"), notShown: flag(t, "notShown"))
            let name = c["name"] as? String ?? ""
            let outcome = AlarmLog.outcome(trace)
            XCTAssertEqual(String(describing: outcome), c["outcome"] as? String, name)
            XCTAssertEqual(AlarmLog.notable(outcome, trace), c["notable"] as? Bool, name)
        }
    }

    func testDeliveryTrace() throws {
        for c in S.list(try S.fixture("alarm_log")["delivery"]) {
            let trace = AlarmLog.deliveryTrace(holdAlarms: flag(c, "hold"), canShow: optionalBool(c["canShow"]),
                                               shown: optionalBool(c["shown"]), saved: flag(c, "saved"),
                                               snoozed: flag(c, "snoozed"))
            if let expected = c["outcome"] as? String {
                XCTAssertEqual(trace.map { String(describing: AlarmLog.outcome($0)) }, expected, "\(c)")
            } else {
                XCTAssertNil(trace, "\(c)")
            }
        }
    }

    private func entry(_ m: [String: Any]) -> AlarmLogEntry {
        AlarmLogEntry(alarmId: Int64(S.number(m["id"]) ?? 0), at: Int64(S.number(m["at"]) ?? 0), pair: "BTC/USDT",
                      exchange: "Binance", quote: "USDT", price: 100, condition: .PRICE_ABOVE, threshold: 100,
                      outcome: AlarmLogOutcome(rawValue: m["out"] as? String ?? "") ?? .NOT_REACHED,
                      notable: flag(m, "n"))
    }

    func testRecordCollapsesRepeatsButNeverFirings() throws {
        let c = try XCTUnwrap(try S.fixture("alarm_log")["record"] as? [String: Any])
        let state = AlarmLog.record(.empty, S.list(c["entries"]).map(entry), now: Int64(S.number(c["now"]) ?? 0))
        let events = S.list(c["events"])
        XCTAssertEqual(state.events.count, events.count)
        for (expected, actual) in zip(events, state.events) {
            XCTAssertEqual(actual.alarmId, Int64(S.number(expected["id"]) ?? -1))
            XCTAssertEqual(actual.at, Int64(S.number(expected["at"]) ?? -1))
            XCTAssertEqual(actual.until, Int64(S.number(expected["until"]) ?? -1))
            XCTAssertEqual(actual.outcome.rawValue, expected["out"] as? String)
        }
        let last = S.list(c["lastChecks"])
        XCTAssertEqual(state.lastChecks.count, last.count)
        for expected in last {
            let actual = try XCTUnwrap(state.lastChecks[Int64(S.number(expected["id"]) ?? -1)])
            XCTAssertEqual(actual.at, Int64(S.number(expected["at"]) ?? -1))
            XCTAssertEqual(actual.outcome.rawValue, expected["out"] as? String)
        }
    }

    func testDecodeFileFormat() throws {
        let c = try XCTUnwrap(try S.fixture("alarm_log")["decode"] as? [String: Any])
        let state = AlarmLog.decode(c["text"] as? String)
        let last = S.list(c["lastChecks"])
        XCTAssertEqual(state.lastChecks.count, last.count)
        for expected in last {
            let actual = try XCTUnwrap(state.lastChecks[Int64(S.number(expected["id"]) ?? -1)])
            XCTAssertEqual(actual.at, Int64(S.number(expected["at"]) ?? -1))
            XCTAssertEqual(actual.pair, expected["pair"] as? String)
            XCTAssertEqual(actual.exchange, expected["exchange"] as? String)
            S.assertNumber(actual.price, S.number(expected["price"]), accuracy: 1e-9, "price")
            XCTAssertEqual(actual.condition.rawValue, expected["condition"] as? String)
            XCTAssertEqual(actual.threshold, S.number(expected["threshold"]) ?? .nan, accuracy: 1e-9)
            XCTAssertEqual(String(describing: actual.outcome), expected["outcome"] as? String)
            XCTAssertEqual(actual.source?.rawValue, expected["source"] as? String)
        }
        let events = S.list(c["events"])
        XCTAssertEqual(state.events.count, events.count, "unbekanntes Ergebnis fällt weg")
        for (expected, actual) in zip(events, state.events) {
            XCTAssertEqual(actual.alarmId, Int64(S.number(expected["id"]) ?? -1))
            XCTAssertEqual(actual.at, Int64(S.number(expected["at"]) ?? -1))
            S.assertNumber(actual.price, S.number(expected["price"]), accuracy: 1e-9, "price")
            XCTAssertEqual(String(describing: actual.outcome), expected["outcome"] as? String)
            XCTAssertEqual(actual.exchangeTime, S.number(expected["exchangeTime"]).map { Int64($0) })
            XCTAssertEqual(actual.until, Int64(S.number(expected["until"]) ?? -1))
            XCTAssertEqual(actual.notable, expected["notable"] as? Bool)
            XCTAssertEqual(actual.currency, expected["currency"] as? String)
            S.assertNumber(actual.shownPrice, S.number(expected["shownPrice"]), accuracy: 1e-9, "shownPrice")
            XCTAssertEqual(actual.source?.rawValue, expected["source"] as? String, "Herkunft (unbekannt = nil)")
        }
        XCTAssertTrue(AlarmLog.decode(c["wrongVersion"] as? String).events.isEmpty)
        // Hin und zurück unverändert
        XCTAssertEqual(AlarmLog.decode(AlarmLog.encode(state)), state)
    }

    func testLimits() throws {
        let limits = try XCTUnwrap(try S.fixture("alarm_log")["limits"] as? [String: Any])
        XCTAssertEqual(AlarmLog.maxEvents, Int(S.number(limits["maxEvents"]) ?? 0))
        XCTAssertEqual(AlarmLog.retentionMillis, Int64(S.number(limits["retentionDays"]) ?? 0) * 24 * 60 * 60_000)
        XCTAssertEqual(AlarmLog.liveWriteIntervalMillis, Int64(S.number(limits["liveWriteIntervalMillis"]) ?? 0))
    }
}
