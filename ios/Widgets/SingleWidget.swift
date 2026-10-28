import AppIntents
import SwiftUI
import WidgetKit

// MARK: Widget

struct SingleWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetData.singleKind,
                               intent: SingleWidgetIntent.self,
                               provider: SingleProvider()) { entry in
            SingleWidgetView(entry: entry)
        }
        .configurationDisplayName(L("single_widget_label"))
        .description(L("single_widget_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
        .contentMarginsDisabled()
    }
}

// MARK: Ansicht

struct SingleWidgetView: View {
    let entry: SingleEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group { content }
            // %-Basis für Zeitraum und VoiceOver der Veränderung
            .environment(\.changeView, entry.changeView)
            // Coin-Logo (Schalter «Coin-Logos in Widgets»); leer = Initialen
            .environment(\.widgetLogos, entry.logos)
            .environment(\.widgetLogosEnabled, entry.showLogos)
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryRectangular:
            SingleRectangularView(watch: entry.shownWatch, outdated: entry.outdated, date: entry.date,
                                  outdatedAfter: entry.outdatedAfter)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(Self.url(entry.watch))
        case .accessoryInline:
            SingleInlineView(watch: entry.shownWatch)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(Self.url(entry.watch))
        default:
            WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                         highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
                Group {
                    if let watch = entry.shownWatch {
                        if family == .systemMedium {
                            SingleMediumView(watch: watch, candles: entry.candles, range: entry.range,
                                             chartType: entry.chartType, palette: palette,
                                             outdated: entry.outdated, date: entry.date,
                                             outdatedAfter: entry.outdatedAfter, foreignChart: entry.foreignChart)
                        } else {
                            SingleSmallView(watch: watch, candles: entry.candles, range: entry.range,
                                            chartType: entry.chartType, palette: palette,
                                            outdated: entry.outdated, date: entry.date,
                                            outdatedAfter: entry.outdatedAfter, foreignChart: entry.foreignChart)
                        }
                    } else {
                        SingleChooseView(accent: entry.accent, palette: palette)
                    }
                }
                .widgetURL(Self.url(entry.watch))
            }
        }
    }

    static func url(_ watch: Watch?) -> URL? {
        watch.flatMap { WidgetData.watchURL($0.id) }
    }
}

/// VoiceOver-Satz zum Chart: Zeitraum, Start, Ende, Veränderung, Hoch, Tief (in der Quote).
/// Linie: Schlusskurse; Kerzen: erstes Open, letztes Close, höchstes High, tiefstes Low.
/// Kerzen einer anderen Börse: «…, Chart: Binance» (wie Android).
private func chartAccessibility(_ candles: [MarketCandle], _ type: WidgetChartTypeOption,
                                _ range: WidgetChartRangeOption, _ quote: String, foreignChart: String? = nil) -> String {
    let text: String = WidgetChartGeometry.accessibility(candles, type: type.chartType, range: range.chartRange, quote: quote)
    guard let foreignChart else { return text }
    return text + ", " + L("watch_row_source_chart", foreignChart)
}

/// Mindestens zwei brauchbare Kerzen, sonst kein Chart.
private func chartCandles(_ candles: [MarketCandle]?) -> [MarketCandle]? {
    guard let candles, WidgetChartGeometry.clean(candles).count >= 2 else { return nil }
    return candles
}

/// Paar, Börse, Kurs und Veränderung als ein Satz; veraltet mit «veraltet, letzte Aktualisierung …».
private struct SingleSummaryA11y: ViewModifier {
    let watch: Watch
    var outdated = false
    var date = Date()
    /// Grenze «veraltet» (für «Kurs unverändert seit …»).
    var outdatedAfter: Int64 = 0
    @Environment(\.changeView) private var changeView

    func body(content: Content) -> some View {
        content
            .accessibilityElement(children: .combine)
            .accessibilityLabel(A11y.watchRow(watch, stale: spokenState, changeView: ChangeView(basis: changeView.basis)))
    }

    /// «Deaktiviert» bzw. «veraltet, letzte Aktualisierung …» bzw. «Kurs unverändert seit …».
    private var spokenState: String? {
        if watch.disabled { return L("watch_disabled") }
        if outdated { return WidgetOutdated.spoken(watch, at: date, afterMillis: outdatedAfter) }
        return outdatedAfter > 0 ? WidgetOutdated.unchanged(watch, at: date, afterMillis: outdatedAfter) : nil
    }
}

/// Börse, bei deaktivierten Paaren «Binance · deaktiviert»; Chart einer anderen Börse: «Kraken · Chart: Binance».
private func singleExchangeLine(_ watch: Watch, foreignChart: String? = nil) -> String {
    if watch.disabled { return L("watchlist_row_disabled", BidiText.isolate(watch.exchangeName)) }
    guard let foreignChart else { return watch.exchangeName }
    return watch.exchangeName + " · " + L("watch_row_source_chart", BidiText.isolate(foreignChart))
}

/// Klein: Paar, Börse, Kurs gross, Mini-Chart, Uhrzeit.
private struct SingleSmallView: View {
    let watch: Watch
    let candles: [MarketCandle]?
    let range: WidgetChartRangeOption
    let chartType: WidgetChartTypeOption
    let palette: WidgetPalette
    var outdated = false
    var date = Date()
    /// Grenze «veraltet» (`WidgetOutdated`), auch für «Kurs unverändert seit …».
    var outdatedAfter: Int64 = 0
    /// Börse der Kerzen, falls nicht die des Paars (nur mit sichtbarem Chart genannt).
    var foreignChart: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Paar und Kurs wachsen mit Dynamic Type, solange sie in der Höhe Platz haben (der Chart gibt nach)
            WidgetPrimaryFit(in: .vertical) { scale in
                VStack(alignment: .leading, spacing: 0) {
                    SingleHeader(watch: watch, palette: palette, scale: scale)
                    Text(singleExchangeLine(watch, foreignChart: chartCandles(candles) == nil ? nil : foreignChart))
                        .widgetSecondaryFont()
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .padding(.top, 2)
                    // Deaktiviert grau statt 60 % Deckkraft (wie Android)
                    WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 24, palette: palette,
                                    color: watch.disabled ? palette.secondary : nil, scale: scale)
                        .padding(.top, 3)
                }
            }
            .modifier(SingleSummaryA11y(watch: watch, outdated: outdated, date: date, outdatedAfter: outdatedAfter))
            Group {
                if let candles = chartCandles(candles) {
                    WidgetPriceChartView(candles: candles, type: chartType, range: range, palette: palette,
                                         lineWidth: 1.6, currentPrice: watch.lastPrice,
                                         accessibilityText: chartAccessibility(candles, chartType, range, watch.quoteAsset, foreignChart: foreignChart),
                                         stable: PortfolioStables.isStable(watch.baseAsset))
                } else {
                    Color.clear
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 4)
            // «14:05 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42 · 24h»; stehender Kurs einer
            // Börse ohne eigene Zeit: «Kurs unverändert seit 11:02 · 24h» (wie die Zeile der App)
            Text(WidgetOutdated.timeText(watch, outdated: outdated, at: date, afterMillis: outdatedAfter) + " · " + range.shortLabel)
                .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .invalidatableContent()
        }
        .padding(12)
    }
}

/// Mittel: Angaben links, Chart mit Preisstufen rechts.
private struct SingleMediumView: View {
    let watch: Watch
    let candles: [MarketCandle]?
    let range: WidgetChartRangeOption
    let chartType: WidgetChartTypeOption
    @Environment(\.changeView) private var changeView
    let palette: WidgetPalette
    var outdated = false
    var date = Date()
    /// Grenze «veraltet» (`WidgetOutdated`), auch für «Kurs unverändert seit …».
    var outdatedAfter: Int64 = 0
    /// Börse der Kerzen, falls nicht die des Paars (nur mit sichtbarem Chart genannt).
    var foreignChart: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            // Paar und Kurs wachsen mit Dynamic Type, solange die Spalte in der Höhe Platz hat
            WidgetPrimaryFit(in: .vertical) { scale in summary(scale: scale) }
            .frame(maxWidth: .infinity, alignment: .leading)
            .modifier(SingleSummaryA11y(watch: watch, outdated: outdated, date: date, outdatedAfter: outdatedAfter))

            // Hoch/Tief stehen jetzt als Preisstufen im Chart (oben/unten)
            if let candles = chartCandles(candles) {
                WidgetPriceChartView(candles: candles, type: chartType, range: range, palette: palette, lineWidth: 2,
                                     currentPrice: watch.lastPrice,
                                     accessibilityText: chartAccessibility(candles, chartType, range, watch.quoteAsset, foreignChart: foreignChart),
                                     stable: PortfolioStables.isStable(watch.baseAsset))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(12)
    }

    /// Angaben links; `scale` = Faktor der Haupttexte.
    private func summary(scale: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                WidgetCoinBadge(symbol: CoinLogoStore.logoKey(for: watch), size: 24, dark: palette.dark, accent: palette.accent,
                                logo: CoinLogos.allowed(forExchange: watch.exchangeKey))
                    .opacity(watch.disabled ? 0.45 : 1)
                VStack(alignment: .leading, spacing: 0) {
                    Text(watch.displayName)
                        .font(.system(size: WidgetFont.body * scale, weight: .bold))
                        .foregroundStyle(watch.disabled ? palette.secondary : palette.text)
                        .lineLimit(1)
                        .minimumScaleFactor(WidgetTextSize.minimumFactor(0.7, primaryScale: scale))
                    Text(singleExchangeLine(watch, foreignChart: chartCandles(candles) == nil ? nil : foreignChart))
                        .widgetSecondaryFont()
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            // Deaktiviert grau statt 60 % Deckkraft (wie Android)
            WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 28, palette: palette,
                            color: watch.disabled ? palette.secondary : nil, scale: scale)
            if watch.lastPrice != nil {
                // Farbiger Text ohne Pille (deaktiviert grau), Zeitraum dahinter grau — wie Android
                WidgetChangeLabel(change: watch.shownChange24h, palette: palette, size: 12, showsArrow: true, day: true,
                                  suffix: A11y.changeShortLabel(changeView.basis), basis: changeView.basis,
                                  neutral: watch.disabled)
                    .padding(.top, 4)
                    .invalidatableContent()
            }
            Spacer(minLength: 4)
            // Zeitraum des Charts neben der Uhrzeit, z. B. «14:05 · 7T»; veraltet «veraltet · 06:42 · 7T»;
            // stehender Kurs: «Kurs unverändert seit 11:02 · 7T»
            Text(WidgetOutdated.timeText(watch, outdated: outdated, at: date, afterMillis: outdatedAfter) + " · " + range.shortLabel)
                .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .invalidatableContent()
        }
    }
}

/// Noch kein Paar gewählt.
private struct SingleChooseView: View {
    let accent: AccentColor
    let palette: WidgetPalette

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                WidgetLogo(accent: accent, dark: palette.dark, size: 16)
                Text(WidgetData.appName)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "hand.tap")
                .font(.system(size: 20))
                .foregroundStyle(palette.accent)
                .accessibilityHidden(true)
            Text(L("single_widget_choose"))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(palette.text)
            Text("—")
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .foregroundStyle(palette.secondary)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(12)
    }
}

// MARK: Sperrbildschirm

private struct SingleRectangularView: View {
    let watch: Watch?
    var outdated = false
    var date = Date()
    /// Grenze «veraltet», auch für «Kurs unverändert seit …».
    var outdatedAfter: Int64 = 0

    var body: some View {
        if let watch {
            // Paar und Kurs wachsen mit Dynamic Type, solange sie in der Höhe Platz haben
            WidgetPrimaryFit(in: .vertical) { scale in content(watch, scale: scale) }
                .frame(maxWidth: .infinity, alignment: .leading)
                .modifier(SingleSummaryA11y(watch: watch, outdated: outdated, date: date, outdatedAfter: outdatedAfter))
        } else {
            Text(L("single_widget_choose"))
                .font(.system(size: 13, weight: .semibold))
        }
    }

    private func content(_ watch: Watch, scale: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Text(watch.displayName)
                    .font(.system(size: 13 * scale, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(WidgetTextSize.minimumFactor(1, primaryScale: scale))
                Spacer(minLength: 2)
                if let change = PriceFormat.changePercent(watch.shownChange24h) {
                    HStack(spacing: 2) {
                        ChangeArrowIcon(change: watch.shownChange24h)
                            .font(.system(size: 9, weight: .bold))
                        Text(change)
                            .font(.system(size: 12, weight: .semibold))
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                }
            }
            // Währung klein und grau wie auf dem Startbildschirm (`Text.quoted`)
            Text.quoted(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset), quote: watch.quoteAsset,
                        size: 19 * scale, weight: .bold, design: .rounded, quoteColor: .secondary)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(WidgetTextSize.minimumFactor(0.6, primaryScale: scale))
                .widgetAccentable()
            // Stehender Kurs: «Kraken · Kurs unverändert seit 11:02», passt das nicht, die Zeit der
            // letzten Änderung statt der frischen Abrufzeit
            ViewThatFits(in: .horizontal) {
                rectangularMeta(watch, hint: true)
                    .fixedSize(horizontal: true, vertical: false)
                rectangularMeta(watch, hint: false)
            }
        }
    }

    private func rectangularMeta(_ watch: Watch, hint: Bool) -> some View {
        Text(verbatim: watch.disabled ? singleExchangeLine(watch)
             : "\(BidiText.isolate(watch.exchangeName)) · "
                + WidgetOutdated.timeText(watch, outdated: outdated, at: date, afterMillis: outdatedAfter, allowsHint: hint))
            .font(.system(size: 12))
            .monospacedDigit()
            .foregroundStyle(.secondary)
            .lineLimit(1)
    }
}

private struct SingleInlineView: View {
    let watch: Watch?
    @Environment(\.changeView) private var changeView

    var body: some View {
        if let watch {
            let parts = [watch.baseAsset, PriceFormat.price(watch.lastPrice), PriceFormat.changePercent(watch.shownChange24h)]
            Text(parts.compactMap { $0 }.joined(separator: " "))
                .accessibilityLabel(A11y.watchRow(watch, changeView: ChangeView(basis: changeView.basis)))
        } else {
            Text(L("single_widget_choose"))
        }
    }
}
