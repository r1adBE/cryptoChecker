import SwiftUI

/// Kompakter Kopf wie in Portfolio-Trackern (Build 46 entlastet): Gesamtwert und die Umrechnung, darunter
/// EINE Veränderung «Heute» (`PortfolioTotalChangeLine`, %-Basis der App), dann zwei Kennzahlen
/// nebeneinander — «7 T» (Gewinn seit dem Tagesschluss vor 7 Tagen, `PortfolioPeriodChange.gain`) und
/// «Seit Kauf» (± unrealisiert seit Kauf). Hinter «Details» (zugeklappt): 1 J, Belohnungen, Einstand,
/// Realisiert, Ausgebucht. L-15: Hinweise (Kursquellen, Durchschnittskosten, fehlende Coins, Börsen,
/// fehlende Kurse) und Stand der Kurse stehen UNTER der Karte, zugeklappt als eine Zeile «Hinweise (n)»
/// (`hints`); weitere Hinweiszeilen gehören dorthin. Beträge in der
/// Anzeigewährung (ohne Devisenkurs in USDT; verborgen «•••»), Prozente bleiben; unbekannt «—». Wie `TotalCard` (Android).
struct PortfolioTotalCard: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    let summary: PortfolioSummary
    let updatedAt: Int64
    let currency: String
    let fxRate: Double?
    /// «Seit Kauf» und Einstand in der Anzeigewährung zum Kurs der Erwerbstage (`PortfolioModel.costFx`); gilt nur, wenn
    /// die Karte umrechnet — nil dann = Kurse laden noch («—»). In USDT bleiben die Zahlen aus `summary`.
    var costFx: PortfolioCostFxFigures? = nil
    /// «Heute»: Betrag (USDT) und Prozent; nil = noch keine Vergleichsbasis.
    var today: (amount: Double?, percent: Double?)? = nil
    /// Coins, die in «Heute» fehlen (kein Vergleichskurs).
    var todaySkipped: [String] = []
    /// «7 T» in USDT; nil = unbekannt.
    var weekChange: PortfolioChange? = nil
    /// «1 J» für die Zeile unter dem Gesamtwert; nil = keine Zeile.
    var yearChange: PortfolioChange? = nil
    /// Kurse älter als die Frische-Schwelle (gemerkter Stand, ohne Netz): Gesamtwert in onSurfaceVariant
    /// wie ein veralteter Kurs der Merkliste (nicht mehr abgeblasst, L-13), «Kurse von … · veraltet» in Warnfarbe.
    var stale = false
    /// Coin → Börse, deren Kurs gilt (`PortfolioPrices.sources`); leer = nur Binance.
    var priceSources: [String: String] = [:]
    /// Coin → eingetragene Börse ohne Kurs (dann Binance).
    var exchangeFailed: [String: String] = [:]
    /// Coins mit mehreren eingetragenen Börsen (Binance).
    var mixedExchange: Set<String> = []
    /// Coins, deren «7 T»/«1 J» Binance auf den Börsenkurs skaliert nutzt (`PortfolioModel.periodScaled`).
    var periodScaled: [String] = []
    /// FIFO je Wallet: Abgänge über den Bestand ihrer Wallet (`PortfolioCalculator.walletShortfalls`).
    var walletShortfalls: [PortfolioWalletShortfall] = []
    /// Hinweis «Bestand in … reicht nicht» angetippt: Transaktion mit dieser Id öffnen.
    var onShortfall: (Int64) -> Void = { _ in }
    /// Letzte Messung USDT/USD und Schwelle (Zehntel-%): Zeile «USDT = 1 angenommen» über `DepegAlarm.cardNote`.
    var depegReading: DepegAlarm.Reading? = nil
    var depegThresholdTenths: Int = DepegAlarm.defaultThresholdTenths
    /// Uhr für die Frische der Messung (Ansicht tickt mit ihrer Uhr).
    var now: Int64 = TimeUtils.nowMillis
    @Environment(\.appAccent) var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.hidePortfolioAmounts) var hideAmounts
    /// Build 46: Details und Hinweise aufklappbar — wie Android `rememberSaveable`.
    @State private var showDetails = false
    @State var showHints = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            card
            // L-15: Hinweise klein unter der Karte (bleiben sichtbar, die Karte bleibt ein ruhiger Bericht)
            hints
        }
        // Weniger Bewegung: Ziffern wechseln ohne Rollen
        .animation(reduceMotion ? nil : .snappy, value: summary.totalValue)
    }

    /// Verlauf der Karte (L-15): ruhig, 8 % → 2 % der Akzentfarbe über `container` (früher 16 % → 4 %
    /// ohne Grund) — wie Android (surfaceContainer + `accentTint`), hell und dunkel gleich.
    static let tintStart = 0.08
    static let tintEnd = 0.02

    /// Einheit der Karte: Anzeigewährung mit Devisenkurs, sonst USDT.
    var unit: PortfolioCardUnit { PortfolioCardUnit(currency: currency, fxRate: fxRate) }

    /// Woher die Kurse stammen: «Kurse: Binance · USDT zum Marktkurs» bzw. «Kurse: Kraken (BTC, ETH), sonst Binance · …».
    var priceSourceText: String {
        priceSources.isEmpty ? L("portfolio_price_source")
            : L("portfolio_price_source_routes", PortfolioExchangeRoute.sourcesText(priceSources))
    }

    /// USDT zum Marktkurs (Owner-Entscheid Okt. 2026): ohne frische Messung trägt Ersatz 1 die Zahlen — dann
    /// «USDT = 1 angenommen» auf der Karte (unabhängig vom Depeg-Alarm); mit Messung keine Zeile. Wie Android.
    var usdtNote: DepegAlarm.CardNote? {
        DepegAlarm.cardNote(
            holdingCoins: summary.open.map(\.coin),
            valuesViaUsdt: PortfolioExchangeRoute.usdtParityShown(displayCurrency: currency, converted: unit.converted,
                                                                  sources: priceSources),
            reading: depegReading, thresholdTenths: depegThresholdTenths, now: now, usdtMarket: true)
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("portfolio_total_value"))
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)
            // Gross in der Anzeigewährung (wie das Widget), USDT klein darunter; ohne Devisenkurs gross USDT
            totalValue
                .padding(.top, 2)
            // Teilsumme: Coins ohne Kurs zählen als 0 — direkt an der Zahl «ohne 2 Coins» (antippen: Hinweise)
            if !summary.missingCurrentPrices.isEmpty {
                let count: Int = summary.missingCurrentPrices.count
                partialMarker(L("widget_portfolio_missing_coins", count: count))
                    .padding(.bottom, 2)
            }
            // «≈ 117’345.12 USDT» — nur wenn gross umgerechnet steht (Faktor da; auch USD, USDT zum Marktkurs)
            if unit.converted {
                PriceWithQuote.text("≈ " + PortfolioInsights.mask(PortfolioFormat.usdtValue(summary.totalValue), hidden: hideAmounts))
                    .font(AppFont.amount(.subheadline))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .contentTransition(.numericText())
            }
            // «USDT = 1 angenommen» auf der Karte selbst, sobald der Ersatz 1 die Zahlen trägt (`DepegAlarm.cardNote`
            // mit USDT zum Marktkurs: nur noch USDT_ASSUMED); die Kursquelle steht unter «Hinweise»
            if let note = usdtNote {
                switch note.kind {
                case .DEVIATION:
                    // Mit usdtMarket nicht mehr geliefert (Abweichung = Bewertung zum Marktkurs, keine Warnzeile)
                    EmptyView()
                case .PRICE_SOURCE:
                    Text(verbatim: priceSourceText)
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                case .USDT_ASSUMED:
                    Text(L("portfolio_usdt_assumed"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                case .USDT_BREAK:
                    // Bestätigter Einbruch: Werte rechnen mit dem Marktkurs — deutlich in Warnfarbe statt «angenommen»
                    Text(L("portfolio_usdt_break", LocaleNumbers.decimal(note.reading?.price ?? 0, maxDecimals: 4)))
                        .font(.footnote)
                        .foregroundStyle(AppColors.error)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                case .VIA_USDC:
                    // Kurs nur aus USDC/USDT: Herkunft nennen und warnen — ein Einbruch von USDC verschiebt sonst still alle Werte
                    Text(L("portfolio_usdt_via_usdc"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                }
            }
            // Build 46: eine Veränderung direkt unter dem Gesamtwert — «↗ +412.95 CHF +1.85% · Heute»
            // (Prozent ohne Betrag, z. B. ohne Devisenkurs, bleibt sichtbar); ohne Daten keine Zeile
            if let line = todayLine {
                line.padding(.top, 2)
            }

            // M4: Überschrift «Gewinn/Verlust» mit (i) — was die Zahlen bedeuten (und was nicht)
            PortfolioGainHeader(method: summary.method)
                .padding(.top, 12)
            figureRow
                .padding(.top, 2)

            // «Details»: 1 J, Belohnungen, Einstand, Realisiert, Ausgebucht — zugeklappt
            Button {
                showDetails.toggle()
            } label: {
                ExpandToggleLabel(title: L("portfolio_card_details"), expanded: showDetails)
            }
            .buttonStyle(.plain)
            .frame(minHeight: 44)
            .accessibilityValue(L(showDetails ? "a11y_expanded" : "a11y_collapsed"))
            if showDetails {
                details
            }
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.top, Spacing.lg)
        .padding(.bottom, Spacing.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Karte wie `appCard()`, hervorgehoben nur durch den Verlauf (kein Akzentrand: eine einzige Hervorhebung)
        .background(
            RoundedRectangle(cornerRadius: CardLevel.top.radius, style: .continuous)
                .fill(AppColors.container)
                .overlay(
                    RoundedRectangle(cornerRadius: CardLevel.top.radius, style: .continuous)
                        .fill(LinearGradient(
                            colors: [accent.tint(Self.tintStart), accent.tint(Self.tintEnd)],
                            startPoint: .topLeading, endPoint: .bottomTrailing))
                )
        )
    }

    /// «Heute» unter dem Gesamtwert; nil = weder Betrag noch Prozent.
    private var todayLine: PortfolioTotalChangeLine? {
        let percent: Double? = today?.percent
        let amount: Double? = percent == nil ? nil : today?.amount
        guard amount?.isFinite == true || percent?.isFinite == true else { return nil }
        return PortfolioTotalChangeLine(amount: amount, percent: percent, unit: unit,
                                        period: L("portfolio_card_today"), spokenLabel: L("portfolio_card_today"))
    }

    /// Inhalt von «Details»: 1 J (mit Belohnungen), Einstand/Realisiert, Ausgebucht — wie Android.
    @ViewBuilder
    private var details: some View {
        // «↘ −38’412.95 CHF −27.85% · 1J» wie im Portfolio-Widget; ohne Daten keine Zeile
        if let yearChange, yearChange.amount.isFinite {
            PortfolioTotalChangeLine(amount: yearChange.amount, percent: yearChange.percent, unit: unit,
                                     period: L("portfolio_history_range_1y"), spokenLabel: L("portfolio_history_period_1y"))
            // Belohnungen im Jahr: Zufluss, nicht Gewinn — klein darunter
            PortfolioRewardsNote(rewards: yearChange.rewards, unit: unit)
        }
        HStack(alignment: .top, spacing: 12) {
            if unit.converted {
                // Einstand in der Anzeigewährung zum Kurs der Erwerbstage, USDT klein darunter (wie der Gesamtwert)
                let invested: Double? = costFx?.invested.map(PortfolioDecimal.double)
                VStack(alignment: .leading, spacing: 2) {
                    PortfolioMetric(
                        label: L("portfolio_invested"),
                        value: invested.map { PortfolioInsights.mask(PriceFormat.valueWithCurrency($0, unit.code), hidden: hideAmounts) } ?? "—"
                    )
                    if invested != nil, costFx?.investedFxMissing == true {
                        partialMarker(L("portfolio_export_incomplete"))
                    } else if invested != nil, let usdt = summary.invested {
                        PriceWithQuote.text("≈ " + PortfolioInsights.mask(PortfolioFormat.usdtValue(usdt), hidden: hideAmounts))
                            .font(AppFont.amount(.caption))
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                PortfolioMetric(
                    label: L("portfolio_invested"),
                    value: summary.invested.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—"
                )
            }
            if PortfolioFormat.showRealized(summary.realized, incomplete: summary.realizedIncomplete) {
                PortfolioMetric(
                    label: L("portfolio_realized"),
                    value: PortfolioInsights.mask(
                        PortfolioFormat.realizedText(summary.realized, incomplete: summary.realizedIncomplete),
                        hidden: hideAmounts),
                    valueTone: PortfolioFormat.plTone(summary.realized)
                )
            }
        }
        .padding(.top, Spacing.sm)
        // Steuer-CSV rechnet nach den Regeln des Landes (Fristen, Freigrenzen, Pools): kann abweichen — wie Android
        if PortfolioFormat.showRealized(summary.realized, incomplete: summary.realizedIncomplete) {
            Text(L("tax_export_realized_differs"))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 2)
        }
        // «Ausgebucht: +X (Wertänderung bis zur Auszahlung)»: steckt im Gewinn/Verlust des Wertverlaufs,
        // nicht in «Seit Kauf»/«realisiert» — nur wenn ≠ 0. Wie Android
        if let gain = withdrawnGain {
            Text(L("portfolio_withdrawn_gain",
                   PortfolioInsights.mask(PortfolioFormat.realizedText(gain, incomplete: summary.withdrawnGainIncomplete),
                                          hidden: hideAmounts)))
                .font(AppFont.amount(.caption))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, Spacing.xs)
        }
        Spacer().frame(height: Spacing.sm)
    }

    /// Gesamtwert gross mit rollenden Ziffern (Anzeigewährung, sonst USDT), dahinter die Einheit klein und grau
    /// (Grundlinie wie die Zahl, immer rechts davon). Passt beides nicht, wird nur die Zahl
    /// verkleinert (bis auf die Hälfte); verborgen («•••») ohne Währung — wie `FittedTotal` (Android).
    private var totalValue: some View {
        let unit = self.unit
        let shown = unit.of(summary.totalValue)
        let number = PriceFormat.valueWithCurrency(shown, unit.code)
        let suffix = " " + unit.code
        let plain = number.hasSuffix(suffix) ? String(number.dropLast(suffix.count)) : number
        return HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
            Text(PortfolioInsights.mask(plain, hidden: hideAmounts))
                .displayFont()
                // Veraltet: onSurfaceVariant statt halber Deckkraft (50 % lag unter 4.5:1, L-13)
                .foregroundStyle(stale ? AppColors.onSurfaceVariant : AppColors.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .contentTransition(.numericText(value: shown))
            if !hideAmounts {
                Text(verbatim: unit.code)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityElement(children: .combine)
    }

    /// Wertänderung ausgebuchter Coins (`PortfolioSummary.withdrawnGainExact`); nil = keine Zeile (unbekannt oder 0).
    private var withdrawnGain: Double? {
        guard let exact = summary.withdrawnGainExact else { return nil }
        let value = PortfolioDecimal.double(exact)
        return value.isFinite && !PortfolioFormat.isZero(value) ? value : nil
    }

    /// 7 T · Seit Kauf nebeneinander (gleich breit); «Heute» steht unter dem Gesamtwert (Build 46).
    private var figureRow: some View {
        HStack(alignment: .top, spacing: Spacing.sm) {
            PortfolioInsightFigure(label: L("portfolio_history_range_7d"), spokenLabel: L("portfolio_history_period_7d"),
                                   amount: weekChange?.amount, percent: weekChange?.percent, unit: unit,
                                   rewards: weekChange?.rewards ?? 0)
            // «Seit Kauf (CHF)»: Gewinn/Verlust seit Kauf der Coins mit Kaufpreis (in der Einheit des Gesamtwerts);
            // umgerechnet: Wert zum heutigen Kurs − Einstand zum Kurs der Erwerbstage (`PortfolioModel.costFx`), nie der
            // Einstand zum heutigen Kurs
            VStack(alignment: .leading, spacing: 2) {
                PortfolioInsightFigure(label: L("ledger_since_purchase_currency", unit.code),
                                       spokenLabel: L("ledger_since_purchase_spoken"),
                                       amount: sinceAmount,
                                       percent: sinceAmount == nil ? nil : sincePercent,
                                       unit: unit,
                                       amountInUnit: unit.converted)
                // Teilsumme (Coins ohne Kaufpreis, ohne Kurs oder ohne Devisenkurs am Kauftag fehlen): «unvollständig»
                if sinceBuyPartial {
                    partialMarker(L("portfolio_export_incomplete"))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// «Seit Kauf» in der Einheit der Karte: umgerechnet aus `costFx` (schon in der Währung), sonst USDT aus `summary`.
    private var sinceAmount: Double? {
        unit.converted ? costFx?.unrealized.map(PortfolioDecimal.double) : summary.unrealized
    }

    private var sincePercent: Double? { unit.converted ? costFx?.unrealizedPercent : summary.unrealizedPercent }

    /// «Seit Kauf» ist eine Teilsumme: Coins bzw. Teile ohne Kaufpreis, ohne aktuellen Kurs oder (umgerechnet) ohne
    /// Devisenkurs am Kauftag fehlen darin.
    private var sinceBuyPartial: Bool {
        let fxMissing = unit.converted && costFx?.unrealizedFxMissing == true
        return summary.unrealized != nil && (fxMissing || (sinceAmount != nil
            && (!summary.missingCostCoins.isEmpty || !summary.partialCostParts.isEmpty || !summary.missingCurrentPrices.isEmpty)))
    }

    /// Kleiner Hinweis direkt an einer Teilsumme («ohne 2 Coins», «unvollständig») — nicht nur in den zugeklappten
    /// Hinweisen; antippen klappt «Hinweise» auf. Wie Android `PartialMarker`.
    private func partialMarker(_ text: String) -> some View {
        Button {
            showHints = true
        } label: {
            Text(text)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Capsule().fill(AppColors.containerHighest))
        }
        .buttonStyle(.plain)
    }
}
