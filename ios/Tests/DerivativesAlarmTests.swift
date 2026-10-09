import XCTest
@testable import CryptoChecker

/// Futures-Alarme («Funding über/unter», «Open Interest steigt/fällt») — gemeinsame Fälle aus
/// `alarms_derivatives.json` wie `DerivativesParityTest.kt`, dazu Verlauf und Satz wie
/// `DerivativesAlarmTest.kt`.
final class DerivativesAlarmTests: XCTestCase {

    private typealias S = TestSupport

    private func name(_ d: DerivativesAlarm.Decision) -> String {
        switch d {
        case .idle: return "none"
        case .rearm: return "rearm"
        case .fire: return "fire"
        }
    }

    /// [minutesAgo, units] → Messung relativ zu `now`.
    private func points(_ raw: Any?, now: Int64) -> [DerivativesAlarm.OiPoint] {
        (raw as? [Any] ?? []).compactMap { item in
            guard let pair = item as? [Any], pair.count >= 2,
                  let minutes = S.number(pair[0]), let units = S.number(pair[1]) else { return nil }
            return DerivativesAlarm.OiPoint(units: units, time: now - Int64(minutes * 60_000))
        }
    }

    private func minutesAgo(_ points: [DerivativesAlarm.OiPoint], now: Int64) -> [Double] {
        points.map { Double(now - $0.time) / 60_000 }
    }

    private func expectedMinutes(_ raw: Any?) -> [Double] {
        (raw as? [Any] ?? []).compactMap { S.number($0) }
    }

    func testDecide() throws {
        let data = try S.fixture("alarms_derivatives")
        let now = Int64(S.number(data["now"]) ?? 0)
        let cases = S.list(data["decide"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let result = DerivativesAlarm.decide(
                condition: AlarmCondition(rawValue: c["condition"] as? String ?? "") ?? .PRICE_ABOVE,
                threshold: S.number(c["threshold"]) ?? 0,
                value: S.number(c["value"]),
                armed: c["armed"] as? Bool ?? true,
                enabled: c["enabled"] as? Bool ?? true,
                lastTriggeredAt: Int64(S.number(c["lastTriggeredAt"]) ?? 0),
                now: now,
                cooldownMinutes: Int(S.number(c["cooldownMinutes"]) ?? 0)
            )
            XCTAssertEqual(name(result), c["expected"] as? String, "decide: \(c["name"] ?? "")")
        }
    }

    func testSequences() throws {
        let data = try S.fixture("alarms_derivatives")
        let now = Int64(S.number(data["now"]) ?? 0)
        let step = Int64(S.number(data["stepMillis"]) ?? 0)
        for c in S.list(data["sequences"]) {
            let condition = AlarmCondition(rawValue: c["condition"] as? String ?? "") ?? .PRICE_ABOVE
            let threshold = S.number(c["threshold"]) ?? 0
            let repeating = c["repeating"] as? Bool ?? false
            let cooldown = Int(S.number(c["cooldownMinutes"]) ?? 0)
            var enabled = true
            var referenceAt: Int64 = 0
            var lastTriggeredAt: Int64 = 0
            var fired = 0
            for (i, raw) in (c["values"] as? [Any] ?? []).enumerated() {
                let time = now + Int64(i) * step
                if !enabled { continue }
                switch DerivativesAlarm.decide(condition: condition, threshold: threshold, value: S.number(raw),
                                               armed: referenceAt <= 0, enabled: enabled,
                                               lastTriggeredAt: lastTriggeredAt, now: time, cooldownMinutes: cooldown) {
                case .idle:
                    break
                case .rearm:
                    referenceAt = 0
                case .fire:
                    fired += 1
                    lastTriggeredAt = time
                    referenceAt = time
                    enabled = repeating
                }
            }
            XCTAssertEqual(fired, Int(S.number(c["expectedFires"]) ?? -1), "sequence: \(c["name"] ?? "")")
        }
    }

    func testOpenInterest() throws {
        let data = try S.fixture("alarms_derivatives")
        let now = Int64(S.number(data["now"]) ?? 0)
        for c in S.list(data["oiChange"]) {
            let result = DerivativesAlarm.oiChangePercent(history: points(c["history"], now: now),
                                                          currentUnits: S.number(c["current"]),
                                                          hours: Int(S.number(c["hours"]) ?? 1), now: now)
            if let expected = S.number(c["expected"]) {
                let value = try XCTUnwrap(result, "oiChange: \(c["name"] ?? "")")
                XCTAssertEqual(value, expected, accuracy: 1e-9, "oiChange: \(c["name"] ?? "")")
            } else {
                XCTAssertNil(result, "oiChange: \(c["name"] ?? "")")
            }
        }
        for c in S.list(data["prune"]) {
            let result = DerivativesAlarm.pruneOi(points(c["history"], now: now), now: now)
            XCTAssertEqual(minutesAgo(result, now: now), expectedMinutes(c["expected"]), "prune: \(c["name"] ?? "")")
        }
        for c in S.list(data["append"]) {
            let point = try XCTUnwrap(points([c["point"] as Any], now: now).first)
            let result = DerivativesAlarm.appendOi(points(c["history"], now: now), point, now: now)
            XCTAssertEqual(minutesAgo(result, now: now), expectedMinutes(c["expected"]), "append: \(c["name"] ?? "")")
        }
    }

    func testInputAndAvailability() throws {
        let data = try S.fixture("alarms_derivatives")
        for c in S.list(data["parseFunding"]) {
            let text = c["text"] as? String ?? ""
            let separator = (c["decimalSeparator"] as? String ?? ".").first ?? "."
            let result = DerivativesAlarm.parseFunding(text, decimalSeparator: separator)
            if let expected = S.number(c["expected"]) {
                let value = try XCTUnwrap(result, "parseFunding: \(text)")
                XCTAssertEqual(value, expected, accuracy: 1e-12, "parseFunding: \(text)")
            } else {
                XCTAssertNil(result, "parseFunding: \(text)")
            }
        }
        for c in S.list(data["supports"]) {
            XCTAssertEqual(DerivativesAlarm.supports(marketKey: c["marketKey"] as? String ?? "",
                                                     perpetual: c["perpetual"] as? Bool ?? false),
                           c["expected"] as? Bool, "supports: \(c["marketKey"] ?? "")")
        }
        for c in S.list(data["oiWindow"]) {
            XCTAssertEqual(DerivativesAlarm.oiWindowHours(Int(S.number(c["hours"]) ?? 0)), Int(S.number(c["expected"]) ?? -1))
        }
    }

    func testConditionsAreDerivativesOnly() {
        let derivatives = AlarmCondition.allCases.filter(\.isDerivatives)
        XCTAssertEqual(derivatives, [.FUNDING_ABOVE, .FUNDING_BELOW, .OI_UP, .OI_DOWN])
        for c in derivatives {
            XCTAssertFalse(c.isPercent)
            XCTAssertFalse(c.isPriceThreshold)
            XCTAssertFalse(c.isNearExtreme)
        }
        XCTAssertTrue(DerivativesAlarm.isValidThreshold(.FUNDING_BELOW, -0.01))
        XCTAssertTrue(DerivativesAlarm.isValidThreshold(.FUNDING_ABOVE, 0))
        XCTAssertFalse(DerivativesAlarm.isValidThreshold(.FUNDING_ABOVE, 10.5))
        XCTAssertFalse(DerivativesAlarm.isValidThreshold(.OI_UP, 0))
        XCTAssertFalse(DerivativesAlarm.isValidThreshold(.PRICE_ABOVE, 5))
    }

    /// Messungen alle 5 Min. über 30 Stunden: Verlauf bleibt klein und deckt 1, 4 und 24 Stunden ab.
    func testHistoryOverADay() {
        let start: Int64 = 1_700_000_000_000
        let minute: Int64 = 60_000
        var history: [DerivativesAlarm.OiPoint] = []
        var now = start
        var units = 1_000.0
        for _ in 0..<(30 * 12) {
            history = DerivativesAlarm.appendOi(history, DerivativesAlarm.OiPoint(units: units, time: now), now: now)
            units += 1
            now += 5 * minute
        }
        let last = now - 5 * minute
        XCTAssertLessThan(history.count, 100)
        for hours in DerivativesAlarm.oiWindows {
            let change = DerivativesAlarm.oiChangePercent(history: history, currentUnits: units, hours: hours, now: last)
            XCTAssertNotNil(change, "window \(hours)")
            XCTAssertGreaterThan(change ?? 0, 0)
        }
        let later = last + 48 * 60 * minute
        XCTAssertNil(DerivativesAlarm.oiChangePercent(history: history, currentUnits: units, hours: 24, now: later))
        XCTAssertTrue(DerivativesAlarm.pruneOi(history, now: later).isEmpty)
    }

    /// Editor: Funding-Eingabe mit Vorzeichen, Bedingungswechsel setzt passende Vorschläge.
    func testDraft() {
        var draft = AlarmDraft(condition: .PRICE_ABOVE, thresholdText: "60000")
        draft = draft.withCondition(.FUNDING_BELOW)
        XCTAssertEqual(draft.threshold ?? -1, DerivativesAlarm.defaultFundingPercent, accuracy: 1e-12)
        draft = draft.withToggledSign()
        XCTAssertEqual(draft.threshold ?? 1, -DerivativesAlarm.defaultFundingPercent, accuracy: 1e-12)
        draft = draft.withToggledSign()
        XCTAssertEqual(draft.threshold ?? -1, DerivativesAlarm.defaultFundingPercent, accuracy: 1e-12)
        draft = draft.withCondition(.OI_UP)
        XCTAssertEqual(draft.threshold, DerivativesAlarm.defaultOiPercent)
        XCTAssertTrue(DerivativesAlarm.oiWindows.contains(draft.windowHours))
        draft = draft.withCondition(.CHANGE_PERCENT_UP)
        XCTAssertNil(draft.threshold)
    }
}
