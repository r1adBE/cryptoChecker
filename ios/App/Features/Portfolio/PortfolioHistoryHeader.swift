import SwiftUI

// Kopfzeile der Verlaufskarte («Wertverlauf · 30 T» mit Änderung, zum Auf-/Zuklappen) und die Zeile
// «Wert … · Gewinn …» über dem Chart.
// Eigene Datei, weil Kopf und Wertzeile eigene VoiceOver-Texte bauen und die Karte sonst zu lang wird
// (wie `PortfolioHistoryHeader.kt`).

extension PortfolioHistoryCard {
    /// Ganze Zeile tippbar: Titel, zugeklappt mit Zeitraum und Änderung, Pfeil zum Auf-/Zuklappen.
    var header: some View {
        Button {
            expanded.toggle()
        } label: {
            HStack(spacing: 8) {
                Text(expanded ? L("portfolio_history_title") : "\(L("portfolio_history_title")) · \(range.shortLabel)")
                    .sectionTitleStyle()
                    .lineLimit(1)
                Spacer(minLength: 8)
                if !expanded {
                    collapsedChange
                }
                // Pfeil in der Themenfarbe wie `ExpandToggleLabel` (alle Auf-/Zuklapp-Pfeile gleich)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
            }
            // Trefferfläche mind. 44 pt
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(headerAccessibility)
        .accessibilityHint(L(expanded ? "portfolio_history_collapse" : "portfolio_history_expand"))
        .accessibilityAddTraits(.isButton)
    }

    /// Zugeklappt: MEIN Gewinn über den Zeitraum (`pnlChange`, Käufe/Verkäufe zählen nie als Gewinn) als
    /// «↗ 2.40%» in der Kursfarbe; ohne Prozent der Betrag, ohne Gewinn/Verlust «—»; beim Laden ein kleiner
    /// Platzhalter — wie Android `CollapsedChange`.
    @ViewBuilder
    private var collapsedChange: some View {
        if let current {
            let series = current.series
            let change = series.hasChart ? series.pnlChange : nil
            if let change, let percent = series.pnlPercent {
                PortfolioPercentPill(percent: percent, value: change, size: .medium)
            } else if let change {
                PortfolioValuePill(text: PortfolioInsights.mask(Self.signedValue(change, unit: current.unit), hidden: hideAmounts),
                                   value: change, size: .medium)
            } else {
                PortfolioValuePill(text: "—", value: nil, size: .medium)
            }
        } else {
            SkeletonPulse(label: nil) {
                Text(verbatim: " ")
                    .font(.subheadline)
                    .cycleSkeletonBar(width: 64)
            }
        }
    }

    private var headerAccessibility: String {
        let title = L("portfolio_history_title")
        if expanded { return title }
        guard let current else { return A11y.join([title, L("portfolio_history_loading")]) }
        guard current.series.hasChart else { return A11y.join([title, range.longLabel]) }
        // Zugeklappt wie die Zahl: Wert und Gewinn über den Zeitraum
        return "\(title). " + Self.spokenGain(current.series, range: current.range, unit: current.unit, hidden: hideAmounts)
    }

    /// Kopfzeile, immer beides: «Wert 93’818.00 CHF · Gewinn +2’340.00 CHF (+2.40%)». Wert = heute, Gewinn = MEIN
    /// Gewinn/Verlust über den Zeitraum (`pnlChange`) in der Kursfarbe, Prozent vom eingesetzten Kapital
    /// (`pnlPercent`, ohne Basis nur der Betrag); ohne Gewinn/Verlust «—» — wie Android `ValueGainLine`.
    func valueGainLine(_ history: PortfolioHistoryUi) -> some View {
        let series = history.series
        let value = PortfolioInsights.mask(PriceFormat.valueWithCurrency(series.points.last?.value ?? 0, history.unit),
                                           hidden: hideAmounts)
        let gainColor = series.pnlChange.map {
            PortfolioFormat.plColor($0, scheme: priceColors, highContrast: highContrast, inverted: inverted)
        } ?? AppColors.onSurfaceVariant
        // Teile mit festen Typen (eine lange `+`-Kette aus Text und String ist für den Type-Checker teuer)
        // Build 46: Umbruch nur zwischen den Teilen («Wert …» · «Gewinn …»), nie in einer Zahl (geschützte Leerzeichen)
        let nbsp: String = "\u{00A0}"
        let valueLabel: String = L("portfolio_value") + nbsp
        let gainLabel: String = " · " + L("portfolio_history_gain_label") + nbsp
        let gain: String = Self.gainText(series, unit: history.unit, hidden: hideAmounts)
            .replacingOccurrences(of: " ", with: nbsp)
        let valueLabelText: Text = Text(valueLabel).foregroundColor(AppColors.onSurfaceVariant)
        let valueText: Text = Text(value.replacingOccurrences(of: " ", with: nbsp)).foregroundColor(AppColors.onSurface)
        let gainLabelText: Text = Text(gainLabel).foregroundColor(AppColors.onSurfaceVariant)
        let gainValueText: Text = Text(gain).fontWeight(.semibold).foregroundColor(gainColor)
        let line: Text = valueLabelText + valueText + gainLabelText + gainValueText
        return line
            .font(AppFont.amount(.subheadline))
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.spokenGain(series, range: history.range, unit: history.unit, hidden: hideAmounts))
    }
}
