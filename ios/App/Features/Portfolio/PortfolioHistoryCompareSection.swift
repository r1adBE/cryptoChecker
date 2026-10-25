import SwiftUI

// Abschnitt «Vergleich» der Verlaufskarte: Prozent-Kurven der gewählten Währung gegen USDT mit Legende
// und Währungseffekt. Eigene Datei, weil er nur bei einer Umrechnungswährung erscheint
// (wie `PortfolioHistoryCompareSection.kt`).

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
                .frame(height: PortfolioHistoryCard.chartHeight)
                .padding(.top, Spacing.xs)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Self.spoken(compare, currency: currency, period: period))
                // Audio Graph mit beiden Linien; Wischen hoch/runter sagt Tag für Tag beide an (wie Android)
                .accessibilityChartDescriptor(Self.audioGraph(compare, currency: currency, period: period))
                .chartPointAdjustable(count: compare.epochDays.count, resetKey: Self.resetKey(compare)) { index in
                    Self.pointText(compare, currency: currency, index: index)
                }
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
        HStack(spacing: Spacing.xs) {
            Capsule()
                .fill(color)
                .frame(width: 14, height: 3)
            Text(verbatim: currency)
                .fontWeight(.semibold)
            Capsule()
                .fill(AppColors.onSurfaceVariant)
                .frame(width: 14, height: 1.5)
                .padding(.leading, Spacing.sm)
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

    /// «12. März 2026: CHF gestiegen um 2.10%, USDT gestiegen um 1.50%» am Tag `index`.
    static func pointText(_ compare: PortfolioCompareSeries, currency: String, index: Int) -> String {
        let day = PortfolioFormat.date(LocalDay(epochDay: compare.epochDays[index]).date.millis)
        let c = A11y.change(compare.currency.indices.contains(index) ? compare.currency[index] : nil) ?? ""
        let u = A11y.change(compare.usdt.indices.contains(index) ? compare.usdt[index] : nil) ?? ""
        return L("label_value", day, L("a11y_chart_two_series", currency, c, PortfolioFormat.usdt, u))
    }

    /// Audio Graph: Linien «CHF» und «USDT» in Prozent über die Tage.
    static func audioGraph(_ compare: PortfolioCompareSeries, currency: String, period: String) -> ChartAudioGraph {
        let millis = compare.epochDays.map { LocalDay(epochDay: $0).date.millis }
        func line(_ name: String, _ values: [Double]) -> ChartAudioGraph.Series {
            let points = zip(millis, values).map { ChartAudioGraph.Point(millis: $0, value: $1) }
            return ChartAudioGraph.Series(name: name, points: points)
        }
        return ChartAudioGraph(
            title: L("portfolio_title"), summary: spoken(compare, currency: currency, period: period),
            xTitle: L("a11y_chart_axis_time"), yTitle: L("a11y_chart_axis_change"),
            series: [line(currency, compare.currency), line(PortfolioFormat.usdt, compare.usdt)],
            timeText: { PortfolioFormat.date($0) },
            valueText: { PortfolioFormat.signedPercent($0) })
    }

    /// Neue Tage: Auswahl der Punkte aufheben.
    static func resetKey(_ compare: PortfolioCompareSeries) -> Int {
        var hasher = Hasher()
        hasher.combine(compare.epochDays.count)
        hasher.combine(compare.epochDays.first)
        hasher.combine(compare.epochDays.last)
        return hasher.finalize()
    }

    /// «Vergleich in Prozent, 30 Tage: CHF gestiegen um 7.00%, USDT gestiegen um 12.10%. Währungseffekt: −5.10%».
    static func spoken(_ compare: PortfolioCompareSeries, currency: String, period: String) -> String {
        let c = A11y.change(compare.currency.last) ?? ""
        let u = A11y.change(compare.usdt.last) ?? ""
        let sentence = L("portfolio_compare_chart_a11y", period, currency, c, u)
        return sentence + ". " + L("portfolio_currency_effect", PortfolioFormat.signedPercent(compare.effect))
    }
}
