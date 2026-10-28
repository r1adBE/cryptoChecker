import SwiftUI

/// «Einträge ohne Einstand» — wie `PortfolioCostGapsSheet.kt`: Zugänge ohne Kaufpreis (`PortfolioCostGaps`),
/// neueste zuerst, wahlweise nur ein Coin. Je Kauf/Einzahlung der Vorschlag Menge × geprüfter Tagesschluss des Datums und
/// «Einstand erfassen» (öffnet den Eintrag mit dem Vorschlag zum Übernehmen und Anpassen) oder «Unbekannt lassen» (zählt
/// danach auf der Karte nicht). Belohnungen: «Einstand = Wert bei Zufluss (Einkommen)» — kein Angebot, ihn zu ändern.
@MainActor
struct PortfolioCostGapsSheet: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.dismiss) private var dismiss
    @AppStorage(PortfolioCostGapUi.unknownKey) private var unknownRaw = ""
    var coin: String? = nil
    let onEdit: (PortfolioTx) -> Void

    var body: some View {
        let all = PortfolioCostGaps.gaps(data.portfolio, template: data.settings.taxTemplate)
        let gaps = coin.map { c in all.filter { PortfolioCalculator.normalizeCoin($0.trade.coin) == PortfolioCalculator.normalizeCoin(c) } } ?? all
        let acknowledged = PortfolioCostGapUi.ids(unknownRaw)
        NavigationStack {
            List {
                Section {
                    Text(L(gaps.isEmpty ? "cost_gaps_empty" : "cost_gaps_sheet_intro"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ForEach(gaps, id: \.trade.id) { gap in
                    PortfolioCostGapRow(gap: gap, acknowledged: acknowledged.contains(gap.trade.id)) {
                        var ids = PortfolioCostGapUi.ids(unknownRaw)
                        ids.remove(gap.trade.id)
                        unknownRaw = PortfolioCostGapUi.raw(ids)
                        if let tx = data.portfolio.first(where: { $0.id == gap.trade.id }) {
                            dismiss()
                            onEdit(tx)
                        }
                    } onLeaveUnknown: {
                        var ids = PortfolioCostGapUi.ids(unknownRaw)
                        ids.insert(gap.trade.id)
                        unknownRaw = PortfolioCostGapUi.raw(ids)
                    }
                }
            }
            .navigationTitle(L("cost_gaps_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } }
            }
        }
        // Merker ohne Lücke mehr (gelöscht oder inzwischen mit Einstand) weg — wie Android
        .onAppear {
            let pruned = PortfolioCostGaps.prune(acknowledged, gaps: all)
            if pruned != acknowledged { unknownRaw = PortfolioCostGapUi.raw(pruned) }
        }
    }
}

/// Eine Zeile: Datum · Art, Menge; Vorschlag bzw. Hinweis «Wert bei Zufluss» und die Aktionen.
@MainActor
private struct PortfolioCostGapRow: View {
    @Environment(\.hidePortfolioAmounts) private var hidden
    let gap: PortfolioCostGap
    let acknowledged: Bool
    let onEdit: () -> Void
    let onLeaveUnknown: () -> Void
    @State private var close: Decimal?
    @State private var loaded = false

    var body: some View {
        let t = gap.trade
        let coin = PortfolioCalculator.normalizeCoin(t.coin)
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(PortfolioFormat.date(t.time) + " · " + PortfolioFormat.typeLabel(t.type))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
            Text(PortfolioInsights.mask(PortfolioFormat.exactAmount(t.amountExact, coin), hidden: hidden))
                .font(.body.weight(.semibold))
            switch gap.kind {
            case .INCOME_RECEIPT_VALUE, .INCOME_ZERO:
                Text(L(gap.kind == .INCOME_ZERO ? "cost_gap_income_zero" : "cost_gap_income"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            case .EDITABLE:
                Text(suggestionText(t))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                HStack(spacing: Spacing.lg) {
                    Button(L("cost_gap_enter"), action: onEdit).font(.subheadline.weight(.semibold))
                    if acknowledged {
                        Text(L("cost_gap_left_unknown")).font(.caption).foregroundStyle(AppColors.onSurfaceVariant)
                    } else {
                        Button(L("cost_gap_leave_unknown"), action: onLeaveUnknown).font(.subheadline)
                    }
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, Spacing.xs)
        .task(id: t.id) {
            guard gap.kind == .EDITABLE else { return }
            close = await PortfolioCostGapUi.close(coin, millis: t.time)
            loaded = true
        }
    }

    private func suggestionText(_ t: PortfolioTx) -> String {
        guard loaded else { return L("cost_gap_suggestion_loading") }
        guard let value = PortfolioCostGaps.suggestion(t, close: close) else { return L("cost_gap_suggestion_none") }
        return L("cost_gap_suggestion", PortfolioFormat.date(t.time),
                 PortfolioInsights.mask(PortfolioFormat.usdtValue(PortfolioDecimal.double(value)), hidden: hidden))
    }
}
