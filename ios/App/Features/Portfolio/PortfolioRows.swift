import SwiftUI

/// Coin-Zeilen, geschlossene Positionen und leeres Portfolio. Wie `PortfolioRows.kt`.
extension PortfolioScreen {
    /// «Geschlossene Positionen (n)» — eingeklappt, nur der realisierte Gewinn/Verlust.
    @ViewBuilder
    func closedSection(_ closed: [CoinPosition]) -> some View {
        Button {
            showClosed.toggle()
        } label: {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .rotationEffect(.degrees(showClosed ? 90 : 0))
                Text(L("portfolio_closed", count: closed.count))
                    .font(.subheadline.weight(.medium))
                Spacer()
            }
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.horizontal, 4)
            .padding(.vertical, Spacing.md)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: showClosed)

        if showClosed {
            VStack(spacing: 0) {
                ForEach(Array(closed.enumerated()), id: \.element.coin) { index, position in
                    if index > 0 { RowDivider() }
                    Button {
                        openCoin = position.coin
                    } label: {
                        HStack(spacing: 12) {
                            CoinBadge(symbol: position.coin, size: 32, portfolio: true)
                            Text(position.coin)
                                .font(.body)
                                .foregroundStyle(AppColors.onSurface)
                            Spacer(minLength: 8)
                            Text(PortfolioInsights.mask(PortfolioFormat.signedUsdt(position.realized), hidden: hideAmounts))
                                .font(AppFont.amount(.subheadline))
                                .foregroundStyle(PortfolioFormat.plColor(position.realized, scheme: priceColors,
                                                                    highContrast: highContrast, inverted: inverted))
                        }
                        .padding(.vertical, Spacing.md)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, 2)
            .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    // MARK: Leer

    var emptyState: some View {
        ScrollView {
            VStack(spacing: 0) {
                EmptyStateView(
                    systemImage: AppSymbol.portfolio,
                    title: L("portfolio_empty_title"),
                    message: L("portfolio_empty_text"),
                    actionTitle: L("portfolio_empty_action"),
                    action: { sheet = PortfolioTxDraft() }
                )
                // Beispiel mit 58 000 in der Umrechnungswährung
                Text(L("portfolio_empty_example",
                       PriceFormat.priceWithCurrency(58_000, data.settings.portfolioCurrency)))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 32)
                    .padding(.bottom, 16)
                PortfolioDisclaimer()
            }
            .padding(.top, 48)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
        }
    }
}

// MARK: Coin-Zeile

/// Zeile je Coin: Plakette, Symbol, Menge, Ø/aktuell, Wert und ± %.
struct PortfolioCoinRow: View {
    let position: CoinPosition
    @Environment(\.hidePortfolioAmounts) var hideAmounts

    var body: some View {
        HStack(spacing: 12) {
            CoinBadge(symbol: position.coin, size: 40, portfolio: true)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Spacing.xs) {
                    Text(position.coin)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    if position.oversold {
                        Image(systemName: "exclamationmark.circle.fill")
                            .scaledFont(size: 13, relativeTo: .footnote)
                            .foregroundStyle(AppColors.error)
                            .accessibilityLabel(L("portfolio_oversold"))
                    }
                }
                Text(PortfolioInsights.mask(PortfolioFormat.amount(position.holdings, position.coin), hidden: hideAmounts))
                    .font(AppFont.amount(.caption))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                Text(L("portfolio_avg_and_now", PriceFormat.price(position.avgCost), PriceFormat.price(position.currentPrice)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 4) {
                Text(position.value.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
                    .font(AppFont.amount(.subheadline, weight: .semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .contentTransition(.numericText())
                if position.priceMissing {
                    Text(L("portfolio_price_missing"))
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                } else {
                    PortfolioPlPill(percent: position.unrealizedPercent)
                }
            }
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(AppColors.outlineVariant.opacity(0.45), lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
