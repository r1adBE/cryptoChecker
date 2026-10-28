import XCTest
@testable import CryptoChecker

/// Wie die `hourStats_*`-Tests in `ActivityAnalyzerTest.kt`: Stunden-Kennzahlen für «Ungewöhnliche
/// Aktivität» nur aus Renditen über genau eine Stunde (Lücken wie bei Coinbase), ausgerichtet auf
/// die laufende Stunde.
final class ActivityHourStatsTests: XCTestCase {

    private let h: Int64 = 3_600_000
    private var t0: Int64 { 1_700_000_000_000 - (1_700_000_000_000 % h) }
    /// Mitten in der laufenden (27.) Stunde.
    private var now: Int64 { t0 + 26 * h + 30 * 60_000 }

    /// Kerzen aus Schlusskursen; Eröffnung = voriger Schluss, letzte Kerze = laufende Stunde.
    private func candles(_ closes: [Double], volumes: [Double]? = nil) -> [MarketCandle] {
        let vols = volumes ?? closes.map { _ in 100 }
        return closes.indices.map { i in
            let open = i == 0 ? closes[0] : closes[i - 1]
            return MarketCandle(openTime: t0 + Int64(i) * h, open: open, high: max(open, closes[i]),
                                low: min(open, closes[i]), close: closes[i], volume: vols[i])
        }
    }

    /// Wie `candles`, aber ohne die Kerzen an den Stellen `missing` (Stunde ohne Handel).
    private func withGaps(_ closes: [Double], missing: Set<Int>, volumes: [Double]? = nil) -> [MarketCandle] {
        candles(closes, volumes: volumes).enumerated().filter { !missing.contains($0.offset) }.map(\.element)
    }

    /// Schlusskurse aus Renditen in %.
    private func fromReturns(_ returns: [Double], start: Double = 100) -> [Double] {
        var closes = [start]
        for r in returns { closes.append(closes.last! * (1 + r / 100)) }
        return closes
    }

    private func window(_ step: Double) -> [Double] { (0..<24).map { $0 % 2 == 0 ? step : -step } }

    func testNeedsEnoughReturns() {
        // 22 Kerzen: nur 19 Vergleichsrenditen → keine Kennzahlen; 23 Kerzen: 20 genügen
        XCTAssertNil(ActivityAnalyzer.hourStats(candles(fromReturns(Array(window(0.5).suffix(19)) + [1, 0]))))
        XCTAssertNotNil(ActivityAnalyzer.hourStats(candles(fromReturns(Array(window(0.5).suffix(20)) + [1, 0]))))
        XCTAssertNotNil(ActivityAnalyzer.hourStats(candles(fromReturns(window(0.5) + [1, 0]))))
        XCTAssertNil(ActivityAnalyzer.hourStats([]))
    }

    func testGapBeforeLastHourIsNotAnHourMove() throws {
        // Vier Stunden ohne Handel vor der letzten abgeschlossenen Stunde: kein ⚡ aus dem Sprung
        let returns = window(0.5) + [1.5, 1.5, 1.5, 1.5] + [0, 0]
        let inRunningHour = t0 + 30 * h + 30 * 60_000
        let stats = ActivityAnalyzer.hourStats(withGaps(fromReturns(returns), missing: [25, 26, 27, 28]),
                                               now: inRunningHour)
        XCTAssertNil(stats)
        XCTAssertTrue(ActivityAnalyzer.signals(stats: stats, fundingPercent: nil, oiChangePercent: nil,
                                               oiMinutes: nil, now: inRunningHour).isEmpty)
        let full = try XCTUnwrap(ActivityAnalyzer.hourStats(candles(fromReturns(returns)), now: inRunningHour))
        XCTAssertEqual(full.movePercent, 0, accuracy: 1e-9)
    }

    func testGapsInWindowSkipOnlyThoseReturns() throws {
        let closes = fromReturns(window(0.5) + [5, 0])
        let stats = try XCTUnwrap(ActivityAnalyzer.hourStats(withGaps(closes, missing: [5, 12]), now: now))
        XCTAssertEqual(stats.movePercent, 5, accuracy: 1e-9)
        XCTAssertEqual(stats.candleOpenTime, t0 + 25 * h)
        // Drei Lücken: nur noch 18 gültige Renditen → keine Kennzahlen
        XCTAssertNil(ActivityAnalyzer.hourStats(withGaps(closes, missing: [3, 7, 11]), now: now))
    }

    func testAlignsToCurrentHour() throws {
        let closes = fromReturns(window(0.5) + [5, 0])
        // Laufende Stunde fehlt (noch kein Handel): die letzte Kerze ist die abgeschlossene Stunde
        let withoutRunning = Array(candles(closes).dropLast())
        let inNextHour = t0 + 26 * h + 10 * 60_000
        let stats = try XCTUnwrap(ActivityAnalyzer.hourStats(withoutRunning, now: inNextHour))
        XCTAssertEqual(stats.movePercent, 5, accuracy: 1e-9)
        XCTAssertEqual(stats.candleOpenTime, t0 + 25 * h)
        // Ohne «jetzt» gilt die letzte Kerze als laufend (Standard)
        XCTAssertEqual(ActivityAnalyzer.hourStats(withoutRunning)?.candleOpenTime, t0 + 24 * h)
        // Neueste volle Stunde fehlt ganz: keine Bewegung, kein PRICE_MOVE
        XCTAssertNil(ActivityAnalyzer.hourStats(withoutRunning, now: inNextHour + h))
        // Uhr geht nach (vor dem Beginn der laufenden Kerze): wie laufende Stunde
        XCTAssertEqual(ActivityAnalyzer.hourStats(candles(closes), now: t0 + 26 * h - 60_000)?.candleOpenTime,
                       t0 + 25 * h)
    }

    func testMissingHoursCountAsEmptyVolume() throws {
        let closes = fromReturns(window(0.5) + [1, 0])
        let volumes = closes.indices.map { $0 == closes.count - 2 ? 500.0 : 100.0 }
        let missing: Set<Int> = [4, 5, 6]
        func zeroAt(_ idx: Set<Int>) -> [Double] { volumes.enumerated().map { idx.contains($0.offset) ? 0 : $0.element } }
        // Drei fehlende Stunden: Schnitt trotzdem über 24 Stunden (21 × 100 / 24)
        let three = try XCTUnwrap(ActivityAnalyzer.hourStats(withGaps(closes, missing: missing, volumes: volumes), now: now))
        XCTAssertEqual(try XCTUnwrap(three.volumeRatio), 500 / (2100.0 / 24), accuracy: 1e-9)
        // Drei fehlende plus drei ohne Umsatz = 6 leere Stunden: noch erlaubt
        let six = try XCTUnwrap(ActivityAnalyzer.hourStats(withGaps(closes, missing: missing, volumes: zeroAt([10, 14, 18])), now: now))
        XCTAssertNotNil(six.volumeRatio)
        // Drei fehlende plus vier ohne Umsatz = 7 leere Stunden: kein Verhältnis
        let seven = try XCTUnwrap(ActivityAnalyzer.hourStats(withGaps(closes, missing: missing, volumes: zeroAt([10, 14, 18, 20])), now: now))
        XCTAssertNil(seven.volumeRatio)
    }
}
