import Accessibility
import SwiftUI

/// Audio Graph für VoiceOver («Diagramm-Details»): ein Kurs- oder Wertverlauf als Datenreihe mit Zeitachse
/// (x, ms seit 1970) und Kurs- bzw. Wertachse (y). Die Zusammenfassung des Charts (Start, Ende, Hoch, Tief) bleibt
/// das Label des Elements und steht zusätzlich als `summary` im Descriptor. Android sagt die Punkte über die Aktionen
/// «Nächster Punkt» / «Vorheriger Punkt» an (`ChartPointSemantics.kt`).
struct ChartAudioGraph: AXChartDescriptorRepresentable {
    struct Point: Equatable, Sendable {
        let millis: Int64
        let value: Double
    }

    /// Eine Linie (z. B. «CHF» und «USDT» im Vergleich).
    struct Series: Equatable, Sendable {
        let name: String
        let points: [Point]
    }

    /// Höchstens so viele Punkte im Descriptor (gleichmässig ausgedünnt, erster und letzter bleiben).
    static let maxPoints = 400

    let title: String
    let summary: String?
    let xTitle: String
    let yTitle: String
    let series: [Series]
    /// Zeit eines Punkts für die Ansage («Di 14:00», «12. März 2026»).
    let timeText: (Int64) -> String
    /// Kurs bzw. Wert für die Ansage («98’450 USDT»).
    let valueText: (Double) -> String

    init(title: String, summary: String?, xTitle: String, yTitle: String, series: [Series],
         timeText: @escaping (Int64) -> String, valueText: @escaping (Double) -> String) {
        self.title = title
        self.summary = summary
        self.xTitle = xTitle
        self.yTitle = yTitle
        self.series = series
        self.timeText = timeText
        self.valueText = valueText
    }

    /// Eine Linie `seriesName` mit `points`.
    init(title: String, summary: String?, seriesName: String, xTitle: String, yTitle: String, points: [Point],
         timeText: @escaping (Int64) -> String, valueText: @escaping (Double) -> String) {
        self.init(title: title, summary: summary, xTitle: xTitle, yTitle: yTitle,
                  series: [Series(name: seriesName, points: points)], timeText: timeText, valueText: valueText)
    }

    /// Erst gebaut, wenn VoiceOver danach fragt. Die Achsen beschreiben Werte über vorab formatierte Texte der Punkte
    /// (nächster Punkt), damit die Beschreibungs-Closures keine Formatierer festhalten.
    func makeChartDescriptor() -> AXChartDescriptor {
        let shown = series.map { Series(name: $0.name, points: Self.thinned($0.points, max: Self.maxPoints)) }
        let all = shown.flatMap(\.points)
        let xs = Self.range(all.map { Double($0.millis) })
        let ys = Self.range(all.map(\.value))
        let times = Array(Set(all.map(\.millis))).sorted()
        let timeKeys = times.map { Double($0) }
        let timeLabels = times.map { timeText($0) }
        let byValue = (all.map(\.value) + [ys.lowerBound, ys.upperBound]).filter(\.isFinite).sorted()
        let valueLabels = byValue.map { valueText($0) }
        let xAxis = AXNumericDataAxisDescriptor(title: xTitle, range: xs, gridlinePositions: []) { value in
            ChartAudioGraph.nearestLabel(value, keys: timeKeys, labels: timeLabels)
        }
        let yAxis = AXNumericDataAxisDescriptor(title: yTitle, range: ys, gridlinePositions: []) { value in
            ChartAudioGraph.nearestLabel(value, keys: byValue, labels: valueLabels)
        }
        let descriptors = shown.map { line in
            AXDataSeriesDescriptor(name: line.name, isContinuous: true,
                                   dataPoints: line.points.map { AXDataPoint(x: Double($0.millis), y: $0.value) })
        }
        return AXChartDescriptor(title: title, summary: summary, xAxis: xAxis, yAxis: yAxis, additionalAxes: [],
                                 series: descriptors)
    }

    func updateChartDescriptor(_ descriptor: AXChartDescriptor) {}

    /// Höchstens `max` Punkte: gleichmässig verteilte Indizes, erster und letzter immer dabei.
    static func thinned(_ points: [Point], max: Int) -> [Point] {
        guard max >= 2, points.count > max else { return points }
        let last = points.count - 1
        return (0..<max).map { step in points[Int((Double(step) * Double(last) / Double(max - 1)).rounded())] }
    }

    /// Text des Schlüssels in `keys` (aufsteigend sortiert), der `value` am nächsten liegt; leer ohne Schlüssel.
    nonisolated static func nearestLabel(_ value: Double, keys: [Double], labels: [String]) -> String {
        guard !keys.isEmpty, keys.count == labels.count else { return "" }
        var low = 0
        var high = keys.count - 1
        while low < high {
            let mid = (low + high) / 2
            if keys[mid] < value { low = mid + 1 } else { high = mid }
        }
        if low > 0, abs(keys[low - 1] - value) <= abs(keys[low] - value) { return labels[low - 1] }
        return labels[low]
    }

    /// Bereich einer Achse; ohne Spanne (ein Wert, alle gleich) ein kleiner Rand, damit die Achse gültig bleibt.
    static func range(_ values: [Double]) -> ClosedRange<Double> {
        let finite = values.filter(\.isFinite)
        guard let low = finite.min(), let high = finite.max() else { return 0...1 }
        if high > low { return low...high }
        let pad = Swift.max(abs(low) * 0.01, 1)
        return (low - pad)...(high + pad)
    }
}
