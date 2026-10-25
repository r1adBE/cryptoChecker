import SwiftUI

// Karte «Jahresübersicht» der Arbeitsunterlage. Eigene Datei, weil sie eine eigenständige Anzeige ist,
// die die Seite «Steuer-Arbeitsunterlage» nur einbettet (wie `PortfolioTaxSummaryCard.kt`).

/// Karte «Jahresübersicht» der Vorlage: Tatsachen-Posten (Summen in der Währung der Vorlage, ohne Devisenkurse USDT) mit
/// dem Hinweis «Orientierung – keine Steuererklärung» (`texts.disclaimer`) unter dem Titel und unter den Summen, dann
/// die Orientierungs-Posten mit Vermerk «Orientierung» und Erklärung der Grenzen — ausdrücklich keine Steuerberatung.
/// Wie `TaxSummaryCard` (Android).
struct PortfolioTaxSummaryCard: View {
    /// nil = wird gerechnet.
    let summary: TaxYearSummary?
    let texts: TaxSummaryTexts
    /// Vorlage rechnet mit einer anderen Methode als die App («Vorlage rechnet FIFO, nicht Ø»); nil = gleich.
    var methodNote: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(texts.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurface)
            Text(texts.disclaimer)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.bottom, methodNote == nil ? 4 : 0)
            if let methodNote {
                Text(methodNote)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 4)
            }
            if let s = summary {
                let firstOrientation = s.lines.firstIndex { $0.line.orientation }
                // Letzter Tatsachen-Posten (Summen): dort steht der Hinweis «keine Steuererklärung» noch einmal
                // — gleich bei den Summen, nicht nur unter dem Titel und in der CSV. Wie Android
                let lastFact = s.lines.lastIndex { !$0.line.orientation }
                ForEach(Array(s.lines.enumerated()), id: \.offset) { index, l in
                    if index == firstOrientation {
                        Text(texts.orientationHeading.isEmpty ? texts.orientation : texts.orientationHeading)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppColors.onSurface)
                            .padding(.top, Spacing.xs)
                    }
                    row(s, l)
                    if index == lastFact {
                        Text(texts.disclaimer)
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 4)
                    }
                }
                if s.template == .DE {
                    Text(L("tax_de_limit_scope"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                }
            } else {
                HStack(spacing: Spacing.sm) {
                    ProgressView().controlSize(.small)
                    Text(L("tax_de_loading"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// Betrag: in der Währung der Vorlage, ohne Devisenkurse USDT; Grenzen nur in der Währung der Vorlage.
    private func money(_ s: TaxYearSummary, _ l: TaxSummaryLine) -> String? {
        let value: Decimal? = s.fxAvailable ? l.target : l.usdt
        if let value {
            return PriceFormat.valueWithCurrency(PortfolioDecimal.double(value), s.fxAvailable ? s.currency : "USDT")
        }
        guard let t = l.target else { return nil }
        return PriceFormat.valueWithCurrency(PortfolioDecimal.double(t), s.currency)
    }

    @ViewBuilder
    private func row(_ s: TaxYearSummary, _ l: TaxSummaryLine) -> some View {
        let plain: String = texts.labels[l.line] ?? ""
        // Orientierungs-Posten sichtbar als «Orientierung: …»
        let label: String = l.line.orientation
            ? taxOrientationLabel(texts.orientationItem, fallback: texts.orientation, label: plain) : plain
        if let st = l.status {
            status(label, st, limit: l.target, currency: s.currency)
        } else {
            let bold: Bool = [TaxLine.NET, TaxLine.SHORT_NET, TaxLine.NEW_NET].contains(l.line)
            HStack(alignment: .firstTextBaseline, spacing: Spacing.sm) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if let value = money(s, l) {
                    Text(verbatim: value)
                        .font(.caption.monospacedDigit().weight(bold ? .semibold : .regular))
                        .foregroundStyle(AppColors.onSurface)
                }
            }
        }
        let notes: [String] = lineNotes(l)
        if !notes.isEmpty {
            Text(notes.joined(separator: ", "))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        // CH Vermögenswert: je Coin Bestand und Wert, ohne Wert «nicht gerechnet – Grund» (wie die CSV) — wie Android
        if l.line == .CH_WEALTH_VALUE, let wealth = s.wealth {
            ForEach(Array(wealth.rows.enumerated()), id: \.offset) { _, r in
                wealthRow(s, r)
            }
        }
    }

    /// Zeile eines Coins im CH-Vermögenswert (eingerückt): Bestand, Wert in der Währung der Vorlage bzw. USDT oder Hinweis.
    private func wealthRow(_ s: TaxYearSummary, _ r: TaxWealthRow) -> some View {
        let v: Decimal? = s.fxAvailable ? r.valueTarget : r.valueUsdt
        let value: String = v.map {
            PriceFormat.valueWithCurrency(PortfolioDecimal.double($0), s.fxAvailable ? s.currency : "USDT")
        } ?? texts.wealthCoinNote(r)
        let below: String? = texts.wealthCoinNoteBelowValue(r, hasValue: v != nil)
        return VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.sm) {
                Text(verbatim: texts.wealthCoinLabel(r.coin, amount: PriceFormat.exactAmount(r.amount)))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Text(verbatim: value)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(v == nil ? AppColors.onSurfaceVariant : AppColors.onSurface)
                    .multilineTextAlignment(.trailing)
            }
            // Teilwert: Wert und darunter «unvollständig – Grund» (wie die CSV)
            if let below {
                Text(verbatim: below)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.leading, 12)
    }

    /// Vermerke eines Postens (unvollständig, ohne Devisenkurs, Zusatz-Vermerk).
    private func lineNotes(_ l: TaxSummaryLine) -> [String] {
        var out: [String] = []
        if l.incomplete { out.append(texts.incomplete) }
        if l.noFx { out.append(texts.noFx) }
        if l.notCalculated && !texts.notCalculated.isEmpty { out.append(texts.notCalculated) }
        if l.estimated && !texts.estimated.isEmpty { out.append(texts.estimated) }
        if let text = l.noteText(texts) { out.append(text) }
        // CH-Vermögen: nur wo Datei und Karte abweichen können (Posten nach der benutzten Beobachtung) — wie Android
        if l.line == .CH_WEALTH_VALUE && summary?.wealth?.addressNote == true && !texts.addressLots.isEmpty {
            out.append(texts.addressLots)
        }
        return out
    }

    /// Grenze: «Grenze …: unter der Grenze», darunter die Erklärung (mit dem Betrag der Grenze).
    private func status(_ label: String, _ status: TaxLimitStatus, limit: Decimal?, currency: String) -> some View {
        let amount = limit.map { PriceFormat.valueWithCurrency(PortfolioDecimal.double($0), currency) } ?? ""
        let text: String
        let explain: String
        switch status {
        case .below:
            text = texts.below
            explain = L("tax_de_explain_below", amount)
        case .reached:
            text = texts.reached
            explain = L("tax_de_explain_reached", amount)
        case .unknown:
            text = texts.unknown
            explain = L("tax_de_explain_unknown")
        case .loss:
            text = texts.loss.isEmpty ? texts.below : texts.loss
            explain = L("tax_de_explain_loss")
        }
        return VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: L("label_value", label, text))
                .font(.caption.weight(.medium))
                .foregroundStyle(status == .reached ? AppColors.error : AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(explain)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }
}
