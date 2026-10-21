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
                                                         price: S.number(c["price"]) ?? 0,
                                                         now: S.number(c["now"]).map { Int64($0) })
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
            var history: [MoveWindow.PricePoint] = []
            for (i, price) in prices.enumerated() {
                let time = now + Int64(i) * step
                if a.enabled {
                    if AlarmEvaluator.needsReference(alarm: a, now: time) {
                        a.referencePrice = price
                        a.referenceAt = time
                    } else if AlarmEvaluator.shouldRearmLevel(alarm: a, price: price, now: time) {
                        a.referenceAt = 0
                    } else if AlarmEvaluator.shouldTrigger(alarm: a, price: price, previousPrice: nil, now: time,
                                                           cooldownMinutes: cooldown, moveHistory: history) {
                        fired += 1
                        a = AlarmEvaluator.triggered(a, price: price, time: time)
                    }
                }
                history = MoveWindow.append(history, MoveWindow.PricePoint(price: price, time: time), now: time)
            }
            XCTAssertEqual(fired, Int(S.number(c["expectedFires"]) ?? -1), "sequence: \(c["name"] ?? "")")
            count += 1
        }
        for c in S.list(data["reference"]) {
            XCTAssertEqual(AlarmEvaluator.needsReference(alarm: S.alarm(c["alarm"] as? [String: Any] ?? [:]), now: now),
                           c["expected"] as? Bool, "reference: \(c["name"] ?? "")")
            count += 1
        }
        let triggered = data["triggered"] as? [String: Any] ?? [:]
        for c in S.list(triggered["cases"]) {
            let name = "triggered: \(c["name"] ?? "")"
            let after = AlarmEvaluator.triggered(
                S.alarm(c["alarm"] as? [String: Any] ?? [:]), price: S.number(c["price"]) ?? 0, time: now,
                candleOpenTime: S.number(c["candleOpenTime"]).map { Int64($0) }, nearLevel: S.number(c["nearLevel"]))
            let e = c["expected"] as? [String: Any] ?? [:]
            if let expected = S.number(e["referencePrice"]) {
                XCTAssertEqual(after.referencePrice ?? .nan, expected, accuracy: 1e-9, "\(name) referencePrice")
            } else {
                XCTAssertNil(after.referencePrice, "\(name) referencePrice")
            }
            XCTAssertEqual(after.referenceAt, Int64(S.number(e["referenceAt"]) ?? -1), "\(name) referenceAt")
            XCTAssertEqual(after.enabled, e["enabled"] as? Bool, "\(name) enabled")
            XCTAssertEqual(after.lastTriggeredAt, Int64(S.number(e["lastTriggeredAt"]) ?? -1), "\(name) lastTriggeredAt")
            XCTAssertEqual(after.lastTriggeredPrice ?? .nan, S.number(e["lastTriggeredPrice"]) ?? -1, accuracy: 1e-9,
                           "\(name) lastTriggeredPrice")
            count += 1
        }
        let minute: Int64 = 60_000
        func point(_ raw: Any?) -> MoveWindow.PricePoint? {
            guard let pair = raw as? [Any], pair.count >= 2, let ago = S.number(pair[0]) else { return nil }
            return MoveWindow.PricePoint(price: S.number(pair[1]) ?? 0, time: now - Int64(ago * Double(minute)))
        }
        let moveChange = data["moveChange"] as? [String: Any] ?? [:]
        for c in S.list(moveChange["cases"]) {
            // Argumente mit festen Typen (für den Type-Checker)
            let history: [MoveWindow.PricePoint] = (c["history"] as? [Any] ?? []).compactMap { point($0) }
            let sinceAgo: Double = (S.number(c["sinceMinutesAgo"]) ?? 0) * Double(minute)
            let since: Int64 = now - Int64(sinceAgo)
            let hours: Int = Int(S.number(c["hours"]) ?? 1)
            let price: Double = S.number(c["price"]) ?? 0
            let result = MoveWindow.changePercent(
                history: history,
                reference: point(c["reference"]),
                price: price,
                hours: hours,
                since: since,
                now: now)
            let name = "moveChange: \(c["name"] ?? "")"
            if let expected = S.number(c["expected"]) {
                XCTAssertEqual(result ?? .nan, expected, accuracy: 1e-9, name)
            } else {
                XCTAssertNil(result, name)
            }
            count += 1
        }
        let nearSequences = data["nearExtremeSequences"] as? [String: Any] ?? [:]
        for c in S.list(nearSequences["cases"]) {
            let rangeSpec = c["range"] as? [String: Any] ?? [:]
            let range = NearExtreme.Range(high: S.number(rangeSpec["high"]) ?? 0, low: S.number(rangeSpec["low"]) ?? 0)
            var a = S.alarm(["condition": c["condition"] ?? "", "threshold": c["threshold"] ?? 0,
                             "repeating": c["repeating"] ?? false, "windowHours": c["windowDays"] ?? 30])
            let step = Int64(S.number(c["stepMillis"]) ?? 0)
            let cooldown = Int(S.number(c["cooldownMinutes"]) ?? 0)
            var decisions: [String] = []
            for (i, price) in (c["prices"] as? [Any] ?? []).compactMap({ S.number($0) }).enumerated() {
                let time = now + Int64(i) * step
                guard a.enabled else {
                    decisions.append("skip")
                    continue
                }
                let decision = NearExtreme.decide(
                    side: a.condition == .NEAR_HIGH ? .high : .low, price: price, range: range,
                    thresholdPercent: a.threshold, armed: a.referenceAt <= 0,
                    lastLevel: NearExtreme.reportedMark(lastLevel: a.referencePrice, lastTriggeredAt: a.lastTriggeredAt,
                                                        windowDays: a.windowHours, now: time),
                    inCooldown: NearExtreme.inCooldown(lastTriggeredAt: a.lastTriggeredAt, now: time, cooldownMinutes: cooldown),
                    lastTriggeredAt: a.lastTriggeredAt, now: time)
                switch decision {
                case .idle:
                    decisions.append("none")
                case .rearm:
                    a.referenceAt = 0
                    decisions.append("rearm")
                case let .fire(_, _, _, level):
                    a = AlarmEvaluator.triggered(a, price: price, time: time, nearLevel: level)
                    decisions.append("fire")
                }
            }
            XCTAssertEqual(decisions, c["expected"] as? [String] ?? [], "nearExtremeSequences: \(c["name"] ?? "")")
            count += 1
        }
        XCTAssertGreaterThanOrEqual(count, 90, "alarm cases read")
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

    // MARK: Menge/Kurs im Bestand und Dezimaltext (threshold.json)

    func testAmountInput() throws {
        let data = try S.fixture("threshold")
        let cases = S.list(data["amount"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let text = c["text"] as? String ?? ""
            let decimal: Character = (c["decimal"] as? String)?.first ?? "."
            let expected = S.number(c["expected"])
            let actual = PriceFormat.parseAmount(text, decimalSeparator: decimal, priceHint: S.number(c["hint"]))
            S.assertNumber(actual, expected, accuracy: abs(expected ?? 0) * 1e-12,
                           "amount '\(text)' (\(decimal), \(c["hint"] ?? "nil"))")
        }
        for c in S.list(data["amountForInput"]) {
            let value = (c["value"] as? String).flatMap { Double($0) }
            let decimal: Character = (c["decimal"] as? String)?.first ?? "."
            XCTAssertEqual(PriceFormat.amountForInput(value, decimalSeparator: decimal), c["expected"] as? String,
                           "amountForInput \(c["value"] ?? "nil")")
        }
    }

    func testDecimalText() throws {
        let data = try S.fixture("threshold")
        for c in S.list(data["plain"]) {
            let value = try XCTUnwrap(Double(c["value"] as? String ?? ""))
            let scale = S.number(c["scale"]).map { Int($0) }
            XCTAssertEqual(DecimalText.plain(value, scale: scale), c["expected"] as? String,
                           "plain \(c["value"] ?? "") \(c["scale"] ?? "nil")")
        }
        for c in S.list(data["fixed"]) {
            let value = try XCTUnwrap(Double(c["value"] as? String ?? ""))
            let scale = Int(try XCTUnwrap(S.number(c["scale"])))
            XCTAssertEqual(DecimalText.fixed(value, scale: scale), c["expected"] as? String, "fixed \(c["value"] ?? "")")
        }
        let export = S.list(data["export"])
        XCTAssertGreaterThanOrEqual(export.count, 5)
        for c in export {
            let value = try XCTUnwrap(Double(c["value"] as? String ?? ""))
            let name = c["value"] as? String ?? ""
            XCTAssertEqual(CutoffExport.amount(value), c["amount"] as? String, "export amount \(name)")
            XCTAssertEqual(CutoffExport.price(value), c["amount"] as? String, "export price \(name)")
            XCTAssertEqual(CutoffExport.rate(value), c["rate"] as? String, "export rate \(name)")
            XCTAssertEqual(CutoffExport.money(value), c["money"] as? String, "export money \(name)")
        }
    }

    /// Alarm-Schwellwert genau wie gespeichert (1.0005 nicht «1») und vorgelesene Kurse unter 1 mit vier Stellen.
    func testThresholdAndSpokenPrice() throws {
        let data = try S.fixture("threshold")
        let display = S.list(data["thresholdDisplay"])
        XCTAssertGreaterThanOrEqual(display.count, 5)
        for c in display {
            let value = (c["value"] as? String).flatMap { Double($0) }
            let locale = Locale(identifier: c["locale"] as? String ?? "en_US")
            XCTAssertEqual(PriceFormat.threshold(value, locale: locale), c["expected"] as? String,
                           "threshold \(c["value"] ?? "nil") \(c["locale"] ?? "")")
        }
        let spoken = S.list(data["spokenPrice"])
        XCTAssertGreaterThanOrEqual(spoken.count, 5)
        for c in spoken {
            let value = try XCTUnwrap(Double(c["value"] as? String ?? ""))
            XCTAssertEqual(PriceFormat.spokenPrice(value), c["expected"] as? String, "spokenPrice \(c["value"] ?? "")")
        }
    }

    // MARK: Stichtag-Export exakt

    func testCutoffExport() throws {
        let data = try S.fixture("cutoff_export")
        let posix = Locale(identifier: "en_US_POSIX")
        let texts = CutoffCsvTexts(
            coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT", rate: "Kurs",
            valueTarget: "Wert", note: "Hinweis", total: "Total", noPrice: "kein Kurs", noFx: "kein Devisenkurs",
            unchecked: "ungeprüft", incomplete: "unvollständig", commentFormat: "# %@ %@ %@"
        )
        let cases = S.list(data["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let holdings = (c["holdings"] as? [[String]] ?? []).map { pair in
                CutoffHolding(coin: pair[0], amount: Decimal(string: pair[1], locale: posix) ?? .nan)
            }
            var prices: [String: Decimal] = [:]
            for (coin, text) in c["prices"] as? [String: String] ?? [:] {
                prices[coin] = Decimal(string: text, locale: posix)
            }
            let fx = (c["fxRate"] as? String).flatMap { Decimal(string: $0, locale: posix) }
            let unchecked = Set(c["unchecked"] as? [String] ?? [])
            let format = CutoffCsvFormat(separator: Character(c["separator"] as? String ?? ";"),
                                         decimal: Character(c["decimal"] as? String ?? "."))
            let rows = CutoffExport.rows(holdings, prices: prices, fxRate: fx, unchecked: unchecked)
            let lines = CutoffExport.csv(day: CutoffDay(year: 2025, month: 12, day: 31), currency: "CHF", fxDate: nil,
                                         rows: rows, texts: texts, format: format)
                .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
            let expected = c["expected"] as? [String] ?? []
            XCTAssertFalse(expected.isEmpty, name)
            XCTAssertEqual(Array(lines.dropFirst(2).prefix(expected.count)), expected, "cutoff \(name)")
            XCTAssertEqual(Array(lines.dropFirst(2 + expected.count)), [""], "cutoff \(name) Ende")
        }
    }

    /// Bezugskurse der Tagesschlüsse: ohne ungeprüfte und (seit Build 101) ohne «Kurs ungeprüft» — wie Android.
    func testCutoffReferencePrices() throws {
        let cases = S.list(try S.fixture("cutoff_export")["referenceCases"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let current = (c["current"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) }
            let got = CutoffExport.referencePrices(current, uncheckedCurrent: Set(c["unchecked"] as? [String] ?? []),
                                                   unverifiedCurrent: Set(c["unverified"] as? [String] ?? []))
            let expected = (c["expected"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) }
            XCTAssertEqual(got, expected, "reference \(name)")
        }
    }

    /// Bezugskurs des Wertverlaufs (Speicher, dann gemerkter Stand) nach derselben Regel (seit Build 101) — wie Android.
    func testChainReference() throws {
        let cases = S.list(try S.fixture("cutoff_export")["chainReferenceCases"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let layers = S.list(c["layers"]).map { l in
                CutoffExport.ReferenceLayer(prices: (l["prices"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) },
                                            unchecked: Set(l["unchecked"] as? [String] ?? []),
                                            unverified: Set(l["unverified"] as? [String] ?? []))
            }
            let got = CutoffExport.referenceOf(c["coin"] as? String ?? "", layers: layers)
            XCTAssertEqual(got, S.number(c["expected"]), "chain \(c["name"] as? String ?? "")")
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
        // Fehlt in der vollständigen Liste: erst einzeln nachfragen, dann entscheiden
        for c in S.list(data["askSingleWhenMissing"]) {
            let error = c["lastError"] as? String
            XCTAssertEqual(NotTraded.askSingleWhenMissing(lastError: error), c["askSingle"] as? Bool, "askSingle: \(error ?? "nil")")
        }
        for c in S.list(data["confirmedBySingle"]) {
            let error = c["error"] as? String
            let gotPrice = c["gotPrice"] as? Bool ?? false
            XCTAssertEqual(NotTraded.confirmedBySingle(gotPrice: gotPrice, error: error), c["confirmed"] as? Bool,
                           "confirmed: \(error ?? "nil")")
        }
    }

    // MARK: Veraltet (Live-Strom 2 Min., sonst 3 × Hintergrund-Intervall)

    func testOutdated() throws {
        let data = try S.fixture("outdated")
        let now = S.millis(data["now"])
        for c in S.list(data["afterMillis"]) {
            let actual = OutdatedRule.afterMillis(
                backgroundIntervalMinutes: Int(S.number(c["backgroundIntervalMinutes"]) ?? 0))
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
                                          quoteIsFiat: c["quoteIsFiat"] as? Bool ?? false,
                                          usdToQuote: S.number(c["usdToQuote"]),
                                          usdToQuoteAtOpen: S.number(c["usdToQuoteAtOpen"]))
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

    func testNearExtremeSeriesAllowed() throws {
        // Alarm «Nahe am Hoch/Tief»: Ausweich-Reihe nur mit Namensprüfung und Kursband ±25 % (wie der Mini-Chart)
        let cases = S.list(try S.fixture("near_extreme")["seriesAllowed"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let actual = NearExtreme.seriesAllowed(nameAllowed: c["nameAllowed"] as? Bool ?? false,
                                                   price: S.number(c["price"]), lastClose: S.number(c["lastClose"]),
                                                   quoteToSeries: S.number(c["rate"]))
            XCTAssertEqual(actual, c["expected"] as? Bool, "seriesAllowed: \(c["name"] ?? "")")
        }
    }
}
