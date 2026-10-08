import SwiftUI

/// Halving als Zeile unter «Einordnung»: rechts das geschätzte Datum des nächsten Halvings,
/// darunter der Fortschritt im Zyklus; Tippen klappt Countdown, Balken und Zyklus-Chart auf.
/// Wie `HalvingRow` in Android.
struct CycleHalvingRow: View {
    let cycle: CycleInfo
    let history: CycleLoad<CycleHistory>
    let onRetry: () -> Void
    var divider = true
    @State private var expanded = false

    var body: some View {
        let today = LocalDay.today()
        let total = max(cycle.lastHalving.days(until: cycle.nextHalvingEstimate), 1)
        let elapsed = min(max(cycle.lastHalving.days(until: today), 0), total)
        let remaining = max(today.days(until: cycle.nextHalvingEstimate), 0)

        MarketRow(
            title: L("insights_halving_title"),
            secondary: L("insights_cycle_progress", elapsed * 100 / total),
            value: CycleFormat.mediumDate(cycle.nextHalvingEstimate),
            divider: divider,
            expanded: $expanded,
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                Text(L("insights_halving_countdown", count: remaining, remaining, CycleFormat.mediumDate(cycle.nextHalvingEstimate)))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                CycleProgressBar(fraction: Double(elapsed) / Double(total), label: L("insights_halving_title"))
                    .padding(.top, Spacing.sm)
                    .padding(.bottom, 16)
                Text(L("insights_cycle_chart"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                switch history {
                case .loading:
                    CycleHistoryChart.skeleton
                case .failed:
                    CycleFailedRow(onRetry: onRetry)
                case .loaded(let value):
                    CycleHistoryChart(history: value, currentHalving: cycle.lastHalving)
                }
            })
        )
    }
}
