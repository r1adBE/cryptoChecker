import SwiftUI

// Zyklus-Vergleich im Halving-Abschnitt: Chart, Marken und Blasen — wie `MarketCycleChart.kt`.

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
            SkeletonPulse(label: L("loading_hint")) {
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
            SkeletonPulse {
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
