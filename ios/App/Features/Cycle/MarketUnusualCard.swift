import SwiftUI

/// «Heute auffällig» — wie `UnusualCard` (`MarketUnusualCard.kt`): bis zu fünf Coins aus den rund
/// 30 grössten, die sich heute ungewöhnlich verhalten — je Zeile Plakette, Symbol, 24-h-Pille und
/// die auffälligste Tatsache in einem Satz. Nichts auffällig: eine ruhige Zeile. Tippen: «Warum?»
/// für beobachtete Coins, sonst die Suche im Hinzufügen-Tab. VoiceOver: je Zeile ein Element.
struct CycleUnusualCard: View {
    let state: CycleLoad<UnusualReport>
    let isWatched: (String) -> Bool
    let onOpen: (UnusualRow) -> Void
    let onRetry: () -> Void

    var body: some View {
        CycleInsightCard(L("unusual_title")) {
            switch state {
            case .loading:
                skeleton
            case .failed:
                HStack(spacing: 8) {
                    Text(L("pulse_unavailable"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    CycleRetryButton(action: onRetry)
                }
            case .loaded(let report):
                if report.rows.isEmpty {
                    Text(L("unusual_nothing"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    VStack(spacing: 2) {
                        ForEach(report.rows) { row in
                            CycleUnusualRowView(row: row, watched: isWatched(row.symbol), onTap: { onOpen(row) })
                        }
                    }
                }
                CycleSourceText(text: L("unusual_source"))
            }
        }
    }

    /// Platzhalter in Zeilenform: drei Zeilen mit Plakette, Symbol, Satz und Pille.
    private var skeleton: some View {
        CycleSkeleton {
            VStack(spacing: 2) {
                ForEach(0..<3, id: \.self) { _ in
                    HStack(spacing: 12) {
                        Circle()
                            .fill(AppColors.containerHighest)
                            .frame(width: 36, height: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: "BTC").font(.subheadline.weight(.semibold)).cycleSkeletonBar(width: 48)
                            Text(verbatim: " ").font(.footnote).cycleSkeletonBar()
                        }
                        CycleSkeletonPill(font: .caption, width: 56, horizontal: 8, vertical: 2)
                    }
                    .frame(minHeight: 56)
                }
            }
        }
    }

    /// Kurzer Satz zur Tatsache, z. B. «Volumen 2.4× üblich», «Funding hoch (+0.062%)».
    static func factText(_ fact: UnusualFact) -> String {
        switch fact.kind {
        case .strongerThanBtc: return L("unusual_fact_stronger")
        case .weakerThanBtc: return L("unusual_fact_weaker")
        case .againstMarket: return L("unusual_fact_against")
        case .volume:
            return L("unusual_fact_volume", String(format: "%.1f", locale: Locale.current, fact.volumeRatio ?? 0))
        case .fundingHigh:
            return L("unusual_fact_funding_high", ActivityTexts.percent(fact.fundingPercent ?? 0, 3))
        case .fundingNegative:
            return L("unusual_fact_funding_negative", ActivityTexts.percent(fact.fundingPercent ?? 0, 3))
        }
    }
}

/// Eine Zeile; für VoiceOver ein Element «Solana, gestiegen um 5.20%. Läuft gegen den Markt.» mit Aktion.
private struct CycleUnusualRowView: View {
    let row: UnusualRow
    let watched: Bool
    let onTap: () -> Void

    var body: some View {
        let fact = CycleUnusualCard.factText(row.fact)
        let name = row.name.isEmpty ? row.symbol : row.name
        let spoken = L("unusual_a11y_row", name, A11y.change(row.change24h) ?? "", fact)
        Button(action: onTap) {
            HStack(spacing: 12) {
                CoinBadge(symbol: row.symbol, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.symbol)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Text(fact)
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                PortfolioPlPill(percent: row.change24h)
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(L(watched ? "unusual_action_why" : "unusual_action_add"))
    }
}

/// Kompakte Zeile «Wirtschaftsdaten» oben im Abschnitt «Jetzt» — wie `MacroHintRow` (Android):
/// nur, wenn heute (oder in den nächsten 18 h) wichtige US-Daten anstehen bzw. heute veröffentlicht
/// wurden. Rechnet jede Minute neu. Grau, ohne Bedeutungsfarbe; für VoiceOver ein Element.
struct CycleMacroHintRow: View {
    let events: [MacroEvent]

    var body: some View {
        if !events.isEmpty {
            TimelineView(.everyMinute) { context in
                let now = Int64(context.date.timeIntervalSince1970 * 1000)
                if let hint = MacroCalendar.hint(events, now: now) {
                    let text = MacroCalendar.hintText(hint)
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "calendar")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .accessibilityHidden(true)
                        Text(text)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L("macro_title") + ". " + text)
                }
            }
        }
    }
}

/// Ziel des «Warum»-Blatts aus «Heute auffällig».
struct CycleWhyTarget: Identifiable, Equatable {
    let id: Int64
}
