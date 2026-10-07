import SwiftUI

extension PortfolioHistoryRange {
    /// Kurzname auf dem Umschalter («30 T»).
    var shortLabel: String {
        switch self {
        case .week: return L("portfolio_history_range_7d")
        case .month: return L("portfolio_history_range_30d")
        case .year: return L("portfolio_history_range_1y")
        }
    }

    /// Ausgeschrieben («30 Tage») für VoiceOver und den Chart-Satz.
    var longLabel: String {
        switch self {
        case .week: return L("portfolio_history_period_7d")
        case .month: return L("portfolio_history_period_30d")
        case .year: return L("portfolio_history_period_1y")
        }
    }
}

/// Wertverlauf über den Positionen — wie `PortfolioHistoryCard.kt`: Zeitraum (7 T / 30 T / 1 J),
/// Änderung über den Zeitraum (Betrag und Prozent mit Vorzeichen, Pfeil und Kursfarbe), Linie
/// mit Fläche und Hinweise (umgerechnet, Coins ohne Tageskurse, Käufe/Verkäufe im Zeitraum).
/// `history` nil = lädt (Platzhalter). Ohne Bewegung gezeichnet.
struct PortfolioHistoryCard: View {
    let history: PortfolioHistoryUi?
    @Binding var range: PortfolioHistoryRange

    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("portfolio_history_title"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)

            Picker(L("portfolio_history_title"), selection: $range) {
                ForEach(PortfolioHistoryRange.allCases) { option in
                    Text(option.shortLabel)
                        .accessibilityLabel(L("portfolio_history_range_a11y", option.longLabel))
                        .tag(option)
                }
            }
            .pickerStyle(.segmented)
            .padding(.top, 8)

            // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
            if let history {
                content(history)
            } else {
                skeleton
            }
        }
        .portfolioSurface()
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
            .accessibilityLabel(Self.spokenChange(series, period: period, unit: history.unit))

            let values = series.points.map(\.value)
            PortfolioHistoryChart(values: values, color: color, highContrast: highContrast)
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

    /// «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF».
    static func signedValue(_ value: Double, unit: String) -> String {
        let sign = PortfolioFormat.isZero(value) ? "" : (value > 0 ? "+" : "−")
        return sign + PriceFormat.valueWithCurrency(abs(value), unit)
    }

    /// «Wert über 30 Tage: gestiegen um 1’234.56 CHF, gestiegen um 4.20%».
    static func spokenChange(_ series: PortfolioHistorySeries, period: String, unit: String) -> String {
        let change = series.change ?? 0
        let amount: String
        if PortfolioFormat.isZero(change) {
            amount = L("a11y_change_flat")
        } else {
            amount = L(change > 0 ? "a11y_change_up" : "a11y_change_down", PriceFormat.valueWithCurrency(abs(change), unit))
        }
        if let percent = A11y.change(series.changePercent) {
            return L("portfolio_history_change_a11y", period, amount, percent)
        }
        return "\(period): \(amount)"
    }
}

/// Linie mit sanfter Fläche (wie das Mini-Chart der Merkliste); hoher Kontrast: Fläche kräftiger.
private struct PortfolioHistoryChart: View {
    let values: [Double]
    let color: Color
    let highContrast: Bool

    var body: some View {
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
