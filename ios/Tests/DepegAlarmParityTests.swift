import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/depeg_alarm.json` — wie `DepegAlarmParityTest.kt`.
final class DepegAlarmParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("depeg_alarm") }

    func testThreshold() throws {
        for c in TestSupport.list(try data()["threshold"]) {
            let stored = Int(TestSupport.number(c["stored"]) ?? -1)
            XCTAssertEqual(DepegAlarm.threshold(stored), Int(TestSupport.number(c["expected"]) ?? -1), "\(c)")
        }
    }

    /// Drei Quellen, Einbruch nur bestätigt, USDC/USDT allein nie alarmfähig.
    func testMeasure() throws {
        let cases = TestSupport.list(try data()["measure"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let m = DepegAlarm.measure(kraken: TestSupport.number(c["kraken"]), coinbase: TestSupport.number(c["coinbase"]),
                                       binanceUsdc: TestSupport.number(c["binance"]))
            if let expected = TestSupport.number(c["expected"]) {
                XCTAssertEqual(m?.price ?? .nan, expected, accuracy: 1e-9, "\(c)")
                XCTAssertEqual(m?.viaUsdc, c["viaUsdc"] as? Bool, "viaUsdc \(c)")
                if let m {
                    XCTAssertEqual(DepegAlarm.mayAlarm(m), !m.viaUsdc, "mayAlarm \(c)")
                    XCTAssertEqual(m.broken, expected < 0.5 || expected > 1.5, "broken \(c)")
                }
            } else {
                XCTAssertNil(m, "\(c)")
            }
        }
    }

    func testEvaluate() throws {
        let cases = TestSupport.list(try data()["evaluate"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let r = DepegAlarm.evaluate(deviationPercent: TestSupport.number(c["deviation"]) ?? 0,
                                        thresholdPercent: TestSupport.number(c["threshold"]) ?? 0,
                                        armed: c["armed"] as? Bool ?? false,
                                        mayFire: c["mayFire"] as? Bool ?? false)
            XCTAssertEqual(r.fire, c["fire"] as? Bool, "fire \(c)")
            XCTAssertEqual(r.armed, c["armedAfter"] as? Bool, "armed \(c)")
        }
    }

    func testEpisodes() throws {
        for e in TestSupport.list(try data()["episodes"]) {
            let threshold = DepegAlarm.thresholdPercent(Int(TestSupport.number(e["thresholdTenths"]) ?? 0))
            let prices = (e["prices"] as? [Any] ?? []).compactMap { TestSupport.number($0) }
            let quiet = e["quiet"] as? [Bool] ?? []
            var armed = true
            var fired: [Bool] = []
            for (i, p) in prices.enumerated() {
                let r = DepegAlarm.evaluate(deviationPercent: DepegAlarm.deviationPercent(p),
                                            thresholdPercent: threshold, armed: armed, mayFire: !quiet[i])
                armed = DepegAlarm.armedAfter(fire: r.fire, posted: r.fire, armedBefore: armed, next: r.armed)
                fired.append(r.fire)
            }
            XCTAssertEqual(fired, e["fired"] as? [Bool], "\(e["name"] ?? "")")
        }
    }

    /// Warnzeile der Portfolio-Karte: frische Messung ≥ Schwelle (30 Min gültig).
    func testCardWarning() throws {
        let now: Int64 = 1_800_000_000_000
        let cases = TestSupport.list(try data()["cardWarning"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let reading = TestSupport.number(c["price"]).map {
                DepegAlarm.Reading(price: $0, atMillis: now - Int64(TestSupport.number(c["ageMillis"]) ?? 0))
            }
            let shown = DepegAlarm.cardWarning(reading, thresholdTenths: Int(TestSupport.number(c["thresholdTenths"]) ?? 0), now: now)
            XCTAssertEqual(shown != nil, c["shown"] as? Bool, "\(c)")
            if shown != nil { XCTAssertEqual(shown, reading, "\(c)") }
        }
    }

    /// Zeile «USDT = 1» der Karte unabhängig vom Alarm (Bestand mit USDT, Werte über USDT = USD, Abweichung).
    func testCardNote() throws {
        let now: Int64 = 1_800_000_000_000
        let cases = TestSupport.list(try data()["cardNote"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let reading = TestSupport.number(c["price"]).map {
                DepegAlarm.Reading(price: $0, atMillis: now - Int64(TestSupport.number(c["ageMillis"]) ?? 0),
                                   viaUsdc: c["viaUsdc"] as? Bool ?? false)
            }
            let note = DepegAlarm.cardNote(holdingCoins: c["holdings"] as? [String] ?? [],
                                           valuesViaUsdt: c["valuesViaUsdt"] as? Bool ?? false, reading: reading,
                                           thresholdTenths: Int(TestSupport.number(c["thresholdTenths"]) ?? 0), now: now,
                                           usdtMarket: c["usdtMarket"] as? Bool ?? false)
            XCTAssertEqual(note?.kind.rawValue, c["kind"] as? String, "\(c)")
            let kind = note?.kind
            let withReading = kind == .DEVIATION || kind == .USDT_BREAK || kind == .VIA_USDC
            XCTAssertEqual(note?.reading, withReading ? reading : nil, "\(c)")
        }
    }

    /// Live-Kurs USDT/USD der Bewertung aus der letzten Messung (USDT zum Marktkurs, Owner-Entscheid Okt. 2026; 6 Std. gültig).
    func testLiveUsdtUsd() throws {
        let now: Int64 = 1_800_000_000_000
        // 6 Std. statt der 30 Min. der Warnzeile (lange Hintergrund-Intervalle)
        XCTAssertEqual(DepegAlarm.liveMaxAgeMillis, 21_600_000)
        XCTAssertEqual(DepegAlarm.cardMaxAgeMillis, 1_800_000)
        let cases = TestSupport.list(try data()["liveUsdtUsd"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let reading = TestSupport.number(c["price"]).map {
                DepegAlarm.Reading(price: $0, atMillis: now - Int64(TestSupport.number(c["ageMillis"]) ?? 0))
            }
            let live = DepegAlarm.liveUsdtUsd(reading, now: now)
            if let expected = TestSupport.number(c["expected"]) {
                XCTAssertEqual(live ?? .nan, expected, accuracy: 1e-12, "\(c)")
            } else {
                XCTAssertNil(live, "\(c)")
            }
        }
    }

    /// Kurs laut Börse älter als 2 Std. zählt wie keine Antwort (wie Kursalarme); ohne Zeit der Börse zählt er.
    func testUsablePrice() throws {
        let now: Int64 = 1_800_000_000_000
        let cases = TestSupport.list(try data()["usablePrice"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            // "exchangeTime" direkt (0 = unbekannt) oder als Alter "exchangeAgeMillis" (null = Börse liefert keine Zeit)
            let exchangeTime: Int64? = c.keys.contains("exchangeTime")
                ? TestSupport.number(c["exchangeTime"]).map { Int64($0) }
                : TestSupport.number(c["exchangeAgeMillis"]).map { now - Int64($0) }
            let used = DepegAlarm.usablePrice(last: TestSupport.number(c["last"]), exchangeTime: exchangeTime, now: now)
            if let expected = TestSupport.number(c["expected"]) {
                XCTAssertEqual(used ?? .nan, expected, accuracy: 1e-12, "\(c)")
            } else {
                XCTAssertNil(used, "\(c)")
            }
        }
    }

    func testNotPostedKeepsArmed() {
        XCTAssertTrue(DepegAlarm.armedAfter(fire: true, posted: false, armedBefore: true, next: false))
    }

    func testMainExchange() throws {
        for c in TestSupport.list(try data()["mainExchange"]) {
            let keys = c["keys"] as? [String] ?? []
            XCTAssertEqual(DepegAlarm.mainExchange(keys), c["expected"] as? String, "\(c)")
            XCTAssertEqual(DepegAlarm.exchangeUrl(keys), c["url"] as? String, "\(c)")
        }
    }

    /// Ältere Einstellungen ohne die Felder: aus, 0.5 %; unbekannte Schwelle → Standard.
    func testSettingsDefaults() throws {
        let old = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertFalse(old.depegAlarm)
        XCTAssertEqual(old.depegThresholdTenths, 5)
        let odd = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"depegAlarm":true,"depegThresholdTenths":7}"#.utf8))
        XCTAssertTrue(odd.depegAlarm)
        XCTAssertEqual(odd.depegThresholdTenths, 5)
        XCTAssertFalse(AppSettings().depegAlarm)
    }

    func testUrlsAreHttps() {
        for entry in DepegAlarm.exchangeUrls { XCTAssertTrue(entry.url.hasPrefix("https://"), entry.url) }
        XCTAssertTrue(DepegAlarm.exchangeUrls.contains { $0.key == DepegAlarm.fallbackExchange })
    }

    /// Messen mit Alarm oder Portfolio (USDT zum Marktkurs braucht den Live-Kurs auch ohne Alarm). Wie Android.
    func testShouldMeasure() {
        XCTAssertTrue(DepegAlarm.shouldMeasure(depegAlarm: true, portfolioEnabled: false, hasPortfolioData: false))
        XCTAssertTrue(DepegAlarm.shouldMeasure(depegAlarm: false, portfolioEnabled: true, hasPortfolioData: true))
        XCTAssertTrue(DepegAlarm.shouldMeasure(depegAlarm: true, portfolioEnabled: true, hasPortfolioData: false))
        // Portfolio aus oder leer: ohne Alarm keine Anfrage
        XCTAssertFalse(DepegAlarm.shouldMeasure(depegAlarm: false, portfolioEnabled: true, hasPortfolioData: false))
        XCTAssertFalse(DepegAlarm.shouldMeasure(depegAlarm: false, portfolioEnabled: false, hasPortfolioData: true))
        XCTAssertFalse(DepegAlarm.shouldMeasure(depegAlarm: false, portfolioEnabled: false, hasPortfolioData: false))
        // Fiat-Alarm bzw. ≈ Umrechnung über USDT: auch ohne Alarm und Portfolio messen
        XCTAssertTrue(DepegAlarm.shouldMeasure(depegAlarm: false, portfolioEnabled: false, hasPortfolioData: false,
                                               fiatConversion: true))
    }
}
