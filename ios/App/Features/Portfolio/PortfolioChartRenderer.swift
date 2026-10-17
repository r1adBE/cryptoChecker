import SwiftUI

// Zeichnen der Portfolio-Charts: Stil, Rahmen und Masse (`PortfolioChartFrame`), Achse, Raster, Linien,
// Kerzen, Höchstmarke und Markierung beim Wischen.
// Eigene Datei, weil es reine Zeichenlogik ohne Zustand ist; Verlaufs- und Vergleichs-Chart nutzen sie
// gemeinsam (wie `PortfolioChartRenderer.kt`).

/// Farben und Schriften des Wertverlauf-Charts (wie `ChartPaint`, Android).
struct PortfolioChartStyle {
    let accent: Color
    let onAccent: Color
    let secondary: Color
    let marker: Color
    /// Hintergrund der Karte (hinter «Hoch …»).
    let background: Color
    let highContrast: Bool
    let labelFont: Font
    let tagFont: Font
}

/// Lage der Zeichenfläche — wie `ChartFrame` (Android): rechts eine Spalte für Beschriftungen und
/// Etikett, jeder Punkt bzw. jede Kerze in einem gleich breiten Slot.
struct PortfolioChartFrame: Equatable {
    let plotLeft: CGFloat
    let plotRight: CGFloat
    let top: CGFloat
    let bottom: CGFloat
    let count: Int
    let low: Double
    let high: Double

    var slot: CGFloat { count > 0 ? (plotRight - plotLeft) / CGFloat(count) : 0 }

    /// Mitte des Slots `index`.
    func x(_ index: Int) -> CGFloat { plotLeft + (CGFloat(index) + 0.5) * slot }

    func y(_ value: Double) -> CGFloat {
        guard high > low else { return (top + bottom) / 2 }
        // In Schritten mit festen Typen (Double/CGFloat gemischt ist für den Type-Checker teuer)
        let fraction = CGFloat((value - low) / (high - low))
        let height: CGFloat = bottom - top
        return bottom - fraction * height
    }
}

/// Gemessener Rahmen samt Texten der Achse (Stufen unten, Mitte, oben und Etikett).
struct PortfolioChartPrepared {
    let frame: PortfolioChartFrame
    let levels: [GraphicsContext.ResolvedText]
    let tag: GraphicsContext.ResolvedText
    let labelHeight: CGFloat
    let tagSize: CGSize
}

/// Lage der zuletzt gezeichneten Fläche für das Ziehen (kein beobachteter Zustand).
final class PortfolioChartBox {
    var frame: PortfolioChartFrame?
}

/// Zeichenregeln des Wertverlauf-Charts (Gitter, Achse, Linie, Kerzen, Hoch, Markierung).
enum PortfolioChartRenderer {
    static let tagPadX: CGFloat = 3
    static let tagPadY: CGFloat = 1
    /// Über so vielen Punkten beim Ziehen kein Ticken je Punkt — es wäre ein Dauersurren.
    static let maxTickPoints = 100

    /// Misst die Beschriftungen und legt den Rahmen an (Spalte höchstens 42 % der Breite); nil ohne Fläche.
    static func prepare(_ ctx: GraphicsContext, size: CGSize, count: Int, low: Double, high: Double,
                        format: (Double) -> String, tagText: String, style: PortfolioChartStyle) -> PortfolioChartPrepared? {
        guard count >= 2, size.width > 0, size.height > 0 else { return nil }
        let lv = PortfolioChartAxis.levels(low: low, high: high)
        var levels: [GraphicsContext.ResolvedText] = []
        for value in [lv.low, lv.mid, lv.high] {
            levels.append(ctx.resolve(Text(verbatim: format(value)).font(style.labelFont).foregroundStyle(style.secondary)))
        }
        let tag = ctx.resolve(Text(verbatim: tagText).font(style.tagFont).foregroundStyle(style.onAccent))
        let probe = CGSize(width: 1000, height: 1000)
        let sizes = levels.map { $0.measure(in: probe) }
        let tagSize = tag.measure(in: probe)
        let labelH = sizes.map(\.height).max() ?? tagSize.height
        let labelW = sizes.map(\.width).max() ?? 0
        let column = Swift.min(Swift.max(labelW, tagSize.width) + 2 * tagPadX + 3, size.width * 0.42)
        let edge = Swift.max(labelH, tagSize.height + 2 * tagPadY) / 2 + 1
        let frame = PortfolioChartFrame(plotLeft: 0, plotRight: Swift.max(1, size.width - column), top: edge,
                                        bottom: Swift.max(edge + 1, size.height - edge), count: count,
                                        low: low, high: high)
        return PortfolioChartPrepared(frame: frame, levels: levels, tag: tag, labelHeight: labelH, tagSize: tagSize)
    }

    /// Feine senkrechte Linien an Kalendergrenzen (`PortfolioChartAxis.gridDays`) und die drei Stufen.
    static func drawGrid(_ ctx: inout GraphicsContext, frame: PortfolioChartFrame, spans: [ClosedRange<Int>],
                         style: PortfolioChartStyle, px: CGFloat) {
        let hair = Swift.max(px, 0.5)
        var grid = Path()
        if let first = spans.first, let last = spans.last {
            for day in PortfolioChartAxis.gridDays(firstDay: first.lowerBound, lastDay: last.upperBound) {
                guard let pos = PortfolioChartAxis.slotPosition(spans, day: day) else { continue }
                let gx = frame.plotLeft + CGFloat(pos) * frame.slot
                guard gx > frame.plotLeft + px, gx < frame.plotRight - px else { continue }
                grid.move(to: CGPoint(x: gx, y: frame.top))
                grid.addLine(to: CGPoint(x: gx, y: frame.bottom))
            }
        }
        ctx.stroke(grid, with: .color(style.secondary.opacity(style.highContrast ? 0.35 : 0.14)), lineWidth: hair)
        let lv = PortfolioChartAxis.levels(low: frame.low, high: frame.high)
        var levels = Path()
        for value in [lv.low, lv.mid, lv.high] {
            let ly = frame.y(value)
            levels.move(to: CGPoint(x: frame.plotLeft, y: ly))
            levels.addLine(to: CGPoint(x: frame.plotRight, y: ly))
        }
        ctx.stroke(levels, with: .color(style.secondary.opacity(style.highContrast ? 0.35 : 0.22)), lineWidth: hair)
    }

    /// Etikett in der Akzentfarbe auf Höhe `y` (optional mit gestrichelter Linie) und die Beschriftungen
    /// der Stufen — eine Beschriftung, die das Etikett überdecken würde, entfällt.
    static func drawAxis(_ ctx: inout GraphicsContext, size: CGSize, prepared: PortfolioChartPrepared, y: CGFloat,
                         dashed: Bool, style: PortfolioChartStyle, px: CGFloat) {
        let frame = prepared.frame
        let lineY = Swift.min(Swift.max(y, frame.top), frame.bottom)
        if dashed {
            var line = Path()
            line.move(to: CGPoint(x: frame.plotLeft, y: lineY))
            line.addLine(to: CGPoint(x: frame.plotRight, y: lineY))
            ctx.stroke(line, with: .color(style.accent.opacity(style.highContrast ? 1 : 0.7)),
                       style: StrokeStyle(lineWidth: Swift.max(px, 0.75), dash: [2, 2]))
        }
        let tagW = prepared.tagSize.width + 2 * tagPadX
        let tagH = prepared.tagSize.height + 2 * tagPadY
        let tagCenter = Swift.min(Swift.max(lineY, tagH / 2), size.height - tagH / 2)
        let lv = PortfolioChartAxis.levels(low: frame.low, high: frame.high)
        let centers = [frame.y(lv.low), frame.y(lv.mid), frame.y(lv.high)]
        let visible = WidgetChartGeometry.visibleLabels(centers: centers, labelHeight: prepared.labelHeight,
                                                        tagCenter: tagCenter, tagHeight: tagH, compact: false,
                                                        flat: !(frame.high > frame.low))
        let textRight = size.width - tagPadX
        for i in 0..<3 where visible[i] {
            ctx.draw(prepared.levels[i], at: CGPoint(x: textRight, y: centers[i]), anchor: .trailing)
        }
        let tagRect = CGRect(x: size.width - tagW, y: tagCenter - tagH / 2, width: tagW, height: tagH)
        ctx.fill(Path(roundedRect: tagRect, cornerRadius: 3, style: .continuous), with: .color(style.accent))
        ctx.draw(prepared.tag, at: CGPoint(x: tagRect.midX, y: tagRect.midY), anchor: .center)
    }

    /// Pfad durch `values` (je Slot ein Wert, mittig).
    static func linePath(_ frame: PortfolioChartFrame, values: [Double]) -> Path {
        var path = Path()
        for (i, v) in values.enumerated() {
            let p = CGPoint(x: frame.x(i), y: frame.y(v))
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        return path
    }

    /// Linie mit zarter Fläche bis zur Grundlinie (wie die Linie im Aktionsblatt).
    static func drawLine(_ ctx: inout GraphicsContext, frame: PortfolioChartFrame, values: [Double], color: Color) {
        guard values.count >= 2 else { return }
        let line = linePath(frame, values: values)
        var area = line
        area.addLine(to: CGPoint(x: frame.x(values.count - 1), y: frame.bottom))
        area.addLine(to: CGPoint(x: frame.x(0), y: frame.bottom))
        area.closeSubpath()
        ctx.fill(area, with: .color(color.opacity(0.16)))
        ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
    }

    /// Kerzen: Körper 65 % des Slots, Docht höchstens 1 pt (wie im Aktionsblatt).
    static func drawCandles(_ ctx: inout GraphicsContext, frame: PortfolioChartFrame, candles: [PortfolioCandle],
                            up: Color, down: Color, px: CGFloat) {
        let bodyW = Swift.max(px, (frame.slot * 0.65 / px).rounded() * px)
        let wickW = Swift.max(px, Swift.min(1, bodyW * 0.3))
        for (i, candle) in candles.enumerated() {
            let color = candle.up ? up : down
            let cx = (frame.x(i) / px).rounded() * px
            var wick = Path()
            wick.move(to: CGPoint(x: cx, y: frame.y(candle.high)))
            wick.addLine(to: CGPoint(x: cx, y: frame.y(candle.low)))
            ctx.stroke(wick, with: .color(color), lineWidth: wickW)
            let yOpen = frame.y(candle.open)
            let yClose = frame.y(candle.close)
            var bodyTop = Swift.min(yOpen, yClose)
            var bodyH = abs(yOpen - yClose)
            if bodyH < px {
                bodyTop = (yOpen + yClose) / 2 - px / 2
                bodyH = px
            }
            ctx.fill(Path(CGRect(x: cx - bodyW / 2, y: bodyTop, width: bodyW, height: bodyH)), with: .color(color))
        }
    }

    /// Höchster Tageswert: kleiner Punkt in Slot `slot` auf `value` und «Hoch 12’345» daneben (rechts davon
    /// in der linken Hälfte, sonst links), senkrecht mittig, ganz in der Zeichenfläche.
    static func drawHigh(_ ctx: inout GraphicsContext, size: CGSize, frame: PortfolioChartFrame, slot: Int,
                         value: Double, text: String, style: PortfolioChartStyle) {
        let mx = frame.x(slot)
        let my = frame.y(value)
        ctx.fill(Path(ellipseIn: CGRect(x: mx - 3.5, y: my - 3.5, width: 7, height: 7)), with: .color(style.background))
        ctx.fill(Path(ellipseIn: CGRect(x: mx - 2.5, y: my - 2.5, width: 5, height: 5)), with: .color(style.marker))
        let label = ctx.resolve(Text(verbatim: text).font(style.labelFont.weight(.semibold)).foregroundStyle(style.marker))
        let s = label.measure(in: CGSize(width: 1000, height: 1000))
        let pad: CGFloat = 2
        let gap: CGFloat = 5
        let rightSide = mx < (frame.plotLeft + frame.plotRight) / 2
        let rawLeft = rightSide ? mx + gap : mx - gap - s.width
        let maxLeft = Swift.max(frame.plotLeft + pad, frame.plotRight - s.width - pad)
        let left = Swift.min(Swift.max(rawLeft, frame.plotLeft + pad), maxLeft)
        let top = Swift.min(Swift.max(my - s.height / 2, 0), Swift.max(0, size.height - s.height))
        let back = CGRect(x: left - pad, y: top, width: s.width + 2 * pad, height: s.height)
        ctx.fill(Path(roundedRect: back, cornerRadius: 3, style: .continuous), with: .color(style.background.opacity(0.85)))
        ctx.draw(label, at: CGPoint(x: left, y: top), anchor: .topLeading)
    }

    /// Ziehen: senkrechte Markierung und Punkt bei `value` in Slot `index`.
    static func drawMarker(_ ctx: inout GraphicsContext, frame: PortfolioChartFrame, index: Int, value: Double,
                           color: Color) {
        let mx = frame.x(index)
        var line = Path()
        line.move(to: CGPoint(x: mx, y: frame.top))
        line.addLine(to: CGPoint(x: mx, y: frame.bottom))
        ctx.stroke(line, with: .color(color.opacity(0.6)), lineWidth: 1)
        let my = frame.y(value)
        ctx.fill(Path(ellipseIn: CGRect(x: mx - 3.5, y: my - 3.5, width: 7, height: 7)), with: .color(color))
    }

    /// Beschriftung der Achse: kompakt wie im Aktionsblatt («12’345», «0.2576»); 0 als «0».
    static func axisValue(_ value: Double) -> String { value > 0 ? PriceFormat.price(value) : "0" }

    /// Gewinn/Verlust auf der Achse: mit Vorzeichen («+1’234», «−56.3»); praktisch 0 als «0». Wie Android.
    static func signedAxisValue(_ value: Double) -> String {
        if PortfolioFormat.isZero(value) { return "0" }
        return (value > 0 ? "+" : "−") + PriceFormat.price(abs(value))
    }

    /// Gestrichelte Nulllinie über die ganze Zeichenfläche (Gewinn/Verlust).
    static func drawZeroLine(_ ctx: inout GraphicsContext, frame: PortfolioChartFrame) {
        var zero = Path()
        let zy = frame.y(0)
        zero.move(to: CGPoint(x: frame.plotLeft, y: zy))
        zero.addLine(to: CGPoint(x: frame.plotRight, y: zy))
        ctx.stroke(zero, with: .color(AppColors.outline.opacity(0.7)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
    }

    /// Gewinn/Verlust als Linie: über der Nulllinie in `up`, darunter in `down` (je an der Nulllinie
    /// abgeschnitten), zarte Fläche bis zur Nulllinie — wie Android `drawPnlLine`.
    static func drawPnlLine(_ ctx: inout GraphicsContext, size: CGSize, frame: PortfolioChartFrame, values: [Double],
                            up: Color, down: Color) {
        guard values.count >= 2 else { return }
        let zy = frame.y(0)
        let line = linePath(frame, values: values)
        var area = line
        area.addLine(to: CGPoint(x: frame.x(values.count - 1), y: zy))
        area.addLine(to: CGPoint(x: frame.x(0), y: zy))
        area.closeSubpath()
        let stroke = StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round)
        for (rect, color) in [(CGRect(x: 0, y: 0, width: size.width, height: zy), up),
                              (CGRect(x: 0, y: zy, width: size.width, height: Swift.max(0, size.height - zy)), down)] {
            var part = ctx
            part.clip(to: Path(rect))
            part.fill(area, with: .color(color.opacity(0.16)))
            part.stroke(line, with: .color(color), style: stroke)
        }
    }

    /// Datum einer Kerze: Tag, Woche («6.–12. Okt. 2026») bzw. Monat («Okt. 2026»), Format der Sprache.
    static func candleDate(_ candle: PortfolioCandle, bucket: PortfolioCandleBucket) -> String {
        let last = LocalDay(epochDay: candle.lastDay).date
        switch bucket {
        case .day:
            return PortfolioFormat.date(last.millis)
        case .week:
            let f = DateIntervalFormatter()
            f.dateStyle = .medium
            f.timeStyle = .none
            return f.string(from: LocalDay(epochDay: candle.firstDay).date, to: last)
        case .month:
            // Nur beim Ziehen gebraucht: kein Zwischenspeicher nötig
            let f = DateFormatter()
            f.locale = Locale.current
            f.setLocalizedDateFormatFromTemplate("yMMM")
            return f.string(from: last)
        }
    }
}
