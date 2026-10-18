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

    /// «Vergleich in Prozent, 30 Tage: CHF gestiegen um 7.00%, USDT gestiegen um 12.10%. Währungseffekt: −5.10%».
    static func spoken(_ compare: PortfolioCompareSeries, currency: String, period: String) -> String {
        let c = A11y.change(compare.currency.last) ?? ""
        let u = A11y.change(compare.usdt.last) ?? ""
        let sentence = L("portfolio_compare_chart_a11y", period, currency, c, u)
        return sentence + ". " + L("portfolio_currency_effect", PortfolioFormat.signedPercent(compare.effect))
    }
}
