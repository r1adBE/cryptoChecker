import SwiftUI

/// Gemeinsame Texte des Portfolio-Widgets. `s` ist der gezeigte Stand (`PortfolioEntry.shown`, bei
/// «Nur USDT» schon in USDT), `plan` Zeitraum, Chart und Veränderung (`PortfolioWidgetChart.plan`).
enum PortfolioWidgetText {
    /// Bis zu dieser Spanne heisst der Stundenverlauf «24h», darüber «48h».
    static let daySpanMillis: Int64 = 25 * 3_600_000

    /// «12’345.67 CHF».
    static func total(_ s: PortfolioWidgetSnapshot, hidden: Bool = false) -> String {
        PortfolioInsights.mask(PriceFormat.valueWithCurrency(s.total, s.currency), hidden: hidden)
    }

    /// «+182.40 CHF» / «−1’968.40 CHF» über den Zeitraum; nil ohne Vergleichswert.
    static func amount(_ plan: PortfolioChartPlan, currency: String, hidden: Bool = false) -> String? {
        guard let value = plan.changeAmount else { return nil }
        if hidden { return PortfolioInsights.hidden }
        return signedAmount(value, currency: currency)
    }

    /// «+182.40 CHF» / «−1’968.40 CHF» / «0.00 CHF» (echtes Minus U+2212).
    static func signedAmount(_ value: Double, currency: String) -> String {
        let zero = abs(value) < 0.005
        let sign = zero ? "" : (value > 0 ? "+" : "−")
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr(sign + PriceFormat.valueWithCurrency(abs(value), currency))
    }

    /// Visuelle Beschriftung einer Kennzahl der Übersicht: «Heute», «7 T», «Gesamt [CHF]» — «Gesamt»
    /// mit der Währung der gezeigten Beträge (`currency`, bei «Nur USDT» «USDT»), wie Android.
    static func figureLabel(_ kind: PortfolioFigureKind, currency: String) -> String {
        switch kind {
        case .today: L("portfolio_card_today")
        case .week: L("portfolio_history_range_7d")
        case .total: L("ledger_since_purchase_currency", currency)
        }
    }

    /// VoiceOver einer Kennzahl: «7 Tage: gestiegen um 2.10%, +123.45 CHF»; ohne Wert «… noch nicht
    /// verfügbar» — wie die Gesamtkarte der App.
    static func figureAccessibility(_ f: PortfolioFigure, currency: String, hidden: Bool) -> String {
        let label: String
        switch f.kind {
        case .today: label = L("portfolio_card_today")
        case .week: label = L("portfolio_history_period_7d")
        case .total: label = L("ledger_since_purchase_spoken")
        }
        let value = f.amount.map { hidden ? L("a11y_amount_hidden") : signedAmount($0, currency: currency) }
        let change = f.percent.flatMap { A11y.change($0) }
        if value == nil && change == nil { return L("portfolio_card_figure_none_a11y", label) }
        return label + ": " + A11y.join([change, value])
    }

    /// VoiceOver einer Zeile der Liste: «BTC 7’420.10 CHF, 60.1% des Portfolios, gestiegen um 125.00 CHF,
    /// gestiegen um 1.80% in 24 Stunden» (MEIN Gewinn/Verlust auf der Position).
    static func listRowAccessibility(_ p: PortfolioWidgetPosition, currency: String, basis: ChangeBasis,
                                     hidden: Bool) -> String {
        let value = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(p.value, currency)
        let name = WidgetQuantityFormat.spokenName(p.symbol, quantity: WidgetQuantityFormat.visible(p.quantity, hidden: hidden))
        return L("a11y_portfolio_position", name, value, share(p.sharePercent)) + ", "
            + pnlAccessibility(PortfolioWidgetViews.rowPnl(p), currency: currency, basis: basis, hidden: hidden)
    }

    /// Gewinn/Verlust für VoiceOver: «gestiegen um 1’250.00 CHF, gestiegen um 1.50% in 24 Stunden» (Betrag
    /// verborgen: «Betrag verborgen»); unbekannt «… nicht verfügbar» — wie Android `spokenPnl`.
    static func pnlAccessibility(_ c: PortfolioListTotalChange?, currency: String, basis: ChangeBasis,
                                 hidden: Bool) -> String {
        guard let c else { return A11y.change(nil, basis: basis) }
        let amount: String
        if hidden {
            amount = L("a11y_amount_hidden")
        } else if c.direction == 0 {
            amount = L("a11y_change_flat")
        } else {
            amount = L(c.direction > 0 ? "a11y_change_up" : "a11y_change_down",
                       PriceFormat.valueWithCurrency(abs(c.amount), currency))
        }
        return A11y.changePhrase(A11y.join([amount, c.percent.flatMap { A11y.change($0) }]), basis: basis)
    }

    /// Richtung für Farbe und Pfeil nach dem Vorzeichen des Betrags (nie nach dem Farbtausch):
    /// +1 / −1 / 0 als Prozentwert für `palette.change` und `ChangeArrowIcon`; nil ohne Vergleichswert.
    static func directionValue(_ plan: PortfolioChartPlan) -> Double? {
        PortfolioWidgetChart.direction(plan).map(Double.init)
    }

    /// %-Basis, mit der der Stand gerechnet ist (älterer Stand: rollend).
    static func basis(_ s: PortfolioWidgetSnapshot) -> ChangeBasis { s.stamp?.basis ?? .ROLLING_24H }

    /// Kurzform des Zeitraums der Veränderung: 24 h nach der %-Basis («24h», «heute»), sonst «7T» …
    static func changePeriod(_ plan: PortfolioChartPlan, _ s: PortfolioWidgetSnapshot) -> String {
        plan.range == .day ? A11y.changeShortLabel(basis(s)) : L(plan.range.shortLabelKey)
    }

    /// «−1’968.40 CHF · 24h» bzw. «… · 7T» (Pfeil davor als Symbol); nil ohne Vergleichswert.
    static func changeLine(_ plan: PortfolioChartPlan, _ s: PortfolioWidgetSnapshot, withPeriod: Bool = true,
                           hidden: Bool = false) -> String? {
        guard let amount = amount(plan, currency: s.currency, hidden: hidden) else { return nil }
        return withPeriod ? amount + " · " + changePeriod(plan, s) : amount
    }

    /// «92’310.00 USDT» bei «Umrechnung + USDT» (`wants`); sonst nil.
    static func usdt(_ s: PortfolioWidgetSnapshot, wants: Bool, hidden: Bool = false) -> String? {
        guard wants, !hidden, let value = s.totalUsdt, value.isFinite,
              PortfolioWidgetStore.showsUsdt(enabled: true, currency: s.currency) else { return nil }
        return PriceFormat.valueWithCurrency(value, "USDT")
    }

    /// «ohne 1 Coin» / «ohne 2 Coins»: offene Coins ohne Kurs (zählen im Gesamtwert als 0). Dazu
    /// «Heute ohne SOL», wenn die sichtbare «Heute»-Zahl Coins auslässt (wie der Hinweis der Gesamtkarte,
    /// `PortfolioWidgetViews.todaySkipped`) und «teilweise alter Kurs» (`stalePrices`). Alles fehlt: nil.
    static func missing(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan, overview: Bool = false) -> String? {
        let count = s.missingCount
        let coins = count > 0 ? L("widget_portfolio_missing_coins", count: count) : nil
        let skipped = PortfolioWidgetViews.todaySkipped(s, plan: plan, overview: overview)
        let today = skipped.isEmpty ? nil : L("widget_portfolio_today_without", skipped.joined(separator: ", "))
        // «7 T ohne SOL»: dieselbe Teilsumme wie «Gewinn 7 T» der App, mit derselben Nennung — wie Android
        let period = PortfolioWidgetViews.periodSkipped(s, plan: plan, overview: overview).map {
            L("widget_portfolio_period_without", L($0.range.shortLabelKey), $0.coins.joined(separator: ", "))
        }
        // Teilweise alter Kurs: der Wert zählt Coins mit ihrem letzten Kurs — nie als frisch ausgeben (wie Android)
        let stale = s.staleCount > 0 ? L("widget_portfolio_partly_stale") : nil
        let text = [coins, stale, today, period].compactMap { $0 }.joined(separator: " · ")
        return text.isEmpty ? nil : text
    }

    /// Tages-Basis: feste Zeitachse vom Tagesbeginn bis Tagesende; nil bei rollend.
    static func dayAxis(_ s: PortfolioWidgetSnapshot) -> (from: Int64, to: Int64)? {
        guard let stamp = s.stamp, stamp.basis.isDay,
              let end = ChangeBasisMath.dayEnd(stamp.basis, dayStart: stamp.dayStart) else { return nil }
        return (from: stamp.dayStart, to: end)
    }

    /// Zeitraum des Charts: lang (VoiceOver) bzw. kurz (Fusszeile); 24 h seit Tagesbeginn «heute» /
    /// «Seit 00:00 …», sonst «24h» bzw. «48h»; 7 T / 30 T / 1 J ihre Bezeichnung.
    static func period(_ plan: PortfolioChartPlan, basis: ChangeBasis = .ROLLING_24H, short: Bool) -> String {
        if plan.range != .day { return L(short ? plan.range.shortLabelKey : plan.range.labelKey) }
        if basis.isDay { return short ? A11y.changeShortLabel(basis) : A11y.changeLongLabel(basis) }
        let end = plan.bars.map(\.end).max() ?? 0
        let day = end - (plan.bars.first?.start ?? 0) <= daySpanMillis
        if short { return L(day ? "widget_range_short_24h" : "widget_portfolio_range_short_48h") }
        return L(day ? "widget_range_24h" : "widget_portfolio_range_48h")
    }

    /// Gewählter Zeitraum ohne Tageskurse (Widget zeigt 24 h): «7T: Portfolio öffnen»; sonst nil.
    static func fallbackHint(_ plan: PortfolioChartPlan) -> String? {
        plan.fallback ? L("widget_portfolio_range_fallback", L(plan.requested.shortLabelKey)) : nil
    }

    /// VoiceOver-Satz zum Chart (Zeitraum, Start, Ende, Veränderung, Hoch, Tief); nil ohne Chart.
    static func chartAccessibility(_ plan: PortfolioChartPlan, currency: String, basis: ChangeBasis = .ROLLING_24H,
                                   hidden: Bool = false) -> String? {
        guard plan.hasChart else { return nil }
        return A11y.chart(period: period(plan, basis: basis, short: false), values: plan.bars.map(\.close),
                          format: { hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency($0, currency) })
    }

    /// Anteil mit einer Nachkommastelle, z. B. «62.3%».
    static func share(_ percent: Double) -> String {
        String(format: "%.1f%%", locale: Locale.current, Swift.min(100, Swift.max(0, percent)))
    }

    /// «Stand 15:19» bzw. «veraltet · 06:42» (ohne Sekunden).
    static func asOf(_ s: PortfolioWidgetSnapshot, outdated: Bool, at date: Date) -> String {
        outdated ? WidgetOutdated.label(s.updatedAt, at: date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: date))
    }

    /// VoiceOver: «Grösste Positionen: BTC, 60.1% des Portfolios, gestiegen um 125.00 CHF, gestiegen um 1.80% in
    /// 24 Stunden; …» (MEIN Gewinn/Verlust je Position).
    /// Mit Menge vor dem Kürzel («1.3 BTC»), ausser bei verborgenen Beträgen (`hidden`).
    static func positionsAccessibility(_ shown: [PortfolioWidgetPosition], currency: String,
                                       basis: ChangeBasis = .ROLLING_24H, hidden: Bool = false) -> String? {
        guard !shown.isEmpty else { return nil }
        let parts = shown.map { p in
            L("a11y_portfolio_position_24h",
              WidgetQuantityFormat.spokenName(p.symbol, quantity: WidgetQuantityFormat.visible(p.quantity, hidden: hidden)),
              share(p.sharePercent),
              pnlAccessibility(PortfolioWidgetViews.rowPnl(p), currency: currency, basis: basis, hidden: hidden))
        }
        return L("a11y_portfolio_positions", parts.joined(separator: "; "))
    }

    /// Veränderung für VoiceOver: 24 h nach der %-Basis («gesunken um 2.31% in 24 Stunden»), sonst
    /// «gestiegen um 4.10%, 7 Tage»; nil ohne Vergleichswert.
    static func spokenChange(_ plan: PortfolioChartPlan, _ s: PortfolioWidgetSnapshot, hidden: Bool) -> String? {
        guard let amount = plan.changeAmount else { return nil }
        let phrase: String
        if let percent = plan.changePercent {
            phrase = A11y.change(percent) ?? L("a11y_change_flat")
        } else if PortfolioWidgetSeries.direction(amount) == 0 {
            phrase = L("a11y_change_flat")
        } else {
            let value = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(abs(amount), s.currency)
            phrase = L(amount > 0 ? "a11y_change_up" : "a11y_change_down", value)
        }
        if plan.range != .day { return phrase + ", " + L(plan.range.labelKey) }
        if let percent = plan.changePercent { return A11y.change(percent, basis: basis(s)) }
        return A11y.changePhrase(phrase, basis: basis(s))
    }

    /// VoiceOver: «Portfolio 83’170.32 CHF, gesunken um 2.31% in 24 Stunden, Stand 15:19» — danach
    /// ≈ USDT, Chart, Hinweis und Positionen, soweit sichtbar. Veraltet: «veraltet, letzte Aktualisierung 06:42».
    static func accessibility(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan, outdated: Bool, at date: Date,
                              usdt: String? = nil, chart: String? = nil, positions: String? = nil,
                              hidden: Bool = false, overview: Bool = false) -> String {
        let time = outdated ? WidgetOutdated.spoken(s.updatedAt, at: date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: date))
        let spokenTotal = hidden ? L("a11y_amount_hidden") : total(s)
        return A11y.join([L("widget_portfolio_name") + " " + spokenTotal, spokenChange(plan, s, hidden: hidden), time,
                          usdt.map { L("a11y_converted", $0) }, missing(s, plan: plan, overview: overview), chart, fallbackHint(plan), positions])
    }

    static var appURL: URL? { URL(string: "cryptochecker://portfolio") }

    /// Portfolio aus: «Einschalten unter Einstellungen › Erweitert › Portfolio.» — Pfad aus den Namen der App (Build 53).
    static var offHint: String {
        L("widget_portfolio_off_hint",
          AlarmTimingHint.settingsPath([L("settings_title"), L("settings_row_advanced"), L("portfolio_title")]))
    }
}
