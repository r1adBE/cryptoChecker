import Foundation

// Geometrie des Portfolio-Charts — Spiegel von `PortfolioWidgetChartGeometry.kt` (Android). Ohne Widget-Typen.

/// Reine Geometrie des Portfolio-Charts — wie `PortfolioWidgetChartGeometry` (Android): drei zarte
/// Hilfslinien, Kerzen (65 % ihrer Zeit) oder Linie, x nach der Zeit über die Achse, Hoch-Markierung.
struct PortfolioWidgetChartGeometry {
    let bars: [PortfolioChartBar]
    let type: PriceChartType
    let plotLeft: Double
    let plotRight: Double
    let plotTop: Double
    let plotBottom: Double
    let low: Double
    let high: Double
    /// Achse: `low`...`high`, aber mindestens `WidgetChartGeometry.minSpan` der Mitte hoch (wie
    /// Einzel-Widget und Aktionsblatt) — ein ruhiger Tag sieht so nicht wie ein Absturz aus.
    let axisLevels: WidgetChartGeometry.Levels
    let axisStart: Int64
    let axisEnd: Int64

    /// nil mit weniger als zwei Balken.
    init?(bars: [PortfolioChartBar], type: PriceChartType, axisFrom: Int64? = nil, axisTo: Int64? = nil,
          width: Double, height: Double, inset: Double, topInset: Double, bottomInset: Double) {
        guard bars.count >= 2 else { return nil }
        self.bars = bars
        self.type = type
        plotLeft = inset
        plotRight = Swift.max(inset + 1, width - inset)
        plotTop = topInset
        plotBottom = Swift.max(topInset + 1, height - bottomInset)
        switch type {
        case .candles:
            low = bars.map(\.low).min() ?? 0
            high = bars.map(\.high).max() ?? 0
        case .line:
            low = bars.map(\.close).min() ?? 0
            high = bars.map(\.close).max() ?? 0
        }
        axisLevels = WidgetChartGeometry.widen(WidgetChartGeometry.Levels(low: low, high: high),
                                               minFraction: WidgetChartGeometry.minSpan)
        if let from = axisFrom, let to = axisTo, to > from {
            axisStart = from
            axisEnd = to
        } else {
            axisStart = bars[0].start
            axisEnd = bars.map(\.end).max() ?? bars[0].start
        }
    }

    /// Gesamtrichtung (letzter Schluss ≥ erster Wert).
    var up: Bool {
        let first = type == .candles ? bars[0].open : bars[0].close
        return bars[bars.count - 1].close >= first
    }

    /// Zeitpunkt → x, auf die Fläche begrenzt; ohne Zeitspanne nach der Lage `index`.
    func x(_ time: Int64, index: Int = 0) -> Double {
        let span = axisEnd - axisStart
        // In Schritten mit festen Typen (für den Type-Checker)
        let f: Double
        if span > 0 {
            let offset = Double(time - axisStart)
            f = offset / Double(span)
        } else {
            let last = Double(bars.count - 1)
            f = Double(index) / last
        }
        let clamped: Double = Swift.min(1, Swift.max(0, f))
        let width: Double = plotRight - plotLeft
        return plotLeft + clamped * width
    }

    func y(_ value: Double) -> Double {
        let axis = axisLevels
        let span = axis.high - axis.low
        guard span > 0 else { return (plotTop + plotBottom) / 2 }
        return plotBottom - (value - axis.low) / span * (plotBottom - plotTop)
    }

    /// y der drei Hilfslinien: Hoch, Mitte, Tief der Achse.
    var levelYs: [Double] {
        let axis = axisLevels
        return [y(axis.high), y(axis.mid), y(axis.low)]
    }

    /// Punkte der Linie (Schluss am Beginn des Balkens).
    var linePoints: [(x: Double, y: Double)] {
        bars.enumerated().map { i, b in (x: x(b.start, index: i), y: y(b.close)) }
    }

    /// Kerze in Punkten: Mitte, Körperbreite, Körper oben/unten, Docht oben/unten.
    struct Candle {
        let x: Double
        let bodyWidth: Double
        let bodyTop: Double
        let bodyBottom: Double
        let wickTop: Double
        let wickBottom: Double
        let up: Bool
    }

    /// Kerzen: mittig in ihrer Zeit, Körper 65 % davon (mindestens `minSize` breit und hoch).
    func candles(minSize: Double = 1) -> [Candle] {
        bars.enumerated().map { i, b in
            let xs = x(b.start, index: i)
            let xe = x(b.end, index: i)
            let yo = y(b.open)
            let yc = y(b.close)
            var top = Swift.min(yo, yc)
            var bottom = Swift.max(yo, yc)
            if bottom - top < minSize {
                bottom = Swift.min(top + minSize, plotBottom)
                top = bottom - minSize
            }
            return Candle(x: (xs + xe) / 2, bodyWidth: Swift.max(minSize, (xe - xs) * 0.65),
                          bodyTop: top, bodyBottom: bottom, wickTop: y(b.high), wickBottom: y(b.low), up: b.up)
        }
    }

    /// Balken mit dem höchsten Wert (Kerzen: Hoch, Linie: Schluss; bei Gleichstand der erste).
    var highIndex: Int {
        var best = 0
        for i in bars.indices {
            let v = type == .candles ? bars[i].high : bars[i].close
            let b = type == .candles ? bars[best].high : bars[best].close
            if v > b { best = i }
        }
        return best
    }

    /// Lage der Hoch-Markierung.
    var highPoint: (x: Double, y: Double) {
        let i = highIndex
        let b = bars[i]
        switch type {
        case .candles: return (x: (x(b.start, index: i) + x(b.end, index: i)) / 2, y: y(high))
        case .line: return (x: x(b.start, index: i), y: y(b.close))
        }
    }

    /// x der senkrechten Gitterlinien (wie im geteilten Chart), nur innerhalb der Fläche.
    func gridXs(grid: PriceChartRange, timeZone: TimeZone = .current) -> [Double] {
        WidgetChartGeometry.gridTimes(firstOpen: axisStart, endMillis: axisEnd, range: grid, timeZone: timeZone)
            .map { x($0) }
            .filter { $0 > plotLeft + 0.5 && $0 < plotRight - 0.5 }
    }

    /// Linker Rand der «Hoch …»-Beschriftung: rechts der Markierung in der linken Hälfte, sonst links; ganz drin.
    static func labelLeft(markerX: Double, labelWidth: Double, left: Double, right: Double, gap: Double) -> Double {
        let rightSide = markerX < (left + right) / 2
        let raw = rightSide ? markerX + gap : markerX - gap - labelWidth
        return Swift.min(Swift.max(raw, left), Swift.max(left, right - labelWidth))
    }
}
