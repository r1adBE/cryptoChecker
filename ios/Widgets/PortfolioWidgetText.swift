import SwiftUI

/// Gemeinsame Texte des Portfolio-Widgets.
enum PortfolioWidgetText {
    /// Bis zu dieser Spanne heisst der Wertverlauf «24h», darüber «48h».
    static let daySpanMillis: Int64 = 25 * 3_600_000

    /// «12’345.67 CHF».
    static func total(_ s: PortfolioWidgetSnapshot, hidden: Bool = false) -> String {
        PortfolioInsights.mask(PriceFormat.valueWithCurrency(s.total, s.currency), hidden: hidden)
    }

    /// Veränderung über 24 h in der Anzeigewährung; nil ohne Vergleichswert.
    static func changeAmount(_ s: PortfolioWidgetSnapshot) -> Double? {
        guard let value = s.changeAmount, value.isFinite else { return nil }
        return value
    }

    /// «+182.40 CHF» / «−1’968.40 CHF»; nil ohne Vergleichswert.
    static func amount(_ s: PortfolioWidgetSnapshot, hidden: Bool = false) -> String? {
        guard let value = changeAmount(s) else { return nil }
        if hidden { return PortfolioInsights.hidden }
        let zero = abs(value) < 0.005
        let sign = zero ? "" : (value > 0 ? "+" : "−")
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr(sign + PriceFormat.valueWithCurrency(abs(value), s.currency))
    }

    /// «2.31%» ohne Vorzeichen (Pille, Richtung zeigt der Pfeil); nil ohne Vergleichswert.
    static func pillPercent(_ s: PortfolioWidgetSnapshot) -> String? {
        guard changeAmount(s) != nil, let value = s.changePercent, value.isFinite else { return nil }
        return String(format: "%.2f%%", locale: Locale.current, abs(value))
    }

    /// Richtung für Farbe und Pfeil nach dem Vorzeichen des Betrags (nie nach dem Farbtausch):
    /// +1 / −1 / 0 als Prozentwert für `palette.change` und `ChangeArrowIcon`; nil ohne Vergleichswert.
    static func directionValue(_ s: PortfolioWidgetSnapshot) -> Double? {
        guard let value = changeAmount(s) else { return nil }
        return Double(PortfolioWidgetSeries.direction(value))
    }

    /// %-Basis, mit der der Stand gerechnet ist (älterer Stand: rollend).
    static func basis(_ s: PortfolioWidgetSnapshot) -> ChangeBasis { s.stamp?.basis ?? .ROLLING_24H }

    /// «−1’968.40 CHF · 24h» bzw. «… · heute» (Pfeil davor als Symbol); nil ohne Vergleichswert.
    static func changeLine(_ s: PortfolioWidgetSnapshot, withPeriod: Bool = true, hidden: Bool = false) -> String? {
        guard let amount = amount(s, hidden: hidden) else { return nil }
        return withPeriod ? amount + " · " + A11y.changeShortLabel(basis(s)) : amount
    }

    /// «92’310.00 USDT», wenn gewünscht und die Anzeigewährung nicht schon USD ist; sonst nil.
    static func usdt(_ s: PortfolioWidgetSnapshot, display: PortfolioDisplayOption, hidden: Bool = false) -> String? {
        guard !hidden, let value = s.totalUsdt, value.isFinite,
              PortfolioWidgetStore.showsUsdt(enabled: display.showsUsdt, currency: s.currency) else { return nil }
        return PriceFormat.valueWithCurrency(value, "USDT")
    }

    /// Wertverlauf mit genug Punkten für den Flächen-Chart, sonst nil («Verlauf folgt»).
    /// Tages-Basis: schon ab zwei Punkten, der Chart wächst über den Tag (`dayAxis`).
    static func chartPoints(_ s: PortfolioWidgetSnapshot) -> [PortfolioWidgetPoint]? {
        let points = s.valueHistory.filter { $0.value.isFinite }
        let enough = dayAxis(s) != nil ? PortfolioWidgetSeries.drawableDay(points) : PortfolioWidgetSeries.drawable(points)
        return enough ? points : nil
    }

    /// Tages-Basis: feste Zeitachse vom Tagesbeginn bis Tagesende; nil bei rollend.
    static func dayAxis(_ s: PortfolioWidgetSnapshot) -> (from: Int64, to: Int64)? {
        guard let stamp = s.stamp, stamp.basis.isDay,
              let end = ChangeBasisMath.dayEnd(stamp.basis, dayStart: stamp.dayStart) else { return nil }
        return (from: stamp.dayStart, to: end)
    }

    /// Zeitraum des Wertverlaufs: lang (VoiceOver) bzw. kurz (Fusszeile); seit Tagesbeginn
    /// «heute» / «Seit 00:00 …», sonst «24h» bzw. «48h».
    static func period(_ points: [PortfolioWidgetPoint], short: Bool, basis: ChangeBasis = .ROLLING_24H) -> String {
        if basis.isDay { return short ? A11y.changeShortLabel(basis) : A11y.changeLongLabel(basis) }
        let day = (points.last?.at ?? 0) - (points.first?.at ?? 0) <= daySpanMillis
        if short { return L(day ? "widget_range_short_24h" : "widget_portfolio_range_short_48h") }
        return L(day ? "widget_range_24h" : "widget_portfolio_range_48h")
    }

    /// VoiceOver-Satz zum Wertverlauf (Zeitraum, Start, Ende, Veränderung, Hoch, Tief).
    static func chartAccessibility(_ points: [PortfolioWidgetPoint], currency: String,
                                   basis: ChangeBasis = .ROLLING_24H, hidden: Bool = false) -> String {
        A11y.chart(period: period(points, short: false, basis: basis), values: points.map(\.value),
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

    /// VoiceOver: «Grösste Positionen: BTC, 60.1% des Portfolios, gestiegen um 1.80% in 24 Stunden; …».
    static func positionsAccessibility(_ shown: [PortfolioWidgetPosition], basis: ChangeBasis = .ROLLING_24H) -> String? {
        guard !shown.isEmpty else { return nil }
        let parts = shown.map { p in
            L("a11y_portfolio_position_24h", p.symbol, share(p.sharePercent), A11y.change(p.change24hPercent, basis: basis))
        }
        return L("a11y_portfolio_positions", parts.joined(separator: "; "))
    }

    /// VoiceOver: «Portfolio 83’170.32 CHF, gesunken um 2.31% in 24 Stunden, Stand 15:19» — danach
    /// ≈ USDT, Verlauf und Positionen, soweit sichtbar. Veraltet: «veraltet, letzte Aktualisierung 06:42».
    static func accessibility(_ s: PortfolioWidgetSnapshot, outdated: Bool, at date: Date, usdt: String? = nil,
                              chart: String? = nil, positions: String? = nil, hidden: Bool = false) -> String {
        var change: String?
        let changeBasis = Self.basis(s)
        if let amount = changeAmount(s) {
            if let percent = s.changePercent, percent.isFinite {
                change = A11y.change(percent, basis: changeBasis)
            } else if PortfolioWidgetSeries.direction(amount) == 0 {
                change = A11y.changePhrase(L("a11y_change_flat"), basis: changeBasis)
            } else {
                let value = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(abs(amount), s.currency)
                change = A11y.changePhrase(L(amount > 0 ? "a11y_change_up" : "a11y_change_down", value), basis: changeBasis)
            }
        }
        let time = outdated ? WidgetOutdated.spoken(s.updatedAt, at: date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: date))
        let spokenTotal = hidden ? L("a11y_amount_hidden") : total(s)
        return A11y.join([L("widget_portfolio_name") + " " + spokenTotal, change, time,
                          usdt.map { L("a11y_converted", $0) }, chart, positions])
    }

    static var appURL: URL? { URL(string: "cryptochecker://portfolio") }
}
