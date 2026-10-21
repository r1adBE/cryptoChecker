import SwiftUI

// Vergleichs-Chart der Verlaufskarte: Wert in der gewählten Währung gegen USDT in Prozent (Währungseffekt).
// Eigene Datei, weil er eine eigene Ansicht mit eigener Legende ist (wie `PortfolioCompareChart.kt`).

// MARK: Vergleich in Prozent

/// Vergleich in Prozent seit dem Ausgangspunkt mit demselben Gitter wie `PortfolioHistoryChart` — wie
/// `HistoryCompareChart` (Android): Stufen in Prozent rechts, 0 % gestrichelt, die gewählte Währung kräftig
/// in `color`, USDT dünn und grau, Etikett mit dem heutigen Prozentwert der Währung (nur Linien, keine
/// Kerzen). Kurz drücken, dann ziehen — der Tag geht an `scrub` (die Karte zeigt darunter den
/// Währungseffekt dieses Tags), das Etikett zeigt «7. Okt. 2026 · CHF +7.00% · USDT +12.10%». Prozente
/// bleiben auch bei «Beträge verbergen».
struct PortfolioCompareChart: View {
    let compare: PortfolioCompareSeries
    let currency: String
    let color: Color
    @Binding var scrub: Int?

    @Environment(\.appAccent) private var accent
    @Environment(\.onAccent) private var onAccent
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.displayScale) private var displayScale
    @ScaledMetric(relativeTo: .caption2) private var labelSize: CGFloat = 10

    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState private var pressX: CGFloat? = nil
    @State private var box = PortfolioChartBox()

    private var style: PortfolioChartStyle {
        let size: CGFloat = Swift.min(labelSize, ChartLabelMetrics.maxSize)
        return PortfolioChartStyle(
            accent: accent.primary,
            onAccent: onAccent,
            secondary: AppColors.onSurfaceVariant,
            marker: AppColors.onSurface,
            background: AppColors.container,
            highContrast: highContrast,
            labelFont: .system(size: size, weight: .medium, design: .rounded).monospacedDigit(),
            tagFont: .system(size: size, weight: .semibold, design: .rounded).monospacedDigit()
        )
    }

    var body: some View {
        let index = scrubIndex()
        canvas(index)
            .overlay(alignment: .topLeading) {
                if let i = index, let frame = box.frame, compare.epochDays.indices.contains(i) {
                    ScrubLabelLayout(centerX: frame.x(i)) {
                        scrubLabel(i)
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(portfolioScrubGesture($pressX))
            // Leichtes Ticken je neuem Tag (folgt den System-Einstellungen)
            .haptic(.selection, trigger: index) { old, new in
                compare.epochDays.count <= PortfolioChartRenderer.maxTickPoints
                    && SheetChart.isNewCandle(previous: old, current: new)
            }
            .onChange(of: index) { _, new in scrub = new }
            // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
            .environment(\.layoutDirection, .leftToRight)
    }

    private func canvas(_ index: Int?) -> some View {
        let style = self.style
        let px = 1 / Swift.max(displayScale, 1)
        let compare = self.compare
        let color = self.color
        let bounds = PortfolioCompare.bounds(compare)
        let spans = compare.epochDays.map { $0...$0 }
        let box = self.box
        return Canvas { context, size in
            guard let last = compare.currency.last,
                  let prepared = PortfolioChartRenderer.prepare(context, size: size, count: spans.count, low: bounds.lo,
                                                                high: bounds.hi,
                                                                format: { PortfolioFormat.signedPercent($0) },
                                                                tagText: PortfolioFormat.signedPercent(last),
                                                                style: style) else { return }
            let frame = prepared.frame
            box.frame = frame
            PortfolioChartRenderer.drawGrid(&context, frame: frame, spans: spans, style: style, px: px)
            Self.drawLines(&context, frame: frame, compare: compare, color: color, style: style)
            if let i = index, compare.currency.indices.contains(i), compare.usdt.indices.contains(i) {
                let ux = frame.x(i)
                let uy = frame.y(compare.usdt[i])
                context.fill(Path(ellipseIn: CGRect(x: ux - 2.5, y: uy - 2.5, width: 5, height: 5)),
                             with: .color(style.secondary))
                PortfolioChartRenderer.drawMarker(&context, frame: frame, index: i, value: compare.currency[i],
                                                  color: style.marker)
            }
            // Etikett ohne eigene Linie (die 0-%-Linie ist schon gestrichelt)
            PortfolioChartRenderer.drawAxis(&context, size: size, prepared: prepared, y: frame.y(last), dashed: false,
                                            style: style, px: px)
        }
    }

    /// 0-%-Linie gestrichelt, darauf USDT dünn und grau, darüber die Währung kräftig.
    private static func drawLines(_ ctx: inout GraphicsContext, frame: PortfolioChartFrame,
                                  compare: PortfolioCompareSeries, color: Color, style: PortfolioChartStyle) {
        var zero = Path()
        let zy = frame.y(0)
        zero.move(to: CGPoint(x: frame.plotLeft, y: zy))
        zero.addLine(to: CGPoint(x: frame.plotRight, y: zy))
        ctx.stroke(zero, with: .color(AppColors.outline.opacity(0.7)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
        ctx.stroke(PortfolioChartRenderer.linePath(frame, values: compare.usdt), with: .color(style.secondary),
                   style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
        ctx.stroke(PortfolioChartRenderer.linePath(frame, values: compare.currency), with: .color(color),
                   style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
    }

    /// Tag unter dem Finger; nil ohne Ziehen oder vor dem ersten Zeichnen.
    private func scrubIndex() -> Int? {
        guard let x = pressX, let frame = box.frame else { return nil }
        return SheetChart.scrubIndex(x: x, plotLeft: frame.plotLeft, slot: frame.slot, count: compare.epochDays.count)
    }

    /// «7. Okt. 2026 · CHF +7.00% · USDT +12.10%».
    private func scrubLabel(_ i: Int) -> some View {
        let date = PortfolioFormat.date(LocalDay(epochDay: compare.epochDays[i]).date.millis)
        return Text(date + " · " + Self.values(compare, currency: currency, index: i))
            .font(AppFont.amount(.caption, weight: .medium))
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
            .fixedSize()
    }

    /// «CHF +7.00% · USDT +12.10%» für den Tag `index`.
    static func values(_ compare: PortfolioCompareSeries, currency: String, index: Int) -> String {
        let c = PortfolioFormat.signedPercent(compare.currency[index])
        let u = PortfolioFormat.signedPercent(compare.usdt[index])
        return "\(currency) \(c) · \(PortfolioFormat.usdt) \(u)"
    }
}
