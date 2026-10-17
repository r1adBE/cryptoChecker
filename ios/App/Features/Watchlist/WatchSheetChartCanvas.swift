import SwiftUI

/// Chart-Fläche im Aktionsblatt: Zeichnen, Ziehen mit Etikett, Zeitformate. Wie `WatchSheetChartCanvas.kt`.
extension WatchSheetChartView {
    // MARK: Chart

    func chart(_ p: SheetChartPrepared) -> some View {
        let candles = p.candles
        let type = p.type
        let chartRange = p.range
        let levels = p.levels
        let px = 1 / Swift.max(displayScale, 1)
        let size: CGFloat = Swift.min(labelSize, ChartLabelMetrics.maxSize)
        let style = PriceChartStyle(
            up: priceColors.up(highContrast: highContrast, inverted: inverted),
            down: priceColors.down(highContrast: highContrast, inverted: inverted),
            accent: accent.primary,
            onAccent: onAccent,
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
        let zone = ChangeBasisMath.chartTimeZone(changeView.basis)
        // Volumen-Balken nur, wenn die Kerzen Umsätze tragen (sonst nil = ausblenden)
        let volumes = p.volumes
        return Canvas { context, canvasSize in
            guard let layout = PriceChartRenderer.render(&context, size: canvasSize, candles: candles, type: type,
                                                         range: chartRange, style: style, currentPrice: price,
                                                         px: px, timeZone: zone, levels: levels,
                                                         prepared: true) else { return }
            box.layout = layout
            if let volumes {
                Self.drawVolume(&context, volumes: volumes, candles: candles, layout: layout, style: style, px: px)
            }
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
                    scrubLabel(p, index: i)
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

    /// Volumen unten (18 % der Fläche), dezent in den Kursfarben; mit `.destinationOver` HINTER
    /// Kerzen bzw. Linie gezeichnet, obwohl der Chart schon steht (wie Android).
    private static func drawVolume(_ context: inout GraphicsContext, volumes: [CGFloat], candles: [MarketCandle],
                                   layout: PriceChartLayout, style: PriceChartStyle, px: CGFloat) {
        var layer = context
        layer.blendMode = .destinationOver
        let zoneHeight = (layout.bottom - layout.top) * SheetChart.volumeHeightFraction
        let barWidth = Swift.max(px, layout.slot * 0.6)
        let alpha = style.highContrast ? 0.40 : 0.22
        for (i, fraction) in volumes.enumerated() where fraction > 0 && candles.indices.contains(i) {
            let h = Swift.max(px, zoneHeight * fraction)
            let rect = CGRect(x: layout.x(i) - barWidth / 2, y: layout.bottom - h, width: barWidth, height: h)
            let color = WidgetChartGeometry.isUp(candles[i]) ? style.up : style.down
            // Nur genäherter Umsatz (Menge × Schluss): halb so kräftig
            let shade = candles[i].volumeApproximated ? alpha * 0.5 : alpha
            layer.fill(Path(rect), with: .color(color.opacity(shade)))
        }
    }

    /// «Vol 12.3M USDT» der Kerze `index` («≈» davor, wenn nur genähert); nil ohne Volumen-Balken oder Umsatz.
    private func volumeText(_ p: SheetChartPrepared, index: Int) -> String? {
        let candle = p.candles[index]
        guard p.volumes != nil, let v = SheetChart.quoteVolume(candle), v > 0 else { return nil }
        let amount = PriceFormat.compact(v) + " " + watch.quoteAsset.uppercased()
        return L("sheet_chart_volume", BidiText.isolate(candle.volumeApproximated ? "≈ " + amount : amount))
    }

    /// Kerze unter dem Finger; nil ohne Ziehen oder vor dem ersten Zeichnen.
    private func scrubIndex(_ count: Int) -> Int? {
        guard let x = pressX, let layout = layoutBox.layout else { return nil }
        return SheetChart.scrubIndex(x: x, plotLeft: layout.plotLeft, slot: layout.slot, count: count)
    }

    /// «98’450 USDT · Di 14:00» und darunter die Anzeige «+1.20%» (wie Android).
    private func scrubLabel(_ p: SheetChartPrepared, index: Int) -> some View {
        let candle = p.candles[index]
        let change = SheetChart.scrubChange(p.candles, type: p.type, index: index)
        let zone = ChangeBasisMath.chartTimeZone(changeView.basis)
        let time = Self.timeFormatter(p.range, timeZone: zone).string(from: Date(millis: candle.openTime))
        return VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: BidiText.isolate(PriceFormat.priceWithCurrency(candle.close, watch.quoteAsset)) + " · " + time)
                .font(AppFont.amount(.caption, weight: .medium))
                .foregroundStyle(AppColors.onSurface)
            if p.type == .candles {
                SheetOhlcReadout(candle: candle)
            }
            scrubChangeLine(change, volume: volumeText(p, index: index))
                .padding(.top, 2)
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
        .fixedSize()
    }

    /// Veränderung als farbige Schrift, daneben grau das Volumen der Kerze (falls gezeigt).
    private func scrubChangeLine(_ change: Double?, volume: String?) -> some View {
        HStack(spacing: Spacing.xs) {
            ChangePill(change: change, dashWhenMissing: true)
            if let volume {
                Text(volume)
                    .font(AppFont.amount(.caption2))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    private static var timeFormatters: [String: DateFormatter] = [:]

    /// Chart-Zeitzone (wie die %-Basis); Datum für 7 und 30 Tage und 1 Jahr (mit Jahr), 12/24 h
    /// nach Systemeinstellung.
    private static func timeFormatter(_ range: PriceChartRange, timeZone: TimeZone) -> DateFormatter {
        let key = "\(range)|\(timeZone.identifier)"
        if let f = timeFormatters[key] { return f }
        let f = DateFormatter()
        f.locale = Locale.current
        f.timeZone = timeZone
        f.setLocalizedDateFormatFromTemplate(range.sheetTimeTemplate)
        timeFormatters[key] = f
        return f
    }
}

/// Kerzen-Modus beim Ziehen: Eröffnung · Hoch / Tief · Schluss in zwei Zeilen, Bezeichnung grau,
/// Wert in Textfarbe (wie Android `OhlcReadout`).
private struct SheetOhlcReadout: View {
    let candle: MarketCandle

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            line(L("sheet_chart_open"), candle.open, L("ticker_high"), candle.high)
            line(L("ticker_low"), candle.low, L("sheet_chart_close"), candle.close)
        }
        .font(AppFont.amount(.caption2))
        .padding(.top, 2)
    }

    private func line(_ a: String, _ av: Double, _ b: String, _ bv: Double) -> some View {
        let label = AppColors.onSurfaceVariant
        let value = AppColors.onSurface
        let first = Text(verbatim: a + " ").foregroundColor(label)
            + Text(verbatim: BidiText.isolate(PriceFormat.price(av))).foregroundColor(value)
        let second = Text(verbatim: "  " + b + " ").foregroundColor(label)
            + Text(verbatim: BidiText.isolate(PriceFormat.price(bv))).foregroundColor(value)
        return first + second
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
