import AppIntents
import SwiftUI
import WidgetKit

// MARK: Daten

struct WatchlistEntry: TimelineEntry {
    var date: Date
    let watches: [Watch]
    let lastRefreshAt: Int64
    /// Ab diesem Alter «veraltet» (`WidgetOutdated`), gemessen an `date`.
    var outdatedAfter: Int64 = WidgetOutdated.afterMillis
    /// Dauer der letzten Aktualisierung in ms (Kopfzeile «Merkliste · 06:42:15 · 840 ms»).
    var lastDuration: Int64 = SharedStorage.lastRefreshDuration
    let theme: WidgetThemeOption
    let accent: AccentColor
    /// Kursfarben aus der App-Einstellung (beim Erstellen des Eintrags gelesen).
    var priceColors: PriceColorScheme = SharedStorage.loadSettings().priceColorScheme
    /// Einstellung «Hoher Kontrast» (beim Erstellen des Eintrags gelesen).
    var highContrast: Bool = SharedStorage.loadSettings().highContrast
    /// Einstellung «Farben tauschen» (beim Erstellen des Eintrags gelesen).
    var priceColorsInverted: Bool = SharedStorage.loadSettings().priceColorsInverted
    /// «Basis der %-Änderung» und Stempel der gespeicherten Werte (beim Erstellen gelesen).
    var changeBasis: ChangeBasis = SharedStorage.loadSettings().changeBasis
    var changeStamp: ChangeStamp? = SharedStorage.changeStamp
    /// Coin-Logos je Basis-Symbol (`WidgetLogos`); leer = Initialen.
    var logos: [String: UIImage] = [:]
    /// Schalter «Coin-Logos in Widgets»; aus = keine Plakette.
    var showLogos: Bool = SharedStorage.loadSettings().widgetCoinLogos

    /// %-Basis zum Zeitpunkt des Eintrags: nach Mitternacht bzw. mit anderer Basis «—».
    var changeView: ChangeView {
        ChangeView.of(stamp: changeStamp, basis: changeBasis, now: Int64(date.timeIntervalSince1970 * 1000))
    }

    /// `group`: Gruppe des Widgets; nil = alle Paare.
    static func current(theme: WidgetThemeOption, group: String? = nil) -> WatchlistEntry {
        WatchlistEntry(date: Date(),
                       watches: WidgetData.watches(group: group),
                       lastRefreshAt: SharedStorage.lastRefreshAt,
                       theme: theme,
                       accent: SharedStorage.loadSettings().accentColor)
    }

    /// Galerie und Platzhalter: Kopfzeile «Merkliste · Uhrzeit · 840 ms» wie die Android-Vorschau.
    static func sample(theme: WidgetThemeOption = .system) -> WatchlistEntry {
        WatchlistEntry(date: Date(), watches: WidgetData.sampleWatches, lastRefreshAt: TimeUtils.nowMillis,
                       lastDuration: 840, theme: theme, accent: SharedStorage.loadSettings().accentColor)
    }
}

struct WatchlistProvider: AppIntentTimelineProvider {
    typealias Entry = WatchlistEntry
    typealias Intent = WatchlistWidgetIntent

    func placeholder(in context: Context) -> WatchlistEntry {
        .sample()
    }

    func snapshot(for configuration: WatchlistWidgetIntent, in context: Context) async -> WatchlistEntry {
        var entry = WatchlistEntry.current(theme: configuration.theme, group: configuration.group?.groupName)
        // Galerie: ohne Paare oder noch ganz ohne Kurse das Beispiel (BTC, ETH, SOL, XRP …)
        let noPrices = entry.watches.allSatisfy { $0.lastPrice == nil }
        if context.isPreview && noPrices { return .sample(theme: configuration.theme) }
        entry.logos = await WidgetLogos.load(for: entry.watches)
        return entry
    }

    func timeline(for configuration: WatchlistWidgetIntent, in context: Context) async -> Timeline<WatchlistEntry> {
        // Alte Kurse zuerst auffrischen (mit Alarmen und Mitteilungen).
        await WidgetRefresh.refreshIfStale(deadline: 20, otherKinds: [WidgetData.singleKind])
        var entry = WatchlistEntry.current(theme: configuration.theme, group: configuration.group?.groupName)
        entry.logos = await WidgetLogos.load(for: entry.watches)
        // Zweiter Eintrag, sobald die Uhrzeit oder ein Kurs veraltet («veraltet · 06:42»)
        let times = [entry.lastRefreshAt] + entry.watches.filter { !ConnectionErrors.isNotTraded($0.lastError) }.map(\.lastUpdate)
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        return Timeline(entries: entries, policy: .after(WidgetData.nextReload))
    }
}

// MARK: Widget

struct WatchlistWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetData.watchlistKind,
                               intent: WatchlistWidgetIntent.self,
                               provider: WatchlistProvider()) { entry in
            WatchlistWidgetView(entry: entry)
        }
        .configurationDisplayName(L("tab_watchlist"))
        .description(L("widget_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

// MARK: Ansicht

struct WatchlistWidgetView: View {
    let entry: WatchlistEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                     highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
            Group {
                switch family {
                case .systemSmall:
                    WatchlistSmallView(entry: entry, palette: palette)
                default:
                    WatchlistListView(entry: entry, palette: palette, rows: family == .systemLarge ? 10 : 4)
                }
            }
            .environment(\.widgetLogos, entry.logos)
            .environment(\.widgetLogosEnabled, entry.showLogos)
        }
    }
}

/// Mittel und Gross: Kopfzeile und Liste.
private struct WatchlistListView: View {
    let entry: WatchlistEntry
    let palette: WidgetPalette
    let rows: Int

    /// Dünne Linie zwischen den Paaren.
    // 1 pt wie 1 dp auf Android – dünn, aber auf 3x-Displays noch sichtbar
    private var dividerHeight: CGFloat { 1 }

    var body: some View {
        VStack(spacing: 4) {
            header
            if entry.watches.isEmpty {
                empty
            } else {
                list
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .widgetURL(entry.watches.isEmpty ? WidgetData.addURL : nil)
    }

    /// Logo · «Merkliste» links, rechts «Uhrzeit · Dauer» und der Aktualisieren-Knopf.
    private var header: some View {
        HStack(spacing: 6) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
            WatchlistHeaderTitle(entry: entry, palette: palette,
                                 titleSizes: (normal: 13, small: 11), metaSize: 10.5)
            WidgetRefreshButton(palette: palette, size: 26)
        }
        .frame(height: 26)
    }

    private var empty: some View {
        VStack(spacing: 6) {
            Spacer(minLength: 0)
            Image(systemName: "list.bullet.rectangle")
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(palette.accent)
                .accessibilityHidden(true)
            Text(L("widget_empty"))
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(palette.secondary)
                .multilineTextAlignment(.center)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }

    private var list: some View {
        let shown = Array(entry.watches.prefix(rows))
        return VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { index in
                if index < shown.count {
                    let watch = shown[index]
                    // Trennlinie zwischen den Paaren (über jeder Zeile ausser der ersten, also nie
                    // unter der letzten): Akzentfarbe mit geringer Deckkraft, beginnt bündig
                    // mit dem Paar-Text (Coin-Kreis 22 + Abstand 6).
                    if index > 0 {
                        Rectangle()
                            .fill(palette.divider)
                            .frame(height: dividerHeight)
                            .padding(.leading, 28)
                            .accessibilityHidden(true)
                    }
                    if let url = WidgetData.watchURL(watch.id) {
                        Link(destination: url) {
                            WatchlistWidgetRow(watch: watch, palette: palette, date: entry.date,
                                               outdatedAfter: entry.outdatedAfter, changeView: entry.changeView)
                        }
                        .frame(maxHeight: .infinity)
                    } else {
                        WatchlistWidgetRow(watch: watch, palette: palette, date: entry.date,
                                           outdatedAfter: entry.outdatedAfter, changeView: entry.changeView)
                            .frame(maxHeight: .infinity)
                    }
                } else {
                    // Freie Plätze halten die Zeilenhöhe gleich.
                    Color.clear
                        .frame(maxHeight: .infinity)
                        .padding(.top, index > 0 ? dividerHeight : 0)
                }
            }
        }
        .frame(maxHeight: .infinity)
    }
}

/// Titel «Merkliste» und «Uhrzeit · Dauer» der Kopfzeile, nie abgeschnitten (wie
/// ListWidgetHeader auf Android): `ViewThatFits` nimmt die erste Stufe, die ganz passt —
/// erst fällt die Dauer weg, dann wird der Titel kleiner, dann fällt die Uhrzeit weg.
/// Der Titel bleibt immer. Veraltet: «veraltet · 06:42» ohne Dauer, eng nur «veraltet».
private struct WatchlistHeaderTitle: View {
    let entry: WatchlistEntry
    let palette: WidgetPalette
    /// Schriftgrössen des Titels: normal, eng.
    let titleSizes: (normal: CGFloat, small: CGFloat)
    let metaSize: CGFloat

    private var outdated: Bool {
        WidgetOutdated.isOutdated(entry.lastRefreshAt, at: entry.date, afterMillis: entry.outdatedAfter)
    }

    var body: some View {
        let stale = WidgetOutdated.label(entry.lastRefreshAt, at: entry.date)
        let full = outdated ? stale : WidgetData.refreshMeta(at: entry.lastRefreshAt, duration: entry.lastDuration)
        let timeOnly = outdated ? stale : WidgetData.refreshMeta(at: entry.lastRefreshAt, duration: 0)
        ViewThatFits(in: .horizontal) {
            row(titleSize: titleSizes.normal, meta: full)
            row(titleSize: titleSizes.normal, meta: timeOnly)
            row(titleSize: titleSizes.small, meta: timeOnly)
            if outdated {
                row(titleSize: titleSizes.small, meta: L("widget_outdated"))
            }
            HStack(spacing: 0) {
                // Letzte Stufe: nur der Titel, notfalls leicht verkleinert statt «Merkl…»
                title(size: titleSizes.small)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 0)
            }
        }
    }

    private func row(titleSize: CGFloat, meta: String) -> some View {
        HStack(spacing: 0) {
            title(size: titleSize)
                .fixedSize()
            Spacer(minLength: 6)
            if !meta.isEmpty {
                Text(meta)
                    .font(.system(size: metaSize, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .fixedSize()
                    .invalidatableContent()
                    // VoiceOver: «veraltet, letzte Aktualisierung 06:42» statt der Kurzform
                    .accessibilityLabel(outdated ? WidgetOutdated.spoken(entry.lastRefreshAt, at: entry.date) : meta)
            }
        }
    }

    private func title(size: CGFloat) -> some View {
        Text(L("tab_watchlist"))
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
    }
}

/// Eine Zeile, zweizeilig und eng: oben Paar und Kurs, unten Börse und
/// Veränderung mit Pfeil und Vorzeichen — die Richtung hängt nie allein an der Farbe.
/// VoiceOver liest die Zeile als einen Satz.
private struct WatchlistWidgetRow: View {
    let watch: Watch
    let palette: WidgetPalette
    /// Datum des Eintrags und Grenze: eigener Stand des Paares veraltet → «Binance · veraltet».
    let date: Date
    let outdatedAfter: Int64
    /// %-Basis und Gültigkeit der gespeicherten Veränderung.
    var changeView = ChangeView()

    private var outdated: Bool { WidgetOutdated.isOutdated(watch, at: date, afterMillis: outdatedAfter) }

    var body: some View {
        HStack(spacing: 6) {
            WidgetCoinBadge(symbol: watch.baseAsset, size: 22, dark: palette.dark, accent: palette.accent,
                            logo: CoinLogos.allowed(forMarket: watch.marketKey))
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 3) {
                    Text(watch.displayName)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                    if watch.favorite {
                        Image(systemName: "star.fill")
                            .font(.system(size: 7, weight: .bold))
                            .foregroundStyle(palette.accent)
                    }
                }
                Text(outdated ? L("watchlist_row_outdated", BidiText.isolate(watch.marketName)) : watch.marketName)
                    .font(.system(size: 10))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
            }
            .layoutPriority(1)
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 0) {
                WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 13,
                                palette: palette, weight: .semibold)
                if watch.lastPrice == nil, watch.lastError != nil {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(palette.down)
                } else {
                    WidgetChangeLabel(change: changeView.shown(watch.shownChange24h), palette: palette, size: 10,
                                      showsArrow: true, day: true, basis: changeView.basis)
                }
            }
            .layoutPriority(2)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(A11y.watchRow(watch, stale: outdated ? WidgetOutdated.spoken(watch.lastUpdate, at: date) : nil,
                                          changeView: changeView))
    }
}

/// Klein: das oberste Paar.
private struct WatchlistSmallView: View {
    let entry: WatchlistEntry
    let palette: WidgetPalette

    var body: some View {
        let watch = entry.watches.first
        let outdated = watch.map { WidgetOutdated.isOutdated($0, at: entry.date, afterMillis: entry.outdatedAfter) } ?? false
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 5) {
                WidgetLogo(accent: entry.accent, dark: palette.dark, size: 14)
                // «Merkliste» · «Uhrzeit · Dauer»; eng: ohne Dauer, kleinerer Titel, ohne Uhrzeit
                WatchlistHeaderTitle(entry: entry, palette: palette,
                                     titleSizes: (normal: 12, small: 10.5), metaSize: 10)
                WidgetRefreshButton(palette: palette, size: 24)
            }
            Spacer(minLength: 4)
            if let watch {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 6) {
                        WidgetCoinBadge(symbol: watch.baseAsset, size: 20, dark: palette.dark, accent: palette.accent,
                                        logo: CoinLogos.allowed(forMarket: watch.marketKey))
                        Text(watch.displayName)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(palette.text)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    Text(watch.marketName)
                        .font(.system(size: 10.5))
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .padding(.top, 2)
                    WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 24, palette: palette)
                        .padding(.top, 2)
                    HStack {
                        WidgetChangeLabel(change: entry.changeView.shown(watch.shownChange24h), palette: palette, size: 11,
                                          showsArrow: true, day: true, basis: entry.changeBasis)
                        Spacer(minLength: 4)
                        Text(WidgetOutdated.timeText(watch.lastUpdate, outdated: outdated, at: entry.date))
                            .font(.system(size: 9.5, weight: .medium))
                            .monospacedDigit()
                            .foregroundStyle(palette.secondary)
                            .lineLimit(1)
                            .invalidatableContent()
                    }
                    .padding(.top, 1)
                }
                // VoiceOver: das Paar als ein Satz (veraltet mit «veraltet, letzte Aktualisierung …»)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(A11y.watchRow(watch, stale: outdated ? WidgetOutdated.spoken(watch.lastUpdate, at: entry.date) : nil,
                                                  changeView: entry.changeView))
            } else {
                Text(L("widget_empty"))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.secondary)
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .widgetURL(watch.flatMap { WidgetData.watchURL($0.id) } ?? WidgetData.addURL)
    }
}
