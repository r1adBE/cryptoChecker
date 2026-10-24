import XCTest
@testable import CryptoChecker

/// Neumess-Logik der Skalierungsprüfung (`PerformanceTiming.measureScaling`) mit vorgegebenen Messwerten statt
/// echter Zeiten: Erst nach überschrittener Grenze wird neu gemessen, dann zuerst die grosse Menge, und je Grösse
/// zählt die kürzere der beiden Messungen. Läuft als gewöhnlicher Unit-Test (kein `*PerformanceTests`, also nicht im
/// Release-Leistungslauf und nicht von `CC_SKIP_PERFORMANCE` betroffen).
final class PerformanceTimingSelfTests: XCTestCase {
    private typealias T = PerformanceTiming

    private let sizes = PerformanceTiming.Sizes(small: 500, large: 2_000, unit: "Paare")

    /// Misst mit den Werten aus `values` (der Reihe nach) und gibt die Reihenfolge der gemessenen Grössen zurück.
    private func outcome(_ values: [Double]) -> (T.ScaleOutcome, [Int], [String]) {
        var queue = values
        var order: [Int] = []
        var lines: [String] = []
        let log: (String) -> Void = { lines.append($0) }
        let result = T.measureScaling("Selbsttest", limitMillis: 3_000, sizes: sizes, log: log) { n in
            order.append(n)
            return queue.removeFirst()
        }
        return (result, order, lines)
    }

    func testWithinLimitMeasuresOnce() {
        let (result, order, lines) = outcome([18, 80])
        XCTAssertFalse(result.remeasured)
        XCTAssertTrue(result.scales)
        XCTAssertEqual(order, [500, 2_000])
        XCTAssertEqual(lines.count, 1)
        XCTAssertEqual(result.scaleLimit, T.scaleLimit(smallMillis: 18))
    }

    func testOutlierIsRemeasuredLargeFirst() {
        // Erste grosse Messung ein Ausreisser (122 ms > 5 × 18 + 20), die zweite normal
        let (result, order, lines) = outcome([18, 122, 75, 20])
        XCTAssertTrue(result.remeasured)
        XCTAssertEqual(order, [500, 2_000, 2_000, 500])
        XCTAssertEqual(result.largeMillis, 75)
        XCTAssertEqual(result.smallMillis, 18)
        XCTAssertTrue(result.scales)
        XCTAssertEqual(lines.count, 2)
        XCTAssertTrue(lines[0].contains("neu gemessen"))
    }

    func testShorterOfBothMeasurementsCountsPerSize() {
        let (result, _, _) = outcome([30, 200, 160, 25])
        XCTAssertEqual(result.smallMillis, 25)
        XCTAssertEqual(result.largeMillis, 160)
        XCTAssertEqual(result.scaleLimit, T.scaleLimit(smallMillis: 25))
        XCTAssertFalse(result.scales)
    }

    func testRealGrowthStillFails() {
        // Quadratisch (×16) auch beim zweiten Mal: bleibt ein Fehlschlag
        let (result, _, _) = outcome([18, 288, 290, 18])
        XCTAssertTrue(result.remeasured)
        XCTAssertFalse(result.scales)
    }

    func testMedianRunsWarmupAndMeasuredRuns() {
        var calls = 0
        let median = T.medianMillis(warmup: 2, runs: 3) { calls += 1 }
        XCTAssertEqual(calls, 5)
        XCTAssertGreaterThanOrEqual(median, 0)
    }
}
