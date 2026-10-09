import SwiftUI

/// Netzwerkgebühren als Zeile unter «Daten»: rechts die normale Ethereum-Gebühr, darunter
/// Bitcoin. Tippen klappt alle Netze (Ethereum und Bitcoin mit langsam/normal/schnell, die
/// L2-/Seitennetze mit der normalen Gebühr, rechts die Kosten einer einfachen Überweisung),
/// den Hinweis auf aktive Gas-Alarme und die Quelle auf — wie `GasSummaryRow`.
struct CycleGasRow: View {
    let state: CycleLoad<GasReport>
    let ethAlertGwei: Double
    let btcAlertSat: Int
    let onRetry: () -> Void
    var divider = true
    /// Herkunft (Ethereum-Knoten, mempool.space) und Stand für die Nebenzeile.
    var stamp: DataStamp? = nil

    @Environment(\.appAccent) private var accent
    @State private var expanded = false

    var body: some View {
        let report = state.value
        let eth = report?.evm.first { $0.network == .ethereum }
        let btcText = report?.btc.map { "Bitcoin \(GasFees.formatGwei($0.normal)) sat/vB" }
        MarketRow(
            title: L("gas_title"),
            secondary: [eth?.network.title, btcText].compactMap { $0 }.joined(separator: " · "),
            value: eth.map { "\(GasFees.formatGwei($0.normalGwei)) gwei" }
                ?? report?.btc.map { "\(GasFees.formatGwei($0.normal)) sat/vB" },
            state: CycleFearGreedRow.rowState(state),
            divider: divider,
            stamp: stamp,
            onRetry: onRetry,
            expanded: report == nil ? nil : $expanded,
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                if let report {
                    networks(report)
                    alertText
                    CycleSourceText(text: L("gas_source"))
                }
            })
        )
    }

    private func networks(_ report: GasReport) -> some View {
        VStack(spacing: 0) {
            ForEach(report.evm, id: \.network) { gas in
                row(
                    name: gas.network.title,
                    value: GasFees.formatGwei(gas.normalGwei),
                    unit: "gwei",
                    cost: gas.transferUsd,
                    detail: gas.network == .ethereum && gas.fastGwei > gas.slowGwei
                        ? L("gas_slow_fast", GasFees.formatGwei(gas.slowGwei), GasFees.formatGwei(gas.fastGwei))
                        : nil
                )
            }
            if let btc = report.btc {
                row(
                    name: "Bitcoin",
                    value: GasFees.formatGwei(btc.normal),
                    unit: "sat/vB",
                    cost: btc.transferUsd,
                    detail: btc.fast > btc.slow
                        ? L("gas_slow_fast", GasFees.formatGwei(btc.slow), GasFees.formatGwei(btc.fast))
                        : nil
                )
            }
        }
    }

    /// Aktive Gas-Alarme — stehen in den Einstellungen fest, also auch im Platzhalter echt.
    @ViewBuilder
    private var alertText: some View {
        let alerts = [
            ethAlertGwei > 0 ? "Ethereum < \(GasFees.formatGwei(ethAlertGwei)) gwei" : nil,
            btcAlertSat > 0 ? "Bitcoin < \(LocaleNumbers.integer(btcAlertSat)) sat/vB" : nil,
        ].compactMap { $0 }
        if !alerts.isEmpty {
            Text(L("gas_alert_active", alerts.joined(separator: " · ")))
                .font(.footnote)
                .foregroundStyle(accent.primary)
                .padding(.top, 8)
        }
    }

    private func row(name: String, value: String, unit: String, cost: Double?, detail: String?) -> some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: name).font(.body)
                if let detail {
                    Text(detail)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 1) {
                Text(verbatim: "\(value) \(unit)")
                    .font(.body.weight(.semibold).monospacedDigit())
                if let cost {
                    Text(L("gas_transfer_cost", GasFees.formatUsd(cost)))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
        .padding(.vertical, Spacing.sm)
        .accessibilityElement(children: .combine)
    }
}
