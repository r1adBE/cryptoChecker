import SwiftUI

// MARK: - Halving & Zyklus-Vergleich

/// Halving als Zeile unter «Einordnung»: rechts das geschätzte Datum des nächsten Halvings,
/// darunter der Fortschritt im Zyklus; Tippen klappt Countdown, Balken und Zyklus-Chart auf.
/// Wie `HalvingRow` in Android.
struct CycleHalvingRow: View {
    let cycle: CycleInfo
    let history: CycleLoad<CycleHistory>
    let onRetry: () -> Void
    var divider = true
    @State private var expanded = false

    var body: some View {
        let today = LocalDay.today()
        let total = max(cycle.lastHalving.days(until: cycle.nextHalvingEstimate), 1)
        let elapsed = min(max(cycle.lastHalving.days(until: today), 0), total)
        let remaining = max(today.days(until: cycle.nextHalvingEstimate), 0)

        MarketRow(
            title: L("insights_halving_title"),
            secondary: L("insights_cycle_progress", elapsed * 100 / total),
            value: CycleFormat.mediumDate(cycle.nextHalvingEstimate),
            divider: divider,
            expanded: $expanded,
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                Text(L("insights_halving_countdown", count: remaining, remaining, CycleFormat.mediumDate(cycle.nextHalvingEstimate)))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                CycleProgressBar(fraction: Double(elapsed) / Double(total), label: L("insights_halving_title"))
                    .padding(.top, Spacing.sm)
                    .padding(.bottom, 16)
                Text(L("insights_cycle_chart"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                switch history {
                case .loading:
                    CycleHistoryChart.skeleton
                case .failed:
                    CycleFailedRow(onRetry: onRetry)
                case .loaded(let value):
                    CycleHistoryChart(history: value, currentHalving: cycle.lastHalving)
                }
            })
        )
    }
}

/// Marke im Zyklus-Chart: Hoch oder Tief eines Zyklus.
struct CycleChartMarker: Equatable {
    let seriesIndex: Int
    let isTop: Bool
    let isCurrentCycle: Bool
    let marker: CycleMarker
    /// Zweites Hoch/Tief (Doppel-Top/-Bottom): kleiner, hohler Punkt.
    var isSecondary = false
}

/// Logarithmische y-Achse und lineare x-Achse (Tage seit Halving) des Zyklus-Charts.
struct CycleChartScale: Equatable {
    static let maxDay: Double = 1440
    let minLog: Double
    let maxLog: Double

    func point(day: Int, value: Double, width w: CGFloat, height h: CGFloat) -> CGPoint {
        CGPoint(
            x: CGFloat(Double(day) / Self.maxDay) * w,
            y: h - CGFloat((log(value) - minLog) / (maxLog - minLog)) * h
        )
    }
}

/// Frühere Zyklen übereinander: Kurs als Vielfaches des Halving-Tageskurses
/// (logarithmisch), Tage seit dem Halving auf der x-Achse. Der laufende Zyklus
/// in der Akzentfarbe, mit Punkt für heute. Je Zyklus ein Punkt am Hoch und
/// (nach mindestens 30 % Rückgang) am Tief danach, dazu kleinere hohle Punkte
/// für Doppel-Top/-Bottom; Antippen zeigt eine Sprechblase.
struct CycleHistoryChart: View {
    let history: CycleHistory
    /// Achsenbereich und Marken — einmal je Datenstand berechnet, nicht je Frame.
    private let scale: CycleChartScale
    private let markers: [CycleChartMarker]

    @Environment(\.appAccent) private var accent
    /// Richtung der Oberfläche — die Zeichnung ist immer links→rechts, der Text der Sprechblase nicht.
    @Environment(\.layoutDirection) private var layoutDirection
    /// Höchstens eine Sprechblase; Index in `markers`.
    @State private var selected: Int?

    private static let maxDay: Double = CycleChartScale.maxDay
    private static let chartHeight: CGFloat = 160
    private static let chartTopPadding: CGFloat = 10
    private static let labelWidth: CGFloat = 16
    /// Trefferfläche 32 pt um die kleinen Punkte.
    private static let hitRadius: CGFloat = 16

    init(history: CycleHistory, currentHalving: LocalDay) {
        self.history = history
        let values = history.series.flatMap { $0.points.map(\.multiple) }.filter { $0 > 0 }
        self.scale = CycleChartScale(
            minLog: min(log(values.min() ?? 0.5), log(0.5)),
            maxLog: max(log(values.max() ?? 2.0), log(2.0))
        )
        var list: [CycleChartMarker] = []
        for (i, s) in history.series.enumerated() {
            let current = s.halving == currentHalving
            if let top = s.top {
                list.append(CycleChartMarker(seriesIndex: i, isTop: true, isCurrentCycle: current, marker: top))
            }
            if let bottom = s.bottom {
                list.append(CycleChartMarker(seriesIndex: i, isTop: false, isCurrentCycle: current, marker: bottom))
            }
            if let second = s.secondTop {
                list.append(CycleChartMarker(seriesIndex: i, isTop: true, isCurrentCycle: current, marker: second, isSecondary: true))
            }
            if let second = s.secondBottom {
                list.append(CycleChartMarker(seriesIndex: i, isTop: false, isCurrentCycle: current, marker: second, isSecondary: true))
            }
        }
        self.markers = list
    }

    var body: some View {
        if !history.series.isEmpty {
            content(history.series)
        }
    }

    /// Ältere Zyklen grau (älteste am hellsten), der laufende in der Akzentfarbe.
    private func colors(_ series: [CycleSeries]) -> [Color] {
        let older = [AppColors.onSurfaceVariant.opacity(0.35), AppColors.onSurfaceVariant.opacity(0.65)]
        return series.indices.map { i in
            i == series.count - 1 ? accent.primary : older[min(i, older.count - 1)]
        }
    }

    /// Nächste Marke innerhalb der Trefferfläche, Koordinaten der Zeichenfläche.
    private func marker(at location: CGPoint, width: CGFloat) -> Int? {
        var best: (index: Int, distance: CGFloat)?
        for (i, m) in markers.enumerated() {
            let p = scale.point(day: m.marker.day, value: m.marker.multiple, width: width, height: Self.chartHeight)
            let d = hypot(p.x - location.x, p.y - location.y)
            if d <= Self.hitRadius, best == nil || d < best!.distance { best = (i, d) }
        }
        return best?.index
    }

    @ViewBuilder
    private func content(_ series: [CycleSeries]) -> some View {
        let scale = self.scale
        let markers = self.markers
        let selected = self.selected
        let lineColors = colors(series)
        let grid = AppColors.outlineVariant
        let dotColor = accent.primary
        let ringColor = AppColors.container

        VStack(alignment: .leading, spacing: 0) {
            Canvas { context, size in
                let w = size.width
                let h = size.height
                func x(_ day: Double) -> CGFloat { CGFloat(day / Self.maxDay) * w }
                func y(_ v: Double) -> CGFloat { scale.point(day: 0, value: v, width: w, height: h).y }

                // Hilfslinien: je Jahr senkrecht, 1× waagrecht
                var gridPath = Path()
                for year in 1...3 {
                    let gx = x(Double(year * 360))
                    gridPath.move(to: CGPoint(x: gx, y: 0))
                    gridPath.addLine(to: CGPoint(x: gx, y: h))
                }
                gridPath.move(to: CGPoint(x: 0, y: y(1.0)))
                gridPath.addLine(to: CGPoint(x: w, y: y(1.0)))
                context.stroke(gridPath, with: .color(grid), lineWidth: 1)

                for (i, s) in series.enumerated() {
                    var path = Path()
                    var started = false
                    for p in s.points where p.multiple > 0 {
                        let point = CGPoint(x: x(Double(p.day)), y: y(p.multiple))
                        if started {
                            path.addLine(to: point)
                        } else {
                            path.move(to: point)
                            started = true
                        }
                    }
                    let current = i == series.count - 1
                    context.stroke(
                        path,
                        with: .color(lineColors[i]),
                        style: StrokeStyle(lineWidth: current ? 2 : 1.25, lineCap: .round, lineJoin: .round)
                    )
                }

                if let last = series.last?.points.last(where: { $0.multiple > 0 }) {
                    let c = CGPoint(x: x(Double(last.day)), y: y(last.multiple))
                    let r: CGFloat = 3.5
                    context.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)),
                                 with: .color(dotColor))
                }

                // Hoch/Tief: kleiner Punkt in Zyklusfarbe mit Ring in Kartenfarbe
                for (i, m) in markers.enumerated() {
                    let c = scale.point(day: m.marker.day, value: m.marker.multiple, width: w, height: h)
                    let base: CGFloat = m.isSecondary ? 2.6 : 3.25
                    let r: CGFloat = i == selected ? base + 1 : base
                    let ring = r + 1.25
                    context.fill(Path(ellipseIn: CGRect(x: c.x - ring, y: c.y - ring, width: 2 * ring, height: 2 * ring)),
                                 with: .color(ringColor))
                    context.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)),
                                 with: .color(lineColors[m.seriesIndex]))
                    // Doppel-Top/-Bottom: hohl, damit das Haupt-Hoch/-Tief hervorsticht
                    if m.isSecondary {
                        let hole = r * 0.45
                        context.fill(Path(ellipseIn: CGRect(x: c.x - hole, y: c.y - hole, width: 2 * hole, height: 2 * hole)),
                                     with: .color(ringColor))
                    }
                }
            }
            .frame(height: Self.chartHeight)
            .padding(.top, Self.chartTopPadding)
            .overlay {
                GeometryReader { geo in
                    ZStack(alignment: .topLeading) {
                        Color.clear
                            .contentShape(Rectangle())
                            .gesture(
                                SpatialTapGesture().onEnded { value in
                                    // Punkt in Koordinaten der Zeichenfläche (ohne Abstand oben)
                                    let location = CGPoint(x: value.location.x,
                                                           y: value.location.y - Self.chartTopPadding)
                                    let hit = marker(at: location, width: geo.size.width)
                                    // Gleiche Marke nochmals oder daneben getippt: schliessen
                                    self.selected = (hit == nil || hit == self.selected) ? nil : hit
                                }
                            )
                        if let selected, selected < markers.count {
                            let m = markers[selected]
                            let p = scale.point(day: m.marker.day, value: m.marker.multiple,
                                                width: geo.size.width, height: Self.chartHeight)
                            CycleBubbleLayout(anchor: CGPoint(x: p.x, y: p.y + Self.chartTopPadding)) {
                                CycleMarkerBubble(marker: m)
                                    .environment(\.layoutDirection, layoutDirection)
                            }
                            .allowsHitTesting(false)
                        }
                    }
                }
            }
            .onChange(of: history) { self.selected = nil }
            // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
            .environment(\.layoutDirection, .leftToRight)
            // VoiceOver: je Zyklus ein Satz (Stand seit dem Halving, Hoch, Tief, Doppel-Top/-Bottom)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L("insights_cycle_chart"))
            .accessibilityValue(Self.accessibilityText(series))

            // Achse: Jahresmarken genau unter den Hilfslinien (gleiche Breite wie die Zeichnung)
            GeometryReader { geo in
                let width = geo.size.width
                ZStack(alignment: .topLeading) {
                    ForEach(0...4, id: \.self) { year in
                        let raw = width * CGFloat(Double(year * 360) / Self.maxDay)
                        let half = Self.labelWidth / 2
                        Text(verbatim: LocaleNumbers.integer(year))
                            .font(.caption2)
                            .monospacedDigit()
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .frame(width: Self.labelWidth, height: 16)
                            .position(x: min(max(raw, half), max(width - half, half)), y: 8)
                    }
                }
                .frame(width: width, height: 16, alignment: .topLeading)
            }
            .frame(height: 16)
            .padding(.top, 4)
            // Jahresmarken unter den Hilfslinien: ebenfalls immer von links nach rechts
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityHidden(true)

            Text(L("insights_chart_axis"))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)

            HStack(spacing: 0) {
                ForEach(Array(series.enumerated()), id: \.offset) { i, s in
                    Circle()
                        .fill(lineColors[i])
                        .frame(width: 10, height: 10)
                        .accessibilityHidden(true)
                    Text(verbatim: LocaleNumbers.integer(s.halving.year))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(AppColors.onSurface)
                        .padding(.leading, 4)
                        .padding(.trailing, 12)
                }
            }
            .padding(.top, 8)

            Text(L("insights_chart_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, Spacing.xs)
            CycleSourceText(text: L("insights_source_history"))
        }
    }
}

extension CycleHistoryChart {
    /// Platzhalter in der Form des Charts: Fläche (160 pt + Abstand), Jahresachse, Legende;
    /// feste Texte (Achse, Hinweis, Quelle) stehen schon echt da.
    static var skeleton: some View {
        VStack(alignment: .leading, spacing: 0) {
            CycleSkeleton(label: L("loading_hint")) {
                VStack(alignment: .leading, spacing: 0) {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(AppColors.containerHighest)
                        .frame(height: chartHeight)
                        .padding(.top, chartTopPadding)
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(AppColors.containerHighest)
                        .frame(height: 16)
                        .padding(.top, 4)
                }
            }
            Text(L("insights_chart_axis"))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
            CycleSkeleton {
                Text(verbatim: " ")
                    .font(.caption.weight(.medium))
                    .cycleSkeletonBar(width: 180)
            }
            .padding(.top, 8)
            Text(L("insights_chart_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, Spacing.xs)
            CycleSourceText(text: L("insights_source_history"))
        }
    }

    /// «Zyklus 2020: gestiegen um 1’130 % seit dem Halving, Tag 1440. Hoch 69’000 USD an Tag 547. …»
    /// Je Zyklus: Stand, Hoch, Doppel-Hoch, Tief, Doppel-Tief — wie Android.
    static func accessibilityText(_ series: [CycleSeries]) -> String {
        func price(_ m: CycleMarker) -> String { PriceFormat.priceWithCurrency(m.priceUsd, "USD") }
        var sentences: [String] = []
        for s in series {
            guard let last = s.points.last(where: { $0.multiple > 0 }) else { continue }
            sentences.append(L("a11y_cycle", LocaleNumbers.integer(s.halving.year), spokenPercent(last.multiple - 1), last.day))
            if let m = s.top { sentences.append(L("a11y_cycle_top", price(m), m.day)) }
            if let m = s.secondTop { sentences.append(L("a11y_cycle_double_top", price(m), m.day)) }
            if let m = s.bottom { sentences.append(L("a11y_cycle_bottom", price(m), m.day)) }
            if let m = s.secondBottom { sentences.append(L("a11y_cycle_double_bottom", price(m), m.day)) }
        }
        return sentences.isEmpty ? L("a11y_chart_empty") : sentences.joined(separator: " ")
    }

    /// Anteil als «gestiegen um 1’130 %» / «gefallen um 40 %» / «unverändert».
    private static func spokenPercent(_ change: Double) -> String {
        let pct = change * 100
        guard pct.isFinite, abs(pct) >= 0.5 else { return L("a11y_change_flat") }
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale.current
        f.usesGroupingSeparator = true
        f.maximumFractionDigits = 0
        let number = (f.string(from: NSNumber(value: abs(pct))) ?? String(Int(abs(pct).rounded()))) + " %"
        return L(pct > 0 ? "a11y_change_up" : "a11y_change_down", number)
    }
}

/// Sprechblase neben einer Marke, immer innerhalb des Charts: bevorzugt rechts
/// oberhalb, sonst links bzw. unterhalb.
struct CycleBubbleLayout: Layout {
    let anchor: CGPoint
    private static let gap: CGFloat = 10

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width * 0.75, height: nil))
            // Waagrecht: rechts vom Punkt, sonst links, notfalls an den Rand geschoben
            var x = anchor.x + Self.gap
            if x + size.width > bounds.width { x = anchor.x - Self.gap - size.width }
            x = min(max(x, 0), max(bounds.width - size.width, 0))
            // Senkrecht: über dem Punkt, sonst darunter
            var y = anchor.y - Self.gap - size.height
            if y < 0 { y = anchor.y + Self.gap }
            y = min(max(y, 0), max(bounds.height - size.height, 0))
            subview.place(
                at: CGPoint(x: bounds.minX + x, y: bounds.minY + y),
                anchor: .topLeading,
                proposal: ProposedViewSize(size)
            )
        }
    }
}

/// Inhalt der Sprechblase: „Top 2021 · 69’000 USD“ / „+1’130 % seit Halving · 10.11.21“.
struct CycleMarkerBubble: View {
    let marker: CycleChartMarker

    private var label: String {
        let year = LocaleNumbers.integer(marker.marker.date.year)
        if marker.isSecondary {
            return L(marker.isTop ? "cycle_marker_double_top" : "cycle_marker_double_bottom", year)
        }
        if marker.isTop {
            return marker.isCurrentCycle ? L("cycle_marker_high_so_far") : L("cycle_marker_top", year)
        }
        return L("cycle_marker_bottom", year)
    }

    private var change: String {
        let pct = Self.percent(marker.marker.change)
        return marker.isTop ? L("cycle_since_halving", pct) : L("cycle_from_top", pct)
    }

    /// Prozent mit Vorzeichen und Tausendertrennung, ohne Nachkommastellen: „+1’130 %“.
    static func percent(_ change: Double) -> String {
        let pct = change * 100
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale.current
        f.usesGroupingSeparator = true
        f.maximumFractionDigits = 0
        let number = f.string(from: NSNumber(value: abs(pct))) ?? String(Int(abs(pct).rounded()))
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr((pct >= 0 ? "+" : "−") + number + " %")
    }

    /// Kurzes Datum im Format des Geräts (`FormatStyle.SHORT`).
    static func shortDate(_ day: LocalDay) -> String {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .none
        return f.string(from: day.date)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: "\(label) · \(PriceFormat.priceWithCurrency(marker.marker.priceUsd, "USD"))")
                .font(.caption.weight(.semibold))
            Text(verbatim: "\(change) · \(Self.shortDate(marker.marker.date))")
                .font(.caption2)
        }
        .monospacedDigit()
        .foregroundStyle(AppColors.surface)
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.sm)
        .background(AppColors.onSurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .shadow(color: AppColors.shadow.opacity(0.15), radius: 2, y: 1)
    }
}

// MARK: - Fear & Greed

enum CycleFearGreedStyle {
    static func level(_ value: Int) -> Int {
        if value < 25 { return 0 }
        if value < 45 { return 1 }
        if value <= 55 { return 2 }
        if value <= 75 { return 3 }
        return 4
    }

    static func labelKey(_ value: Int) -> String {
        switch level(value) {
        case 0: "fng_extreme_fear"
        case 1: "fng_fear"
        case 2: "fng_neutral"
        case 3: "fng_greed"
        default: "fng_extreme_greed"
        }
    }

    /// Gleiche fünf Farben wie die Zonen.
    static func color(_ value: Int) -> Color { CycleZonePalette.gradient[level(value)] }
}

/// Fear & Greed als erste Zeile unter «Einordnung»: Wert rechts, Stufe und Vortag darunter,
/// die Skala 0–100 in voller Breite direkt unter der Zeile. Tippen zeigt Verlauf und Quelle.
/// Wie `FearGreedRow` in Android.
struct CycleFearGreedRow: View {
    let state: CycleLoad<FearGreed>
    let onRetry: () -> Void
    var divider = false
    /// Herkunft und Stand (alternative.me) für die Nebenzeile.
    var stamp: DataStamp? = nil
    @State private var expanded = false

    var body: some View {
        let fg = state.value
        let label = fg.map { L(CycleFearGreedStyle.labelKey($0.value)) } ?? ""
        MarketRow(
            title: L("insights_fng_title"),
            secondary: fg?.yesterday.map { L("market_row_fng_yesterday", label, LocaleNumbers.integer($0)) } ?? label,
            value: fg.map { LocaleNumbers.integer($0.value) },
            state: Self.rowState(state),
            divider: divider,
            stamp: stamp,
            onRetry: onRetry,
            expanded: fg == nil ? nil : $expanded,
            // Skala bleibt auch beim Laden und bei Fehler stehen (grau) — darunter springt nichts
            below: AnyView(scale(fg?.value)),
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                if let fg {
                    Text(L("insights_fng_history",
                           fg.yesterday.map { LocaleNumbers.integer($0) } ?? "—",
                           fg.weekAgo.map { LocaleNumbers.integer($0) } ?? "—",
                           fg.monthAgo.map { LocaleNumbers.integer($0) } ?? "—"))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    CycleSourceText(text: L("insights_source_fng"))
                }
            })
        )
    }

    /// Skala 0–100 mit Markierung; nil = grauer Platzhalter in derselben Höhe (18 pt).
    @ViewBuilder
    private func scale(_ value: Int?) -> some View {
        if let value {
            CycleScaleBar(colors: CycleZonePalette.gradient, fraction: Double(value) / 100)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L("a11y_gauge", L("fng_extreme_fear"), L("fng_extreme_greed"), value))
        } else {
            Capsule()
                .fill(AppColors.containerHighest)
                .frame(height: 8)
                .frame(maxWidth: .infinity, minHeight: 18, maxHeight: 18)
                .accessibilityHidden(true)
        }
    }

    /// Zustand der Zeile aus dem Ladezustand; Fehler mit «Etwas ist schiefgelaufen».
    static func rowState<T>(_ load: CycleLoad<T>, failure: String = L("something_went_wrong")) -> MarketRowState {
        switch load {
        case .loading: .loading
        case .failed: .failed(failure)
        case .loaded: .shown
        }
    }
}

// MARK: - Krypto-Markt (Marktkapitalisierung & Volumen)

/// «Krypto-Markt» als erste Zeile unter «Daten»: rechts die gesamte Marktkapitalisierung mit
/// 24-h-Veränderung, darunter das 24-h-Volumen, in der Umrechnungswährung (sonst USD). Lädt
/// mit der Dominanz (eine CoinGecko-Abfrage). Beim Laden ein form-gleicher Platzhalter, ohne
/// Daten «gerade nicht verfügbar» mit «Erneut». Tippen zeigt die Quelle.
struct CycleMarketCapRow: View {
    let state: CycleLoad<GlobalMarket>
    /// Umrechnungswährung (`portfolioCurrency`).
    let currency: String
    let onRetry: () -> Void
    var divider = false
    /// Herkunft und Stand (CoinGecko) für die Nebenzeile.
    var stamp: DataStamp? = nil
    @State private var expanded = false
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let market = state.value
        let values = market?.values(currency: currency)
        let capText = values.map { CycleFormat.compactMoney($0.marketCap, $0.code) }
        let volumeText = values.map { CycleFormat.compactMoney($0.volume, $0.code) }
        let change = market?.change24hPercent
        let formatted = PriceFormat.changePercent(change)
        let rowState: MarketRowState = state.isLoading ? .loading
            // Fehler oder keine Werte (auch nicht in USD)
            : (capText == nil ? .failed(L("pulse_unavailable")) : .shown)
        MarketRow(
            title: L("market_cap_title"),
            secondary: volumeText.map { "\(L("market_volume_label")) \($0)" } ?? "",
            value: capText,
            state: rowState,
            divider: divider,
            change: change.map { c in formatted.map { "\(PriceFormat.changeArrow(c)) \($0)" } ?? PriceFormat.zeroPercent() },
            // Vorzeichen und Pfeil folgen der Richtung, die Farbe den Kursfarben (Schema, Kontrast, Tausch)
            changeColor: change.flatMap { c in formatted.map { _ in priceColors.forChange(c, highContrast: highContrast, inverted: inverted) } }
                ?? AppColors.onSurfaceVariant,
            // VoiceOver: ein Satz — Titel, Marktkapitalisierung mit Veränderung, Volumen
            spoken: capText.map { cap in
                A11y.join([
                    L("market_cap_title"),
                    "\(L("market_cap_label")) \(cap)",
                    A11y.change(change),
                    "\(L("market_volume_label")) \(volumeText ?? "")",
                ])
            },
            stamp: stamp,
            onRetry: onRetry,
            expanded: capText == nil ? nil : $expanded,
            details: AnyView(CycleSourceText(text: L("market_cap_source")))
        )
    }
}

// MARK: - Dominanz & Altcoin-Saison

/// Bitcoin-Dominanz und Altcoin-Saison als zwei Zeilen unter «Einordnung». Tippen zeigt bei
/// der Dominanz die Anteile als Balken, bei der Altcoin-Saison Balken, Erklärung, Stand mit
/// «Aktualisieren» und die Quelle. Wie `DominanceRows` in Android.
struct CycleDominanceRows: View {
    let dominance: CycleLoad<Dominance>
    let altSeason: CycleLoad<AltSeason>
    let onRetry: () -> Void
    /// Stand der gezeigten Altcoin-Saison (Zwischenspeicher 3 h); nil = noch nichts.
    var altSeasonAsOf: Int64? = nil
    var altSeasonRefreshing = false
    var onRefreshAltSeason: () -> Void = {}
    /// Herkunft und Stand der Dominanz (CoinGecko) bzw. der Altcoin-Saison (Kerzen-Anbieter).
    var dominanceStamp: DataStamp? = nil
    var altSeasonStamp: DataStamp? = nil
    @State private var dominanceExpanded = false
    @State private var altExpanded = false

    var body: some View {
        let d = dominance.value
        let a = altSeason.value
        let altLabel = a.map { L($0.index >= 75 ? "altseason_alt" : ($0.index <= 25 ? "altseason_btc" : "altseason_mixed")) } ?? ""
        VStack(spacing: 0) {
            MarketRow(
                title: L("insights_dominance_title"),
                // BTC steht rechts; hier ETH (ohne ETH-Wert bleibt die Zeile leer, gleiche Höhe)
                secondary: d?.eth.map { L("insights_dominance_eth", CycleFormat.percent1($0)) } ?? "",
                value: d.map { CycleFormat.percent1($0.btc) },
                state: CycleFearGreedRow.rowState(dominance),
                stamp: dominanceStamp,
                onRetry: onRetry,
                expanded: d == nil ? nil : $dominanceExpanded,
                details: AnyView(VStack(alignment: .leading, spacing: 0) {
                    if let d {
                        shareBar(d)
                        CycleSourceText(text: L("insights_source_dominance"))
                    }
                })
            )
            MarketRow(
                title: L("insights_altseason_title"),
                // Anbieter und Alter («… · Binance · heute 14:05») hängt MarketRow aus dem Stand an
                secondary: altLabel,
                value: a.map { LocaleNumbers.integer($0.index) },
                state: CycleFearGreedRow.rowState(altSeason),
                stamp: altSeasonStamp,
                onRetry: onRetry,
                expanded: a == nil ? nil : $altExpanded,
                details: AnyView(VStack(alignment: .leading, spacing: 0) {
                    if let a {
                        CycleProgressBar(fraction: Double(a.index) / 100, label: L("insights_altseason_title"))
                        Text(L("insights_altseason_value", count: a.outperformers, a.outperformers, a.total))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .padding(.top, Spacing.xs)
                        if let asOf = altSeasonAsOf {
                            altSeasonAsOfRow(asOf)
                        }
                        CycleSourceText(text: L("insights_source_dominance"))
                    }
                })
            )
        }
    }

    /// «Stand 14:05» und ein kleines «Aktualisieren»: erst 5 Min. nach dem letzten Stand
    /// wieder aktiv (`CycleCachePolicy.manualMinInterval`), sonst gilt der 3-h-Zwischenspeicher.
    private func altSeasonAsOfRow(_ asOf: Int64) -> some View {
        TimelineView(.periodic(from: .now, by: 15)) { context in
            let now = Int64(context.date.timeIntervalSince1970 * 1000)
            let enabled = !altSeasonRefreshing
                && CycleCachePolicy.canManualRefresh(savedAt: asOf, now: now, minInterval: CycleCachePolicy.manualMinInterval)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(L("pulse_updated", PriceFormat.time(asOf)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(L("action_refresh"), action: onRefreshAltSeason)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.borderless)
                    .disabled(!enabled)
            }
            .padding(.top, 8)
        }
    }

    /// Anteile als Balken: BTC · ETH · übrige.
    private func shareBar(_ d: Dominance) -> some View {
        let btc = max(d.btc, 0.1)
        let eth = max(d.eth ?? 0, 0)
        let rest = max(100 - d.btc - eth, 0)
        let sum = btc + eth + rest
        return GeometryReader { geo in
            HStack(spacing: 0) {
                Rectangle()
                    .fill(AssetColors.bitcoin)
                    .frame(width: geo.size.width * CGFloat(btc / sum))
                if eth > 0 {
                    Rectangle()
                        .fill(AssetColors.ethereum)
                        .frame(width: geo.size.width * CGFloat(eth / sum))
                }
                if rest > 0 {
                    Rectangle()
                        .fill(AppColors.outlineVariant)
                        .frame(width: geo.size.width * CGFloat(rest / sum))
                }
            }
        }
        .frame(height: 10)
        .clipShape(Capsule())
        .accessibilityHidden(true)
    }
}

// MARK: - Netzwerkgebühren (#167)

/// Netzwerkgebühren als Zeile unter «Daten»: rechts die normale Ethereum-Gebühr, darunter
/// Bitcoin. Tippen klappt alle Netze (Ethereum und Bitcoin mit langsam/normal/schnell, die
/// L2-/Seitennetze mit der normalen Gebühr, rechts die Kosten einer einfachen Überweisung),
/// den Hinweis auf aktive Gas-Alarme und die Quelle auf — wie `GasSummaryRow`.
struct CycleGasRow: View {
    let state: CycleLoad<GasReport>
    let ethAlertGwei: Double
    let btcAlertSat: Int
    let onRetry: () -> Void
    var divider = true
    /// Herkunft (Ethereum-Knoten, mempool.space) und Stand für die Nebenzeile.
    var stamp: DataStamp? = nil

    @Environment(\.appAccent) private var accent
    @State private var expanded = false

    var body: some View {
        let report = state.value
        let eth = report?.evm.first { $0.network == .ethereum }
        let btcText = report?.btc.map { "Bitcoin \(GasFees.formatGwei($0.normal)) sat/vB" }
        MarketRow(
            title: L("gas_title"),
            secondary: [eth?.network.title, btcText].compactMap { $0 }.joined(separator: " · "),
            value: eth.map { "\(GasFees.formatGwei($0.normalGwei)) gwei" }
                ?? report?.btc.map { "\(GasFees.formatGwei($0.normal)) sat/vB" },
            state: CycleFearGreedRow.rowState(state),
            divider: divider,
            stamp: stamp,
            onRetry: onRetry,
            expanded: report == nil ? nil : $expanded,
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                if let report {
                    networks(report)
                    alertText
                    CycleSourceText(text: L("gas_source"))
                }
            })
        )
    }

    private func networks(_ report: GasReport) -> some View {
        VStack(spacing: 0) {
            ForEach(report.evm, id: \.network) { gas in
                row(
                    name: gas.network.title,
                    value: GasFees.formatGwei(gas.normalGwei),
                    unit: "gwei",
                    cost: gas.transferUsd,
                    detail: gas.network == .ethereum && gas.fastGwei > gas.slowGwei
                        ? L("gas_slow_fast", GasFees.formatGwei(gas.slowGwei), GasFees.formatGwei(gas.fastGwei))
                        : nil
                )
            }
            if let btc = report.btc {
                row(
                    name: "Bitcoin",
                    value: GasFees.formatGwei(btc.normal),
                    unit: "sat/vB",
                    cost: btc.transferUsd,
                    detail: btc.fast > btc.slow
                        ? L("gas_slow_fast", GasFees.formatGwei(btc.slow), GasFees.formatGwei(btc.fast))
                        : nil
                )
            }
        }
    }

    /// Aktive Gas-Alarme — stehen in den Einstellungen fest, also auch im Platzhalter echt.
    @ViewBuilder
    private var alertText: some View {
        let alerts = [
            ethAlertGwei > 0 ? "Ethereum < \(GasFees.formatGwei(ethAlertGwei)) gwei" : nil,
            btcAlertSat > 0 ? "Bitcoin < \(LocaleNumbers.integer(btcAlertSat)) sat/vB" : nil,
        ].compactMap { $0 }
        if !alerts.isEmpty {
            Text(L("gas_alert_active", alerts.joined(separator: " · ")))
                .font(.footnote)
                .foregroundStyle(accent.primary)
                .padding(.top, 8)
        }
    }

    private func row(name: String, value: String, unit: String, cost: Double?, detail: String?) -> some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: name).font(.body)
                if let detail {
                    Text(detail)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 1) {
                Text(verbatim: "\(value) \(unit)")
                    .font(.body.weight(.semibold).monospacedDigit())
                if let cost {
                    Text(L("gas_transfer_cost", GasFees.formatUsd(cost)))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
        .padding(.vertical, Spacing.sm)
        .accessibilityElement(children: .combine)
    }
}
