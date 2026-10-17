import SwiftUI

// Runde 35 (M1): Die frühere «Marktphase» (Zonen, Hoch-/Tief-Punkte, Skala, Phasen nach dem
// Halving) ist weg. Übrig bleiben reine Messwerte: je Kennzahl der Wert, ein Satz, was sie misst,
// und (Build 55) eine Zeile «hoch heisst …, tief heisst …» — in normaler Textfarbe, ohne Schwellen,
// Punkte oder Signal. Wie `MarketPhaseCard.kt`.

/// «Bitcoin-Kennzahlen» als Zeile unter «Einordnung»: rechts der Kurs, darunter Mayer-Multiple
/// und Abstand zum Allzeithoch; Tippen klappt alle Kennzahlen mit Erklärung und Quelle auf.
/// Wie `MarketPhaseRow` (MarketPhaseCard.kt).
struct MarketPhaseRow: View {
    let state: CycleLoad<BitcoinReport>
    let onRetry: () -> Void
    var first = false
    /// Herkunft (Kerzen-Anbieter, Coin Metrics) und Stand für die Nebenzeile.
    var stamp: DataStamp? = nil

    @State private var expanded = false

    var body: some View {
        let report = state.value
        MarketRow(
            title: L("neutral_btc_indicators_title"),
            secondary: report.map { IndicatorText.summary($0.indicators, currency: "USD") } ?? "",
            value: report.map { PriceFormat.priceWithCurrency($0.price, "USD") },
            state: CycleFearGreedRow.rowState(state, failure: L("market_phase_trend_failed")),
            first: first,
            stamp: stamp,
            onRetry: onRetry,
            expanded: $expanded,
            info: .phase,
            details: AnyView(details(report))
        )
    }

    /// Aufgeklappt: bei Daten alle Kennzahlen; immer Quelle und Hinweis.
    private func details(_ report: BitcoinReport?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let report {
                IndicatorListView(indicators: report.indicators, currency: "USD", historySince: report.historySince)
                if !report.onChainAvailable {
                    Text(L("neutral_onchain_missing"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .padding(.top, Spacing.xs)
                }
            }
            Text(L("market_source"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, Spacing.sm)
            if report?.historySince != nil {
                Text(L("ind_context_note"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.top, Spacing.xs)
            }
            Text(L("neutral_indicators_disclaimer"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 2)
        }
    }
}

/// Namen, Erklärungen und Werte der Kennzahlen — wie `indicatorName`/`indicatorValue` (Android).
enum IndicatorText {
    static func name(_ id: IndicatorId) -> String {
        switch id {
        case .MAYER: "Mayer Multiple"
        case .MA200W: L("neutral_ind_ma200w")
        case .ATH_DISTANCE: L("ind_drawdown")
        case .CHANGE_30D: L("neutral_ind_change_30d")
        case .SMA50_VS_200: L("ind_cross")
        case .RSI_WEEKLY: L("ind_rsi_weekly")
        case .RSI_DAILY: L("ind_rsi_daily")
        case .VS_BTC: L("ind_vs_btc")
        case .MVRV: "MVRV"
        case .PUELL: "Puell Multiple"
        case .HASHRATE_30_60: L("neutral_ind_hashrate")
        case .PI_CYCLE_GAP: L("neutral_ind_pi_gap")
        }
    }

    static func explanation(_ id: IndicatorId) -> String {
        switch id {
        case .MAYER: L("neutral_explain_mayer")
        case .MA200W: L("neutral_explain_ma200w")
        case .ATH_DISTANCE: L("neutral_explain_ath_distance")
        case .CHANGE_30D: L("neutral_explain_change_30d")
        case .SMA50_VS_200: L("neutral_explain_sma50_200")
        case .RSI_WEEKLY: L("neutral_explain_rsi_weekly")
        case .RSI_DAILY: L("neutral_explain_rsi_daily")
        case .VS_BTC: L("neutral_explain_vs_btc")
        case .MVRV: L("neutral_explain_mvrv")
        case .PUELL: L("neutral_explain_puell")
        case .HASHRATE_30_60: L("neutral_explain_hashrate")
        case .PI_CYCLE_GAP: L("neutral_explain_pi_gap")
        }
    }

    /// Build 55 (Review 14): «hoch heisst …, tief heisst …» — was ein hoher und ein tiefer Wert beschreibt,
    /// ohne Kauf- oder Verkaufssignal. Wie `indicatorReading` (Android).
    static func reading(_ id: IndicatorId) -> String {
        switch id {
        case .MAYER: L("ind_read_mayer")
        case .MA200W: L("ind_read_ma200w")
        case .ATH_DISTANCE: L("ind_read_ath_distance")
        case .CHANGE_30D: L("ind_read_change_30d")
        case .SMA50_VS_200: L("ind_read_sma50_vs_200")
        case .RSI_WEEKLY: L("ind_read_rsi_weekly")
        case .RSI_DAILY: L("ind_read_rsi_daily")
        case .VS_BTC: L("ind_read_vs_btc")
        case .MVRV: L("ind_read_mvrv")
        case .PUELL: L("ind_read_puell")
        case .HASHRATE_30_60: L("ind_read_hashrate_30_60")
        case .PI_CYCLE_GAP: L("ind_read_pi_cycle_gap")
        }
    }

    /// Faktoren «1.12×», Prozente «+5.2%», RSI «48», 200-Wochen-Schnitt als Kurs; «—» ohne Wert.
    static func value(_ indicator: Indicator, currency: String) -> String {
        guard let v = indicator.value else { return "—" }
        switch indicator.id {
        case .MAYER, .MVRV, .PUELL:
            return LocaleNumbers.decimal(v, maxDecimals: 2) + "×"
        case .MA200W:
            return PriceFormat.priceWithCurrency(v, currency)
        case .RSI_WEEKLY, .RSI_DAILY:
            return LocaleNumbers.integer(Int(v.rounded()))
        case .ATH_DISTANCE:
            // Abstand unter dem Hoch als negative Zahl («−23.4%»), 0 ohne Vorzeichen
            return PriceFormat.signedPercent(-v, decimals: 1)
        case .CHANGE_30D, .SMA50_VS_200, .VS_BTC, .HASHRATE_30_60, .PI_CYCLE_GAP:
            return PriceFormat.signedPercent(v, decimals: 1)
        }
    }

    /// Einordnung (nur Rechnung): «Seit Juni 2016 an 78 % der Tage tiefer · Bei gleichem Kurs in 30 Tagen: 1.18×»;
    /// nil ohne Verlauf — wie `indicatorContext` (Android).
    static func context(_ indicator: Indicator, since: LocalDay?, currency: String) -> String? {
        guard let stat = indicator.stat else { return nil }
        var parts: [String] = []
        if let since, let p = stat.percentileBelow {
            let month = since.date.formatted(.dateTime.month(.abbreviated).year())
            parts.append(L("ind_context_percentile", month, LocaleNumbers.integer(Int(p.rounded())) + " %"))
        }
        if let f = stat.flat30 {
            parts.append(L("ind_context_flat30", value(Indicator(id: indicator.id, value: f), currency: currency)))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Nebenzeile: die ersten zwei Kennzahlen mit Wert (ohne 200-Wochen-Schnitt).
    static func summary(_ indicators: [Indicator], currency: String) -> String {
        indicators.filter { $0.value != nil && $0.id != .MA200W }
            .prefix(2)
            .map { "\(name($0.id)) \(value($0, currency: currency))" }
            .joined(separator: " · ")
    }
}

/// Kennzahlen als Liste: links Name und darunter klein ein Satz, was sie misst, und wie man hohe und
/// tiefe Werte liest; rechts der Wert in normaler Textfarbe (keine Farbe für «gut» oder «schlecht»). Wie `IndicatorList` (Android).
struct IndicatorListView: View {
    let indicators: [Indicator]
    let currency: String
    /// Erster Tag des Verlaufs der Einordnung (Bitcoin); nil = ohne.
    var historySince: LocalDay? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(indicators.enumerated()), id: \.offset) { _, indicator in
                IndicatorRowView(indicator: indicator, currency: currency, historySince: historySince)
                    .padding(.top, Spacing.sm)
            }
        }
    }
}

private struct IndicatorRowView: View {
    let indicator: Indicator
    let currency: String
    var historySince: LocalDay? = nil

    var body: some View {
        let name = IndicatorText.name(indicator.id)
        let explain = IndicatorText.explanation(indicator.id) + "\n" + IndicatorText.reading(indicator.id)
            + (IndicatorText.context(indicator, since: historySince, currency: currency).map { "\n" + $0 } ?? "")
        let value = indicator.value == nil ? L("ind_missing") : IndicatorText.value(indicator, currency: currency)
        HStack(alignment: .top, spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                Text(explain)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(verbatim: value)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AppColors.onSurface)
                .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(name): \(value). \(explain)"))
    }
}

/// Platzhalter in der Form von `IndicatorListView`.
struct IndicatorListSkeleton: View {
    let count: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(0..<count, id: \.self) { _ in
                HStack(alignment: .top, spacing: Spacing.sm) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: " ").font(.subheadline).cycleSkeletonBar(width: 140)
                        Text(verbatim: " ").font(.caption).cycleSkeletonBar()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: " ").font(.subheadline).cycleSkeletonBar(width: 64)
                }
                .padding(.top, Spacing.sm)
            }
        }
    }
}
