import SwiftUI

// Wertverlauf-Chart wie das Chart im Aktionsblatt eines Paars (`PriceChartRenderer`): drei Stufen mit
// Werten rechts, feine senkrechte Linien an Kalendergrenzen, gestrichelte Akzentlinie mit Etikett beim
// heutigen Wert — als Linie (Tagespunkte) oder Kerzen (`PortfolioCandles`). Wie `PortfolioHistoryChart.kt`.

/// Kurz drücken, dann ziehen (wie im Aktionsblatt); schnelles Wischen bleibt dem Bildschirm (Scrollen).
@MainActor
func portfolioScrubGesture(_ state: GestureState<CGFloat?>) -> some Gesture {
    LongPressGesture(minimumDuration: 0.2)
        .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
        .updating(state) { value, out, _ in
            if case .second(true, let drag?) = value { out = drag.location.x }
        }
}

/// Wertverlauf wie das Chart im Aktionsblatt eines Paars — wie `HistoryChart` (Android): als Linie
/// (Tagespunkte, mit Fläche) oder als Kerzen (`candles`, `PortfolioCandles.build` — bei langen Zeiträumen
/// Wochen- bzw. Monatskerzen); drei Stufen mit Werten rechts, feine senkrechte Linien an Kalendergrenzen,
/// gestrichelte Akzentlinie mit Etikett beim heutigen Wert und der höchste Tageswert des Zeitraums als
/// «Hoch …» am Punkt. «Beträge verbergen»: alle Werte als «•••». Kurz drücken, dann ziehen zeigt Wert,
/// Datum und Veränderung seit Beginn des Punkts bzw. der Kerze unter dem Finger. VoiceOver: Chart-Satz am Aufrufer.
struct PortfolioHistoryChart: View {
    let points: [PortfolioHistoryPoint]
    let unit: String
    /// Farbe der Linie (nach der Änderung über den Zeitraum).
    let color: Color
    let candles: Bool
    /// «Beträge verbergen»: Werte als «•••».
    var hidden = false
    /// Gewinn/Verlust: eigene Skala mit 0 (`PortfolioChartAxis.pnlBounds`), gestrichelte Nulllinie, Beschriftung
    /// mit Vorzeichen, Linie über 0 «steigend», darunter «fallend»; kein «Hoch …»; beim Ziehen ohne Prozent.
    var pnl = false

    @Environment(\.appAccent) private var accent
    @Environment(\.onAccent) private var onAccent
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.displayScale) private var displayScale
    /// Beschriftung im Chart wächst mit der Textgrösse, höchstens bis `ChartLabelMetrics.maxSize` (1.5×).
    @ScaledMetric(relativeTo: .caption2) private var labelSize: CGFloat = 10

    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState private var pressX: CGFloat? = nil
    @State private var box = PortfolioChartBox()

    /// Was gezeichnet wird: Kerzen oder Tagespunkte, Slots, Skala und der höchste Tageswert.
    private struct Model {
        let candles: [PortfolioCandle]?
        let values: [Double]
        let spans: [ClosedRange<Int>]
        let low: Double
        let high: Double
        let maxIndex: Int?
        let maxSlot: Int?
        let bucket: PortfolioCandleBucket

        var count: Int { spans.count }

        init(points: [PortfolioHistoryPoint], candles wanted: Bool, pnl: Bool) {
            let days = (points.last?.epochDay ?? 0) - (points.first?.epochDay ?? 0) + 1
            let values = points.map(\.value)
            let list = wanted ? PortfolioCandles.build(points) : nil
            let spans: [ClosedRange<Int>] = list?.map { $0.firstDay...$0.lastDay } ?? points.map { $0.epochDay...$0.epochDay }
            let bounds = list.flatMap { PortfolioChartAxis.candleBounds($0) }
            // Höchster Tageswert nicht beim Gewinn/Verlust
            let top = pnl ? nil : PortfolioChartAxis.maxIndex(values)
            self.bucket = PortfolioCandles.bucket(dayCount: points.isEmpty ? 0 : days)
            self.values = values
            self.candles = list
            self.spans = spans
            let low = bounds?.low ?? values.min() ?? 0
            let high = bounds?.high ?? values.max() ?? 0
            let shown = pnl ? PortfolioChartAxis.pnlBounds(low: low, high: high) : (low: low, high: high)
            self.low = shown.low
            self.high = shown.high
            self.maxIndex = top
            self.maxSlot = top.flatMap { PortfolioChartAxis.slotOfDay(spans, day: points[$0].epochDay) }
        }

        /// Schluss in Slot `i` (Kerze oder Tagespunkt).
        func close(_ i: Int) -> Double? {
            if let candles { return candles.indices.contains(i) ? candles[i].close : nil }
            return values.indices.contains(i) ? values[i] : nil
        }
    }

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
        let model = Model(points: points, candles: candles, pnl: pnl)
        let scrub = scrubIndex(model.count)
        canvas(model, scrub: scrub)
            .overlay(alignment: .topLeading) {
                if let i = scrub, let frame = box.frame, let value = model.close(i) {
                    ScrubLabelLayout(centerX: frame.x(i)) {
                        scrubLabel(model, index: i, value: value)
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(portfolioScrubGesture($pressX))
            // Leichtes Ticken je neuem Punkt bzw. neuer Kerze (folgt den System-Einstellungen)
            .haptic(.selection, trigger: scrub) { old, new in
                model.count <= PortfolioChartRenderer.maxTickPoints && SheetChart.isNewCandle(previous: old, current: new)
            }
            // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
            .environment(\.layoutDirection, .leftToRight)
    }

    private func canvas(_ model: Model, scrub: Int?) -> some View {
        let style = self.style
        let px = 1 / Swift.max(displayScale, 1)
        let up = priceColors.up(highContrast: highContrast, inverted: inverted)
        let down = priceColors.down(highContrast: highContrast, inverted: inverted)
        let lineColor = color
        let hidden = self.hidden
        let pnl = self.pnl
        let format: (Double) -> String = {
            hidden ? PortfolioInsights.hidden
                : (pnl ? PortfolioChartRenderer.signedAxisValue($0) : PortfolioChartRenderer.axisValue($0))
        }
        let highText = model.maxIndex.map {
            L("portfolio_chart_high", hidden ? PortfolioInsights.hidden : PortfolioChartRenderer.axisValue(model.values[$0]))
        }
        let box = self.box
        return Canvas { context, size in
            guard let last = model.values.last,
                  let prepared = PortfolioChartRenderer.prepare(context, size: size, count: model.count, low: model.low,
                                                                high: model.high, format: format, tagText: format(last),
                                                                style: style) else { return }
            let frame = prepared.frame
            box.frame = frame
            PortfolioChartRenderer.drawGrid(&context, frame: frame, spans: model.spans, style: style, px: px)
            if pnl { PortfolioChartRenderer.drawZeroLine(&context, frame: frame) }
            if let candles = model.candles {
                PortfolioChartRenderer.drawCandles(&context, frame: frame, candles: candles, up: up, down: down, px: px)
            } else if pnl {
                PortfolioChartRenderer.drawPnlLine(&context, size: size, frame: frame, values: model.values, up: up, down: down)
            } else {
                PortfolioChartRenderer.drawLine(&context, frame: frame, values: model.values, color: lineColor)
            }
            if let i = model.maxIndex, let slot = model.maxSlot, let highText {
                PortfolioChartRenderer.drawHigh(&context, size: size, frame: frame, slot: slot, value: model.values[i],
                                                text: highText, style: style)
            }
            if let i = scrub, let value = model.close(i) {
                PortfolioChartRenderer.drawMarker(&context, frame: frame, index: i, value: value, color: style.marker)
            }
            PortfolioChartRenderer.drawAxis(&context, size: size, prepared: prepared, y: frame.y(last), dashed: true,
                                            style: style, px: px)
        }
    }

    /// Slot unter dem Finger; nil ohne Ziehen oder vor dem ersten Zeichnen.
    private func scrubIndex(_ count: Int) -> Int? {
        guard let x = pressX, let frame = box.frame else { return nil }
        return SheetChart.scrubIndex(x: x, plotLeft: frame.plotLeft, slot: frame.slot, count: count)
    }

    /// «12’345.67 CHF · 7. Okt. 2026» und darunter die Anzeige «+1.20%» (Veränderung seit Beginn des Zeitraums).
    private func scrubLabel(_ model: Model, index: Int, value: Double) -> some View {
        let start = model.candles?.first?.open ?? model.values.first ?? 0
        let change = SheetChart.changePercent(start: start, value: value)
        let date: String
        if let candles = model.candles {
            date = PortfolioChartRenderer.candleDate(candles[index], bucket: model.bucket)
        } else {
            date = PortfolioFormat.date(LocalDay(epochDay: points[index].epochDay).date.millis)
        }
        let text = pnl ? PortfolioHistoryCard.signedValue(value, unit: unit) : PriceFormat.valueWithCurrency(value, unit)
        let amount = PortfolioInsights.mask(text, hidden: hidden)
        return VStack(alignment: .leading, spacing: 1) {
            (PriceWithQuote.text(BidiText.isolate(amount)) + Text(verbatim: " · " + date))
                .font(AppFont.amount(.caption, weight: .medium))
                .foregroundStyle(AppColors.onSurface)
            if !pnl {
                ChangePill(change: change, dashWhenMissing: true)
                    .padding(.top, 2)
            }
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
        .fixedSize()
    }
}
