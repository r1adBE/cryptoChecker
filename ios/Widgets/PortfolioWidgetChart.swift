import SwiftUI

/// Chart des Portfolio-Widgets wie die Charts der App (Aktionsblatt, Wertverlauf) — wie
/// `WidgetChartRenderer.drawPortfolioChart` (Android): drei zarte waagrechte Hilfslinien (Hoch,
/// Mitte, Tief), feine senkrechte Linien an Kalendergrenzen, Kerzen in den Kursfarben oder Linie
/// 1.5 pt mit zarter Fläche und Punkt am letzten Wert, der höchste Wert als Punkt mit «Hoch …».
/// Lage aus `PortfolioWidgetChartGeometry` (Shared). Keine Beschriftungsspalte.
struct PortfolioValueChart: View {
    let plan: PortfolioChartPlan
    let palette: WidgetPalette
    /// Farbe der Linie (Richtung der Veränderung).
    let lineColor: Color
    /// «Beträge verbergen»: «Hoch •••».
    var hidden = false
    var lineWidth: CGFloat = 1.5
    var dot: CGFloat = 2.5
    /// Zone des Zeitgitters — dieselbe wie für die Tage der Balken (`PortfolioEntry.chartTimeZone`).
    var timeZone: TimeZone = .current
    @Environment(\.displayScale) private var displayScale

    private static let labelSize: CGFloat = 8.5

    var body: some View {
        let px = 1 / Swift.max(displayScale, 1)
        let zone = timeZone
        Canvas { context, size in
            draw(&context, size: size, px: px, timeZone: zone)
        }
        // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityHidden(true)
    }

    private func highText(_ value: Double) -> String {
        L("portfolio_chart_high", hidden ? PortfolioInsights.hidden : PriceFormat.price(value))
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, px: CGFloat, timeZone: TimeZone) {
        let labelHeight = Double(Self.labelSize) * 1.25
        let inset = Double(dot + lineWidth / 2)
        guard let geo = PortfolioWidgetChartGeometry(
            bars: plan.bars, type: plan.type, axisFrom: plan.axisFrom, axisTo: plan.axisTo,
            width: Double(size.width), height: Double(size.height), inset: inset,
            topInset: Swift.max(labelHeight / 2, Double(dot)) + 1, bottomInset: Double(dot) + 1) else { return }
        drawGuides(&context, geo: geo, size: size, px: px, timeZone: timeZone)
        switch plan.type {
        case .candles: drawCandles(&context, geo: geo, px: px)
        case .line: drawLine(&context, geo: geo)
        }
        drawHigh(&context, geo: geo, size: size, labelHeight: labelHeight)
    }

    /// Zeitgitter (12 %) und drei Stufen (20 %), bei hohem Kontrast 35 % — wie die App.
    private func drawGuides(_ context: inout GraphicsContext, geo: PortfolioWidgetChartGeometry, size: CGSize,
                            px: CGFloat, timeZone: TimeZone) {
        let hairline = Swift.max(px, 0.6)
        let grid = palette.secondary.opacity(palette.highContrast ? 0.35 : 0.12)
        let level = palette.secondary.opacity(palette.highContrast ? 0.35 : 0.20)
        for x in geo.gridXs(grid: plan.grid, timeZone: timeZone) {
            var path = Path()
            path.move(to: CGPoint(x: x, y: 0))
            path.addLine(to: CGPoint(x: x, y: Double(size.height)))
            context.stroke(path, with: .color(grid), lineWidth: hairline)
        }
        for y in geo.levelYs {
            var path = Path()
            path.move(to: CGPoint(x: geo.plotLeft, y: y))
            path.addLine(to: CGPoint(x: geo.plotRight, y: y))
            context.stroke(path, with: .color(level), lineWidth: hairline)
        }
    }

    private func drawCandles(_ context: inout GraphicsContext, geo: PortfolioWidgetChartGeometry, px: CGFloat) {
        for c in geo.candles(minSize: Double(px)) {
            let color = c.up ? palette.up : palette.down
            let wick = Swift.min(1, c.bodyWidth)
            context.fill(Path(CGRect(x: c.x - wick / 2, y: c.wickTop, width: wick, height: c.wickBottom - c.wickTop)),
                         with: .color(color))
            context.fill(Path(CGRect(x: c.x - c.bodyWidth / 2, y: c.bodyTop, width: c.bodyWidth,
                                     height: c.bodyBottom - c.bodyTop)), with: .color(color))
        }
    }

    private func drawLine(_ context: inout GraphicsContext, geo: PortfolioWidgetChartGeometry) {
        let points = geo.linePoints.map { CGPoint(x: $0.x, y: $0.y) }
        guard let first = points.first, let last = points.last else { return }
        var line = Path()
        line.move(to: first)
        for p in points.dropFirst() { line.addLine(to: p) }
        var fill = line
        fill.addLine(to: CGPoint(x: last.x, y: geo.plotBottom))
        fill.addLine(to: CGPoint(x: first.x, y: geo.plotBottom))
        fill.closeSubpath()
        context.fill(fill, with: .color(lineColor.opacity(40.0 / 255.0)))
        context.stroke(line, with: .color(lineColor),
                       style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
        let r = dot
        context.fill(Path(ellipseIn: CGRect(x: last.x - r, y: last.y - r, width: 2 * r, height: 2 * r)),
                     with: .color(lineColor))
    }

    /// Höchster Wert: Punkt mit Rand in der Widget-Fläche, daneben «Hoch …» auf halbtransparenter Fläche.
    private func drawHigh(_ context: inout GraphicsContext, geo: PortfolioWidgetChartGeometry, size: CGSize,
                          labelHeight: Double) {
        let marker = geo.highPoint
        let center = CGPoint(x: marker.x, y: marker.y)
        let ring: CGFloat = 3.5
        context.fill(Path(ellipseIn: CGRect(x: center.x - ring, y: center.y - ring, width: 2 * ring, height: 2 * ring)),
                     with: .color(palette.base))
        context.fill(Path(ellipseIn: CGRect(x: center.x - dot, y: center.y - dot, width: 2 * dot, height: 2 * dot)),
                     with: .color(palette.text))
        let text = Text(highText(geo.high))
            .font(.system(size: Self.labelSize, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(palette.text)
        let resolved = context.resolve(text)
        let measured = resolved.measure(in: size)
        let pad = 2.0
        let left = PortfolioWidgetChartGeometry.labelLeft(markerX: marker.x, labelWidth: Double(measured.width),
                                                          left: pad, right: Double(size.width) - pad, gap: 5)
        let height = Swift.max(labelHeight, Double(measured.height))
        let top = Swift.min(Swift.max(0, marker.y - height / 2), Swift.max(0, Double(size.height) - height))
        let backing = CGRect(x: left - pad, y: top, width: Double(measured.width) + 2 * pad, height: height)
        context.fill(Path(roundedRect: backing, cornerRadius: 3), with: .color(palette.base.opacity(0.85)))
        context.draw(resolved, at: CGPoint(x: left, y: top + height / 2), anchor: .leading)
    }
}
