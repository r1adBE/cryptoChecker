import SwiftUI

/// Halving als Zeile unter «Einordnung»: rechts der geschätzte Monat des nächsten Halvings
/// («~Apr. 2028»), darunter das Datum des letzten. Bewusst ohne Fortschrittsbalken, Prozent oder
/// Countdown in Tagen: Die App kennt die Blockhöhe nicht, der Termin ist nur geschätzt, und ein
/// Zyklus-Fortschritt wäre eine Deutung. Tippen klappt den Zyklus-Chart auf (Kursverlauf
/// vergangener Zyklen, ausdrücklich keine Prognose). Wie `HalvingRow` in Android.
struct CycleHalvingRow: View {
    let halving: HalvingInfo
    let history: CycleLoad<CycleHistory>
    let onRetry: () -> Void
    var first = false
    /// Rechnerisch aus Blockhöhe und Blockzeit; nil = nur Kalender.
    var estimate: HalvingEstimate? = nil
    @State private var expanded = false

    /// «~Apr. 2028» (Monat und Jahr in der App-Sprache).
    private var nextMonth: String {
        let f = DateFormatter()
        f.locale = LocaleNumbers.appLocale
        f.setLocalizedDateFormatFromTemplate("MMMyyyy")
        return "~" + f.string(from: (estimate?.date ?? halving.nextHalvingEstimate).date)
    }

    private func nextText(_ next: String) -> String {
        guard let estimate else { return L("neutral_halving_next", next) }
        return L("halving_estimate_text", next,
                 LocaleNumbers.integer(estimate.remainingBlocks, grouping: true),
                 LocaleNumbers.decimal(estimate.avgBlockSeconds / 60, maxDecimals: 1))
    }

    var body: some View {
        let next = nextMonth
        MarketRow(
            title: L("insights_halving_title"),
            secondary: L("neutral_halving_last", CycleFormat.mediumDate(halving.lastHalving)),
            value: next,
            first: first,
            expanded: $expanded,
            info: .halving,
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                Text(nextText(next))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                    .padding(.bottom, 16)
                Text(L("insights_cycle_chart"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                switch history {
                case .loading:
                    CycleHistoryChart.skeleton
                case .failed:
                    CycleUnavailableRow(onRetry: onRetry)
                case .loaded(let value):
                    CycleHistoryChart(history: value, currentHalving: halving.lastHalving)
                }
            })
        )
    }
}
