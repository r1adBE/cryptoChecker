import AppIntents
import SwiftUI
import WidgetKit

// MARK: Daten

struct SingleEntry: TimelineEntry {
    var date: Date
    /// nil = noch kein Paar gewählt (oder gelöscht).
    let watch: Watch?
    /// Kerzen (OHLC) im gewählten Zeitraum; nil = nicht verfügbar (Chart ausgeblendet).
    let candles: [MarketCandle]?
    /// Zeitraum des Charts (Kurzform neben der Uhrzeit).
    let range: WidgetChartRangeOption
    /// Kerzen oder Linie.
    let chartType: WidgetChartTypeOption
    let theme: WidgetThemeOption
    let accent: AccentColor
    /// Kursfarben aus der App-Einstellung (beim Erstellen des Eintrags gelesen).
    var priceColors: PriceColorScheme = SharedStorage.loadSettings().priceColorScheme
    /// Einstellung «Hoher Kontrast» (beim Erstellen des Eintrags gelesen).
    var highContrast: Bool = SharedStorage.loadSettings().highContrast
    /// Einstellung «Farben tauschen» (beim Erstellen des Eintrags gelesen).
    var priceColorsInverted: Bool = SharedStorage.loadSettings().priceColorsInverted
    /// Ab diesem Alter «veraltet» (`WidgetOutdated`), gemessen an `date`.
    var outdatedAfter: Int64 = WidgetOutdated.afterMillis

    /// Kurs des Paares am Datum des Eintrags veraltet?
    var outdated: Bool {
        guard let watch else { return false }
        return WidgetOutdated.isOutdated(watch, at: date, afterMillis: outdatedAfter)
    }

    static func sample(theme: WidgetThemeOption = .system, range: WidgetChartRangeOption = .day,
                       chartType: WidgetChartTypeOption = .candles) -> SingleEntry {
        SingleEntry(date: Date(), watch: WidgetData.sampleWatches.first,
                    candles: WidgetData.sampleCandles(range: range), range: range, chartType: chartType,
                    theme: theme, accent: SharedStorage.loadSettings().accentColor)
    }
}

struct SingleProvider: AppIntentTimelineProvider {
    typealias Entry = SingleEntry
    typealias Intent = SingleWidgetIntent

    func placeholder(in context: Context) -> SingleEntry {
        .sample()
    }

    func snapshot(for configuration: SingleWidgetIntent, in context: Context) async -> SingleEntry {
        let watch = Self.watch(for: configuration)
        // Galerie: ohne Paar, ohne Kurs oder ohne Chart (z. B. offline) das Beispiel (BTC mit Kerzen)
        // statt eines leeren Widgets; Beispielkerzen nie mit einem echten Paar mischen.
        let sample = SingleEntry.sample(theme: configuration.theme, range: configuration.range,
                                        chartType: configuration.chartType)
        if context.isPreview, watch?.lastPrice == nil {
            return sample
        }
        let real = await self.entry(for: configuration, watch: watch)
        if context.isPreview, real.candles == nil {
            return sample
        }
        return real
    }

    func timeline(for configuration: SingleWidgetIntent, in context: Context) async -> Timeline<SingleEntry> {
        let initial = Self.watch(for: configuration)
        // Kurse und Chart gleichzeitig holen.
        async let refreshed: Void = WidgetRefresh.refreshIfStale(deadline: 18, otherKinds: [WidgetData.watchlistKind])
        async let chart: [MarketCandle]? = Self.candles(for: initial, range: configuration.range)
        _ = await refreshed
        let candles = await chart
        let watch = Self.watch(for: configuration)
        let entry = SingleEntry(date: Date(), watch: watch, candles: watch == nil ? nil : candles,
                                range: configuration.range, chartType: configuration.chartType,
                                theme: configuration.theme, accent: SharedStorage.loadSettings().accentColor)
        // Zweiter Eintrag, sobald der Kurs veraltet («veraltet · 06:42»)
        var times: [Int64] = []
        if let watch, !ConnectionErrors.isNotTraded(watch.lastError) { times = [watch.lastUpdate] }
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        return Timeline(entries: entries, policy: .after(WidgetData.nextReload))
    }

    private func entry(for configuration: SingleWidgetIntent, watch: Watch?) async -> SingleEntry {
        let candles = await Self.candles(for: watch, range: configuration.range)
        return SingleEntry(date: Date(), watch: watch, candles: candles, range: configuration.range,
                           chartType: configuration.chartType,
                           theme: configuration.theme, accent: SharedStorage.loadSettings().accentColor)
    }

    private static func watch(for configuration: SingleWidgetIntent) -> Watch? {
        guard let id = configuration.watch?.watchId else { return nil }
        return SharedStorage.loadSnapshot().watches.first { $0.id == id }
    }

    private static func candles(for watch: Watch?, range: WidgetChartRangeOption) async -> [MarketCandle]? {
        guard let watch else { return nil }
        return await WidgetSparkline.candles(base: watch.baseAsset, quote: watch.quoteAsset, range: range)
    }
}

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
        switch family {
        case .accessoryRectangular:
            SingleRectangularView(watch: entry.watch, outdated: entry.outdated, date: entry.date)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(Self.url(entry.watch))
        case .accessoryInline:
            SingleInlineView(watch: entry.watch)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(Self.url(entry.watch))
        default:
            WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                         highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
                Group {
                    if let watch = entry.watch {
                        if family == .systemMedium {
                            SingleMediumView(watch: watch, candles: entry.candles, range: entry.range,
                                             chartType: entry.chartType, palette: palette,
                                             outdated: entry.outdated, date: entry.date)
                        } else {
                            SingleSmallView(watch: watch, candles: entry.candles, range: entry.range,
                                            chartType: entry.chartType, palette: palette,
                                            outdated: entry.outdated, date: entry.date)
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
private func chartAccessibility(_ candles: [MarketCandle], _ type: WidgetChartTypeOption,
                                _ range: WidgetChartRangeOption, _ quote: String) -> String {
    WidgetChartGeometry.accessibility(candles, type: type.chartType, range: range.chartRange, quote: quote)
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

    func body(content: Content) -> some View {
        content
            .accessibilityElement(children: .combine)
            .accessibilityLabel(A11y.watchRow(watch, stale: outdated ? WidgetOutdated.spoken(watch.lastUpdate, at: date) : nil))
    }
}

private struct SingleHeader: View {
    let watch: Watch
    let palette: WidgetPalette
    var badge: CGFloat = 20

    /// Volles Paar («BTC/USDT»), wenn es neben der Veränderung Platz hat, sonst nur die
    /// Basis («BTC» statt «BT…»). Die Veränderung wird nie gekürzt (feste Breite, Vorrang).
    var body: some View {
        HStack(spacing: 6) {
            WidgetCoinBadge(symbol: watch.baseAsset, size: badge, dark: palette.dark)
            ViewThatFits(in: .horizontal) {
                pairText(watch.displayName)
                    .fixedSize(horizontal: true, vertical: false)
                pairText(watch.baseAsset)
                    .minimumScaleFactor(0.7)
            }
            .layoutPriority(1)
            Spacer(minLength: 4)
            // Veränderung über 24 Stunden (wie die Pille der App); der Chart kann einen anderen Zeitraum zeigen
            WidgetChangeLabel(change: watch.change24h, palette: palette, size: 11.5, showsArrow: true, day: true,
                              suffix: L("widget_range_short_24h"))
                .fixedSize(horizontal: true, vertical: false)
                .layoutPriority(2)
                .invalidatableContent()
        }
    }

    private func pairText(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13.5, weight: .bold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
    }
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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                SingleHeader(watch: watch, palette: palette)
                Text(watch.marketName)
                    .font(.system(size: 10.5))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .padding(.top, 2)
                WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 24, palette: palette)
                    .padding(.top, 3)
            }
            .modifier(SingleSummaryA11y(watch: watch, outdated: outdated, date: date))
            Group {
                if let candles = chartCandles(candles) {
                    WidgetPriceChartView(candles: candles, type: chartType, range: range, palette: palette,
                                         lineWidth: 1.6, currentPrice: watch.lastPrice,
                                         accessibilityText: chartAccessibility(candles, chartType, range, watch.quoteAsset))
                } else {
                    Color.clear
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 4)
            // «14:05 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42 · 24h»
            Text(WidgetOutdated.timeText(watch.lastUpdate, outdated: outdated, at: date) + " · " + range.shortLabel)
                .font(.system(size: 9.5, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .invalidatableContent()
        }
        .padding(14)
    }
}

/// Mittel: Angaben links, Chart mit Preisstufen rechts.
private struct SingleMediumView: View {
    let watch: Watch
    let candles: [MarketCandle]?
    let range: WidgetChartRangeOption
    let chartType: WidgetChartTypeOption
    let palette: WidgetPalette
    var outdated = false
    var date = Date()

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    WidgetCoinBadge(symbol: watch.baseAsset, size: 24, dark: palette.dark)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(watch.displayName)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(palette.text)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(watch.marketName)
                            .font(.system(size: 10.5))
                            .foregroundStyle(palette.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 28, palette: palette)
                if watch.lastPrice != nil {
                    let change = watch.change24h
                    WidgetChangeLabel(change: change, palette: palette, size: 12, showsArrow: true, day: true,
                                      suffix: L("widget_range_short_24h"))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(palette.change(change).opacity(palette.dark ? 0.14 : 0.06)))
                        .padding(.top, 4)
                        .invalidatableContent()
                }
                Spacer(minLength: 4)
                // Zeitraum des Charts neben der Uhrzeit, z. B. «14:05 · 7T»; veraltet «veraltet · 06:42 · 7T»
                Text(WidgetOutdated.timeText(watch.lastUpdate, outdated: outdated, at: date) + " · " + range.shortLabel)
                    .font(.system(size: 10, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .invalidatableContent()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .modifier(SingleSummaryA11y(watch: watch, outdated: outdated, date: date))

            // Hoch/Tief stehen jetzt als Preisstufen im Chart (oben/unten)
            if let candles = chartCandles(candles) {
                WidgetPriceChartView(candles: candles, type: chartType, range: range, palette: palette, lineWidth: 2,
                                     currentPrice: watch.lastPrice,
                                     accessibilityText: chartAccessibility(candles, chartType, range, watch.quoteAsset))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(16)
    }
}

/// Noch kein Paar gewählt.
private struct SingleChooseView: View {
    let accent: AccentColor
    let palette: WidgetPalette

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
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
        .padding(14)
    }
}

// MARK: Sperrbildschirm

private struct SingleRectangularView: View {
    let watch: Watch?
    var outdated = false
    var date = Date()

    var body: some View {
        if let watch {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    Text(watch.displayName)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                    Spacer(minLength: 2)
                    if let change = PriceFormat.changePercent(watch.change24h) {
                        HStack(spacing: 2) {
                            ChangeArrowIcon(change: watch.change24h)
                                .font(.system(size: 9, weight: .bold))
                            Text(change)
                                .font(.system(size: 12, weight: .semibold))
                                .monospacedDigit()
                                .lineLimit(1)
                        }
                    }
                }
                Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                    .font(.system(size: 19, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .widgetAccentable()
                Text("\(watch.marketName) · \(WidgetOutdated.timeText(watch.lastUpdate, outdated: outdated, at: date))")
                    .font(.system(size: 11))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .modifier(SingleSummaryA11y(watch: watch, outdated: outdated, date: date))
        } else {
            Text(L("single_widget_choose"))
                .font(.system(size: 13, weight: .semibold))
        }
    }
}

private struct SingleInlineView: View {
    let watch: Watch?

    var body: some View {
        if let watch {
            let parts = [watch.baseAsset, PriceFormat.price(watch.lastPrice), PriceFormat.changePercent(watch.change24h)]
            Text(parts.compactMap { $0 }.joined(separator: " "))
                .accessibilityLabel(A11y.watchRow(watch))
        } else {
            Text(L("single_widget_choose"))
        }
    }
}
