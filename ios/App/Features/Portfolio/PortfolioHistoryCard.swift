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
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.appAccent) private var accent
    /// «Beträge verbergen»: Beträge als «•••», Prozente bleiben.
    @Environment(\.hidePortfolioAmounts) private var hideAmounts

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
    var current: PortfolioHistoryUi? {
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
                let text = Self.collapsedText(series, change: change, unit: current.unit, hidden: hideAmounts)
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
            SkeletonPulse(label: nil) {
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
        return "\(title). \(Self.spokenChange(current.series, range: current.range, unit: current.unit, hidden: hideAmounts))"
    }

    /// Zeitraum-Chips, waagrecht scrollbar (lange Übersetzungen von «Seit 1. Kauf»).
    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.xs) {
                ForEach(PortfolioHistoryRange.allCases) { option in
                    let selected = option == range
                    Button {
                        range = option
                    } label: {
                        Text(option.shortLabel)
                            .font(.subheadline.weight(selected ? .semibold : .regular))
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .padding(.vertical, Spacing.sm)
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
                    Text(PortfolioInsights.mask(Self.signedValue(change, unit: history.unit), hidden: hideAmounts))
                        .font(AppFont.amount(.headline))
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
            .accessibilityLabel(Self.spokenChange(series, range: history.range, unit: history.unit, hidden: hideAmounts))

            let values = series.points.map(\.value)
            PortfolioHistoryChart(points: series.points, unit: history.unit, color: color, highContrast: highContrast,
                                  hidden: hideAmounts)
                .frame(height: 140)
                .padding(.top, Spacing.sm)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(A11y.chart(period: period, values: values,
                                               format: { hideAmounts ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency($0, history.unit) }))

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
            // Wie der Chart darüber: Beginn links, «heute» rechts — auch bei Rechts-nach-links-Sprachen
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityHidden(true)
        } else {
            PortfolioHint(text: L(series.points.isEmpty ? "portfolio_history_unavailable" : "portfolio_history_too_short"))
                .padding(.top, Spacing.sm)
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

    func caption(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, Spacing.xs)
    }

    /// Platzhalter in der Form von Änderung und Chart.
    private var skeleton: some View {
        SkeletonPulse(label: L("portfolio_history_loading")) {
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: " ")
                    .font(.headline)
                    .cycleSkeletonBar(width: 150)
                    .padding(.top, 12)
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(AppColors.containerHighest)
                    .frame(height: 140)
                    .padding(.top, Spacing.sm)
                Text(verbatim: " ")
                    .font(.caption2)
                    .cycleSkeletonBar(width: 80)
                    .padding(.top, 4)
            }
        }
    }

    // MARK: Texte

    /// Zugeklappt: «+4.20%» (bei praktisch 0 «0.00%»); ohne Prozent der Betrag.
    static func collapsedText(_ series: PortfolioHistorySeries, change: Double, unit: String, hidden: Bool = false) -> String {
        guard let percent = series.changePercent else {
            return PortfolioInsights.mask(signedValue(change, unit: unit), hidden: hidden)
        }
        return PriceFormat.changePercent(percent) ?? PriceFormat.zeroPercent()
    }

    /// «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF».
    static func signedValue(_ value: Double, unit: String) -> String {
        let sign = PortfolioFormat.isZero(value) ? "" : (value > 0 ? "+" : "−")
        return sign + PriceFormat.valueWithCurrency(abs(value), unit)
    }

    /// «Wert über 30 Tage: gestiegen um 1’234.56 CHF, gestiegen um 4.20%» bzw. «Wert seit dem ersten Kauf: …».
    static func spokenChange(_ series: PortfolioHistorySeries, range: PortfolioHistoryRange, unit: String,
                             hidden: Bool = false) -> String {
        let period = range.longLabel
        let change = series.change ?? 0
        let amount: String
        if PortfolioFormat.isZero(change) {
            amount = L("a11y_change_flat")
        } else {
            let value = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(abs(change), unit)
            amount = L(change > 0 ? "a11y_change_up" : "a11y_change_down", value)
        }
        if let percent = A11y.change(series.changePercent) {
            if range == .sinceFirst { return L("portfolio_history_change_since_first_a11y", amount, percent) }
            return L("portfolio_history_change_a11y", period, amount, percent)
        }
        return "\(period): \(amount)"
    }
}
