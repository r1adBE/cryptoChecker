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
    /// Titel im Kopf: «Merkliste» oder die gewählte Gruppe (`WidgetData.headerTitle`).
    var title: String = L("tab_watchlist")
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
        let selection = WidgetData.groupWatches(group: group)
        var entry = WatchlistEntry(date: Date(),
                                   watches: selection.watches,
                                   lastRefreshAt: SharedStorage.lastRefreshAt,
                                   theme: theme,
                                   accent: SharedStorage.loadSettings().accentColor)
        entry.title = WidgetData.headerTitle(group: selection.group)
        return entry
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
        // Eben eingerichtet oder geändert (z. B. andere Gruppe): sofort mit den gespeicherten Kursen
        // zeichnen, statt auf das Auffrischen zu warten; die Kurse folgen kurz danach.
        // Sonst alte Kurse zuerst auffrischen (mit Kurs-Mitteilungen und Kursalarmen — siehe `WidgetRefresh`;
        // nach einem misslungenen Versuch erst nach `WidgetRefresh.retryAfterMillis` wieder).
        let configKey = WidgetGroupSelection.configKey(theme: configuration.theme.rawValue, group: configuration.group?.id)
        let newConfig = WidgetGroupSelection.markSeen(configKey)
        let refreshSoon = newConfig && WidgetRefresh.isStale
        if !newConfig {
            // Höchstens `drawDeadlineSeconds` warten, sonst mit den gespeicherten Kursen zeichnen
            await WidgetRefresh.refreshIfStale(ownKind: WidgetData.watchlistKind, otherKinds: [WidgetData.singleKind])
        }
        // Uhrzeit und Kurse aus demselben Stand: schreibt die App die Kurse noch, kurz darauf warten
        let pricesWritten = await WidgetData.awaitPricesForRefreshTime()
        var entry = WatchlistEntry.current(theme: configuration.theme, group: configuration.group?.groupName)
        entry.logos = await WidgetLogos.load(for: entry.watches)
        // Zweiter Eintrag, sobald die Uhrzeit oder ein Kurs veraltet («veraltet · 06:42»)
        // (deaktivierte Paare werden nie «veraltet»)
        let times = [entry.lastRefreshAt] + entry.watches.filter { !$0.disabled && !ConnectionErrors.isNotTraded($0.lastError) }.map(\.lastUpdate)
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        // Kurse folgen erst noch: ein früher Neuversuch, höchstens einer je Takt (`WidgetCadence`)
        let reload = WidgetData.reload(kind: WidgetData.watchlistKind, wantsQuickRetry: refreshSoon || !pricesWritten)
        return Timeline(entries: entries, policy: .after(reload))
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
            // L-19: Logo 18 wie Android (`widget_list.xml`), Titel 13 fett, Uhrzeit 12
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 18)
            WatchlistHeaderTitle(entry: entry, palette: palette,
                                 titleSizes: (normal: 13, small: 11), metaSize: 12)
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
                    // unter der letzten): neutral (Haupttext mit geringer Deckkraft, nie Akzentfarbe —
                    // wie WidgetContrast.dividerColor auf Android). L-19: eine Regel für beide —
                    // über die ganze Zeilenbreite (wie `item_divider` in widget_list_item.xml),
                    // nicht mehr eingerückt (die Plakette fehlt ohne «Coin-Logos in Widgets»).
                    if index > 0 {
                        Rectangle()
                            .fill(palette.divider)
                            .frame(height: dividerHeight)
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
/// erst wird der Titel kleiner (die Dauer der Aktualisierung bleibt so sichtbar), dann fällt die Dauer
/// weg, dann die Uhrzeit. Der Titel bleibt immer. Veraltet: «veraltet · 06:42» ohne Dauer, eng nur «veraltet».
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
            row(titleSize: titleSizes.small, meta: full)
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
                    .widgetSecondaryFont(metaSize, weight: .medium, monospacedDigit: true)
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
        Text(entry.title)
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
    }
}

/// Eine Zeile, zweizeilig und eng: oben Paar und Kurs, unten Börse und
/// Veränderung mit Pfeil und Vorzeichen — die Richtung hängt nie allein an der Farbe.
/// VoiceOver liest die Zeile als einen Satz. L-19: Masse wie Android (`widget_list_item.xml`):
/// Plakette 24, Abstand 8, Paar und Kurs 14 fett, Börse und Veränderung 11; kein Favoriten-Stern.
private struct WatchlistWidgetRow: View {
    let watch: Watch
    let palette: WidgetPalette
    /// Datum des Eintrags und Grenze: eigener Stand des Paares veraltet → «Binance · veraltet».
    let date: Date
    let outdatedAfter: Int64
    /// %-Basis und Gültigkeit der gespeicherten Veränderung.
    var changeView = ChangeView()

    private var outdated: Bool { WidgetOutdated.isOutdated(watch, at: date, afterMillis: outdatedAfter) }

    /// Börse ohne eigene Zeit, Kurs steht lange: «Kurs unverändert seit 14:05» (wie die Zeile der App).
    private var unchanged: String? {
        outdated ? nil : WidgetOutdated.unchanged(watch, at: date, afterMillis: outdatedAfter)
    }

    /// «Binance · deaktiviert» bzw. «Binance · veraltet» bzw. «Kraken · Kurs unverändert seit 14:05»
    /// bzw. nur die Börse.
    private var marketLine: String {
        if watch.disabled { return L("watchlist_row_disabled", BidiText.isolate(watch.marketName)) }
        if outdated { return L("watchlist_row_outdated", BidiText.isolate(watch.marketName)) }
        if let unchanged { return BidiText.isolate(watch.marketName) + " · " + unchanged }
        return watch.marketName
    }

    /// VoiceOver-Zusatz: «Deaktiviert» bzw. «veraltet, letzte Aktualisierung …» bzw. «Kurs unverändert seit …».
    private var spokenState: String? {
        if watch.disabled { return L("watch_disabled") }
        return outdated ? WidgetOutdated.spoken(watch.lastUpdate, at: date) : unchanged
    }

    var body: some View {
        HStack(spacing: 8) {
            WidgetCoinBadge(symbol: CoinLogoStore.logoKey(for: watch), size: 24, dark: palette.dark, accent: palette.accent,
                            logo: CoinLogos.allowed(forMarket: watch.marketKey))
                // Deaktiviert: Logo gedämpft (wie Android `DISABLED_LOGO_ALPHA`), Texte grau statt blass
                .opacity(watch.disabled ? 0.45 : 1)
            VStack(alignment: .leading, spacing: 1) {
                Text(watch.displayName)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(watch.disabled ? palette.secondary : palette.text)
                    .lineLimit(1)
                Text(marketLine)
                    .font(.system(size: 12))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
            }
            .layoutPriority(1)
            Spacer(minLength: 4)
            priceColumn
                .layoutPriority(2)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(A11y.watchRow(watch, stale: spokenState, changeView: changeView))
    }

    private var priceColumn: some View {
        VStack(alignment: .trailing, spacing: 1) {
            // Deaktiviert: Kurs grau (palette.secondary) statt 60 % Deckkraft — wie Android
            WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 14,
                            palette: palette, weight: .bold,
                            color: watch.disabled ? palette.secondary : nil)
            if watch.lastPrice == nil, watch.lastError != nil, !watch.disabled {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(palette.down)
            } else {
                // Änderung in der Listenzeile: 12 pt wie Android (widget_list_item, 12sp)
                WidgetChangeLabel(change: changeView.shown(watch), palette: palette, size: 12,
                                  showsArrow: true, day: true, basis: changeView.basis, neutral: watch.disabled)
            }
        }
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
                // «Merkliste» · «Uhrzeit · Dauer»; eng: kleinerer Titel, ohne Dauer, ohne Uhrzeit
                WatchlistHeaderTitle(entry: entry, palette: palette,
                                     titleSizes: (normal: 12, small: 11), metaSize: 12)
                WidgetRefreshButton(palette: palette, size: 24)
            }
            Spacer(minLength: 4)
            if let watch {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 6) {
                        WidgetCoinBadge(symbol: CoinLogoStore.logoKey(for: watch), size: 20, dark: palette.dark, accent: palette.accent,
                                        logo: CoinLogos.allowed(forMarket: watch.marketKey))
                        Text(watch.displayName)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(palette.text)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    // Deaktiviert: «Binance · deaktiviert», Kurs und Veränderung grau
                    Text(watch.disabled ? L("watchlist_row_disabled", BidiText.isolate(watch.marketName)) : watch.marketName)
                        .widgetSecondaryFont()
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .padding(.top, 2)
                    WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 24, palette: palette,
                                    color: watch.disabled ? palette.secondary : nil)
                        .padding(.top, 2)
                    HStack {
                        WidgetChangeLabel(change: entry.changeView.shown(watch), palette: palette, size: 12,
                                          showsArrow: true, day: true, basis: entry.changeBasis, neutral: watch.disabled)
                        Spacer(minLength: 4)
                        // Kein Platz für «Kurs unverändert seit …»: dann die Zeit der letzten Änderung
                        Text(WidgetOutdated.timeText(watch, outdated: outdated, at: entry.date,
                                                     afterMillis: entry.outdatedAfter, allowsHint: false))
                            .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
                            .foregroundStyle(palette.secondary)
                            .lineLimit(1)
                            .invalidatableContent()
                    }
                    .padding(.top, 1)
                }
                // VoiceOver: das Paar als ein Satz (veraltet mit «veraltet, letzte Aktualisierung …»)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(A11y.watchRow(watch, stale: watch.disabled ? L("watch_disabled")
                                                    : (outdated ? WidgetOutdated.spoken(watch.lastUpdate, at: entry.date)
                                                       : WidgetOutdated.unchanged(watch, at: entry.date,
                                                                                  afterMillis: entry.outdatedAfter)),
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
