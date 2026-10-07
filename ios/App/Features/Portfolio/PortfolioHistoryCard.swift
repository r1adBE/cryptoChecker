import SwiftUI

extension PortfolioHistoryRange {
    /// Kurzname auf dem Chip («30 T», «Seit 1. Kauf»).
    var shortLabel: String {
        switch self {
        case .week: return L("portfolio_history_range_7d")
        case .month: return L("portfolio_history_range_30d")
        case .year: return L("portfolio_history_range_1y")
        case .sinceFirst: return L("portfolio_history_range_since_first")
        }
    }

    /// Ausgeschrieben («30 Tage», «seit dem ersten Kauf») für VoiceOver und den Chart-Satz.
    var longLabel: String {
        switch self {
        case .week: return L("portfolio_history_period_7d")
        case .month: return L("portfolio_history_period_30d")
        case .year: return L("portfolio_history_period_1y")
        case .sinceFirst: return L("portfolio_history_period_since_first")
        }
    }

    /// VoiceOver des Chips («Verlauf für 30 Tage zeigen» / «Verlauf seit dem ersten Kauf zeigen»).
    var chipAccessibility: String {
        self == .sinceFirst
            ? L("portfolio_history_range_since_first_a11y")
            : L("portfolio_history_range_a11y", longLabel)
    }
}

/// Wertverlauf über den Positionen — wie `PortfolioHistoryCard.kt`, standardmässig zugeklappt:
/// eine Zeile «Wertverlauf · 30 T ▲ +4.20%» (Änderung über den gewählten Zeitraum mit Vorzeichen,
/// Pfeil und Kursfarbe; Platzhalter, solange geladen wird). Tippen klappt auf: Zeitraum-Chips
/// (7 T / 30 T / 1 J / Seit 1. Kauf), Änderung als Betrag und Prozent, Linie mit Fläche und
/// Hinweise (umgerechnet, Coins ohne Tageskurse, Käufe/Verkäufe im Zeitraum, auf 5 Jahre
/// begrenzt). `history` nil = lädt. Ohne Bewegung gezeichnet.
struct PortfolioHistoryCard: View {
    let history: PortfolioHistoryUi?
    @Binding var range: PortfolioHistoryRange
    @Binding var expanded: Bool

    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if expanded {
                chips
                    .padding(.top, 8)
                // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
                if let history {
                    content(history)
                } else {
                    skeleton
                }
            }
        }
        .portfolioSurface()
    }

    // MARK: Kopfzeile

    /// Ein Verlauf eines anderen Zeitraums (gerade gewechselt) zählt wie «lädt».
    private var current: PortfolioHistoryUi? {
        guard let history, history.range == range else { return nil }
        return history
    }

    /// Ganze Zeile tippbar: Titel, zugeklappt mit Zeitraum und Änderung, Pfeil zum Auf-/Zuklappen.
    private var header: some View {
        Button {
            expanded.toggle()
        } label: {
            HStack(spacing: 8) {
                Text(expanded ? L("portfolio_history_title") : "\(L("portfolio_history_title")) · \(range.shortLabel)")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if !expanded {
                    collapsedChange
                }
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
            }
            .frame(minHeight: 30)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(headerAccessibility)
        .accessibilityHint(L(expanded ? "portfolio_history_collapse" : "portfolio_history_expand"))
        .accessibilityAddTraits(.isButton)
    }

    /// Zugeklappt: «▲ +4.20%» in der Kursfarbe; ohne Prozent der Betrag, ohne Verlauf «—»;
    /// beim Laden ein kleiner Platzhalter.
    @ViewBuilder
    private var collapsedChange: some View {
        if let current {
            let series = current.series
            if series.hasChart, let change = series.change {
                let color = PortfolioFormat.plColor(change, scheme: priceColors, highContrast: highContrast, inverted: inverted)
                let text = Self.collapsedText(series, change: change, unit: current.unit)
                HStack(spacing: 3) {
                    if !PortfolioFormat.isZero(change) {
                        ChangeArrowIcon(change: change)
                            .scaledFont(size: 9, weight: .bold, relativeTo: .subheadline)
                    }
                    Text(text)
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .lineLimit(1)
                }
                .foregroundStyle(color)
            } else {
                Text(verbatim: "—")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        } else {
            CycleSkeleton(label: nil) {
                Text(verbatim: " ")
                    .font(.subheadline)
                    .cycleSkeletonBar(width: 64)
            }
        }
    }

    private var headerAccessibility: String {
        let title = L("portfolio_history_title")
        if expanded { return title }
        guard let current else { return A11y.join([title, L("portfolio_history_loading")]) }
        guard current.series.hasChart else { return A11y.join([title, range.longLabel]) }
        return "\(title). \(Self.spokenChange(current.series, range: current.range, unit: current.unit))"
    }

    /// Zeitraum-Chips, waagrecht scrollbar (lange Übersetzungen von «Seit 1. Kauf»).
    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(PortfolioHistoryRange.allCases) { option in
                    let selected = option == range
                    Button {
                        range = option
                    } label: {
                        Text(option.shortLabel)
                            .font(.subheadline.weight(selected ? .semibold : .regular))
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                            .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(option.chipAccessibility)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.vertical, 2)
        }
        .scrollClipDisabled()
    }

    // MARK: Inhalt

    @ViewBuilder
    private func content(_ history: PortfolioHistoryUi) -> some View {
        let series = history.series
        let period = history.range.longLabel
        if series.hasChart {
            let change = series.change ?? 0
            let color = PortfolioFormat.plColor(change, scheme: priceColors, highContrast: highContrast, inverted: inverted)
            HStack(spacing: 8) {
                HStack(spacing: 3) {
                    // Pfeil nach Vorzeichen (nie getauscht), bei praktisch 0 keiner
                    if !PortfolioFormat.isZero(change) {
                        ChangeArrowIcon(change: change)
                            .scaledFont(size: 11, weight: .bold, relativeTo: .headline)
                    }
                    Text(Self.signedValue(change, unit: history.unit))
                        .font(.system(.headline, design: .rounded).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                .foregroundStyle(color)
                if let percent = series.changePercent {
                    PortfolioPlPill(percent: percent)
                }
            }
            .padding(.top, 12)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.spokenChange(series, range: history.range, unit: history.unit))

            let values = series.points.map(\.value)
            PortfolioHistoryChart(points: series.points, unit: history.unit, color: color, highContrast: highContrast)
                .frame(height: 140)
                .padding(.top, 10)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(A11y.chart(period: period, values: values,
                                               format: { PriceFormat.valueWithCurrency($0, history.unit) }))

            // Beginn und Ende der Achse (für VoiceOver im Chart-Satz enthalten)
            HStack {
                if let first = series.points.first {
                    Text(PortfolioFormat.date(LocalDay(epochDay: first.epochDay).date.millis))
                }
                Spacer(minLength: 8)
                Text(L("portfolio_history_today"))
            }
            .font(.caption2.monospacedDigit())
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.top, 4)
            .accessibilityHidden(true)
        } else {
            PortfolioHint(text: L(series.points.isEmpty ? "portfolio_history_unavailable" : "portfolio_history_too_short"))
                .padding(.top, 10)
        }

        if series.hasChart && history.converted {
            caption(L("portfolio_history_converted"))
        }
        if !series.skipped.isEmpty {
            caption(L("portfolio_history_without", series.skipped.joined(separator: ", ")))
        }
        if series.hasChart && series.tradesInRange {
            caption(L("portfolio_history_includes_trades"))
        }
        if series.capped {
            caption(L("portfolio_history_capped"))
        }
    }

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 6)
    }

    /// Platzhalter in der Form von Änderung und Chart.
    private var skeleton: some View {
        CycleSkeleton(label: L("portfolio_history_loading")) {
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: " ")
                    .font(.headline)
                    .cycleSkeletonBar(width: 150)
                    .padding(.top, 12)
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(AppColors.containerHighest)
                    .frame(height: 140)
                    .padding(.top, 10)
                Text(verbatim: " ")
                    .font(.caption2)
                    .cycleSkeletonBar(width: 80)
                    .padding(.top, 4)
            }
        }
    }

    // MARK: Texte

    /// Zugeklappt: «+4.20%» (bei praktisch 0 «0.00%»); ohne Prozent der Betrag.
    static func collapsedText(_ series: PortfolioHistorySeries, change: Double, unit: String) -> String {
        guard let percent = series.changePercent else { return signedValue(change, unit: unit) }
        return PriceFormat.changePercent(percent) ?? "0.00%"
    }

    /// «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF».
    static func signedValue(_ value: Double, unit: String) -> String {
        let sign = PortfolioFormat.isZero(value) ? "" : (value > 0 ? "+" : "−")
        return sign + PriceFormat.valueWithCurrency(abs(value), unit)
    }

    /// «Wert über 30 Tage: gestiegen um 1’234.56 CHF, gestiegen um 4.20%» bzw. «Wert seit dem ersten Kauf: …».
    static func spokenChange(_ series: PortfolioHistorySeries, range: PortfolioHistoryRange, unit: String) -> String {
        let period = range.longLabel
        let change = series.change ?? 0
        let amount: String
        if PortfolioFormat.isZero(change) {
            amount = L("a11y_change_flat")
        } else {
            amount = L(change > 0 ? "a11y_change_up" : "a11y_change_down", PriceFormat.valueWithCurrency(abs(change), unit))
        }
        if let percent = A11y.change(series.changePercent) {
            if range == .sinceFirst { return L("portfolio_history_change_since_first_a11y", amount, percent) }
            return L("portfolio_history_change_a11y", period, amount, percent)
        }
        return "\(period): \(amount)"
    }
}

/// Linie mit sanfter Fläche (wie das Mini-Chart der Merkliste); hoher Kontrast: Fläche kräftiger.
/// Kurz drücken, dann ziehen (wie das Chart im Aktionsblatt) zeigt Markierung, Punkt und
/// «7. Okt. 2026 · 12’345.67 CHF» des nächsten Tages; Loslassen blendet beides aus. Schnelles
/// Wischen bleibt dem Bildschirm (Scrollen). VoiceOver: Chart-Satz am Aufrufer.
private struct PortfolioHistoryChart: View {
    let points: [PortfolioHistoryPoint]
    let unit: String
    let color: Color
    let highContrast: Bool

    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState private var pressX: CGFloat? = nil

    /// Rand für die Strichbreite — gleich wie `historyPoints`.
    private static let inset: CGFloat = 2

    var body: some View {
        let values = points.map(\.value)
        GeometryReader { proxy in
            let rect = CGRect(origin: .zero, size: proxy.size)
            let scrub = scrubIndex(width: proxy.size.width)
            ZStack(alignment: .bottom) {
                HistoryAreaShape(values: values)
                    .fill(LinearGradient(colors: [color.opacity(highContrast ? 0.20 : 0.12), color.opacity(0)],
                                         startPoint: .top, endPoint: .bottom))
                HistoryLineShape(values: values)
                    .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                Rectangle()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 0.5)
            }
            .overlay(alignment: .topLeading) {
                // Ziehen: senkrechte Markierung und Punkt auf dem Wert
                if let i = scrub {
                    let all = historyPoints(values, in: rect)
                    if all.indices.contains(i) {
                        let p = all[i]
                        Path { path in
                            path.move(to: CGPoint(x: p.x, y: 0))
                            path.addLine(to: CGPoint(x: p.x, y: rect.maxY))
                        }
                        .stroke(AppColors.onSurface.opacity(0.6), lineWidth: 1)
                        Circle()
                            .fill(AppColors.onSurface)
                            .frame(width: 7, height: 7)
                            .position(p)
                        ScrubLabelLayout(centerX: p.x) {
                            scrubLabel(points[i])
                        }
                    }
                }
            }
            .contentShape(Rectangle())
            // Kurz drücken, dann ziehen; schnelles Wischen bleibt dem Bildschirm (Scrollen)
            .gesture(
                LongPressGesture(minimumDuration: 0.2)
                    .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
                    .updating($pressX) { value, state, _ in
                        if case .second(true, let drag?) = value { state = drag.location.x }
                    }
            )
            // Leichtes Ticken je neuem Tag (folgt den System-Einstellungen)
            .sensoryFeedback(.selection, trigger: scrub) { old, new in
                SheetChart.isNewCandle(previous: old, current: new)
            }
        }
        // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
        .environment(\.layoutDirection, .leftToRight)
    }

    /// Tag unter dem Finger; nil ohne Ziehen.
    private func scrubIndex(width: CGFloat) -> Int? {
        guard let x = pressX else { return nil }
        return PortfolioHistory.scrubIndex(x: Double(x), left: Double(Self.inset),
                                           width: Double(width - 2 * Self.inset), count: points.count)
    }

    /// «7. Okt. 2026 · 12’345.67 CHF».
    private func scrubLabel(_ point: PortfolioHistoryPoint) -> some View {
        // Datum wie an der Achse (Format der Sprache)
        let date = PortfolioFormat.date(LocalDay(epochDay: point.epochDay).date.millis)
        return Text(date + " · " + PriceFormat.valueWithCurrency(point.value, unit))
            .font(.system(.caption, design: .rounded).weight(.medium).monospacedDigit())
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .fixedSize()
    }
}

/// Punkte der Linie im Rechteck (2 pt Rand für die Strichbreite); flache Reihe in der Mitte.
private func historyPoints(_ values: [Double], in rect: CGRect) -> [CGPoint] {
    guard values.count >= 2, let lo = values.min(), let hi = values.max() else { return [] }
    let inset: CGFloat = 2
    let w = rect.width - 2 * inset
    let h = rect.height - 2 * inset
    guard w > 0, h > 0 else { return [] }
    let span = hi - lo
    let last = CGFloat(values.count - 1)
    return values.enumerated().map { index, value in
        let x = rect.minX + inset + w * CGFloat(index) / last
        let y: CGFloat = span > 0
            ? rect.minY + inset + h - CGFloat((value - lo) / span) * h
            : rect.minY + inset + h / 2
        return CGPoint(x: x, y: y)
    }
}

private struct HistoryLineShape: Shape {
    let values: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let points = historyPoints(values, in: rect)
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        return path
    }
}

private struct HistoryAreaShape: Shape {
    let values: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let points = historyPoints(values, in: rect)
        guard let first = points.first, let last = points.last else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        path.addLine(to: CGPoint(x: last.x, y: rect.maxY))
        path.addLine(to: CGPoint(x: first.x, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}
