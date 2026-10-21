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
    /// Aktualisieren-Knopf gesperrt bis (ms; 0 = nie): bis dahin Countdown statt Symbol (`WidgetRefreshState`).
    var refreshBlockedUntil: Int64 = 0

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
        // In der 15-s-Sperre nach einer vollständigen Aktualisierung (oder einem Tipp): Countdown statt Knopf;
        // gleiche Zeit wie der Countdown der App (`SharedStorage.lastRefreshAt`)
        entry.refreshBlockedUntil = WidgetRefreshState.blockedUntil(stored: WidgetRefresh.blockedUntil,
                                                                    lastFinishedAt: entry.lastRefreshAt,
                                                                    runningSince: WidgetRefresh.runningSince,
                                                                    now: Int64(entry.date.timeIntervalSince1970 * 1000))
        return entry
    }

    /// Galerie und Platzhalter: Kopfzeile «Merkliste · Uhrzeit · 840 ms» wie die Android-Vorschau.
    static func sample(theme: WidgetThemeOption = .system) -> WatchlistEntry {
        // Galerie: immer mit Knopf (`refreshBlockedUntil` 0)
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
        // (deaktivierte Paare werden nie «veraltet»; nur gezeigte Paare, `WidgetRows`)
        let times = [entry.lastRefreshAt] + WidgetRows.shown(entry.watches).filter { !$0.disabled && !ConnectionErrors.isNotTraded($0.lastError) }.flatMap { WidgetFreshness.outdatedTimes($0, afterMillis: entry.outdatedAfter) }
        var entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        // Nach einem Tipp in der Sperre: ein Eintrag zu ihrem Ende bringt das Symbol zurück (der Countdown davor
        // zählt von selbst, kein Neuladen je Sekunde)
        if let unlock = WidgetRefreshState.unlockAt(blockedUntil: entry.refreshBlockedUntil,
                                                    now: Int64(entry.date.timeIntervalSince1970 * 1000)) {
            let unlockDate = Date(timeIntervalSince1970: TimeInterval(unlock) / 1000)
            var later = entries.last { $0.date <= unlockDate } ?? entry
            later.date = unlockDate
            entries = (entries + [later]).sorted { $0.date < $1.date }
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
                    WatchlistListView(entry: entry, palette: palette, rows: family == .systemLarge ? WidgetRows.maxListRows : WidgetRows.mediumListRows)
                }
            }
            .environment(\.widgetLogos, entry.logos)
            .environment(\.widgetLogosEnabled, entry.showLogos)
        }
    }
}
