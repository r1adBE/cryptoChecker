import SwiftUI

/// Kurs-Chart oben im Aktionsblatt (unter Kopf und Kurs, über Alarm · Warum? · Favorit) — wie
/// `SheetPriceChart` (Android): Veränderung über den Zeitraum, Chart wie das Einzel-Widget
/// (`PriceChartRenderer`: Kerzen oder Linie, Preisstufen, Gitter, Kurs-Etikett), darunter
/// 24h · 7T · 30T · 1J und Kerzen/Linie. Kurz drücken und ziehen zeigt Kurs, Zeit und Veränderung
/// der Kerze unter dem Finger. DEX- und andere Paare ohne Kerzenquelle: ganz ausgeblendet.
@MainActor
struct WatchSheetChartView: View {
    let watch: Watch

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale
    /// %-Basis: bei «seit 00:00» heisst der erste Zeitraum «Heute» und beginnt beim Tagesbeginn.
    @Environment(\.changeView) private var changeView
    /// Beschriftung im Chart wächst mit der Textgrösse, höchstens bis 13 pt.
    @ScaledMetric(relativeTo: .caption2) private var labelSize: CGFloat = 10

    @State private var range: PriceChartRange = .day
    @State private var result: SheetChartResult? = nil
    @State private var layoutBox = LayoutBox()
    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState private var pressX: CGFloat? = nil

    /// Lage der zuletzt gezeichneten Fläche (kein beobachteter Zustand).
    final class LayoutBox {
        var layout: PriceChartLayout?
    }

    /// Mit dem Paar: wurde es bearbeitet (gleiche Id, anderes Paar/Börse), neu laden.
    private struct LoadKey: Hashable {
        let watchId: Int64
        let pair: WatchEdit.Key
        let range: PriceChartRange
    }

    static let chartHeight: CGFloat = 180

    /// Über so vielen Kerzen (1 Jahr) beim Ziehen kein Ticken je Kerze — es wäre ein Dauersurren.
    private static let maxTickCandles = 100

    /// Zeitraum, Art oder Kerzen gewechselt: alter und neuer Verlauf blenden kurz ineinander.
    private struct FadeKey: Hashable {
        let range: PriceChartRange
        let type: PriceChartType
        let first: Int64?
        let count: Int
    }

    private var chartType: PriceChartType { data.settings.sheetChartLine ? .line : .candles }

    var body: some View {
        let current = result ?? SheetChartStore.shared.cached(watch, range: range)
        if current != .unsupported {
            VStack(alignment: .leading, spacing: 8) {
                switch current {
                case .some(.ready(let loaded, _)):
                    let candles = shownCandles(loaded)
                    header(candles)
                    chart(candles)
                        .id(fadeKey(candles))
                        .transition(.opacity)
                case .some(.noData):
                    Text(L("sheet_chart_no_data"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .padding(.vertical, 8)
                default:
                    skeleton
                }
                chips
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: currentFadeKey(current))
            .task(id: LoadKey(watchId: watch.id, pair: WatchEdit.Key(watch), range: range)) { await load() }
        }
    }

    private func fadeKey(_ candles: [MarketCandle]) -> FadeKey {
        FadeKey(range: range, type: chartType, first: candles.first?.openTime, count: candles.count)
    }

    /// Schlüssel der Überblendung; nil ohne fertige Kerzen (Platzhalter, keine Daten).
    private func currentFadeKey(_ current: SheetChartResult?) -> FadeKey? {
        guard case .some(.ready(let loaded, _)) = current else { return nil }
        return fadeKey(shownCandles(loaded))
    }

    private func load() async {
        let wanted = range
        if let hit = SheetChartStore.shared.cached(watch, range: wanted) {
            result = hit
            return
        }
        result = nil
        let loaded = await SheetChartStore.shared.load(watch, range: wanted)
        guard wanted == range, !Task.isCancelled else { return }
        result = loaded
    }

    /// Erster Zeitraum bei einer Tages-Basis der %-Änderung: «Heute» statt «24h».
    private var today: Bool { range == .day && changeView.basis.isDay }

    /// Zeitraum ausgeschrieben (VoiceOver): «24h» / «Seit 00:00 UTC» …
    private var periodLong: String { today ? A11y.changeLongLabel(changeView.basis) : L(range.labelKey) }

    /// «Heute»: Kerzen erst ab Tagesbeginn (mindestens zwei); sonst alle geladenen.
    private func shownCandles(_ candles: [MarketCandle]) -> [MarketCandle] {
        guard today, let start = ChangeBasisMath.dayStart(changeView.basis, now: TimeUtils.nowMillis) else { return candles }
        return ChangeBasisMath.sinceDayStart(candles, dayStart: start) { $0.openTime }
    }

    // MARK: Kopf

    /// «▲ +2.31% in 24h» bzw. «▲ +2.31% heute» in der Kursfarbe; VoiceOver: «gestiegen um 2.31%, 24h».
    /// Erster Zeitraum: dieselbe Zahl wie die Pille neben dem Kurs; 7T/30T/1J aus den Kerzen.
    private func header(_ candles: [MarketCandle]) -> some View {
        let change = SheetChart.headerChange(range: range, candles, type: chartType,
                                             dayChange: changeView.shown(watch.shownChange24h))
        let spoken = (A11y.change(change) ?? L("a11y_chart_empty")) + ", " + periodLong
        let text = today
            ? L("sheet_chart_change_since", Self.changeText(change), A11y.changeShortLabel(changeView.basis))
            : L("sheet_chart_change", Self.changeText(change), L(range.shortLabelKey))
        return Text(text)
            .font(AppFont.amount(.subheadline, weight: .semibold))
            .foregroundStyle(changeColor(change))
            .lineLimit(1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
    }

    /// «▲ +2.31%», «0.00%» oder «—» — Pfeil nach dem Vorzeichen, nie nach «Farben tauschen».
    static func changeText(_ change: Double?) -> String {
        guard let change else { return "—" }
        guard let formatted = PriceFormat.changePercent(change) else { return PriceFormat.zeroPercent() }
        return (change > 0 ? "▲ " : "▼ ") + formatted
    }

    private func changeColor(_ change: Double?) -> Color {
        guard let change, PriceFormat.changePercent(change) != nil else { return AppColors.onSurfaceVariant }
        return priceColors.forChange(change, highContrast: highContrast, inverted: inverted)
    }

    // MARK: Chart

    private func chart(_ candles: [MarketCandle]) -> some View {
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

    // MARK: Platzhalter

    /// Grauer Block in Form von Kopf und Chart; pulsiert nur ohne «Bewegung reduzieren».
    @ViewBuilder
    private var skeleton: some View {
        let shape = VStack(alignment: .leading, spacing: 8) {
            Capsule().fill(AppColors.containerHighest).frame(width: 130, height: 14)
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(AppColors.containerHighest)
                .frame(maxWidth: .infinity)
                .frame(height: Self.chartHeight)
        }
        .accessibilityHidden(true)
        if reduceMotion {
            shape.opacity(0.7)
        } else {
            shape.phaseAnimator([0.45, 1.0]) { view, phase in
                view.opacity(phase)
            } animation: { _ in
                .easeInOut(duration: 0.9)
            }
        }
    }

    // MARK: Zeitraum und Chart-Art

    /// Zeitraum links, Kerzen/Linie als kompakter Zwei-Symbol-Schalter rechts in derselben
    /// Zeile; reicht der Platz nicht (grosse Schrift), rutscht der Schalter rechtsbündig darunter.
    private var chips: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) {
                rangeChips
                Spacer(minLength: 6)
                typeToggle
            }
            VStack(alignment: .trailing, spacing: 8) {
                FlowLayout(spacing: 6) { rangeChips }
                    .frame(maxWidth: .infinity, alignment: .leading)
                typeToggle
            }
        }
    }

    @ViewBuilder
    private var rangeChips: some View {
        ForEach(PriceChartRange.allCases, id: \.self) { option in
            // Tages-Basis: «Heute» statt «24h» (VoiceOver: «Seit 00:00 UTC» bzw. «… Ortszeit»)
            let optionToday = option == .day && changeView.basis.isDay
            chip(optionToday ? L("sheet_chart_today") : L(option.shortLabelKey),
                 spoken: optionToday ? A11y.changeLongLabel(changeView.basis) : L(option.labelKey),
                 selected: option == range) {
                guard option != range else { return }
                WatchlistHaptics.selection()
                result = SheetChartStore.shared.cached(watch, range: option)
                range = option
            }
        }
    }

    /// Kerzen | Linie als zwei Symbole in einer Pille (Grösse wie ein Segment-Schalter von iOS:
    /// je Hälfte 44 × 32 pt); VoiceOver: «Kerzen»/«Linie» mit «ausgewählt».
    private var typeToggle: some View {
        HStack(spacing: 0) {
            typeSegment(nil, label: L("widget_chart_candles"), selected: chartType == .candles) {
                guard data.settings.sheetChartLine else { return }
                WatchlistHaptics.selection()
                data.settings.sheetChartLine = false
            }
            Rectangle()
                .fill(AppColors.outlineVariant)
                .frame(width: 1, height: 30)
            typeSegment("chart.xyaxis.line", label: L("widget_chart_line"), selected: chartType == .line) {
                guard !data.settings.sheetChartLine else { return }
                WatchlistHaptics.selection()
                data.settings.sheetChartLine = true
            }
        }
        .background(AppColors.containerHigh, in: Capsule())
        .overlay(Capsule().strokeBorder(AppColors.outlineVariant, lineWidth: 1))
        .clipShape(Capsule())
        .fixedSize()
    }

    /// `symbol` nil = Kerzen-Symbol (SF Symbols hat keins; wie `ic_chart_candles` in Android).
    private func typeSegment(_ symbol: String?, label: String, selected: Bool,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let symbol {
                    Image(systemName: symbol)
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                } else {
                    CandlesIcon().frame(width: 18, height: 18)
                }
            }
            .foregroundStyle(selected ? accent.onContainer : AppColors.onSurfaceVariant)
            .frame(width: 44, height: 32)
            .background(selected ? accent.container : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Kleiner Auswahl-Chip wie `ChoiceChips`; VoiceOver: Knopf mit «ausgewählt».
    private func chip(_ title: String, spoken: String? = nil, selected: Bool,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spoken ?? title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Zwei Kerzen mit Docht (24er-Raster wie Material «candlestick_chart»), in der Vordergrundfarbe.
private struct CandlesIcon: View {
    var body: some View {
        Canvas { context, size in
            let k = min(size.width, size.height) / 24
            func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> Path {
                Path(CGRect(x: x * k, y: y * k, width: w * k, height: h * k))
            }
            var p = Path()
            p.addPath(r(7, 4, 2, 16))   // Docht links
            p.addPath(r(5, 6, 6, 12))   // Körper links
            p.addPath(r(15, 4, 2, 16))  // Docht rechts
            p.addPath(r(13, 8, 6, 7))   // Körper rechts
            context.fill(p, with: .foreground)
        }
        .accessibilityHidden(true)
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
