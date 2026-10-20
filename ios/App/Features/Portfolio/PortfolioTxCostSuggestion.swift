import SwiftUI

/// Einstand im Erfassen-Blatt eines gespeicherten Zugangs ohne Preis (Build 84) — wie `TxCostSuggestion` (Android):
/// Einzahlung/Kauf: «Vorschlag: Tagesschluss <Datum>: …» mit «Übernehmen» (setzt den geprüften Tagesschluss als Preis je
/// Coin, markiert, änderbar; gespeichert erst mit «Speichern»). Belohnung: nur «Einstand = Wert bei Zufluss
/// (Einkommen)» (AT ohne Zins: Anschaffungskosten 0) — kein Vorschlag, den Einstand zu ändern. In der Anzeigewährung kein
/// USDT-Vorschlag.
@MainActor
struct PortfolioTxCostSuggestion: View {
    let isEdit: Bool
    let txId: Int64
    let storedPrice: Decimal?
    let type: PortfolioTxType
    let coin: String
    let coinValid: Bool
    let amount: Decimal?
    let millis: Int64
    let interest: Bool
    let entryFiat: Bool
    let template: TaxTemplate
    /// Dezimalzeichen des Blatts (`PortfolioTxSheet.decimalSeparator`).
    let decimalSeparator: Character
    @Binding var priceText: String
    @Binding var priceHint: Double?
    @Binding var priceAuto: Bool
    @Binding var priceHistoricDay: String?
    @State private var close: Decimal?
    @Environment(\.hidePortfolioAmounts) private var hidden

    private var probe: PortfolioTx {
        var t = PortfolioTx(id: txId, coin: coin, type: type, amountExact: amount ?? 1, priceExact: nil, time: millis)
        t.interest = interest
        return t
    }

    private var visible: Bool {
        isEdit && storedPrice == nil && priceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && coinValid
            && PortfolioCostGaps.isGap(probe)
    }

    var body: some View {
        if visible {
            let kind = PortfolioCostGaps.kind(probe, template: template)
            if kind != .EDITABLE {
                Text(L(kind == .INCOME_ZERO ? "cost_gap_income_zero" : "cost_gap_income"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else if !entryFiat {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    if let c = close {
                        let value = amount.flatMap { _ in PortfolioCostGaps.suggestion(probe, close: c) }
                        Text(L("cost_gap_suggestion", PortfolioFormat.date(millis),
                               PortfolioInsights.mask(value.map { PortfolioFormat.usdtValue(PortfolioDecimal.double($0)) }
                                                      ?? PortfolioFormat.price(PortfolioDecimal.double(c)), hidden: hidden)))
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                        Button(L("cost_gap_apply")) {
                            priceText = PriceFormat.exactAmountForInput(c, decimalSeparator: decimalSeparator)
                            priceHint = PortfolioDecimal.double(c)
                            priceAuto = false
                            priceHistoricDay = PortfolioFormat.date(millis)
                        }
                        .font(.subheadline.weight(.semibold))
                        .buttonStyle(.borderless)
                    }
                }
                .task(id: "\(coin)|\(millis)") {
                    close = await PortfolioCostGapUi.close(coin, millis: millis)
                }
            }
        }
    }
}
