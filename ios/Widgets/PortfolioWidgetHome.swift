import SwiftUI
import WidgetKit
/// Portfolio-Widget auf dem Startbildschirm (klein, mittel, gross).
/// Startbildschirm (Stufe = Familie, wie `PortfolioWidgetSize` in Android): klein = Kopfzeile mit
/// Pille, Gesamtwert, Betrag über 24 h, ≈ USDT, Fusszeile; mittel = Werte links, Wertverlauf rechts;
/// gross = dazu die drei grössten Positionen unter dem Wertverlauf.
struct PortfolioHomeView: View {
    let entry: PortfolioEntry
    let palette: WidgetPalette
    let family: WidgetFamily

    /// So viele Positionen zeigt die grosse Stufe.
    private static let largeRows = 3

    /// Gesperrtes Gerät (StandBy): Beträge sind `privacySensitive` und werden verdeckt.
    @Environment(\.redactionReasons) var redactionReasons

    /// VoiceOver ohne Beträge — bei «Beträge verbergen» und solange sie verdeckt sind.
    private var spokenHidden: Bool { entry.hideAmounts || redactionReasons.contains(.privacy) }

    var body: some View {
        Group {
            if entry.locked {
                lockedBody
            } else if let s = entry.snapshot, !s.empty {
                switch family {
                case .systemMedium: mediumBody(s)
                case .systemLarge: largeBody(s)
                default: smallBody(s)
                }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    header(nil)
                    Spacer(minLength: 4)
                    message(L("widget_portfolio_empty"))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(family == .systemSmall ? 14 : 16)
    }

    /// Logo, «Portfolio» und rechts die Pille «▼ 2.31%» (24 h). Wird es eng, fällt der Titel weg
    /// (lieber kein Titel als «Portf…»; VoiceOver nennt ihn trotzdem).
    private func header(_ s: PortfolioWidgetSnapshot?) -> some View {
        ViewThatFits(in: .horizontal) {
            headerRow(s, title: true)
            headerRow(s, title: false)
        }
    }

    private func headerRow(_ s: PortfolioWidgetSnapshot?, title: Bool) -> some View {
        HStack(spacing: 6) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
            if title {
                Text(L("widget_portfolio_name"))
                    .font(.system(size: 12.5, weight: .bold))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                    .fixedSize()
            }
            Spacer(minLength: 0)
            if let s { pill(s) }
        }
    }

    /// Portfolio-Sperre: normaler Rahmen mit Titel, Schloss und Hinweis — keine Beträge, kein
    /// Chart, keine Positionen. Tippen öffnet den Portfolio-Tab (`widgetURL`).
    private var lockedBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            header(nil)
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

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Klein

    private func smallBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display, hidden: entry.hideAmounts)
        return VStack(alignment: .leading, spacing: 0) {
            header(s)
            Spacer(minLength: 4)
            totalText(s, size: 22)
            changeLine(s)
            usdtLine(usdt)
            Spacer(minLength: 4)
            footer(s, period: nil)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, outdated: entry.outdated, at: entry.date,
                                                              usdt: spokenHidden ? nil : usdt, hidden: spokenHidden))
    }

    // MARK: Mittel

    private func mediumBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display, hidden: entry.hideAmounts)
        let points = PortfolioWidgetText.chartPoints(s)
        let chartText = points.map { PortfolioWidgetText.chartAccessibility($0, currency: s.currency,
                                                                                 basis: PortfolioWidgetText.basis(s),
                                                                                 hidden: spokenHidden) }
        return VStack(alignment: .leading, spacing: 0) {
            header(s)
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    totalText(s, size: 24)
                    changeLine(s)
                    usdtLine(usdt)
                    Spacer(minLength: 4)
                    footer(s, period: points.map { PortfolioWidgetText.period($0, short: true, basis: PortfolioWidgetText.basis(s)) })
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                chart(s, points: points)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.top, 6)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, outdated: entry.outdated, at: entry.date,
                                                              usdt: spokenHidden ? nil : usdt, chart: chartText,
                                                              hidden: spokenHidden))
    }

    // MARK: Gross

    private func largeBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display, hidden: entry.hideAmounts)
        let points = PortfolioWidgetText.chartPoints(s)
        let chartText = points.map { PortfolioWidgetText.chartAccessibility($0, currency: s.currency,
                                                                                 basis: PortfolioWidgetText.basis(s),
                                                                                 hidden: spokenHidden) }
        let shown = Array(s.topPositions.prefix(Self.largeRows))
        return VStack(alignment: .leading, spacing: 0) {
            header(s)
            totalText(s, size: 30)
                .padding(.top, 8)
            changeLine(s)
            usdtLine(usdt)
            chart(s, points: points)
                .frame(maxWidth: .infinity, minHeight: 44, maxHeight: .infinity)
                .padding(.vertical, 10)
            if !shown.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(shown, id: \.symbol) { position in
                        positionRow(position, basis: PortfolioWidgetText.basis(s))
                    }
                }
                .padding(.bottom, 8)
            }
            footer(s, period: points.map { PortfolioWidgetText.period($0, short: true, basis: PortfolioWidgetText.basis(s)) })
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(
            s, outdated: entry.outdated, at: entry.date, usdt: spokenHidden ? nil : usdt, chart: chartText,
            positions: PortfolioWidgetText.positionsAccessibility(shown, basis: PortfolioWidgetText.basis(s)),
            hidden: spokenHidden))
    }

    /// Kürzel, Anteil als dünner Balken (neutral, nicht in der Akzentfarbe) und Text,
    /// Veränderung über 24 h mit Pfeil und Vorzeichen in der Kursfarbe (ohne Wert «—»).
    private func positionRow(_ p: PortfolioWidgetPosition, basis: ChangeBasis) -> some View {
        HStack(spacing: 8) {
            Text(p.symbol)
                .font(.system(size: 12.5, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .frame(minWidth: 38, alignment: .leading)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.secondary.opacity(0.18))
                    Capsule().fill(palette.secondary.opacity(0.65))
                        .frame(width: geo.size.width * CGFloat(Swift.min(100, Swift.max(0, p.sharePercent)) / 100))
                }
            }
            .frame(height: 3)
            Text(PortfolioWidgetText.share(p.sharePercent))
                .font(.system(size: 11, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .frame(minWidth: 40, alignment: .trailing)
            WidgetChangeLabel(change: p.change24hPercent, palette: palette, size: 11, showsArrow: true, day: true,
                              basis: basis)
                .frame(minWidth: 62, alignment: .trailing)
        }
    }

    // MARK: Bausteine

    private func totalText(_ s: PortfolioWidgetSnapshot, size: CGFloat) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(entry.hideAmounts ? PortfolioInsights.hidden
                 : PriceFormat.valueWithCurrency(s.total, "").trimmingCharacters(in: .whitespaces))
                .font(.system(size: size, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
            Text(s.currency)
                .font(.system(size: size >= 26 ? 12 : 10, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .fixedSize()
        }
        // Gesperrtes Gerät (StandBy): Betrag verdeckt
        .privacySensitive()
    }

    /// Pille «▼ 2.31%» in der Kursfarbe (Pfeil nach dem Vorzeichen); ohne Wert keine.
    @ViewBuilder
    private func pill(_ s: PortfolioWidgetSnapshot) -> some View {
        if let percent = PortfolioWidgetText.pillPercent(s) {
            let direction = PortfolioWidgetText.directionValue(s)
            let color = palette.change(direction)
            HStack(spacing: 2) {
                ChangeArrowIcon(change: direction)
                    .font(.system(size: 8.5, weight: .bold))
                Text(percent)
                    .font(.system(size: 11, weight: .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background(Capsule().fill(color.opacity(palette.dark ? 0.14 : 0.07)))
            .fixedSize()
        }
    }

    /// «▼ −1’968.40 CHF · 24h» in der Kursfarbe; passt «· 24h» nicht, ohne. Ohne Wert nichts.
    @ViewBuilder
    func changeLine(_ s: PortfolioWidgetSnapshot) -> some View {
        if let full = PortfolioWidgetText.changeLine(s, hidden: entry.hideAmounts),
           let short = PortfolioWidgetText.changeLine(s, withPeriod: false, hidden: entry.hideAmounts) {
            let direction = PortfolioWidgetText.directionValue(s)
            ViewThatFits(in: .horizontal) {
                changeLabel(full, direction: direction)
                changeLabel(short, direction: direction)
            }
            // Betrag der Veränderung: bei gesperrtem Gerät verdeckt (die Pille mit % bleibt)
            .privacySensitive()
            .padding(.top, 2)
        }
    }

    private func changeLabel(_ text: String, direction: Double?) -> some View {
        HStack(spacing: 3) {
            ChangeArrowIcon(change: direction)
                .font(.system(size: 9, weight: .bold))
            Text(text)
                .font(.system(size: 12, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
        }
        .foregroundStyle(palette.change(direction))
        .fixedSize()
    }

    /// «≈ 92’310.00 USDT» (je Widget wählbar), kleiner und schwächer; nil = keine Zeile.
    @ViewBuilder
    private func usdtLine(_ usdt: String?) -> some View {
        if let usdt {
            Text("≈ " + usdt)
                .font(.system(size: 10.5, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .privacySensitive()
                .padding(.top, 1)
        }
    }

    /// Wertverlauf oder, solange es zu wenige Stundenwerte gibt, ruhig «Verlauf folgt».
    @ViewBuilder
    func chart(_ s: PortfolioWidgetSnapshot, points: [PortfolioWidgetPoint]?) -> some View {
        if let points {
            let direction = PortfolioWidgetText.directionValue(s)
                ?? ((points.last?.value ?? 0) >= (points.first?.value ?? 0) ? 1 : -1)
            PortfolioValueChart(points: points, color: palette.change(direction), baseline: palette.secondary,
                                axis: PortfolioWidgetText.dayAxis(s))
                // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
                .environment(\.layoutDirection, .leftToRight)
        } else {
            Text(L("widget_portfolio_chart_pending"))
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(palette.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// «Stand 15:19 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42».
    private func footer(_ s: PortfolioWidgetSnapshot, period: String?) -> some View {
        let time = PortfolioWidgetText.asOf(s, outdated: entry.outdated, at: entry.date)
        return Text([time, period].compactMap { $0 }.joined(separator: " · "))
            .font(.system(size: 10, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
    }
}
