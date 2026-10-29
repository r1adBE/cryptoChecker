import SwiftUI
import WidgetKit

// Ansicht «Liste» des Portfolio-Widgets: Gesamtzeile und die grössten Positionen mit Anteil, Wert und
// Änderung. Eigene Datei, weil jede Ansicht des Widgets für sich lesbar sein soll (wie `PortfolioWidgetList.kt`).

extension PortfolioHomeView {
    /// Höhen der Liste (pt): Kopfzeile, Abstand darunter, Fusszeile — für `PortfolioWidgetViews.listRows`.
    static var listHeaderHeight: Double { 16 }
    static var listGap: Double { 4 }
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
        // Dynamic Type: Kürzel und Werte wachsen (Zeilen höher, also weniger), solange noch eine Position Platz hat;
        // sonst Grundgrösse
        let grown = listFit(height: height, count: sorted.count, wide: wide, scale: Double(primaryScale))
        let scale: Double = primaryScale > 1 && (sorted.isEmpty || grown.rows > 0) ? Double(primaryScale) : 1
        let row = PortfolioWidgetViews.rowHeight(wide: wide, fontScale: scale)
        // Total-Zeile geht den Zeilen vor (`listRowsBelowTotal`, wie Android `listLayout`)
        let fit = listFit(height: height, count: sorted.count, wide: wide, scale: scale)
        let textScale = CGFloat(scale)
        let shown = Array(sorted.prefix(fit.rows))
        let entries = PortfolioWidgetViews.listEntries(shown, filter: entry.entryFilter, height: height,
                                                       header: Self.listHeaderHeight, gap: Self.listGap, row: row,
                                                       footer: Self.listFooterHeight, fontScale: scale, wide: wide)
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
                    listRows(shown, entries: entries, currency: s.currency, basis: basis, wide: wide, scale: textScale,
                             row: row)
                        .padding(.top, CGFloat(Self.listGap))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .clipped()
            listTotal(s, change: change, basis: basis, wide: wide, scale: textScale)
            // «Stand …» (samt «Heute ohne …») immer sichtbar — die Zeilen weichen zuerst
            listFooter(s, missing: missing, more: moreText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(listAccessibility(s, shown: shown, entries: entries, more: moreText, missing: missing, change: change,
                                              basis: basis, empty: sorted.isEmpty))
    }

    /// Zeilen unter der Total-Zeile bei der Höhe `height` und dem Schriftfaktor `scale` (`listRowsBelowTotal`).
    private func listFit(height: Double, count: Int, wide: Bool, scale: Double) -> (rows: Int, footer: Bool) {
        PortfolioWidgetViews.listRowsBelowTotal(height: height, header: Self.listHeaderHeight, gap: Self.listGap,
                                                row: PortfolioWidgetViews.rowHeight(wide: wide, fontScale: scale),
                                                footer: Self.listFooterHeight, count: count, fontScale: scale,
                                                wide: wide)
    }

    /// Total-Zeile (immer sichtbar), gestaltet wie die Listenzeilen ohne Logo (`wide` einzeilig, sonst
    /// zweizeilig): «Gesamt» (fett wie das Kürzel) und «100%» (wie der Anteil), dazu der Wert (Währung klein und grau;
    /// verborgen «•••») und MEIN Gewinn/Verlust wie in den Zeilen («24h +1’500.00 CHF · +1.61%», die Summe der Zeilen);
    /// ohne Betrag bleibt der Platz leer. Darüber die Trennlinie — wie Android `bindListTotal`.
    private func listTotal(_ s: PortfolioWidgetSnapshot, change: PortfolioListTotalChange?,
                           basis: ChangeBasis, wide: Bool, scale: CGFloat) -> some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(palette.divider)
                .frame(height: 1)
            if wide {
                listTotalWide(s, change: change, basis: basis, scale: scale)
                    .padding(.vertical, 4)
            } else {
                listTotalNarrow(s, change: change, basis: basis, scale: scale)
                    .padding(.vertical, 4)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Total einzeilig wie `listRow` (breit): «Gesamt», Anteil, Wert, Gewinn/Verlust nebeneinander.
    private func listTotalWide(_ s: PortfolioWidgetSnapshot, change: PortfolioListTotalChange?,
                               basis: ChangeBasis, scale: CGFloat) -> some View {
        HStack(spacing: 4) {
            listTotalLabel(scale: scale)
                .frame(minWidth: 38, alignment: .leading)
            listTotalShare(scale: scale)
            Spacer(minLength: 4)
            listTotalValue(s, scale: scale)
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
                                 basis: ChangeBasis, scale: CGFloat) -> some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 1) {
                listTotalLabel(scale: scale)
                listTotalShare(scale: scale)
            }
            .layoutPriority(1)
            Spacer(minLength: 2)
            VStack(alignment: .trailing, spacing: 1) {
                listTotalValue(s, scale: scale)
                if let change {
                    WidgetPnlLabel(change: change, currency: s.currency, palette: palette, hidden: entry.hideAmounts,
                                   prefix: A11y.changeShortLabel(basis))
                }
            }
            .layoutPriority(2)
        }
    }

    private func listTotalLabel(scale: CGFloat) -> some View {
        Text(L("portfolio_card_total"))
            .font(.system(size: WidgetFont.label * scale, weight: .semibold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(WidgetTextSize.minimumFactor(0.7, primaryScale: scale))
    }

    private func listTotalShare(scale: CGFloat) -> some View {
        Text(PortfolioWidgetText.share(PortfolioWidgetViews.listTotalShare))
            .font(.system(size: 12 * scale, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }

    private func listTotalValue(_ s: PortfolioWidgetSnapshot, scale: CGFloat) -> some View {
        let value: String = entry.hideAmounts ? PortfolioInsights.hidden
            : PriceFormat.valueWithCurrency(s.total, s.currency)
        return Text.quoted(value, quote: s.currency, size: WidgetFont.label * scale, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(WidgetTextSize.minimumFactor(0.6, primaryScale: scale))
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

    private func listRows(_ shown: [PortfolioWidgetPosition], entries: [[PortfolioWidgetEntry]], currency: String,
                          basis: ChangeBasis, wide: Bool, scale: CGFloat, row: Double) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(shown.enumerated()), id: \.element.symbol) { index, p in
                let below = index < entries.count ? entries[index] : []
                let last = index == shown.count - 1
                listRow(p, currency: currency, basis: basis, wide: wide, scale: scale)
                    .frame(height: CGFloat(row) - 1)
                // Trennlinie wie in der Merkliste, nicht nach der letzten Zeile; mit Buchungen erst nach diesen
                Rectangle()
                    .fill(last || !below.isEmpty ? Color.clear : palette.divider)
                    .frame(height: 1)
                ForEach(Array(below.enumerated()), id: \.offset) { i, e in
                    entryRow(e, currency: currency, scale: scale, divider: i == below.count - 1 && !last)
                }
            }
        }
    }

    /// Buchung unter einem Coin: eingerückt Menge (Abgang mit «−») und gekürzte Notiz (grau), rechts der heutige Wert
    /// (Währung klein und grau; verborgen «•••», dann auch ohne Menge; ohne Kurs «—») — wie Android `entryRow`.
    private func entryRow(_ e: PortfolioWidgetEntry, currency: String, scale: CGFloat, divider: Bool) -> some View {
        let size = CGFloat(PortfolioWidgetViews.entrySize) * scale
        let label = PortfolioWidgetText.entryLabel(e, hidden: entry.hideAmounts)
        let value = e.value.map { PortfolioInsights.mask(PriceFormat.valueWithCurrency($0, currency), hidden: entry.hideAmounts) }
            ?? "—"
        return VStack(spacing: 0) {
            HStack(spacing: 4) {
                Text(label)
                    .font(.system(size: size, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .privacySensitive()
                Spacer(minLength: 4)
                Text.quoted(value, quote: currency, size: size, quoteColor: palette.secondary)
                    .monospacedDigit()
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .privacySensitive()
                    .layoutPriority(1)
            }
            .padding(.leading, 16)
            .frame(height: CGFloat(PortfolioWidgetViews.entryHeight(fontScale: Double(scale))) - (divider ? 1 : 0))
            if divider {
                Rectangle()
                    .fill(palette.divider)
                    .frame(height: 1)
            }
        }
    }

    /// Eine Position: Coin-Plakette (Schalter «Coin-Logos in Widgets»), Kürzel, Anteil (grau), Wert (Währung
    /// klein und grau) und MEIN Gewinn/Verlust je Coin nach der %-Basis («24h +1’250.00 CHF · +1.50%», eng nur der
    /// Betrag; unbekannt «—») — wie Android `listRow`.
    @ViewBuilder
    private func listRow(_ p: PortfolioWidgetPosition, currency: String, basis: ChangeBasis, wide: Bool,
                         scale: CGFloat) -> some View {
        if wide {
            HStack(spacing: 4) {
                listBadge(p, size: 20)
                quantityPrefixed(p, size: 12 * scale) {
                    listSymbol(p, scale: scale)
                        .frame(minWidth: 38, alignment: .leading)
                }
                listShare(p, scale: scale)
                Spacer(minLength: 4)
                listValue(p, currency: currency, scale: scale)
                WidgetPnlLabel(change: PortfolioWidgetViews.rowPnl(p), currency: currency, palette: palette,
                               hidden: entry.hideAmounts, prefix: A11y.changeShortLabel(basis))
                    .frame(minWidth: 58, alignment: .trailing)
            }
        } else {
            HStack(spacing: 4) {
                listBadge(p, size: 22)
                VStack(alignment: .leading, spacing: 1) {
                    quantityPrefixed(p, size: 12 * scale) { listSymbol(p, scale: scale) }
                    listShare(p, scale: scale)
                }
                .layoutPriority(1)
                Spacer(minLength: 2)
                VStack(alignment: .trailing, spacing: 1) {
                    listValue(p, currency: currency, scale: scale)
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

    private func listSymbol(_ p: PortfolioWidgetPosition, scale: CGFloat) -> some View {
        Text(p.symbol)
            .font(.system(size: WidgetFont.label * scale, weight: .semibold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(WidgetTextSize.minimumFactor(0.7, primaryScale: scale))
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

    private func listShare(_ p: PortfolioWidgetPosition, scale: CGFloat) -> some View {
        Text(PortfolioWidgetText.share(p.sharePercent))
            .font(.system(size: 12 * scale, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }

    /// Wert «7’420.10 CHF», die Währung klein und grau; verborgen «•••».
    private func listValue(_ p: PortfolioWidgetPosition, currency: String, scale: CGFloat) -> some View {
        let text = entry.hideAmounts ? PortfolioInsights.hidden : PriceFormat.valueWithCurrency(p.value, currency)
        return Text.quoted(text, quote: currency, size: WidgetFont.label * scale, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(WidgetTextSize.minimumFactor(0.6, primaryScale: scale))
            .privacySensitive()
    }

    /// «Portfolio 93’818.79 CHF, gestiegen um 1.61% in 24 Stunden, Stand 01:02, …, Positionen, + 3 weitere» —
    /// Veränderung des Totals wie gezeigt, wie Android `listViews`.
    private func listAccessibility(_ s: PortfolioWidgetSnapshot, shown: [PortfolioWidgetPosition],
                                   entries: [[PortfolioWidgetEntry]], more: String?,
                                   missing: String?, change: PortfolioListTotalChange?, basis: ChangeBasis,
                                   empty: Bool) -> String {
        let time = entry.outdated ? WidgetOutdated.spoken(s.updatedAt, at: entry.date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: entry.date))
        let total = L("widget_portfolio_name") + " "
            + (spokenHidden ? L("a11y_amount_hidden") : PortfolioWidgetText.total(s))
        let rows = shown.enumerated().map { index, p in
            let row = PortfolioWidgetText.listRowAccessibility(p, currency: s.currency, basis: basis, hidden: spokenHidden)
            let below = (index < entries.count ? entries[index] : []).map {
                PortfolioWidgetText.entryAccessibility($0, currency: s.currency, hidden: spokenHidden)
            }
            return ([row] + below).joined(separator: ", ")
        }
        let list = rows.isEmpty ? nil : L("a11y_portfolio_positions", rows.joined(separator: "; "))
        let spokenChange = change.map {
            PortfolioWidgetText.pnlAccessibility($0, currency: s.currency, basis: basis, hidden: spokenHidden)
        }
        return A11y.join([total, spokenChange, time, missing, list, more,
                          empty ? L("widget_portfolio_list_hint") : nil])
    }
}
