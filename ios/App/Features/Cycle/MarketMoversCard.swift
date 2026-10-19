import SwiftUI

/// «Gewinner und Verlierer 24 h» unter dem Pulse — wie `MarketMoversCard` (`MarketMoversCard.kt`): je bis zu fünf
/// Coins mit dem grössten Plus bzw. Minus unter den Top 100 ohne Stablecoins (`MarketMovers`, dieselbe CoinGecko-Antwort
/// wie die Marktbreite). Solange der Pulse lädt, ein form-gleicher Platzhalter (`skeleton`) — darunter springt nichts,
/// wenn die Zeilen kommen; bei Fehler nichts (den zeigt schon die Pulse-Karte darüber). Je Zeile Logo, Kürzel und
/// Name, «24h» und die Änderung mit Pfeil und Vorzeichen in der Kursfarbe (nie Farbe allein). Tippen: dasselbe wie in
/// «Heute auffällig» (Aktionsblatt des Paars, sonst seine Vorschau, siehe `CycleScreen`). VoiceOver: je Zeile ein
/// Element mit Aktion. Keine Prognose, keine Empfehlung.
///
/// Auf «Jetzt» ruhig und kompakt (Build 83): je Gruppe zuerst `compactCount` Zeilen, «Alle zeigen» klappt auf alle
/// (bis fünf) auf, «Weniger zeigen» wieder zu — gemerkt, solange der Tab lebt. Wie Android `MOVERS_COMPACT`.
struct CycleMoversCard: View {
    let market: CycleLoad<PulseMarketData>
    let onOpen: (MoverCoin) -> Void
    @State private var expanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// So viele Zeilen je Gruppe zeigt die kompakte Karte, bevor «Alle zeigen» aufklappt.
    static let compactCount = 3

    var body: some View {
        if market.isLoading {
            skeleton
        } else if market.value?.hasMarket == true, let movers = market.value?.movers, !movers.isEmpty {
            let more = movers.gainers.count > Self.compactCount || movers.losers.count > Self.compactCount
            let limit = expanded ? Int.max : Self.compactCount
            CycleInsightCard(L("movers_title")) {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    group(L("movers_gainers"), empty: L("movers_no_gainers"), coins: Array(movers.gainers.prefix(limit)))
                    group(L("movers_losers"), empty: L("movers_no_losers"), coins: Array(movers.losers.prefix(limit)))
                }
                if more {
                    Button {
                        WatchlistHaptics.selection()
                        expanded.toggle()
                    } label: {
                        // Klappt nur auf (kein Seitenwechsel): Pfeil nach unten bzw. oben wie bei «Was bewegt sich mit?»
                        ExpandToggleLabel(title: L(expanded ? "market_movers_show_less" : "market_movers_show_all"),
                                          expanded: expanded)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, Spacing.xs)
                }
                CycleSourceText(text: L("movers_source"))
                CoinGeckoAttribution()
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: expanded)
        }
    }

    /// Platzhalter beim Laden: dieselbe Karte mit Titel und Unterüberschriften, je Gruppe `compactCount` Zeilen
    /// in der Form von `CycleMoverRowView` (Logo, Kürzel, Name, Änderung) — so hoch wie die Karte mit Daten.
    private var skeleton: some View {
        CycleInsightCard(L("movers_title")) {
            VStack(alignment: .leading, spacing: Spacing.md) {
                ForEach([L("movers_gainers"), L("movers_losers")], id: \.self) { title in
                    VStack(alignment: .leading, spacing: Spacing.sm) {
                        groupTitle(title)
                        SkeletonPulse {
                            VStack(spacing: ListSegment.gap) {
                                ForEach(0..<Self.compactCount, id: \.self) { index in
                                    skeletonRow(shape: ListSegment.shape(index, Self.compactCount))
                                }
                            }
                        }
                    }
                }
            }
            CycleSourceText(text: L("movers_source"))
            CoinGeckoAttribution()
        }
    }

    /// Eine Platzhalter-Zeile mit denselben Abständen und Zeilenhöhen wie `CycleMoverRowView`.
    private func skeletonRow(shape: UnevenRoundedRectangle) -> some View {
        HStack(spacing: Spacing.md) {
            Circle()
                .fill(AppColors.containerHighest)
                .frame(width: ListSegment.logo, height: ListSegment.logo)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: "BTC").font(.headline).cycleSkeletonBar(width: 48)
                Text(verbatim: "Bitcoin").font(.footnote).cycleSkeletonBar(width: 96)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            CycleSkeletonPill(font: .caption, width: 56, horizontal: Spacing.sm, vertical: 2)
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, Spacing.md)
        .frame(minHeight: 56)
        .background(AppColors.containerHigh, in: shape)
    }

    /// Unterüberschrift einer Gruppe; für VoiceOver eine Überschrift.
    private func groupTitle(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(AppColors.onSurfaceVariant)
            .accessibilityAddTraits(.isHeader)
    }

    /// Unterüberschrift (für VoiceOver eine Überschrift) und die Zeilen als eine Fläche (`ListSegment`).
    private func group(_ title: String, empty: String, coins: [MoverCoin]) -> some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            groupTitle(title)
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
