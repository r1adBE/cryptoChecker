import SwiftUI

/// Ziel der Vergleichsansicht: die markierten Paare in Listenreihenfolge.
struct WatchlistCompareTarget: Identifiable, Hashable {
    let ids: [Int64]
    var id: String { ids.map(String.init).joined(separator: ",") }
}

/// Vergleichsansicht (Merkliste › markieren › «Vergleichen», 2–4 Paare, Wunsch 27.10.2026): oben eine Linie je Paar in %
/// ab Beginn des Zeitraums (24 h bzw. «Heute» / 7 T / 30 T / 1 J), darunter je Paar Kurs, 24 h, 7 T, 30 T und Abstand
/// zum 30-Tage-Hoch (`CompareMath`). Kerzen wie im Aktionsblatt (`SheetChartStore`, derselbe Zwischenspeicher). Jede
/// Linie hat eigene Farbe UND eigene Strichart (Farbsehschwäche). Eigene Seite mit Zurück wie «Warum?». Wie Android
/// `CompareScreen`.
struct CompareScreen: View {
    let watches: [Watch]
    @Environment(\.changeView) private var changeView
    @Environment(\.appAccent) private var accent
    @State private var range: PriceChartRange = .week
    /// Linie je Paar (nil = lädt, leer = keine Kursdaten).
    @State private var series: [[ComparePoint]]?
    @State private var figures: [CompareFigures]?

    private var today: Bool { range == .day && changeView.basis.isDay }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                rangeChips
                chart
                Text(L("compare_basis"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                ForEach(Array(watches.enumerated()), id: \.element.id) { index, watch in
                    Divider()
                    row(watch, index: index)
                }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.sm)
        }
        .background(AppColors.background)
        .navigationTitle(L("compare_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: TaskKey(range: range, basis: String(describing: changeView.basis))) { await loadSeries() }
        .task { await loadFigures() }
    }

    private struct TaskKey: Equatable {
        let range: PriceChartRange
        let basis: String
    }

    private func loadSeries() async {
        series = nil
        let shownRange = range
        let start = today ? ChangeBasisMath.dayStart(changeView.basis, now: TimeUtils.nowMillis) : nil
        var out = [[ComparePoint]](repeating: [], count: watches.count)
        await withTaskGroup(of: (Int, [ComparePoint]).self) { group in
            for (i, w) in watches.enumerated() {
                group.addTask {
                    guard case .ready(let candles, _, _) = await SheetChartStore.shared.load(w, range: shownRange) else { return (i, []) }
                    let shown = start.map { s in ChangeBasisMath.sinceDayStart(candles, dayStart: s) { $0.openTime } } ?? candles
                    return (i, CompareMath.normalized(shown))
                }
            }
            for await (i, line) in group { out[i] = line }
        }
        guard shownRange == range else { return }
        series = out
    }

    /// Kennzahlen aus den Tageskerzen des Jahres (30 T braucht eine Kerze von vor 30 Tagen), unabhängig vom Zeitraum.
    private func loadFigures() async {
        let now = TimeUtils.nowMillis
        var out = [CompareFigures](repeating: CompareFigures(change7d: nil, change30d: nil, fromHigh30d: nil), count: watches.count)
        await withTaskGroup(of: (Int, CompareFigures).self) { group in
            for (i, w) in watches.enumerated() {
                group.addTask {
                    var daily: [MarketCandle] = []
                    if case .ready(let candles, _, _) = await SheetChartStore.shared.load(w, range: .year) { daily = candles }
                    return (i, CompareMath.figures(daily, price: w.lastPrice, now: now))
                }
            }
            for await (i, f) in group { out[i] = f }
        }
        figures = out
    }

    // MARK: Zeitraum

    private func shortLabel(_ option: PriceChartRange) -> String {
        option == .day && changeView.basis.isDay ? L("sheet_chart_today") : L(option.shortLabelKey)
    }

    private var periodLong: String { today ? A11y.changeLongLabel(changeView.basis) : L(range.labelKey) }

    private var rangeChips: some View {
        HStack(spacing: Spacing.xs) {
            ForEach(PriceChartRange.allCases, id: \.self) { option in
                let selected = option == range
                Button { range = option } label: {
                    Text(shortLabel(option))
                        .font(.subheadline.weight(selected ? .semibold : .regular))
                        .lineLimit(1)
                        .padding(.horizontal, Spacing.md)
                        .padding(.vertical, Spacing.sm)
                        .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                        .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }

    // MARK: Chart

    /// Farben der Linien (lesbar in hell und dunkel; Grün/Rot bleiben der Kursrichtung vorbehalten).
    private var colors: [Color] {
        [accent.primary, AppColors.onSurface, Color.dynamic(ColorTokens.ethereum), AppColors.warningText]
    }

    /// Strichart je Linie: durchgezogen, gestrichelt, gepunktet, Strich-Punkt.
    private static func dash(_ index: Int) -> [CGFloat] {
        switch index % 4 {
        case 0: return []
        case 1: return [9, 5]
        case 2: return [2, 4]
        default: return [9, 4, 2, 4]
        }
    }

    private var chartSentence: String {
        guard let series else { return "" }
        let parts = watches.enumerated().map { i, w in
            L("label_value", w.displayName, A11y.change(CompareMath.last(i < series.count ? series[i] : [])) ?? L("a11y_change_flat"))
        }
        return L("a11y_compare_chart", periodLong, parts.joined(separator: "; "))
    }

    private var chart: some View {
        let lines = series ?? []
        let bounds = CompareMath.bounds(lines)
        return VStack(alignment: .leading, spacing: 4) {
            Text(bounds.flatMap { PriceFormat.changePercent($0.maxPercent) } ?? " ")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
            Canvas { context, size in
                guard let b = bounds else { return }
                let span = Double(max(b.maxTime - b.minTime, 1))
                let pSpan = b.maxPercent - b.minPercent
                func point(_ p: ComparePoint) -> CGPoint {
                    CGPoint(x: Double(p.time - b.minTime) / span * size.width,
                            y: size.height - (p.percent - b.minPercent) / pSpan * size.height)
                }
                var zero = Path()
                let y0 = size.height - (0 - b.minPercent) / pSpan * size.height
                zero.move(to: CGPoint(x: 0, y: y0))
                zero.addLine(to: CGPoint(x: size.width, y: y0))
                context.stroke(zero, with: .color(AppColors.outlineVariant), lineWidth: 1)
                for (i, line) in lines.enumerated() where line.count >= 2 {
                    var path = Path()
                    path.move(to: point(line[0]))
                    for p in line.dropFirst() { path.addLine(to: point(p)) }
                    context.stroke(path, with: .color(colors[i % colors.count]),
                                   style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round, dash: Self.dash(i)))
                }
            }
            .frame(height: 200)
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityElement()
            .accessibilityLabel(chartSentence)
            Text(bounds.flatMap { PriceFormat.changePercent($0.minPercent) } ?? " ")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
        }
    }

    // MARK: Tabelle

    private func lineSample(_ index: Int) -> some View {
        Canvas { context, size in
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height / 2))
            path.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            context.stroke(path, with: .color(colors[index % colors.count]),
                           style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: Self.dash(index)))
        }
        .frame(width: 28, height: 12)
        .accessibilityHidden(true)
    }

    private func row(_ watch: Watch, index: Int) -> some View {
        let line = series.map { index < $0.count ? $0[index] : [] }
        let noData = line?.isEmpty == true
        let f = figures.flatMap { index < $0.count ? $0[index] : nil }
        let values: [(String, Double?)] = [
            (shortLabel(range), line.flatMap(CompareMath.last)),
            (A11y.changeShortLabel(changeView.basis), changeView.shown(watch.shownChange24h)),
            (L("widget_range_short_7d"), f?.change7d),
            (L("widget_range_short_30d"), f?.change30d),
            (L("compare_from_high"), f?.fromHigh30d),
        ]
        let price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset)
        var spoken = [watch.displayName, L("label_value", L("watchlist_sort_price"), price)]
        if noData { spoken.append(L("sheet_chart_no_data")) }
        for (label, v) in values.dropLast() {
            spoken.append(L("label_value", label, A11y.change(v) ?? "—"))
        }
        if let high = f?.fromHigh30d { spoken.append(L("a11y_compare_from_high", PriceFormat.changePercent(high) ?? "")) }
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: Spacing.sm) {
                lineSample(index)
                Text(watch.displayName)
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: Spacing.sm)
                Text(price)
                    .font(.subheadline.monospacedDigit())
                    .lineLimit(1)
            }
            if noData {
                Text(L("sheet_chart_no_data"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Spacing.md) { figureCells(values) }
                VStack(alignment: .leading, spacing: 2) { figureCells(values) }
            }
        }
        .padding(.vertical, Spacing.sm)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken.joined(separator: ", "))
    }

    @ViewBuilder
    private func figureCells(_ values: [(String, Double?)]) -> some View {
        ForEach(Array(values.enumerated()), id: \.offset) { _, item in
            HStack(spacing: 3) {
                Text(item.0)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Text(PriceFormat.changePercent(item.1) ?? "—")
                    .fontWeight(.semibold)
                    .monospacedDigit()
            }
            .font(.footnote)
            .lineLimit(1)
        }
    }
}
