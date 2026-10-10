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
/// begrenzt). `history` nil = lädt. Bei einer Umrechnungswährung mit Devisen-Tageskursen zusätzlich
/// der Umschalter «CHF | USDT | Vergleich» (`view`); «Vergleich» zeigt beide Kurven in Prozent und
/// den Währungseffekt. Ohne Bewegung gezeichnet.
struct PortfolioHistoryCard: View {
    let history: PortfolioHistoryUi?
    @Binding var range: PortfolioHistoryRange
    @Binding var expanded: Bool
    /// Gespeicherte Darstellung (`AppSettings.portfolioHistoryView`); gilt nur, wenn sie möglich ist.
    @Binding var view: PortfolioHistoryView

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
                // «CHF | USDT | Vergleich» nur bei einer Umrechnungswährung mit Tageskursen
                if let history, history.usdtSeries != nil {
                    viewSwitch(history)
                        .padding(.top, Spacing.sm)
                }
                // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
                if let shown {
                    content(shown)
                } else {
                    skeleton
                }
            }
        }
        .portfolioSurface()
    }

    // MARK: Kopfzeile

    /// Darstellung, die für diesen Verlauf gilt: USDT und Vergleich nur mit Devisen-Tageskursen
    /// (Vergleich zusätzlich mit Ausgangswert), sonst in der Währung wie bisher.
    var shownView: PortfolioHistoryView {
        guard let history else { return .currency }
        return PortfolioCompare.effectiveView(view, usdtAvailable: history.usdtSeries != nil,
                                              compareAvailable: history.compare != nil)
    }

    /// Angezeigter Verlauf (in USDT der unumgerechnete).
    var shown: PortfolioHistoryUi? { history?.forView(shownView) }

    /// Ein Verlauf eines anderen Zeitraums (gerade gewechselt) zählt wie «lädt».
    var current: PortfolioHistoryUi? {
        guard let shown, shown.range == range else { return nil }
        return shown
    }

    /// Ganze Zeile tippbar: Titel, zugeklappt mit Zeitraum und Änderung, Pfeil zum Auf-/Zuklappen.
    private var header: some View {
        Button {
            expanded.toggle()
        } label: {
            HStack(spacing: 8) {
                Text(expanded ? L("portfolio_history_title") : "\(L("portfolio_history_title")) · \(range.shortLabel)")
                    .sectionTitleStyle()
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

            if shownView == .compare, let compare = history.compare {
                PortfolioHistoryCompareSection(compare: compare, currency: history.unit, period: period)
            } else {
                singleChart(history, color: color, period: period)
            }
        } else {
            PortfolioHint(text: L(series.points.isEmpty ? "portfolio_history_unavailable" : "portfolio_history_too_short"))
                .padding(.top, Spacing.sm)
        }

        // Nur wenn die Devisen-Tageskurse fehlten und alles mit dem heutigen Kurs umgerechnet ist
        if series.hasChart && history.converted && history.approximateFx {
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

    /// Eine Linie in `unit` mit Achse darunter (Währung oder USDT).
    @ViewBuilder
    private func singleChart(_ history: PortfolioHistoryUi, color: Color, period: String) -> some View {
        let series = history.series
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
    }

    /// Umschalter «CHF | USDT | Vergleich» (ohne Ausgangswert ohne «Vergleich»); VoiceOver liest die
    /// Segmente mit Auswahl-Zustand («Wert in CHF», «Vergleich CHF und USDT in Prozent»).
    private func viewSwitch(_ history: PortfolioHistoryUi) -> some View {
        let options: [PortfolioHistoryView] = history.compare != nil ? PortfolioHistoryView.allCases : [.currency, .usdt]
        let selection = Binding<PortfolioHistoryView>(get: { shownView }, set: { view = $0 })
        return Picker(L("portfolio_history_title"), selection: selection) {
            ForEach(options) { option in
                Text(Self.viewLabel(option, currency: history.unit))
                    .accessibilityLabel(Self.viewAccessibility(option, currency: history.unit))
                    .tag(option)
            }
        }
        .pickerStyle(.segmented)
    }

    static func viewLabel(_ option: PortfolioHistoryView, currency: String) -> String {
        switch option {
        case .currency: return currency
        case .usdt: return PortfolioFormat.usdt
        case .compare: return L("portfolio_compare")
        }
    }

    static func viewAccessibility(_ option: PortfolioHistoryView, currency: String) -> String {
        switch option {
        case .currency: return L("portfolio_history_in_a11y", currency)
        case .usdt: return L("portfolio_history_in_a11y", PortfolioFormat.usdt)
        case .compare: return L("portfolio_compare_a11y", currency)
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

/// «Vergleich»: Legende, beide Kurven in Prozent seit dem Ausgangspunkt, Achse und darunter
/// «Währungseffekt: −5.10%» — am letzten bzw. am gezogenen Tag. VoiceOver: ein Chart-Satz mit
/// beiden Prozenten und dem Währungseffekt — wie `CompareChart` (Android).
struct PortfolioHistoryCompareSection: View {
    let compare: PortfolioCompareSeries
    let currency: String
    let period: String

    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.priceHighContrast) private var highContrast
    /// Gezogener Tag; nil = letzter Tag.
    @State private var scrub: Int?

    var body: some View {
        let color = PortfolioFormat.plColor(compare.currency.last, scheme: priceColors, highContrast: highContrast,
                                            inverted: inverted)
        VStack(alignment: .leading, spacing: 0) {
            legend(color)
                .padding(.top, Spacing.sm)
            PortfolioCompareChart(compare: compare, currency: currency, color: color, scrub: $scrub)
                .frame(height: 140)
                .padding(.top, Spacing.xs)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Self.spoken(compare, currency: currency, period: period))
            axis
                .padding(.top, 4)
            Text(L("portfolio_currency_effect", PortfolioFormat.signedPercent(effect)))
                .font(AppFont.amount(.subheadline, weight: .semibold))
                .foregroundStyle(AppColors.onSurface)
                .padding(.top, Spacing.xs)
        }
    }

    /// Währungseffekt am gezogenen bzw. letzten Tag (Prozent in der Währung minus Prozent in USDT).
    private var effect: Double {
        if let scrub, let value = compare.effect(at: scrub) { return value }
        return compare.effect
    }

    /// Legende «▬ CHF  ▬ USDT» (Strichstärke wie im Chart); für VoiceOver im Chart-Satz enthalten.
    private func legend(_ color: Color) -> some View {
        HStack(spacing: 6) {
            Capsule()
                .fill(color)
                .frame(width: 14, height: 3)
            Text(verbatim: currency)
                .fontWeight(.semibold)
            Capsule()
                .fill(AppColors.onSurfaceVariant)
                .frame(width: 14, height: 1.5)
                .padding(.leading, 6)
            Text(verbatim: PortfolioFormat.usdt)
        }
        .font(.caption2)
        .foregroundStyle(AppColors.onSurfaceVariant)
        .accessibilityHidden(true)
    }

    /// Beginn (Ausgangspunkt) und Ende der Achse; wie der Chart immer von links nach rechts.
    private var axis: some View {
        HStack {
            Text(PortfolioFormat.date(LocalDay(epochDay: compare.epochDays.first ?? 0).date.millis))
            Spacer(minLength: 8)
            Text(L("portfolio_history_today"))
        }
        .font(.caption2.monospacedDigit())
        .foregroundStyle(AppColors.onSurfaceVariant)
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityHidden(true)
    }

    /// «Vergleich in Prozent, 30 Tage: CHF gestiegen um 7.00%, USDT gestiegen um 12.10%. Währungseffekt: −5.10%».
    static func spoken(_ compare: PortfolioCompareSeries, currency: String, period: String) -> String {
        let c = A11y.change(compare.currency.last) ?? ""
        let u = A11y.change(compare.usdt.last) ?? ""
        let sentence = L("portfolio_compare_chart_a11y", period, currency, c, u)
        return sentence + ". " + L("portfolio_currency_effect", PortfolioFormat.signedPercent(compare.effect))
    }
}
