import SwiftUI

/// Marktphase nach dem Zonenmodell: Wie viele historische Top- bzw.
/// Bottom-Signale treffen gerade gleichzeitig zu? Dazu Halving-Zeit und Trend.
/// Wie `MarketPhaseCard.kt`.
struct MarketPhaseCard: View {
    let cycle: CycleInfo
    let state: CycleLoad<CycleReport>
    let onRetry: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showDetails = false
    @State private var showScoreInfo = false

    init(cycle: CycleInfo, state: CycleLoad<CycleReport>, onRetry: @escaping () -> Void) {
        self.cycle = cycle
        self.state = state
        self.onRetry = onRetry
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CycleCardTitle(text: L("market_phase_title"))

            switch state {
            case .loading:
                skeleton

            case .failed:
                HStack(spacing: 8) {
                    Text(L("market_phase_trend_failed"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurface)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    CycleRetryButton(action: onRetry)
                }
                .padding(.top, 8)

            case .loaded(let report):
                loaded(report)
            }

            // Zyklus nach Kalender — immer sichtbar, auch ohne Internet
            // Das nächste Halving steht in der Halving-Karte und wird hier nicht wiederholt
            Text(L("market_phase_cycle_since", count: cycle.monthsSinceHalving,
                   cycle.monthsSinceHalving,
                   CycleFormat.mediumDate(cycle.lastHalving)))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 10)
            Text(L("market_source"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 6)
            Text(L("market_phase_disclaimer"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background {
            ZStack {
                AppColors.container
                // Leichter Schein in der Zonenfarbe hinter der Karte
                if let zone = state.value?.zone {
                    LinearGradient(
                        colors: [CycleZonePalette.color(zone).opacity(0.22), CycleZonePalette.color(zone).opacity(0)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: CycleCardTitle.cornerRadius, style: .continuous))
        .alert(L("market_scores_info_title"), isPresented: $showScoreInfo) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("market_scores_info_text"))
        }
    }

    @ViewBuilder
    private func loaded(_ report: CycleReport) -> some View {
        // Zone als grosses Etikett, rechts daneben der Index gross; darunter die Kurzdeutung
        HStack(alignment: .center, spacing: 12) {
            CycleZonePill(zone: report.zone)
            Spacer(minLength: 0)
            // VoiceOver liest den Index über die Skala («63 von 100»)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(String(report.index))
                    .font(.system(.title, design: .rounded).weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppColors.onSurface)
                Text(verbatim: "/ 100")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .lineLimit(1)
            .fixedSize()
            .accessibilityHidden(true)
        }
        Text(L(report.zone.hintKey))
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurface)
            .padding(.top, 8)

        CycleZoneGauge(index: report.index)
            .padding(.top, 14)

        HStack(spacing: 8) {
            // Zwei Signal-Pillen; ab 5 Punkten leicht in der Farbe des jeweiligen Endes getönt
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { signalPills(report) }
                VStack(alignment: .leading, spacing: 6) { signalPills(report) }
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

        // Kurzdeutung der beiden Zahlen, immer sichtbar
        Text(L(report.topScore == 0 && report.bottomScore == 0 ? "market_scores_none" : "market_scores_hint"))
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)

        if !report.onChainAvailable {
            Text(L("market_onchain_missing"))
                .font(.footnote)
                .foregroundStyle(AppColors.error)
                .padding(.top, 6)
        }

        Button {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { showDetails.toggle() }
        } label: {
            HStack(spacing: 4) {
                Text(L(showDetails ? "market_hide_indicators" : "market_show_indicators"))
                Image(systemName: showDetails ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.semibold))
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(accent.primary)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 6)

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

    /// Platzhalter in der Form der geladenen Karte (Zonen-Etikett und Index, Kurzdeutung,
    /// Skala, Hinweis-Pillen, Erklärung, Knopf «Indikatoren»), damit die Karte nicht wächst,
    /// wenn die Daten spät kommen. VoiceOver liest «Trend wird geladen …».
    private var skeleton: some View {
        CycleSkeleton(label: L("market_phase_trend_loading")) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center, spacing: 12) {
                    CycleSkeletonPill(font: .title2, width: 96, horizontal: 18, vertical: 6)
                    Spacer(minLength: 0)
                    Text(verbatim: " ")
                        .font(.system(.title, design: .rounded).weight(.semibold))
                        .cycleSkeletonBar(width: 76)
                }
                // Kurzdeutung: typische Länge (Zone «Bear»)
                Text(L(MarketZone.BEAR.hintKey))
                    .font(.subheadline)
                    .cycleSkeletonLines(.subheadline)
                    .padding(.top, 8)
                CycleZoneGaugeSkeleton()
                    .padding(.top, 14)
                HStack(spacing: 8) {
                    CycleSkeletonPill(font: .footnote.weight(.medium))
                    CycleSkeletonPill(font: .footnote.weight(.medium))
                    // Platz des ⓘ-Knopfs (36 pt)
                    Color.clear.frame(width: 36, height: 36)
                }
                .padding(.top, 8)
                Text(L("market_scores_hint"))
                    .font(.footnote)
                    .cycleSkeletonLines(.footnote)
                // Platz des Knopfs «Indikatoren zeigen»
                Text(verbatim: " ")
                    .font(.subheadline.weight(.semibold))
                    .padding(.vertical, 8)
                    .hidden()
                    .padding(.top, 6)
            }
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
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(tint?.opacity(0.14) ?? AppColors.containerHighest, in: Capsule())
    }
}
