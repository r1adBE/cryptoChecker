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
                // Beispiel mit 58 000 USDT — wie das Preisfeld beim Erfassen (wie Android)
                Text(L("portfolio_empty_example",
                       PriceFormat.priceWithCurrency(58_000, "USDT")))
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
    /// Kursänderung über die %-Basis (z. B. «24h ▲ +2.1 %»), klein unter dem ± % — ersetzt die
    /// frühere Karte «Grösste Bewegungen»; wie `CoinRow(dayChange)` (Android).
    var dayChange: Double?
    var basis: ChangeBasis?
    /// Anzahl Transaktionen: nur die Zahl, klein neben der Menge — wie `TxCountBadge` (Android).
    var txCount = 0
    /// Form in der Liste (oben/unten stark gerundet, dazwischen leicht) — wie `ListSegment`.
    var shape: UnevenRoundedRectangle = ListSegment.shape(0, 1)
    @Environment(\.hidePortfolioAmounts) var hideAmounts
    @Environment(\.priceColorScheme) var priceColors
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.priceColorsInverted) var inverted

    var body: some View {
        HStack(spacing: 12) {
            CoinBadge(symbol: position.coin, size: ListSegment.logo, portfolio: true)
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
                HStack(spacing: Spacing.xs) {
                    Text(PortfolioInsights.mask(PortfolioFormat.amount(position.holdings, position.coin), hidden: hideAmounts))
                        .font(AppFont.amount(.caption))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                    if txCount > 0 {
                        Text(verbatim: LocaleNumbers.integer(txCount))
                            .font(.caption2.weight(.semibold).monospacedDigit())
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(AppColors.containerHighest, in: Capsule())
                            .accessibilityLabel(Text(verbatim: L("portfolio_transactions") + ": " + LocaleNumbers.integer(txCount)))
                    }
                }
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
                if let dayChange, let basis, dayChange.isFinite {
                    HStack(spacing: 3) {
                        Text(A11y.changeShortLabel(basis))
                        if !PortfolioFormat.isZero(dayChange) {
                            ChangeArrowIcon(change: dayChange)
                                .scaledFont(size: 8, weight: .bold, relativeTo: .caption2)
                        }
                        Text(PortfolioFormat.signedPercent(dayChange))
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(PortfolioFormat.plColor(dayChange, scheme: priceColors,
                                                             highContrast: highContrast, inverted: inverted))
                    .lineLimit(1)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(A11y.change(dayChange, basis: basis))
                }
            }
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: shape)
        .overlay(shape.strokeBorder(AppColors.outlineVariant.opacity(0.45), lineWidth: 1))
        .contentShape(shape)
    }
}
