import SwiftUI
import WidgetKit

// Ansicht «Übersicht» des Portfolio-Widgets: Gesamtwert und Kennzahlen (Heute · Zeitraum · Gesamt).
// Eigene Datei, weil jede Ansicht des Widgets für sich lesbar sein soll (wie `PortfolioWidgetOverview.kt`).

extension PortfolioHomeView {
    // MARK: Übersicht

    /// Ansicht «Übersicht» — wie die Gesamtkarte im Portfolio-Tab (wie Android `overviewViews`): Kopfzeile,
    /// Gesamtwert, Betrag und Prozent über den Zeitraum, ≈ USDT, Heute · 7 T · Gesamt (klein untereinander,
    /// mittel und gross als Spalten mit Betrag) und Fusszeile. Wird es eng, fallen Teile in der Reihenfolge
    /// Beträge der Kennzahlen, ≈ USDT, Fusszeile, Betragszeile weg (wie `PortfolioWidgetViews.overviewLayout`).
    func overviewBody(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        let figures = PortfolioWidgetViews.figures(s)
        let usdt = PortfolioWidgetText.usdt(s, wants: entry.wantsUsdt, hidden: entry.hideAmounts)
        let wide = family != .systemSmall
        return ViewThatFits(in: .vertical) {
            overviewContent(s, plan: plan, figures: figures, usdt: usdt, footer: true, amounts: wide)
            overviewContent(s, plan: plan, figures: figures, usdt: usdt, footer: true, amounts: false)
            overviewContent(s, plan: plan, figures: figures, usdt: nil, footer: true, amounts: false)
            overviewContent(s, plan: plan, figures: figures, usdt: nil, footer: false, amounts: false)
            overviewContent(s, plan: plan, figures: figures, usdt: nil, footer: false, amounts: false, change: false)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(overviewAccessibility(s, plan: plan, figures: figures, usdt: usdt))
    }

    private func overviewContent(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan, figures: [PortfolioFigure],
                                 usdt: String?, footer showsFooter: Bool, amounts: Bool,
                                 change: Bool = true) -> some View {
        let large = family == .systemLarge
        let missing = PortfolioWidgetText.missing(s, plan: plan, overview: true)
        // Ohne zweite Zeile: «ohne 1 Coin» / «Heute ohne SOL» in die Fusszeile
        let footerMissing = usdt == nil ? missing : nil
        return VStack(alignment: .leading, spacing: 0) {
            header()
            Spacer(minLength: 2)
            totalText(s, size: large ? 30 : (family == .systemSmall ? 20 : 24))
            if change { changeLine(s, plan: plan) }
            if usdt != nil { usdtLine(usdt, missing: missing) }
            Spacer(minLength: 6)
            figuresView(figures, currency: s.currency, amounts: amounts)
            if showsFooter {
                Spacer(minLength: 4)
                footer(s, period: nil, hint: PortfolioWidgetText.fallbackHint(plan), extra: footerMissing)
            }
        }
    }

    /// Klein: drei Zeilen (Beschriftung links, Wert rechtsbündig); sonst drei Spalten (Beschriftung, Wert, Betrag).
    @ViewBuilder
    private func figuresView(_ figures: [PortfolioFigure], currency: String, amounts: Bool) -> some View {
        if family == .systemSmall {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(figures, id: \.kind) { f in
                    HStack(spacing: 6) {
                        figureLabel(f, currency: currency, size: 12)
                        Spacer(minLength: 4)
                        figureValue(f)
                    }
                }
            }
        } else {
            HStack(alignment: .top, spacing: 8) {
                ForEach(figures, id: \.kind) { f in
                    figureColumn(f, currency: currency, amounts: amounts)
                }
            }
        }
    }

    private func figureColumn(_ f: PortfolioFigure, currency: String, amounts: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            figureLabel(f, currency: currency, size: family == .systemLarge ? 11 : 10)
            figureValue(f)
            if amounts { figureAmount(f, currency: currency) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func figureLabel(_ f: PortfolioFigure, currency: String, size: CGFloat) -> some View {
        Text(PortfolioWidgetText.figureLabel(f.kind, currency: currency))
            .font(.system(size: size, weight: .medium))
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }

    /// Wert «↗ +1.50%» als Text in der Kursfarbe nach dem Vorzeichen (Betrag, sonst Prozent; keine Pille,
    /// 0 grau ohne Pfeil); ohne Wert grau «—».
    @ViewBuilder
    private func figureValue(_ f: PortfolioFigure) -> some View {
        if let percent = f.percent {
            let direction = PortfolioWidgetViews.figureDirection(f)
            HStack(spacing: 2) {
                ChangeArrowIcon(change: direction)
                    .font(.system(size: 8.5, weight: .bold))
                // Ein Coin fehlt (kein Devisenkurs am Kauftag, seither gekauft/verkauft): «*» wie «unvollständig» der Karte
                Text(PriceFormat.signedPercent(percent, decimals: 2) + (f.incomplete ? "*" : ""))
                    .font(.system(size: 12, weight: .bold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .foregroundStyle(palette.change(direction))
            .fixedSize()
        } else {
            Text(verbatim: "—")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(palette.secondary)
        }
    }

    /// Betrag unter dem Wert «+182.40 CHF» (Währung klein und grau); verborgen «•••», ohne Wert «—».
    private func figureAmount(_ f: PortfolioFigure, currency: String) -> some View {
        // Einheit des Betrags: «7 T» ohne Tageskurse der Anzeigewährung in USDT (nie USDT × heutiger Kurs)
        let unit = f.unit ?? currency
        let text = f.amount.map {
            entry.hideAmounts ? PortfolioInsights.hidden : PortfolioWidgetText.signedAmount($0, currency: unit)
        } ?? "—"
        return Text.quoted(text, quote: unit, size: 12, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .privacySensitive()
    }

    private func overviewAccessibility(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan,
                                       figures: [PortfolioFigure], usdt: String?) -> String {
        let base = PortfolioWidgetText.accessibility(s, plan: plan, outdated: entry.outdated, at: entry.date,
                                                     usdt: spokenHidden ? nil : usdt, hidden: spokenHidden,
                                                     overview: true)
        let spoken = figures.map { PortfolioWidgetText.figureAccessibility($0, currency: s.currency, hidden: spokenHidden) }
        return A11y.join([base, spoken.joined(separator: "; ")])
    }
}
