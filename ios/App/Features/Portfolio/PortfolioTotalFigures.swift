import SwiftUI

// Bausteine der Gesamtkarte: Kopf «Gewinn/Verlust» mit Methode, Änderungszeile, Kennzahlen, Hinweis auf
// Staking-Erträge und die Anzeigewährung der Karte (`PortfolioCardUnit`).
// Eigene Datei, weil `PortfolioTotalCard` nur die Anordnung bestimmt (wie `PortfolioTotalFigures.kt`).

/// Kleine Überschrift «Gewinn/Verlust» über Heute · 7 T · Seit Kauf, rechts ein (i): erklärt in wenigen
/// Sätzen, wie die Zahlen gerechnet sind (Käufe/Verkäufe im Zeitraum herausgerechnet, Einstand nach der gewählten
/// Methode FIFO oder Ø, Gebühren, Bewertung mit Binance-Kursen, USDT zum Marktkurs). Wie `GainHeader` (Android).
struct PortfolioGainHeader: View {
    let method: CostMethod
    @State private var showInfo = false

    var body: some View {
        HStack(spacing: 4) {
            Text(L("ledger_gain_title"))
                .font(.caption.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            Button {
                showInfo = true
            } label: {
                Image(systemName: "info.circle")
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    // Trefferfläche 44 pt, im Layout weiter 28 pt (negativer Rand)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(-8)
            .accessibilityLabel(L("ledger_gain_info_a11y"))
            Spacer(minLength: 0)
        }
        .alert(L("ledger_gain_title"), isPresented: $showInfo) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L(method.isFifo ? "ledger_gain_info_fifo" : "ledger_gain_info"))
        }
    }
}

/// Zeile unter dem Gesamtwert wie im Portfolio-Widget: «↘ −38’412.95 USDT −27.85% · 1J» — Pfeil,
/// Betrag (verborgen «•••») und Prozent als farbige Schrift ohne Pille in der Kursfarbe (Richtung
/// nach dem Betrag, ohne Betrag nach dem Prozent; Null-Regel des Portfolios), die Währung klein und
/// grau, der Zeitraum `period` klein und grau dahinter. Für «Heute» und «1 J» (Details).
/// VoiceOver: ein Satz («1 Jahr: gefallen um 27.85%, −38’412.95 USDT»). Wie `TotalChangeLine` (Android).
struct PortfolioTotalChangeLine: View {
    /// Betrag in USDT; nil = nur Prozent.
    let amount: Double?
    let percent: Double?
    let unit: PortfolioCardUnit
    let period: String
    let spokenLabel: String
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    private var validPercent: Double? { percent.flatMap { $0.isFinite ? $0 : nil } }
    private var shownAmount: Double? { amount.flatMap { $0.isFinite ? unit.of($0) : nil } }

    private var spoken: String {
        let amountSpoken: String? = shownAmount.map { portfolioSpokenAmount(unit.signed($0), hidden: hideAmounts) }
        return L("label_value", spokenLabel, A11y.join([A11y.change(validPercent), amountSpoken]))
    }

    var body: some View {
        let tone = PortfolioFormat.plTone(shownAmount ?? validPercent)
        let color: Color = tone.content(priceColors, highContrast: highContrast, inverted: inverted)
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            if let arrow = tone.arrowSymbol {
                Image(systemName: arrow)
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
            }
            label
        }
        .foregroundStyle(color)
        .lineLimit(2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    /// Betrag (Währung klein und grau), Prozent und grau « · Zeitraum» als ein Text.
    private var label: Text {
        let amountText: Text = shownAmount.map {
            PriceWithQuote.text(PortfolioInsights.mask(unit.signed($0), hidden: hideAmounts))
        } ?? Text(verbatim: "")
        let gap: String = shownAmount == nil ? "" : "  "
        let percentText = Text(verbatim: validPercent.map { gap + PortfolioFormat.signedPercent($0) } ?? "")
        let periodText = Text(verbatim: " · " + period)
            .font(.caption.weight(.regular))
            .foregroundStyle(AppColors.onSurfaceVariant)
        let combined: Text = amountText + percentText + periodText
        return combined.font(AppFont.amount(.subheadline, weight: .semibold))
    }
}

/// Kleine Kennzahl im Kopf: Beschriftung, Betrag mit Vorzeichen (USDT ohne Einheit, verborgen
/// «•••», neutrale Schrift wie der Kurs einer Zeile) und darunter die Prozent-Änderung «↗ +1.23%»
/// (`PortfolioPlPill`, farbige Schrift in der Kursfarbe); ohne Wert «—». VoiceOver: ein Satz («7 Tage: gestiegen um 2.10%,
/// +123.45 USDT»; verborgen «Betrag verborgen»). Wie `InsightFigure` (Android).
struct PortfolioInsightFigure: View {
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    let label: String
    let spokenLabel: String
    let amount: Double?
    let percent: Double?
    /// Einheit der Karte; `amount` und `rewards` kommen in USDT.
    let unit: PortfolioCardUnit
    /// Belohnungen im Zeitraum (`PortfolioChange.rewards`); > 0: «Belohnungen +X (nicht im Gewinn)» darunter.
    var rewards: Double = 0
    /// `amount` schon in der Einheit der Karte (`PortfolioModel.costFx`), nicht in USDT.
    var amountInUnit = false

    /// Belohnungen nur zu einer bekannten Zahl.
    private var shownRewards: Double { validAmount == nil ? 0 : rewards }

    private var validAmount: Double? { amount.flatMap { $0.isFinite ? (amountInUnit ? $0 : unit.of($0)) : nil } }
    private var validPercent: Double? { percent.flatMap { $0.isFinite ? $0 : nil } }

    private var amountText: String {
        guard let value = validAmount else { return "—" }
        let plain = unit.signed(value)
        let suffix = " " + unit.code
        return PortfolioInsights.mask(plain.hasSuffix(suffix) ? String(plain.dropLast(suffix.count)) : plain,
                                      hidden: hideAmounts)
    }

    private var spoken: String {
        let value = validAmount.map { portfolioSpokenAmount(unit.signed($0), hidden: hideAmounts) }
        let change = A11y.change(validPercent)
        if value == nil && change == nil { return L("portfolio_card_figure_none_a11y", spokenLabel) }
        let figure = L("label_value", spokenLabel, A11y.join([change, value]))
        guard let rewardsSpoken = PortfolioRewardsNote.text(shownRewards, unit: unit, hidden: hideAmounts, spoken: true) else { return figure }
        return figure + ". " + rewardsSpoken
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
            Text(amountText)
                .font(AppFont.amount(.subheadline, weight: .semibold))
                .foregroundStyle(AppColors.onSurface)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
                .contentTransition(.numericText())
            PortfolioPlPill(percent: validPercent)
                .padding(.top, 2)
            PortfolioRewardsNote(rewards: shownRewards, unit: unit)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }
}

/// «Belohnungen +12.34 (nicht im Gewinn)» unter einer Zeitraum-Zahl: Belohnungen zählen als Zufluss, nicht als Gewinn
/// (`PortfolioPeriodChange`). Betrag ohne Vorzeichen und Einheit (das «+» steht im Text), verborgen «•••»; nichts bei 0.
/// Wie `RewardsNote` (Android).
struct PortfolioRewardsNote: View {
    /// Belohnungen in USDT.
    let rewards: Double
    let unit: PortfolioCardUnit
    @Environment(\.hidePortfolioAmounts) private var hideAmounts

    /// Text der Zeile; `spoken` mit Einheit bzw. «Betrag verborgen». nil = keine Belohnungen.
    static func text(_ rewards: Double, unit: PortfolioCardUnit, hidden: Bool, spoken: Bool = false) -> String? {
        guard rewards.isFinite, rewards > 0, !PortfolioFormat.isZero(rewards) else { return nil }
        let plain = PriceFormat.valueWithCurrency(unit.of(rewards), unit.code)
        let amount: String
        if spoken {
            amount = portfolioSpokenAmount(plain, hidden: hidden)
        } else {
            let suffix = " " + unit.code
            amount = PortfolioInsights.mask(plain.hasSuffix(suffix) ? String(plain.dropLast(suffix.count)) : plain,
                                            hidden: hidden)
        }
        return L("portfolio_period_rewards", amount)
    }

    var body: some View {
        if let text = Self.text(rewards, unit: unit, hidden: hideAmounts) {
            Text(text)
                .font(AppFont.amount(.caption))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 2)
        }
    }
}

/// Einheit der Gesamtkarte: die Anzeigewährung mit dem Faktor USDT → Währung (Devisenkurs × USDT/USD, `displayRate`),
/// sonst USDT mit Kurs 1. Beträge kommen in USDT und werden mit `of` umgerechnet. Wie `CardUnit` (Android).
struct PortfolioCardUnit {
    let code: String
    private let rate: Double

    init(currency: String, fxRate: Double?) {
        // Auch USD: USDT gilt dort seinen Marktkurs USDT/USD (Faktor = 1 × USDT/USD, ohne Messung 1 mit
        // «USDT = 1 angenommen»), nie still 1:1. Wie Android `cardUnit`.
        if let fx = fxRate, currency != PortfolioFormat.usdt, fx > 0, fx.isFinite {
            code = currency
            rate = fx
        } else {
            code = PortfolioFormat.usdt
            rate = 1
        }
    }

    func of(_ usdt: Double) -> Double { usdt * rate }
    /// Gross umgerechnet (dann «≈ … USDT» darunter).
    var converted: Bool { code != PortfolioFormat.usdt }
    /// «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF».
    func signed(_ value: Double) -> String {
        let plain = PortfolioFormat.signedUsdt(value)
        let suffix = " " + PortfolioFormat.usdt
        return (plain.hasSuffix(suffix) ? String(plain.dropLast(suffix.count)) : plain) + " " + code
    }
}
