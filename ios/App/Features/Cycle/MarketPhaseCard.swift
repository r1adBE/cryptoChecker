import SwiftUI

/// Marktphase nach dem Zonenmodell als Zeile unter «Einordnung»: wie viele historische Top-
/// bzw. Bottom-Signale treffen gerade gleichzeitig zu? Rechts die Zone, darunter die
/// Kurzdeutung; Tippen klappt Skala, Scores, Indikatoren, Zyklus und Quelle auf.
/// Wie `MarketPhaseRow` (MarketPhaseCard.kt).
struct MarketPhaseRow: View {
    let cycle: CycleInfo
    let state: CycleLoad<CycleReport>
    let onRetry: () -> Void
    var divider = true
    /// Herkunft (Kerzen-Anbieter, Coin Metrics) und Stand für die Nebenzeile.
    var stamp: DataStamp? = nil

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false
    @State private var showDetails = false
    @State private var showScoreInfo = false

    var body: some View {
        let report = state.value
        MarketRow(
            title: L("market_phase_title"),
            secondary: report.map { L($0.zone.hintKey) } ?? "",
            value: report.map { L($0.zone.labelKey) },
            state: CycleFearGreedRow.rowState(state, failure: L("market_phase_trend_failed")),
            divider: divider,
            stamp: stamp,
            onRetry: onRetry,
            expanded: $expanded,
            details: AnyView(details(report))
        )
        .alert(L("market_scores_info_title"), isPresented: $showScoreInfo) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("market_scores_info_text"))
        }
    }

    /// Aufgeklappt: bei Daten Index, Skala, Pillen, Scores, Indikatoren; immer Zyklus nach
    /// Kalender (auch ohne Internet), Quelle und Hinweis.
    private func details(_ report: CycleReport?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let report {
                loaded(report)
            }
            // Das nächste Halving steht in der Halving-Zeile und wird hier nicht wiederholt
            Text(L("market_phase_cycle_since", count: cycle.monthsSinceHalving,
                   cycle.monthsSinceHalving,
                   CycleFormat.mediumDate(cycle.lastHalving)))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, Spacing.sm)
            Text(L("market_source"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, Spacing.xs)
            Text(L("market_phase_disclaimer"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 2)
        }
    }

    @ViewBuilder
    private func loaded(_ report: CycleReport) -> some View {
        // Index gross; VoiceOver liest ihn über die Skala («63 von 100»)
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(verbatim: LocaleNumbers.integer(report.index))
                .displayFont(.compact)
                .foregroundStyle(AppColors.onSurface)
            Text(verbatim: "/ " + LocaleNumbers.integer(100))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .lineLimit(1)
        .fixedSize()
        .accessibilityHidden(true)

        CycleZoneGauge(index: report.index)
            .padding(.top, Spacing.sm)

        HStack(spacing: 8) {
            // Zwei Signal-Pillen; ab 5 Punkten leicht in der Farbe des jeweiligen Endes getönt
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { signalPills(report) }
                VStack(alignment: .leading, spacing: Spacing.xs) { signalPills(report) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                showScoreInfo = true
            } label: {
                Image(systemName: "info.circle")
                    .font(.body)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("market_scores_info_title"))
        }
        .padding(.top, 8)

        // Kurzdeutung der beiden Zahlen
        Text(L(report.topScore == 0 && report.bottomScore == 0 ? "market_scores_none" : "market_scores_hint"))
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)

        if !report.onChainAvailable {
            Text(L("market_onchain_missing"))
                .font(.footnote)
                .foregroundStyle(AppColors.error)
                .padding(.top, Spacing.xs)
        }

        Button {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { showDetails.toggle() }
        } label: {
            ExpandToggleLabel(title: L(showDetails ? "market_hide_indicators" : "market_show_indicators"),
                              expanded: showDetails)
        }
        .buttonStyle(.plain)
        .padding(.top, Spacing.xs)

        if showDetails {
            VStack(spacing: 8) {
                ForEach(Array(report.signals.enumerated()), id: \.offset) { _, signal in
                    CycleSignalRow(
                        name: Self.name(of: signal.id),
                        value: signal.value,
                        topPoints: signal.topPoints,
                        bottomPoints: signal.bottomPoints
                    )
                }
            }
            .padding(.bottom, 4)
            .transition(.opacity)
        }
    }

    @ViewBuilder
    private func signalPills(_ report: CycleReport) -> some View {
        MarketSignalPill(text: L("market_signal_top", report.topScore),
                         tint: report.topScore >= 5 ? CycleZonePalette.color(.EXTREME_BULL) : nil)
        MarketSignalPill(text: L("market_signal_bottom", report.bottomScore),
                         tint: report.bottomScore >= 5 ? CycleZonePalette.color(.EXTREME_BEAR) : nil)
    }

    static func name(of id: SignalId) -> String {
        switch id {
        case .MVRV_NUPL: "MVRV"
        case .PUELL: "Puell Multiple"
        case .MAYER: "Mayer Multiple"
        case .MA200W: L("ind_ma200w")
        case .DRAWDOWN: L("ind_drawdown")
        case .HASH_RIBBON: L("ind_hash")
        case .PARABOLIC: L("ind_parabolic")
        case .HALVING_TIME: L("ind_halving")
        case .ATH_TIME: L("ind_ath")
        }
    }
}

/// Tonale Pille «Anzeichen für ein Hoch 6/10»; die Bedeutung steht im Text,
/// die Tönung (≈ 14 %) betont nur einen hohen Wert.
private struct MarketSignalPill: View {
    let text: String
    let tint: Color?

    var body: some View {
        Text(text)
            .font(.footnote.weight(.medium).monospacedDigit())
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, Spacing.sm)
            .padding(.vertical, Spacing.xs)
            .background(tint?.opacity(0.14) ?? AppColors.containerHighest, in: Capsule())
    }
}
