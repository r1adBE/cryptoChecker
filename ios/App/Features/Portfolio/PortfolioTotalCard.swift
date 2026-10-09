import SwiftUI

/// Gesamtwert, ± unrealisiert, investiert/realisiert, Umrechnung und Stand der Kurse.
struct PortfolioTotalCard: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    let summary: PortfolioSummary
    let updatedAt: Int64
    let currency: String
    let fxRate: Double?
    @Environment(\.appAccent) var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.hidePortfolioAmounts) var hideAmounts

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("portfolio_total_value"))
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)
            Text(PortfolioInsights.mask(PortfolioFormat.usdtValue(summary.totalValue), hidden: hideAmounts))
                .displayFont()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .contentTransition(.numericText(value: summary.totalValue))
                .padding(.top, 2)
            // «≈ 12’345.67 CHF» — nur mit Devisenkurs und nicht bei USD
            if currency != "USD", let fxRate {
                Text("≈ " + PortfolioInsights.mask(PriceFormat.valueWithCurrency(summary.totalValue * fxRate, currency), hidden: hideAmounts))
                    .font(AppFont.amount(.subheadline))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .contentTransition(.numericText())
            }

            HStack(spacing: 8) {
                Text(summary.unrealized.map { PortfolioInsights.mask(PortfolioFormat.signedUsdt($0), hidden: hideAmounts) } ?? "—")
                    .font(AppFont.amount(.headline))
                    .foregroundStyle(PortfolioFormat.plColor(summary.unrealized, scheme: priceColorsDependency,
                                            highContrast: highContrastDependency, inverted: invertedDependency))
                    .contentTransition(.numericText())
                if summary.unrealized != nil {
                    PortfolioPlPill(percent: summary.unrealizedPercent)
                }
            }
            .padding(.top, Spacing.sm)

            HStack(alignment: .top, spacing: 12) {
                PortfolioMetric(
                    label: L("portfolio_invested"),
                    value: summary.invested.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—"
                )
                if !PortfolioFormat.isZero(summary.realized) {
                    PortfolioMetric(
                        label: L("portfolio_realized"),
                        value: PortfolioInsights.mask(PortfolioFormat.signedUsdt(summary.realized), hidden: hideAmounts),
                        valueColor: PortfolioFormat.plColor(summary.realized, scheme: priceColorsDependency,
                                            highContrast: highContrastDependency, inverted: invertedDependency)
                    )
                }
            }
            .padding(.top, 12)

            // Hinweise, warum ± fehlt
            if summary.costMissing {
                PortfolioHint(text: L("portfolio_price_missing_hint")).padding(.top, 8)
            }
            if !summary.missingCurrentPrices.isEmpty {
                PortfolioHint(text: L("portfolio_current_missing", summary.missingCurrentPrices.joined(separator: ", ")))
                    .padding(.top, 8)
            }
            if updatedAt > 0 {
                Text(L("portfolio_updated", PriceFormat.time(updatedAt)))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.top, Spacing.sm)
            }
        }
        .padding(Spacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(LinearGradient(
                    colors: [accent.primary.opacity(0.16), accent.primary.opacity(0.04)],
                    startPoint: .topLeading, endPoint: .bottomTrailing))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(accent.primary.opacity(0.25), lineWidth: 1)
        )
        // Weniger Bewegung: Ziffern wechseln ohne Rollen
        .animation(reduceMotion ? nil : .snappy, value: summary.totalValue)
    }
}
