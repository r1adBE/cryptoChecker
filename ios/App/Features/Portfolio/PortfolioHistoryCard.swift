import SwiftUI

extension PortfolioHistoryRange {
    /// Kurzname auf dem Chip («30 T», «Seit 1. Kauf»).
    var shortLabel: String {
        switch self {
        case .week: return L("portfolio_history_range_7d")
        case .month: return L("portfolio_history_range_30d")
        case .year: return L("portfolio_history_range_1y")
        case .sinceFirst: return L("portfolio_history_range_since_first")
        }
    }

    /// Ausgeschrieben («30 Tage», «seit dem ersten Kauf») für VoiceOver und den Chart-Satz.
    var longLabel: String {
        switch self {
        case .week: return L("portfolio_history_period_7d")
        case .month: return L("portfolio_history_period_30d")
        case .year: return L("portfolio_history_period_1y")
        case .sinceFirst: return L("portfolio_history_period_since_first")
        }
    }

    /// Text auf dem Chip: «Seit 1. Kauf» als «Seit Kauf» (nicht mehr ∞) — wie Android.
    var chipLabel: String {
        self == .sinceFirst ? L("portfolio_history_chip_since_first") : shortLabel
    }

    /// VoiceOver des Chips («Verlauf für 30 Tage zeigen» / beim Chip «Seit Kauf» ausgeschrieben «Seit 1. Kauf»).
    var chipAccessibility: String {
        self == .sinceFirst
            ? L("portfolio_history_range_since_first")
            : L("portfolio_history_range_a11y", longLabel)
    }
}

/// Wertverlauf über den Positionen — wie `PortfolioHistoryCard.kt`, standardmässig zugeklappt:
/// eine Zeile «Wertverlauf · 30 T [+4.20%]» (Änderung über den gewählten Zeitraum als farbige
/// Schrift; Platzhalter, solange geladen wird). Tippen klappt auf: Änderung als Betrag und Prozent,
/// Linie mit Fläche, UNTER dem Chart die Zeitraum-Chips (7 T / 30 T / 1 J / Seit Kauf) und
/// Hinweise (umgerechnet, Coins ohne Tageskurse, Käufe/Verkäufe im Zeitraum, Kursdaten erst ab
/// 2017). `history` nil = lädt. Bei einer Umrechnungswährung mit Devisen-Tageskursen zusätzlich
/// der Umschalter «CHF | USDT | Vergleich» (`view`); «Vergleich» zeigt beide Kurven in Prozent und
/// den Währungseffekt. Ohne Bewegung gezeichnet. Wie im Aktionsblatt eines Paars rechts neben den Chips
/// (unter dem Chart) der Schalter Kerzen | Linie (`candles`); im «Vergleich» ausgeblendet (dort nur Linien).
/// Über dem Chart die Chips «Wert | Gewinn/Verlust» (`pnl`): «Gewinn/Verlust» zeigt MEIN Gewinn/Verlust je Tag
/// (`PortfolioHistorySeries.pnlPoints`, Käufe/Verkäufe verschieben ihn nicht) mit Nulllinie; im «Vergleich»
/// ausgeblendet. Die Kopfzeile zeigt immer beides: «Wert 93’818.00 CHF · Gewinn +2’340.00 CHF (+2.40%)».
struct PortfolioHistoryCard: View {
    let history: PortfolioHistoryUi?
    @Binding var range: PortfolioHistoryRange
    @Binding var expanded: Bool
    /// Gespeicherte Darstellung (`AppSettings.portfolioHistoryView`); gilt nur, wenn sie möglich ist.
    @Binding var view: PortfolioHistoryView
    /// Kerzen statt Linie (`AppSettings.portfolioHistoryCandles`).
    @Binding var candles: Bool
    /// «Gewinn/Verlust» statt «Wert» (`AppSettings.portfolioHistoryPnl`).
    @Binding var pnl: Bool

    @Environment(\.priceColorScheme) var priceColors
    @Environment(\.priceColorsInverted) var inverted
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.appAccent) var accent
    /// «Beträge verbergen»: Beträge als «•••», Prozente bleiben.
    @Environment(\.hidePortfolioAmounts) var hideAmounts

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if expanded {
                // «CHF | USDT | Vergleich» nur bei einer Umrechnungswährung mit Tageskursen
                if let history, history.usdtSeries != nil {
                    viewSwitch(history)
                        .padding(.top, Spacing.sm)
                }
                // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
                if let shown {
                    content(shown)
                } else {
                    skeleton
                    controls
                }
            }
        }
        .appCard(padding: .list)
    }

    // MARK: Kopfzeile

    /// Darstellung, die für diesen Verlauf gilt: USDT und Vergleich nur mit Devisen-Tageskursen
    /// (Vergleich zusätzlich mit Ausgangswert), sonst in der Währung wie bisher.
    var shownView: PortfolioHistoryView {
        guard let history else { return .currency }
        return PortfolioCompare.effectiveView(view, usdtAvailable: history.usdtSeries != nil,
                                              compareAvailable: history.compare != nil)
    }

    /// Angezeigter Verlauf (in USDT der unumgerechnete).
    var shown: PortfolioHistoryUi? { history?.forView(shownView) }

    /// Ein Verlauf eines anderen Zeitraums (gerade gewechselt) zählt wie «lädt».
    var current: PortfolioHistoryUi? {
        guard let shown, shown.range == range else { return nil }
        return shown
    }

    // MARK: Inhalt

    @ViewBuilder
    private func content(_ history: PortfolioHistoryUi) -> some View {
        let series = history.series
        let period = history.range.longLabel
        // «Gewinn/Verlust» nur ausserhalb des Vergleichs (dort Prozent beider Währungen)
        let compare = shownView == .compare ? history.compare : nil
        let showPnl = pnl && compare == nil
        if series.hasChart {
            // Wert und Gewinn über den Zeitraum — immer beides
            valueGainLine(history)
                .padding(.top, 12)

            if let compare {
                PortfolioHistoryCompareSection(compare: compare, currency: history.unit, period: period)
            } else {
                modeSwitch
                    .padding(.top, Spacing.sm)
                if !showPnl {
                    let color = PortfolioFormat.plColor(series.change ?? 0, scheme: priceColors, highContrast: highContrast,
                                                        inverted: inverted)
                    singleChart(history, color: color, period: period, pnl: false)
                } else if series.hasPnlChart {
                    let color = PortfolioFormat.plColor(series.pnlChange ?? 0, scheme: priceColors, highContrast: highContrast,
                                                        inverted: inverted)
                    singleChart(history, color: color, period: period, pnl: true)
                } else {
                    // Alle Coins fehlen im Gewinn/Verlust (Zufluss ohne Preis): das ehrlich sagen — wie Android
                    PortfolioHint(text: L(series.pnlSkipped.isEmpty ? "portfolio_history_unavailable"
                                                                     : "portfolio_history_pnl_needs_prices"))
                        .padding(.top, Spacing.sm)
                }
            }
        } else {
            PortfolioHint(text: L(series.points.isEmpty ? "portfolio_history_unavailable" : "portfolio_history_too_short"))
                .padding(.top, Spacing.sm)
        }
        // Zeitraum und Kerzen/Linie direkt unter dem Chart
        controls

        // Nur wenn die Devisen-Tageskurse fehlten und alles mit dem heutigen Kurs umgerechnet ist
        if series.hasChart && history.converted && history.approximateFx {
            caption(L("portfolio_history_converted"))
        }
        // Mit Tageskursen umgerechnet: Kurve enthält die Devisenbewegung; USDT gilt dabei als USD (je einmal, klein)
        if series.hasChart && history.converted
            && PortfolioHistoryFx.includesFxMovement(history.unit, approximate: history.approximateFx) {
            caption(L("portfolio_history_incl_fx"))
        }
        // USDT zum USD-Marktkurs (Tagesschluss USDT/USD); an Tagen ohne Kurs 1 als Näherung — dann «USDT = 1 angenommen»
        if series.hasChart && history.converted && PortfolioHistoryFx.convertsUsdtAsUsd(history.unit) {
            caption(L("portfolio_usdt_as_usd"))
            if history.usdtApproximate { caption(L("portfolio_usdt_assumed")) }
        }
        if !series.skipped.isEmpty {
            // Grund: keine oder nicht passende Tageskurse (anderer Token, kein aktueller Kurs)
            caption(L("portfolio_history_without_matching", series.skipped.joined(separator: ", ")))
        }
        if series.hasChart && !history.scaled.isEmpty {
            // Keine eigenen Tageskerzen der Börse: Binance-Verlauf auf den Börsenkurs skaliert
            caption(L("portfolio_history_scaled", history.scaled.joined(separator: ", ")))
        }
        // Die Wertkurve springt bei Käufen/Verkäufen (der Gewinn/Verlust nicht)
        if series.hasChart && series.tradesInRange && !showPnl && compare == nil {
            caption(L("portfolio_history_includes_trades"))
        }
        // Gewinn/Verlust enthält die Wertänderung ausgebuchter Coins bis zur Auszahlung (Karte «Seit Kauf» nicht)
        if series.hasChart && series.withdrawalsInRange {
            caption(L("portfolio_history_incl_withdrawn"))
        }
        // Coins, die im Gewinn/Verlust fehlen (Zufluss ohne Preis, Tag ohne Kurs) — ehrlich nennen
        // (nicht doppelt, wenn der Gewinn/Verlust-Modus schon deswegen leer ist)
        if series.hasChart && !series.pnlSkipped.isEmpty && !(showPnl && !series.hasPnlChart) {
            caption(L("portfolio_history_pnl_without", series.pnlSkipped.joined(separator: ", ")))
        }
        if series.capped {
            caption(L("portfolio_history_capped_data_start"))
        }
        // Tage, Heute / 7 T / 1 J und Kerzen laufen in UTC (Steuerdaten in Ortszeit) — einmal, klein (wie Android)
        if series.hasChart {
            caption(L("portfolio_history_utc_days"))
        }
    }

    /// Linie oder Kerzen (`candles`) in `unit` mit Achse darunter (Währung oder USDT); `pnl`: Gewinn/Verlust je
    /// Tag (`pnlPoints`) mit Nulllinie und Vorzeichen.
    @ViewBuilder
    private func singleChart(_ history: PortfolioHistoryUi, color: Color, period: String, pnl: Bool) -> some View {
        let series = history.series
        let points = pnl ? series.pnlPoints : series.points
        let values = points.map(\.value)
        PortfolioHistoryChart(points: points, unit: history.unit, color: color, candles: candles,
                              hidden: hideAmounts, pnl: pnl)
            .frame(height: Self.chartHeight)
            .padding(.top, Spacing.sm)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(A11y.chart(period: period, values: values,
                                           format: { hideAmounts ? L("a11y_amount_hidden")
                                               : (pnl ? Self.signedValue($0, unit: history.unit) : PriceFormat.valueWithCurrency($0, history.unit)) }))

        // Beginn und Ende der Achse (für VoiceOver im Chart-Satz enthalten)
        HStack {
            if let first = points.first {
                Text(PortfolioFormat.date(LocalDay(epochDay: first.epochDay).date.millis))
            }
            Spacer(minLength: 8)
            Text(L("portfolio_history_today"))
        }
        .font(.caption2.monospacedDigit())
        .foregroundStyle(AppColors.onSurfaceVariant)
        .padding(.top, 4)
        // Wie der Chart darüber: Beginn links, «heute» rechts — auch bei Rechts-nach-links-Sprachen
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityHidden(true)
    }

    func caption(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, Spacing.xs)
    }

    /// Platzhalter in der Form von Änderung und Chart.
    private var skeleton: some View {
        SkeletonPulse(label: L("portfolio_history_loading")) {
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: " ")
                    .font(.headline)
                    .cycleSkeletonBar(width: 150)
                    .padding(.top, 12)
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(AppColors.containerHighest)
                    .frame(height: Self.chartHeight)
                    .padding(.top, Spacing.sm)
                Text(verbatim: " ")
                    .font(.caption)
                    .cycleSkeletonBar(width: 80)
                    .padding(.top, 4)
            }
        }
    }

    /// Höhe der Chart-Fläche (wie im Aktionsblatt eines Paars).
    static let chartHeight: CGFloat = 180

    // MARK: Texte

    /// Gewinn der Kopfzeile: «+2’340.00 CHF (+2.40%)»; ohne Prozent nur der Betrag (verborgen «•••»); ohne
    /// Gewinn/Verlust «—».
    static func gainText(_ series: PortfolioHistorySeries, unit: String, hidden: Bool = false) -> String {
        guard let change = series.pnlChange else { return "—" }
        let amount = PortfolioInsights.mask(signedValue(change, unit: unit), hidden: hidden)
        guard let percent = series.pnlPercent else { return amount }
        return "\(amount) (\(PortfolioFormat.signedPercent(percent)))"
    }

    /// «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF».
    static func signedValue(_ value: Double, unit: String) -> String {
        let sign = PortfolioFormat.isZero(value) ? "" : (value > 0 ? "+" : "−")
        return sign + PriceFormat.valueWithCurrency(abs(value), unit)
    }

    /// «Wert 93’818.00 CHF. Gewinn, 30 Tage: gestiegen um 2’340.00 CHF, gestiegen um 2.40%»; ohne Gewinn/Verlust
    /// «Gewinn: noch nicht verfügbar» — wie Android `gainSentence`.
    static func spokenGain(_ series: PortfolioHistorySeries, range: PortfolioHistoryRange, unit: String,
                           hidden: Bool = false) -> String {
        let value = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(series.points.last?.value ?? 0, unit)
        guard let change = series.pnlChange else {
            return L("portfolio_value") + " " + value + ". "
                + L("portfolio_card_figure_none_a11y", L("portfolio_history_gain_label"))
        }
        let amount: String
        if PortfolioFormat.isZero(change) {
            amount = L("a11y_change_flat")
        } else {
            let text = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(abs(change), unit)
            amount = L(change > 0 ? "a11y_change_up" : "a11y_change_down", text)
        }
        let gain = A11y.join([amount, series.pnlPercent.flatMap { A11y.change($0) }])
        return L("portfolio_history_gain_a11y", value, range.longLabel, gain)
    }
}
