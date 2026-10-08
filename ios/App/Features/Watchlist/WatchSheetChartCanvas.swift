import SwiftUI

/// Chart-Fläche im Aktionsblatt: Zeichnen, Ziehen mit Etikett, Zeitformate. Wie `WatchSheetChartCanvas.kt`.
extension WatchSheetChartView {
    // MARK: Chart

    func chart(_ candles: [MarketCandle]) -> some View {
        let type = chartType
        let chartRange = range
        let px = 1 / Swift.max(displayScale, 1)
        let size = Swift.min(labelSize, 13)
        let style = PriceChartStyle(
            up: priceColors.up(highContrast: highContrast, inverted: inverted),
            down: priceColors.down(highContrast: highContrast, inverted: inverted),
            accent: accent.primary,
            onAccent: accent.onPrimary,
            secondary: AppColors.onSurfaceVariant,
            highContrast: highContrast,
            labelFont: .system(size: size, weight: .medium, design: .rounded).monospacedDigit(),
            tagFont: .system(size: size, weight: .semibold, design: .rounded).monospacedDigit(),
            lineWidth: 2
        )
        let box = layoutBox
        let price = watch.lastPrice
        let marker = AppColors.onSurface
        let scrub = scrubIndex(candles.count)
        return Canvas { context, canvasSize in
            guard let layout = PriceChartRenderer.render(&context, size: canvasSize, candles: candles, type: type,
                                                         range: chartRange, style: style, currentPrice: price,
                                                         px: px) else { return }
            box.layout = layout
            // Ziehen: senkrechte Markierung und Punkt auf dem Schluss
            guard let i = scrub, candles.indices.contains(i) else { return }
            let mx = layout.x(i)
            var line = Path()
            line.move(to: CGPoint(x: mx, y: layout.top))
            line.addLine(to: CGPoint(x: mx, y: layout.bottom))
            context.stroke(line, with: .color(marker.opacity(0.6)), lineWidth: 1)
            let my = layout.y(candles[i].close)
            context.fill(Path(ellipseIn: CGRect(x: mx - 3.5, y: my - 3.5, width: 7, height: 7)), with: .color(marker))
        }
        .frame(maxWidth: .infinity)
        .frame(height: Self.chartHeight)
        .overlay(alignment: .topLeading) {
            if let i = scrub, candles.indices.contains(i), let layout = box.layout {
                ScrubLabelLayout(centerX: layout.x(i)) {
                    scrubLabel(candles, index: i, type: type)
                }
            }
        }
        .contentShape(Rectangle())
        // Kurz drücken, dann ziehen; schnelles Wischen bleibt dem Blatt (Scrollen, Schliessen)
        .gesture(
            LongPressGesture(minimumDuration: 0.2)
                .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
                .updating($pressX) { value, state, _ in
                    if case .second(true, let drag?) = value { state = drag.location.x }
                }
        )
        // Leichtes Ticken je neuer Kerze (folgt den System-Einstellungen)
        .sensoryFeedback(.selection, trigger: scrub) { old, new in
            candles.count <= Self.maxTickCandles && SheetChart.isNewCandle(previous: old, current: new)
        }
        // VoiceOver: ein Element mit dem Chart-Satz (Start, Ende, Hoch, Tief); Ziehen nicht nötig
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(WidgetChartGeometry.accessibility(candles, type: type, range: chartRange,
                                                              quote: watch.quoteAsset, period: periodLong))
        // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
        .environment(\.layoutDirection, .leftToRight)
    }

    /// Kerze unter dem Finger; nil ohne Ziehen oder vor dem ersten Zeichnen.
    private func scrubIndex(_ count: Int) -> Int? {
        guard let x = pressX, let layout = layoutBox.layout else { return nil }
        return SheetChart.scrubIndex(x: x, plotLeft: layout.plotLeft, slot: layout.slot, count: count)
    }

    /// «98’450 USDT · Di 14:00» und «▲ +1.20%».
    private func scrubLabel(_ candles: [MarketCandle], index: Int, type: PriceChartType) -> some View {
        let candle = candles[index]
        let change = SheetChart.scrubChange(candles, type: type, index: index)
        let time = Self.timeFormatter(range).string(from: Date(millis: candle.openTime))
        return VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: BidiText.isolate(PriceFormat.priceWithCurrency(candle.close, watch.quoteAsset)) + " · " + time)
                .font(AppFont.amount(.caption, weight: .medium))
                .foregroundStyle(AppColors.onSurface)
            Text(Self.changeText(change))
                .font(AppFont.amount(.caption2, weight: .semibold))
                .foregroundStyle(changeColor(change))
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .fixedSize()
    }

    private static var timeFormatters: [PriceChartRange: DateFormatter] = [:]

    /// Ortszeit; Datum für 7 und 30 Tage und 1 Jahr (mit Jahr), 12/24 h nach Systemeinstellung.
    private static func timeFormatter(_ range: PriceChartRange) -> DateFormatter {
        if let f = timeFormatters[range] { return f }
        let f = DateFormatter()
        f.locale = Locale.current
        f.timeZone = TimeZone.current
        f.setLocalizedDateFormatFromTemplate(range.sheetTimeTemplate)
        timeFormatters[range] = f
        return f
    }
}

/// Setzt das Etikett beim Ziehen mittig über die Markierung, aber ganz in die Fläche
/// (`SheetChart.labelLeft`).
struct ScrubLabelLayout: Layout {
    let centerX: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let size = subviews.first?.sizeThatFits(.unspecified) ?? .zero
        return CGSize(width: proposal.width ?? size.width, height: size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let view = subviews.first else { return }
        let size = view.sizeThatFits(.unspecified)
        let left = SheetChart.labelLeft(centerX: centerX, width: size.width, minX: 0, maxX: bounds.width)
        view.place(at: CGPoint(x: bounds.minX + left, y: bounds.minY), anchor: .topLeading,
                   proposal: ProposedViewSize(size))
    }
}
