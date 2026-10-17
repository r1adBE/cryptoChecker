import SwiftUI
import WidgetKit
/// Portfolio-Widget auf dem Startbildschirm (klein, mittel, gross).
/// Startbildschirm (Stufe = Familie, wie `PortfolioWidgetSize` in Android): klein = Kopfzeile,
/// Gesamtwert, Betrag und Prozent über den Zeitraum (Text, keine Pille), ≈ USDT, Fusszeile; mittel = Werte links, Chart rechts;
/// gross = dazu die drei grössten Positionen unter dem Chart. Anzeige, Zeitraum und Chart-Art je
/// Widget (`PortfolioWidgetIntent`, `PortfolioWidgetChart.plan`). Das ist die Ansicht «Chart»; die
/// Ansichten «Übersicht» und «Liste» stehen in `PortfolioWidgetOverview.swift` und `PortfolioWidgetList.swift`
/// (`overviewBody`, `listBody`).
struct PortfolioHomeView: View {
    let entry: PortfolioEntry
    let palette: WidgetPalette
    let family: WidgetFamily

    /// So viele Positionen zeigt die grosse Stufe.
    private static let largeRows = 3

    /// Gesperrtes Gerät (StandBy): Beträge sind `privacySensitive` und werden verdeckt.
    @Environment(\.redactionReasons) var redactionReasons

    /// VoiceOver ohne Beträge — bei «Beträge verbergen» und solange sie verdeckt sind.
    var spokenHidden: Bool { entry.hideAmounts || redactionReasons.contains(.privacy) }

    var body: some View {
        Group {
            if entry.state == .off {
                offBody
            } else if entry.locked {
                lockedBody
            } else if let s = entry.shown, !s.empty {
                let plan = entry.plan(for: s)
                switch entry.view {
                case .overview: overviewBody(s, plan: plan)
                case .list: listBody(s, plan: plan)
                case .chart: chartBody(s, plan: plan)
                }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    header()
                    Spacer(minLength: 4)
                    message(L("widget_portfolio_empty"))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(family == .systemSmall ? 14 : 16)
    }

    /// Ansicht «Chart» je Familie.
    @ViewBuilder
    private func chartBody(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        switch family {
        case .systemMedium: mediumBody(s, plan: plan)
        case .systemLarge: largeBody(s, plan: plan)
        default: smallBody(s, plan: plan)
        }
    }

    /// Logo und «Portfolio» — keine Pille; das Prozent steht als Text in der Betragszeile (`changeLine`),
    /// wie Android.
    func header(_ title: String = L("widget_portfolio_name")) -> some View {
        HStack(spacing: 6) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
            Text(title)
                .font(.system(size: 12.5, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
    }

    /// Portfolio-Sperre: normaler Rahmen mit Titel, Schloss und Hinweis — keine Beträge, kein
    /// Chart, keine Positionen. Tippen öffnet den Portfolio-Tab (`widgetURL`).
    private var lockedBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            header()
            Spacer(minLength: 4)
            Image(systemName: "lock.fill")
                .font(.system(size: family == .systemSmall ? 18 : 20, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .accessibilityHidden(true)
            message(L("widget_portfolio_locked_hint"))
                .padding(.top, 6)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([L("widget_portfolio_name"), L("widget_portfolio_locked_hint")]))
    }

    /// Portfolio aus: «Portfolio ist aus» statt «Portfolio» in der Kopfzeile und wo man es einschaltet —
    /// keine Beträge. Tippen öffnet Einstellungen › Erweitert › Portfolio (`widgetURL`, `PortfolioOff.widgetTarget`).
    private var offBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            header(L("widget_portfolio_off_title"))
            Spacer(minLength: 4)
            message(PortfolioWidgetText.offHint)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([L("widget_portfolio_off_title"), PortfolioWidgetText.offHint]))
    }

    func message(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Klein

    private func smallBody(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, wants: entry.wantsUsdt, hidden: entry.hideAmounts)
        return VStack(alignment: .leading, spacing: 0) {
            header()
            Spacer(minLength: 4)
            totalText(s, size: 22)
            changeLine(s, plan: plan)
            usdtLine(usdt, missing: PortfolioWidgetText.missing(s, plan: plan))
            Spacer(minLength: 4)
            footer(s, period: nil, hint: PortfolioWidgetText.fallbackHint(plan))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, plan: plan, outdated: entry.outdated, at: entry.date,
                                                              usdt: spokenHidden ? nil : usdt, hidden: spokenHidden))
    }

    // MARK: Mittel

    private func mediumBody(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, wants: entry.wantsUsdt, hidden: entry.hideAmounts)
        let basis = PortfolioWidgetText.basis(s)
        let chartText = PortfolioWidgetText.chartAccessibility(plan, currency: s.currency, basis: basis,
                                                               hidden: spokenHidden)
        return VStack(alignment: .leading, spacing: 0) {
            header()
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    totalText(s, size: 24)
                    changeLine(s, plan: plan)
                    usdtLine(usdt, missing: PortfolioWidgetText.missing(s, plan: plan))
                    Spacer(minLength: 4)
                    footer(s, period: plan.hasChart ? PortfolioWidgetText.period(plan, basis: basis, short: true) : nil,
                           hint: PortfolioWidgetText.fallbackHint(plan))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                chart(plan)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.top, 6)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, plan: plan, outdated: entry.outdated, at: entry.date,
                                                              usdt: spokenHidden ? nil : usdt, chart: chartText,
                                                              hidden: spokenHidden))
    }

    // MARK: Gross

    private func largeBody(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, wants: entry.wantsUsdt, hidden: entry.hideAmounts)
        let basis = PortfolioWidgetText.basis(s)
        let chartText = PortfolioWidgetText.chartAccessibility(plan, currency: s.currency, basis: basis,
                                                               hidden: spokenHidden)
        let shown = Array(s.topPositions.prefix(Self.largeRows))
        return VStack(alignment: .leading, spacing: 0) {
            header()
            totalText(s, size: 30)
                .padding(.top, 8)
            changeLine(s, plan: plan)
            usdtLine(usdt, missing: PortfolioWidgetText.missing(s, plan: plan))
            chart(plan)
                .frame(maxWidth: .infinity, minHeight: 44, maxHeight: .infinity)
                .padding(.vertical, 10)
            if !shown.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(shown, id: \.symbol) { position in
                        positionRow(position, currency: s.currency, basis: basis)
                    }
                }
                .padding(.bottom, 8)
            }
            footer(s, period: plan.hasChart ? PortfolioWidgetText.period(plan, basis: basis, short: true) : nil,
                   hint: PortfolioWidgetText.fallbackHint(plan))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(
            s, plan: plan, outdated: entry.outdated, at: entry.date, usdt: spokenHidden ? nil : usdt, chart: chartText,
            positions: PortfolioWidgetText.positionsAccessibility(shown, currency: s.currency, basis: basis,
                                                                  hidden: spokenHidden),
            hidden: spokenHidden))
    }

    /// Kürzel, Anteil als dünner Balken (neutral, nicht in der Akzentfarbe) und Text, MEIN Gewinn/Verlust
    /// auf der Position nach der %-Basis in der Kursfarbe nach dem Betrag (eng: nur Betrag; unbekannt «—»).
    /// Breite des Anteil-Balkens (0…100 % von `width`); eigene Funktion mit festen Typen (für den Type-Checker).
    private static func shareWidth(_ width: CGFloat, percent: Double) -> CGFloat {
        let clamped: Double = Swift.min(100, Swift.max(0, percent))
        return width * CGFloat(clamped / 100)
    }

    private func positionRow(_ p: PortfolioWidgetPosition, currency: String, basis: ChangeBasis) -> some View {
        HStack(spacing: 8) {
            quantityPrefixed(p, size: 12) {
                Text(p.symbol)
                    .font(.system(size: 12.5, weight: .bold))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(minWidth: 38, alignment: .leading)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.secondary.opacity(0.18))
                    Capsule().fill(palette.secondary.opacity(0.65))
                        .frame(width: Self.shareWidth(geo.size.width, percent: p.sharePercent))
                }
            }
            .frame(height: 3)
            Text(PortfolioWidgetText.share(p.sharePercent))
                .font(.system(size: 12, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .frame(minWidth: 40, alignment: .trailing)
            WidgetPnlLabel(change: PortfolioWidgetViews.rowPnl(p), currency: currency, palette: palette,
                           hidden: entry.hideAmounts)
                .frame(minWidth: 62, alignment: .trailing)
                .layoutPriority(1)
        }
    }

    // MARK: Bausteine

    func totalText(_ s: PortfolioWidgetSnapshot, size: CGFloat) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(entry.hideAmounts ? PortfolioInsights.hidden
                 : PriceFormat.valueWithCurrency(s.total, "").trimmingCharacters(in: .whitespaces))
                .font(.system(size: size, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
            Text(s.currency)
                .font(.system(size: size >= 26 ? 12 : 11, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .fixedSize()
        }
        // Gesperrtes Gerät (StandBy): Betrag verdeckt
        .privacySensitive()
    }

    /// «↘ −1’968.40 CHF  −2.31% · 24h» bzw. «· 7T» — Betrag und Prozent als Text in der Kursfarbe (keine
    /// Pille), Währung klein und grau, Zeitraum grau (nie rot/grün). Wird es eng: erst kleiner (12 → 11 pt),
    /// dann ohne Zeitraum, dann nur das Prozent (`PortfolioWidgetViews.changeLineCandidates`, wie Android).
    /// Ohne Wert nichts.
    @ViewBuilder
    func changeLine(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan) -> some View {
        if let amount = PortfolioWidgetText.amount(plan, currency: s.currency, hidden: entry.hideAmounts) {
            let direction = PortfolioWidgetText.directionValue(plan)
            let all = PortfolioWidgetViews.changeLineCandidates(
                amount: amount, percent: PortfolioWidgetChart.percentText(plan),
                period: PortfolioWidgetText.changePeriod(plan, s))
            // Gesperrtes Gerät (StandBy): nur das Prozent zeigen (wie früher die Pille); ohne Prozent verdeckt
            let percentOnly = all.filter { $0.amount == nil }
            let options = redactionReasons.contains(.privacy) && !percentOnly.isEmpty ? percentOnly : all
            ViewThatFits(in: .horizontal) {
                ForEach(options, id: \.self) { option in
                    ForEach(PortfolioWidgetViews.changeLineSizes, id: \.self) { size in
                        changeLabel(option, currency: s.currency, direction: direction, size: size)
                    }
                }
            }
            .privacySensitive(options.contains { $0.amount != nil })
            .padding(.top, 2)
        }
    }

    /// Pfeil, Betrag (Währung klein und grau) und Prozent in der Kursfarbe, Zeitraum grau.
    private func changeLabel(_ option: PortfolioChangeLine, currency: String, direction: Double?,
                             size: CGFloat) -> some View {
        HStack(spacing: 3) {
            ChangeArrowIcon(change: direction)
                .font(.system(size: size * 0.75, weight: .bold))
            changeText(option, currency: currency, size: size)
                .monospacedDigit()
                .lineLimit(1)
        }
        .foregroundStyle(palette.change(direction))
        .fixedSize()
    }

    private func changeText(_ option: PortfolioChangeLine, currency: String, size: CGFloat) -> Text {
        let font = Font.system(size: size, weight: .semibold)
        var text = Text(verbatim: "")
        if let amount = option.amount {
            text = text + Text.quoted(amount, quote: currency, size: size, quoteColor: palette.secondary)
        }
        if let percent = option.percent {
            text = text + Text(verbatim: (option.amount == nil ? "" : "  ") + percent).font(font)
        }
        if let period = option.period {
            text = text + Text(verbatim: " · " + period).font(font).foregroundStyle(palette.secondary)
        }
        return text
    }

    /// «≈ 92’310.00 USDT» (je Widget wählbar), kleiner und schwächer, dazu «ohne 1 Coin» (Coins ohne
    /// Kurs zählen im Gesamtwert als 0); wird es eng, bleibt nur der Hinweis. Beides nil = keine Zeile.
    @ViewBuilder
    func usdtLine(_ usdt: String?, missing: String?) -> some View {
        if let usdt, let missing {
            ViewThatFits(in: .horizontal) {
                secondLine("≈ " + usdt + " · " + missing)
                secondLine(missing)
            }
            .privacySensitive()
            .padding(.top, 1)
        } else if let usdt {
            secondLine("≈ " + usdt)
                .privacySensitive()
                .padding(.top, 1)
        } else if let missing {
            secondLine(missing)
                .padding(.top, 1)
        }
    }

    private func secondLine(_ text: String) -> some View {
        Text.quoted(text, quote: "USDT", size: 12, weight: .medium, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    /// Chart wie in der App oder, solange es zu wenige Werte gibt, ruhig «Verlauf folgt».
    @ViewBuilder
    func chart(_ plan: PortfolioChartPlan) -> some View {
        if plan.hasChart {
            let first = plan.type == .candles ? plan.bars[0].open : plan.bars[0].close
            let direction = PortfolioWidgetText.directionValue(plan)
                ?? ((plan.bars.last?.close ?? 0) >= first ? 1 : -1)
            PortfolioValueChart(plan: plan, palette: palette, lineColor: direction >= 0 ? palette.up : palette.down,
                                hidden: entry.hideAmounts || redactionReasons.contains(.privacy))
        } else {
            Text(L("widget_portfolio_chart_pending"))
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(palette.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// «Stand 15:19 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42»; gewählter Zeitraum ohne
    /// Tageskurse dazu «7T: Portfolio öffnen» (passt es nicht, ohne).
    func footer(_ s: PortfolioWidgetSnapshot, period: String?, hint: String?, extra: String? = nil) -> some View {
        let time = PortfolioWidgetText.asOf(s, outdated: entry.outdated, at: entry.date)
        let base = [time, period].compactMap { $0 }.joined(separator: " · ")
        return ViewThatFits(in: .horizontal) {
            if let hint { footerText(base + " · " + hint) }
            // Liste: «+ 3 weitere» hinter der Uhrzeit, wird es eng, ohne
            if let extra { footerText(base + " · " + extra) }
            footerText(base)
        }
    }

    func footerText(_ text: String) -> some View {
        Text(text)
            .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
    }
}
