import Accessibility
import XCTest
@testable import CryptoChecker

/// Audio Graph der Kursdiagramme (`ChartAudioGraph`): eine Serie, Zeitachse (x) und Kursachse (y) mit Texten der
/// Punkte. Gegenstück auf Android: `ChartPointSemanticsTest` (Aktionen «Nächster/Vorheriger Punkt»).
@MainActor
final class ChartAudioGraphTests: XCTestCase {
    private let hour: Int64 = 3_600_000
    private let start: Int64 = 1_700_000_000_000

    private func graph(_ values: [Double]) -> ChartAudioGraph {
        let start = self.start
        let hour = self.hour
        let points = values.enumerated().map { index, value in
            ChartAudioGraph.Point(millis: start + Int64(index) * hour, value: value)
        }
        return ChartAudioGraph(
            title: "BTC/USDT", summary: "Chart 24 hours", seriesName: "BTC/USDT",
            xTitle: "Time", yTitle: "Price", points: points,
            timeText: { "t\(($0 - start) / hour)" },
            valueText: { "\(Int($0.rounded())) USDT" })
    }

    func testDescriptorHasSeriesAndBothAxes() throws {
        let descriptor = graph([100, 120, 90, 110]).makeChartDescriptor()
        XCTAssertEqual(descriptor.title, "BTC/USDT")
        XCTAssertEqual(descriptor.summary, "Chart 24 hours")
        XCTAssertEqual(descriptor.series.count, 1)
        let series = try XCTUnwrap(descriptor.series.first)
        XCTAssertEqual(series.name, "BTC/USDT")
        XCTAssertTrue(series.isContinuous)
        XCTAssertEqual(series.dataPoints.count, 4)
        XCTAssertEqual(series.dataPoints[1].xValue.number, Double(start + hour))
        XCTAssertEqual(series.dataPoints[2].yValue?.number, 90)

        let xAxis = try XCTUnwrap(descriptor.xAxis as? AXNumericDataAxisDescriptor)
        XCTAssertEqual(xAxis.title, "Time")
        XCTAssertEqual(xAxis.range, Double(start)...Double(start + 3 * hour))
        XCTAssertEqual(xAxis.valueDescriptionProvider(Double(start + 2 * hour)), "t2")

        let yAxis = try XCTUnwrap(descriptor.yAxis)
        XCTAssertEqual(yAxis.title, "Price")
        XCTAssertEqual(yAxis.range, 90...120)
        XCTAssertEqual(yAxis.valueDescriptionProvider(120), "120 USDT")
        XCTAssertEqual(yAxis.valueDescriptionProvider(91), "90 USDT")
    }

    func testFlatSeriesKeepsValidAxis() {
        let range = ChartAudioGraph.range([50, 50, 50])
        XCTAssertLessThan(range.lowerBound, 50)
        XCTAssertGreaterThan(range.upperBound, 50)
        XCTAssertEqual(ChartAudioGraph.range([]), 0...1)
        XCTAssertEqual(ChartAudioGraph.range([.nan, 2, 4]), 2...4)
    }

    func testThinningKeepsFirstAndLast() {
        let points = (0..<1000).map { ChartAudioGraph.Point(millis: Int64($0), value: Double($0)) }
        let thinned = ChartAudioGraph.thinned(points, max: ChartAudioGraph.maxPoints)
        XCTAssertEqual(thinned.count, ChartAudioGraph.maxPoints)
        XCTAssertEqual(thinned.first, points.first)
        XCTAssertEqual(thinned.last, points.last)
        XCTAssertEqual(ChartAudioGraph.thinned(Array(points.prefix(10)), max: ChartAudioGraph.maxPoints).count, 10)
        let millis = thinned.map(\.millis)
        XCTAssertEqual(millis, millis.sorted())
    }

    func testNearestLabel() {
        let keys: [Double] = [10, 20, 30]
        let labels = ["a", "b", "c"]
        XCTAssertEqual(ChartAudioGraph.nearestLabel(0, keys: keys, labels: labels), "a")
        XCTAssertEqual(ChartAudioGraph.nearestLabel(24, keys: keys, labels: labels), "b")
        XCTAssertEqual(ChartAudioGraph.nearestLabel(26, keys: keys, labels: labels), "c")
        XCTAssertEqual(ChartAudioGraph.nearestLabel(99, keys: keys, labels: labels), "c")
        XCTAssertEqual(ChartAudioGraph.nearestLabel(5, keys: [], labels: []), "")
    }

    /// Vergleich: zwei Linien in einem Descriptor, gemeinsame Achsen über beide.
    func testTwoSeriesShareAxes() throws {
        let millis: [Int64] = [start, start + hour, start + 2 * hour]
        let chf = zip(millis, [0, 2.5, 1.0]).map { ChartAudioGraph.Point(millis: $0, value: $1) }
        let usdt = zip(millis, [0, -1.5, 4.0]).map { ChartAudioGraph.Point(millis: $0, value: $1) }
        let lines = [ChartAudioGraph.Series(name: "CHF", points: chf),
                     ChartAudioGraph.Series(name: "USDT", points: usdt)]
        let graph = ChartAudioGraph(
            title: "Portfolio", summary: nil, xTitle: "Time", yTitle: "Change", series: lines,
            timeText: { "\($0)" }, valueText: { "\($0)%" })
        let descriptor = graph.makeChartDescriptor()
        XCTAssertEqual(descriptor.series.map(\.name), ["CHF", "USDT"])
        XCTAssertEqual(descriptor.series.map(\.dataPoints.count), [3, 3])
        let yAxis = try XCTUnwrap(descriptor.yAxis)
        XCTAssertEqual(yAxis.range, -1.5...4.0)
        XCTAssertEqual(yAxis.title, "Change")
    }
}
