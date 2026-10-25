import SwiftUI

/// Regelstand auf der Seite «Steuer-Arbeitsunterlage» (nur Länder-Vorlagen). Liegt das Steuerjahr nach dem Jahr des
/// Regelstands (`TaxRulesDate.rulesStale`), darunter ein deutlicher Hinweis in Bernstein mit ⚠ — Sätze und Freibeträge
/// sind fest hinterlegt und können sich geändert haben. Für VoiceOver «Warnung, …». Blockiert keinen Export.
/// Wie Android `TaxRulesNotice`.
struct PortfolioTaxRulesNotice: View {
    let template: TaxTemplate
    let taxYear: Int

    var body: some View {
        if template != .NEUTRAL {
            let rulesText = TaxRulesDate.label()
            PortfolioHint(text: L("tax_export_rules_date", rulesText))
                .padding(.horizontal, 4)
            if TaxRulesDate.rulesStale(taxYear: taxYear) {
                let warning = L("tax_export_rules_stale", rulesText, template.label(taxYear))
                HStack(alignment: .firstTextBaseline, spacing: Spacing.sm) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(AppColors.warningText)
                    Text(warning)
                        .font(.footnote)
                        .foregroundStyle(AppColors.warningText)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(Spacing.sm)
                .background(AppColors.warning.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: L("a11y_warning") + ", " + warning))
            }
        }
    }
}
