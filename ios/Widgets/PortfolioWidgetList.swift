import SwiftUI
import WidgetKit

// Ansicht «Liste» des Portfolio-Widgets: Gesamtzeile und die grössten Positionen mit Anteil, Wert und
// Änderung. Eigene Datei, weil jede Ansicht des Widgets für sich lesbar sein soll (wie `PortfolioWidgetList.kt`).

extension PortfolioHomeView {
    /// Höhen der Liste (pt): Kopfzeile, Abstand darunter, Fusszeile — für `PortfolioWidgetViews.listRows`.
    static var listHeaderHeight: Double { 16 }
    static var listGap: Double { 6 }
    static var listFooterHeight: Double { 16 }

    // MARK: Liste

    /// Ansicht «Liste» — wie die Positionen im Portfolio-Tab (wie Android `listViews`): Kopfzeile,
    /// so viele Positionen nach Wert, wie ganz Platz haben (`PortfolioWidgetViews.listRows`; klein zweizeilig,
    /// sonst einzeilig), fest darunter die Total-Zeile (`listTotal`) und «Stand 15:19 · + 3 weitere».
    /// Ohne gespeicherte Positionen der Hinweis.
    func listBody(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        GeometryReader { geo in
            listContent(s, plan: plan, height: Double(geo.size.height))
        }
    }

    private func listContent(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan, height: Double) -> some View {
        let wide = family != .systemSmall
        let sorted = PortfolioWidgetViews.sortedByValue(s.topPositions)
        let row = PortfolioWidgetViews.rowHeight(wide: wide)
        // Total-Zeile geht den Zeilen vor (`listRowsBelowTotal`, wie Android `listLayout`)
        let fit = PortfolioWidgetViews.listRowsBelowTotal(height: height, header: Self.listHeaderHeight,
                                                          gap: Self.listGap, row: row,
                                                          footer: Self.listFooterHeight, count: sorted.count,
                                                          wide: wide)
        let shown = Array(sorted.prefix(fit.rows))
        let more = sorted.count - shown.count + Swift.max(0, s.otherPositions ?? 0)
        let moreText: String? = more > 0 && !sorted.isEmpty ? L("widget_portfolio_more", count: more) : nil
        let basis = PortfolioWidgetText.basis(s)
        let missing = PortfolioWidgetText.missing(s, plan: plan, overview: true)
        let change = PortfolioWidgetViews.listTotalChange(s)
        return VStack(alignment: .leading, spacing: 0) {
            header()
            // Zeilen füllen den Platz über den Fusszeilen; was nicht passt, wird abgeschnitten
            VStack(alignment: .leading, spacing: 0) {
                if sorted.isEmpty {
                    message(L("widget_portfolio_list_hint"))
                        .padding(.top, 8)
                } else {
                    listRows(shown, currency: s.currency, basis: basis, wide: wide, row: row)
                        .padding(.top, CGFloat(Self.listGap))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .clipped()
            listTotal(s, change: change, basis: basis, wide: wide)
            // «Stand …» (samt «Heute ohne …») immer sichtbar — die Zeilen weichen zuerst
            listFooter(s, missing: missing, more: moreText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(listAccessibility(s, shown: shown, more: moreText, missing: missing, change: change,
                                              basis: basis, empty: sorted.isEmpty))
    }

    /// Total-Zeile (immer sichtbar), gestaltet wie die Listenzeilen ohne Logo (Build 46: `wide` einzeilig, sonst
    /// zweizeilig): «Gesamt» (fett wie das Kürzel) und «100%» (wie der Anteil), dazu der Wert (Währung klein und grau;
    /// verborgen «•••») und MEIN Gewinn/Verlust wie in den Zeilen («24h +1’500.00 CHF · +1.61%», die Summe der Zeilen);
    /// ohne Betrag bleibt der Platz leer. Darüber die Trennlinie — wie Android `bindListTotal`.
    private func listTotal(_ s: PortfolioWidgetSnapshot, change: PortfolioListTotalChange?,
                           basis: ChangeBasis, wide: Bool) -> some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(palette.divider)
                .frame(height: 1)
            if wide {
                listTotalWide(s, change: change, basis: basis)
                    .padding(.vertical, 4)
            } else {
                listTotalNarrow(s, change: change, basis: basis)
                    .padding(.vertical, 4)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Total einzeilig wie `listRow` (breit): «Gesamt», Anteil, Wert, Gewinn/Verlust nebeneinander.
    private func listTotalWide(_ s: PortfolioWidgetSnapshot, change: PortfolioListTotalChange?,
                               basis: ChangeBasis) -> some View {
        HStack(spacing: 6) {
            listTotalLabel()
                .frame(minWidth: 38, alignment: .leading)
            listTotalShare()
            Spacer(minLength: 4)
            listTotalValue(s)
            // Ohne Betrag kein Platzhalter (Android: pl_change ausgeblendet)
            if let change {
                WidgetPnlLabel(change: change, currency: s.currency, palette: palette, hidden: entry.hideAmounts,
                               prefix: A11y.changeShortLabel(basis))
                    .frame(minWidth: 58, alignment: .trailing)
            }
        }
    }

    /// Total zweizeilig wie `listRow` (schmal).
    private func listTotalNarrow(_ s: PortfolioWidgetSnapshot, change: PortfolioListTotalChange?,
                                 basis: ChangeBasis) -> some View {
        HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 1) {
                listTotalLabel()
                listTotalShare()
            }
            .layoutPriority(1)
            Spacer(minLength: 2)
            VStack(alignment: .trailing, spacing: 1) {
                listTotalValue(s)
                if let change {
                    WidgetPnlLabel(change: change, currency: s.currency, palette: palette, hidden: entry.hideAmounts,
                                   prefix: A11y.changeShortLabel(basis))
                }
            }
            .layoutPriority(2)
        }
    }

    private func listTotalLabel() -> some View {
        Text(L("portfolio_card_total"))
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    private func listTotalShare() -> some View {
        Text(PortfolioWidgetText.share(PortfolioWidgetViews.listTotalShare))
            .font(.system(size: 11, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }

    private func listTotalValue(_ s: PortfolioWidgetSnapshot) -> some View {
        let value: String = entry.hideAmounts ? PortfolioInsights.hidden
            : PriceFormat.valueWithCurrency(s.total, s.currency)
        return Text.quoted(value, quote: s.currency, size: 13, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .privacySensitive()
    }

    /// «Stand 15:19 · Heute ohne SOL · + 3 weitere»; wird es eng, zuerst ohne «weitere», dann ohne Hinweis —
    /// wie Android (Fusszeile der Liste).
    private func listFooter(_ s: PortfolioWidgetSnapshot, missing: String?, more: String?) -> some View {
        let time = PortfolioWidgetText.asOf(s, outdated: entry.outdated, at: entry.date)
        let candidates = [[time, missing, more], [time, missing], [time, more], [time]]
            .map { $0.compactMap { $0 }.joined(separator: " · ") }
        var seen = Set<String>()
        let unique = candidates.filter { seen.insert($0).inserted }
        return ViewThatFits(in: .horizontal) {
            ForEach(unique, id: \.self) { footerText($0) }
        }
    }

    private func listRows(_ shown: [PortfolioWidgetPosition], currency: String, basis: ChangeBasis, wide: Bool,
                          row: Double) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(shown.enumerated()), id: \.element.symbol) { index, p in
                listRow(p, currency: currency, basis: basis, wide: wide)
                    .frame(height: CGFloat(row) - 1)
                // Trennlinie wie in der Merkliste, nicht nach der letzten Zeile
                Rectangle()
                    .fill(index == shown.count - 1 ? Color.clear : palette.divider)
                    .frame(height: 1)
            }
        }
    }

    /// Eine Position: Coin-Plakette (Schalter «Coin-Logos in Widgets»), Kürzel, Anteil (grau), Wert (Währung
    /// klein und grau) und MEIN Gewinn/Verlust je Coin nach der %-Basis («24h +1’250.00 CHF · +1.50%», eng nur der
    /// Betrag; unbekannt «—») — wie Android `listRow`.
    @ViewBuilder
    private func listRow(_ p: PortfolioWidgetPosition, currency: String, basis: ChangeBasis, wide: Bool) -> some View {
        if wide {
            HStack(spacing: 6) {
                listBadge(p, size: 20)
                quantityPrefixed(p, size: 11) {
                    listSymbol(p)
                        .frame(minWidth: 38, alignment: .leading)
                }
                listShare(p)
                Spacer(minLength: 4)
                listValue(p, currency: currency)
                WidgetPnlLabel(change: PortfolioWidgetViews.rowPnl(p), currency: currency, palette: palette,
                               hidden: entry.hideAmounts, prefix: A11y.changeShortLabel(basis))
                    .frame(minWidth: 58, alignment: .trailing)
            }
        } else {
            HStack(spacing: 6) {
                listBadge(p, size: 22)
                VStack(alignment: .leading, spacing: 1) {
                    quantityPrefixed(p, size: 11) { listSymbol(p) }
                    listShare(p)
                }
                .layoutPriority(1)
                Spacer(minLength: 2)
                VStack(alignment: .trailing, spacing: 1) {
                    listValue(p, currency: currency)
                    WidgetPnlLabel(change: PortfolioWidgetViews.rowPnl(p), currency: currency, palette: palette,
                                   hidden: entry.hideAmounts, prefix: A11y.changeShortLabel(basis))
                }
                .layoutPriority(2)
            }
        }
    }

    private func listBadge(_ p: PortfolioWidgetPosition, size: CGFloat) -> some View {
        WidgetCoinBadge(symbol: CoinLogos.logoKey(p.symbol, tradFi: false), size: size, dark: palette.dark,
                        accent: palette.accent)
    }

    private func listSymbol(_ p: PortfolioWidgetPosition) -> some View {
        Text(p.symbol)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    /// Menge klein und grau vor dem Kürzel («1.3 BTC»), wie Android `pl_quantity`: bei knapper Breite wird
    /// zuerst die Menge gekürzt, das Kürzel bleibt ganz; bei verborgenen Beträgen keine Menge.
    func quantityPrefixed<Symbol: View>(_ p: PortfolioWidgetPosition, size: CGFloat,
                                        @ViewBuilder symbol: () -> Symbol) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            if let quantity = WidgetQuantityFormat.visible(p.quantity, hidden: entry.hideAmounts) {
                Text(quantity)
                    .font(.system(size: size, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .privacySensitive()
            }
            symbol()
                .layoutPriority(1)
        }
    }

    private func listShare(_ p: PortfolioWidgetPosition) -> some View {
        Text(PortfolioWidgetText.share(p.sharePercent))
            .font(.system(size: 11, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }

    /// Wert «7’420.10 CHF», die Währung klein und grau; verborgen «•••».
    private func listValue(_ p: PortfolioWidgetPosition, currency: String) -> some View {
        let text = entry.hideAmounts ? PortfolioInsights.hidden : PriceFormat.valueWithCurrency(p.value, currency)
        return Text.quoted(text, quote: currency, size: 13, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .privacySensitive()
    }

    /// «Portfolio 93’818.79 CHF, gestiegen um 1.61% in 24 Stunden, Stand 01:02, …, Positionen, + 3 weitere» —
    /// Veränderung des Totals wie gezeigt, wie Android `listViews`.
    private func listAccessibility(_ s: PortfolioWidgetSnapshot, shown: [PortfolioWidgetPosition], more: String?,
                                   missing: String?, change: PortfolioListTotalChange?, basis: ChangeBasis,
                                   empty: Bool) -> String {
        let time = entry.outdated ? WidgetOutdated.spoken(s.updatedAt, at: entry.date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: entry.date))
        let total = L("widget_portfolio_name") + " "
            + (spokenHidden ? L("a11y_amount_hidden") : PortfolioWidgetText.total(s))
        let rows = shown.map { PortfolioWidgetText.listRowAccessibility($0, currency: s.currency, basis: basis,
                                                                         hidden: spokenHidden) }
        let list = rows.isEmpty ? nil : L("a11y_portfolio_positions", rows.joined(separator: "; "))
        let spokenChange = change.map {
            PortfolioWidgetText.pnlAccessibility($0, currency: s.currency, basis: basis, hidden: spokenHidden)
        }
        return A11y.join([total, spokenChange, time, missing, list, more,
                          empty ? L("widget_portfolio_list_hint") : nil])
    }
}
