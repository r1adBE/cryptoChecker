import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle Android/iOS: dieselben JSON-Dateien wie `ParityFixturesTest.kt`
/// (Quelle `android/testdata/parity`, vom Generator nach `Tests/Parity` kopiert).
/// Gleiche Eingaben → gleiche erwarteten Ergebnisse auf beiden Plattformen.
final class ParityFixtureTests: XCTestCase {

    private typealias S = TestSupport

    // MARK: Alarme

    func testAlarms() throws {
        let data = try S.fixture("alarms")
        let now = Int64(S.number(data["now"]) ?? 0)
        var count = 0

        for c in S.list(data["trigger"]) {
            let result = AlarmEvaluator.shouldTrigger(
                alarm: S.alarm(c["alarm"] as? [String: Any] ?? [:]),
                price: S.number(c["price"]) ?? 0,
                previousPrice: S.number(c["previousPrice"]),
                now: now,
                cooldownMinutes: Int(S.number(c["cooldownMinutes"]) ?? 0)
            )
            XCTAssertEqual(result, c["expected"] as? Bool, "trigger: \(c["name"] ?? "")")
            count += 1
        }
        for c in S.list(data["rearm"]) {
            let result = AlarmEvaluator.shouldRearmLevel(alarm: S.alarm(c["alarm"] as? [String: Any] ?? [:]),
                                                         price: S.number(c["price"]) ?? 0)
            XCTAssertEqual(result, c["expected"] as? Bool, "rearm: \(c["name"] ?? "")")
            count += 1
        }
        for c in S.list(data["volumeSpike"]) {
            let result = AlarmEvaluator.shouldTriggerVolumeSpike(
                alarm: S.alarm(c["alarm"] as? [String: Any] ?? [:]),
                ratio: S.number(c["ratio"]),
                candleOpenTime: Int64(S.number(c["candleOpenTime"]) ?? 0),
                now: now,
                cooldownMinutes: Int(S.number(c["cooldownMinutes"]) ?? 0)
            )
            XCTAssertEqual(result, c["expected"] as? Bool, "volumeSpike: \(c["name"] ?? "")")
            count += 1
        }
        let sequences = data["sequences"] as? [String: Any] ?? [:]
        for c in S.list(sequences["cases"]) {
            var a = S.alarm(c["alarm"] as? [String: Any] ?? [:])
            let step = Int64(S.number(c["stepMillis"]) ?? 0)
            let cooldown = Int(S.number(c["cooldownMinutes"]) ?? 0)
            let prices = (c["prices"] as? [Any] ?? []).compactMap { S.number($0) }
            var fired = 0
            for (i, price) in prices.enumerated() {
                let time = now + Int64(i) * step
                if !a.enabled { continue }
                if AlarmEvaluator.shouldRearmLevel(alarm: a, price: price) {
                    a.referenceAt = 0
                    continue
                }
                if AlarmEvaluator.shouldTrigger(alarm: a, price: price, previousPrice: nil, now: time,
                                                cooldownMinutes: cooldown) {
                    fired += 1
                    a.lastTriggeredAt = time
                    a.lastTriggeredPrice = price
                    a.referenceAt = time
                    a.enabled = a.repeating
                }
            }
            XCTAssertEqual(fired, Int(S.number(c["expectedFires"]) ?? -1), "sequence: \(c["name"] ?? "")")
            count += 1
        }
        XCTAssertGreaterThanOrEqual(count, 40, "alarm cases read")
    }

    // MARK: Schwellwert-Eingabe

    func testThresholdParser() throws {
        let cases = try S.list(S.fixture("threshold")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 40)
        for c in cases {
            let text = c["text"] as? String ?? ""
            let decimal: Character = (c["decimal"] as? String)?.first ?? "."
            let expected = S.number(c["expected"])
            let actual = ThresholdParser.parse(text, decimalSeparator: decimal, priceHint: S.number(c["hint"]))
            S.assertNumber(actual, expected, accuracy: abs(expected ?? 0) * 1e-12,
                           "threshold '\(text)' (\(decimal), \(c["hint"] ?? "nil"))")
        }
    }

    // MARK: Basis der %-Änderung

    func testChangeBasis() throws {
        let data = try S.fixture("change_basis")
        for c in S.list(data["dayStart"]) {
            let basis = try XCTUnwrap(ChangeBasis(rawValue: c["basis"] as? String ?? ""))
            let zone = try XCTUnwrap(TimeZone(identifier: c["zone"] as? String ?? ""))
            let actual = ChangeBasisMath.dayStart(basis, now: S.millis(c["now"]), timeZone: zone)
            let expected: Int64? = c["expected"] is String ? S.millis(c["expected"]) : nil
            XCTAssertEqual(actual, expected, "dayStart: \(c["name"] ?? "")")
        }
        for c in S.list(data["choose"]) {
            let basis = try XCTUnwrap(ChangeBasis(rawValue: c["basis"] as? String ?? ""))
            let candles = S.number(c["candles"])
            let actual = ChangeBasisMath.choose(basis, tickerChange: S.number(c["ticker"])) { candles }
            S.assertNumber(actual, S.number(c["expected"]), accuracy: 1e-9, "choose: \(c)")
        }
        for c in S.list(data["isCurrent"]) {
            var stamp: ChangeStamp?
            if let s = c["stamp"] as? [String: Any] {
                stamp = try ChangeStamp(basis: XCTUnwrap(ChangeBasis(rawValue: s["basis"] as? String ?? "")),
                                    dayStart: S.millis(s["dayStart"]))
            }
            let basis = try XCTUnwrap(ChangeBasis(rawValue: c["basis"] as? String ?? ""))
            let zone = try XCTUnwrap(TimeZone(identifier: c["zone"] as? String ?? ""))
            let actual = ChangeBasisMath.isCurrent(stamp: stamp, basis: basis, now: S.millis(c["now"]), timeZone: zone)
            XCTAssertEqual(actual, c["expected"] as? Bool, "isCurrent: \(c["name"] ?? "")")
        }
        for c in S.list(data["sinceLast"]) {
            let actual = ChangeBasisMath.sinceLast(last: S.number(c["last"]), previous: S.number(c["previous"]))
            S.assertNumber(actual, S.number(c["expected"]), accuracy: 1e-9, "sinceLast: \(c["name"] ?? "")")
        }
        for c in S.list(data["fromName"]) {
            let expected = try XCTUnwrap(ChangeBasis(rawValue: c["expected"] as? String ?? ""))
            XCTAssertEqual(ChangeBasis.from(name: c["name"] as? String), expected, "fromName: \(c["name"] ?? "nil")")
        }
        for c in S.list(data["zoneLabel"]) {
            let seconds = Int(try XCTUnwrap(S.number(c["offsetSeconds"])))
            XCTAssertEqual(ChangeBasisMath.zoneLabel(offsetSeconds: seconds), c["expected"] as? String, "zoneLabel: \(seconds)")
        }
    }

    // MARK: Nicht mehr gehandelt

    func testNotTraded() throws {
        let data = try S.fixture("not_traded")
        XCTAssertEqual(data["marker"] as? String, NotTraded.marker)
        for c in S.list(data["cases"]) {
            let error = c["lastError"] as? String
            XCTAssertEqual(NotTraded.isMarker(error), c["isMarker"] as? Bool, "isMarker: \(error ?? "nil")")
            S.assertNumber(NotTraded.shownChange24h(lastError: error, change24h: S.number(c["change24h"])),
                           S.number(c["shown"]), accuracy: 0, "shown: \(error ?? "nil")")
        }
    }

    // MARK: Veraltet (Live-Modus 2 Min., sonst 3 × Intervall)

    func testOutdated() throws {
        let data = try S.fixture("outdated")
        let now = S.millis(data["now"])
        for c in S.list(data["afterMillis"]) {
            let actual = OutdatedRule.afterMillis(
                liveService: c["liveService"] as? Bool ?? false,
                liveIntervalSeconds: Int(S.number(c["liveIntervalSeconds"]) ?? 0),
                backgroundIntervalMinutes: Int(S.number(c["backgroundIntervalMinutes"]) ?? 0),
                live: c["live"] as? Bool ?? false
            )
            XCTAssertEqual(actual, S.millis(c["expected"]), "afterMillis: \(c["name"] ?? "")")
        }
        for c in S.list(data["stale"]) {
            let age = S.number(c["ageMillis"])
            let time: Int64 = age.map { now - Int64($0) } ?? 0
            let outdated = OutdatedRule.isOutdated(time, now: now, afterMillis: S.millis(c["afterMillis"]))
            XCTAssertEqual(outdated, c["expected"] as? Bool, "stale: \(c["name"] ?? "")")
        }
    }

    // MARK: 24-h-Veränderung

    private func reference(_ value: Any?) -> DayReference? {
        guard let pair = value as? [Any], pair.count == 2,
              let open = S.number(pair[0]), let close = S.number(pair[1]) else { return nil }
        return DayReference(open: open, lastClose: close)
    }

    func testDayChange() throws {
        let data = try S.fixture("day_change")
        for c in S.list(data["fromTicker"]) {
            S.assertNumber(DayChange.fromTicker(S.number(c["value"])), S.number(c["expected"]), accuracy: 0,
                           "fromTicker: \(c["value"] ?? "nil")")
        }
        for c in S.list(data["select"]) {
            let actual = DayChange.select(price: S.number(c["price"]), pairReference: reference(c["pair"]),
                                          usdtReference: reference(c["usdt"]),
                                          quoteIsFiat: c["quoteIsFiat"] as? Bool ?? false)
            S.assertNumber(actual, S.number(c["expected"]), accuracy: 1e-9, "select: \(c["name"] ?? "")")
        }
        for c in S.list(data["reference"]) {
            let valid = DayReference.of(open: S.number(c["open"]), lastClose: S.number(c["lastClose"])) != nil
            XCTAssertEqual(valid, c["valid"] as? Bool, "reference: \(c)")
        }
        for c in S.list(data["candleQuote"]) {
            XCTAssertEqual(DayChange.candleQuote(c["quote"] as? String ?? ""), c["expected"] as? String)
        }
        for c in S.list(data["change24h"]) {
            let args = (c["args"] as? [Any] ?? []).compactMap { S.number($0) }
            let actual: Double?
            switch c["kind"] as? String {
            case "percent": actual = Change24h.percent(args[0])
            case "fraction": actual = Change24h.fraction(args[0])
            case "fromOpen": actual = Change24h.fromOpen(last: args[0], open: args[1])
            case "fromAbsolute": actual = Change24h.fromAbsolute(last: args[0], change: args[1])
            default:
                XCTFail("unknown kind \(c["kind"] ?? "")")
                continue
            }
            S.assertNumber(actual, S.number(c["expected"]), accuracy: 1e-9, "change24h: \(c)")
        }
    }
}
