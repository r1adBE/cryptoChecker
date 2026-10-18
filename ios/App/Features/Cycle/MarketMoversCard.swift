import SwiftUI

/// «Gewinner und Verlierer 24 h» unter dem Pulse — wie `MarketMoversCard` (`MarketMoversCard.kt`): je bis zu fünf
/// Coins mit dem grössten Plus bzw. Minus unter den Top 100 ohne Stablecoins (`MarketMovers`, dieselbe CoinGecko-Antwort
/// wie die Marktbreite). Nur mit Daten — Laden und Fehler zeigt schon die Pulse-Karte darüber. Je Zeile Logo, Kürzel und
/// Name, «24h» und die Änderung mit Pfeil und Vorzeichen in der Kursfarbe (nie Farbe allein). Tippen: dasselbe wie in
/// «Heute auffällig» (Aktionsblatt des Paars, sonst seine Vorschau, siehe `CycleScreen`). VoiceOver: je Zeile ein
/// Element mit Aktion. Keine Prognose, keine Empfehlung.
struct CycleMoversCard: View {
    let market: CycleLoad<PulseMarketData>
    let onOpen: (MoverCoin) -> Void

    var body: some View {
        if market.value?.hasMarket == true, let movers = market.value?.movers, !movers.isEmpty {
            CycleInsightCard(L("movers_title")) {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    group(L("movers_gainers"), empty: L("movers_no_gainers"), coins: movers.gainers)
                    group(L("movers_losers"), empty: L("movers_no_losers"), coins: movers.losers)
                }
                CycleSourceText(text: L("movers_source"))
                CoinGeckoAttribution()
            }
        }
    }

    /// Unterüberschrift (für VoiceOver eine Überschrift) und die Zeilen als eine Fläche (`ListSegment`).
    private func group(_ title: String, empty: String, coins: [MoverCoin]) -> some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            if coins.isEmpty {
                Text(empty)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                VStack(spacing: ListSegment.gap) {
                    ForEach(Array(coins.enumerated()), id: \.element.id) { index, coin in
                        CycleMoverRowView(coin: coin, shape: ListSegment.shape(index, coins.count),
                                          onTap: { onOpen(coin) })
                    }
                }
            }
        }
    }
}

/// Eine Zeile; für VoiceOver ein Element «Solana (SOL), gestiegen um 7.10%» mit Aktion.
private struct CycleMoverRowView: View {
    let coin: MoverCoin
    var shape: UnevenRoundedRectangle = ListSegment.shape(0, 1)
    let onTap: () -> Void

    var body: some View {
        let name = coin.name.isEmpty ? coin.symbol : coin.name
        let spoken = L("movers_a11y_row", name, coin.symbol, A11y.change(coin.change24h) ?? "")
        Button(action: onTap) {
            HStack(spacing: Spacing.md) {
                CoinBadge(symbol: coin.symbol, size: ListSegment.logo)
                VStack(alignment: .leading, spacing: 1) {
                    Text(coin.symbol)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    if name != coin.symbol {
                        Text(name)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // «24h ↗ +9.4%»: Zeitraum klein und grau vor der Änderung, wie in der Merkliste
                HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                    Text(L("why_change_24h"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                    PortfolioPlPill(percent: coin.change24h)
                }
                .fixedSize()
            }
            // Gleicher Innenabstand wie die Merkliste und «Heute auffällig»
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .frame(minHeight: 56)
            .background(AppColors.containerHigh, in: shape)
            .segmentLines(shape, outline: false)
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
        .accessibilityAddTraits(.isButton)
        // Ein Ziel für alle Zeilen: Kurs und Aktionen (wie ein Tipp in der Merkliste)
        .accessibilityHint(L("unusual_action_open"))
    }
}
