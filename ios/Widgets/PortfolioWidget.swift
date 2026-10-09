import AppIntents
import SwiftUI
import WidgetKit

// MARK: Daten

struct PortfolioEntry: TimelineEntry {
    var date: Date
    /// nil = noch nie berechnet (wie leer behandelt).
    let snapshot: PortfolioWidgetSnapshot?
    /// Portfolio-Sperre an: Titel, Schloss und «Gesperrt – in der App entsperren», keine Werte.
    let locked: Bool
    let accent: AccentColor
    /// Anzeige je Widget: nur Umrechnung oder zusätzlich «≈ … USDT».
    var display: PortfolioDisplayOption = .conversionUsdt
    var theme: WidgetThemeOption = .system
    var priceColors: PriceColorScheme = .default
    var highContrast = false
    var priceColorsInverted = false
    /// «Beträge verbergen» (Einstellung der App, App Group): Beträge als «•••», Prozente bleiben.
    var hideAmounts = false
    /// Ab diesem Alter «veraltet» (`WidgetOutdated`), gemessen an `date`.
    var outdatedAfter: Int64 = WidgetOutdated.afterMillis

    /// Momentaufnahme am Datum des Eintrags veraltet? (Gesperrt oder leer: nie.)
    var outdated: Bool {
        guard !locked, let snapshot, !snapshot.empty else { return false }
        return WidgetOutdated.isOutdated(snapshot.updatedAt, at: date, afterMillis: outdatedAfter)
    }

    static func current(display: PortfolioDisplayOption = .conversionUsdt,
                        theme: WidgetThemeOption = .system) -> PortfolioEntry {
        let settings = SharedStorage.loadSettings()
        // %-Basis: Stand mit anderer Basis oder von einem früheren Tag → Veränderung «—»
        let snapshot = PortfolioWidgetStore.load()?.shown(basis: settings.changeBasis.storage, now: TimeUtils.nowMillis)
        return PortfolioEntry(date: Date(), snapshot: snapshot,
                              locked: PortfolioLockPolicy.widgetLocked(lockSetting: settings.appLock),
                              accent: settings.accentColor, display: display, theme: theme,
                              priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                              priceColorsInverted: settings.priceColorsInverted,
                              hideAmounts: settings.hidePortfolioAmounts)
    }

    static func sample(display: PortfolioDisplayOption = .conversionUsdt,
                       theme: WidgetThemeOption = .system) -> PortfolioEntry {
        let settings = SharedStorage.loadSettings()
        let now = TimeUtils.nowMillis
        let hour: Int64 = 3_600_000
        // 24 Stundenwerte und der aktuelle (wie der Stundenverlauf)
        let values: [Double] = [12_163, 12_180, 12_150, 12_120, 12_135, 12_170, 12_166, 12_205, 12_230, 12_211,
                                12_240, 12_275, 12_260, 12_290, 12_315, 12_296, 12_324, 12_352, 12_333, 12_366,
                                12_395, 12_377, 12_410, 12_434, 12_345.67]
        var history: [PortfolioWidgetPoint] = []
        for (i, value) in values.enumerated() {
            history.append(PortfolioWidgetPoint(at: now - Int64(values.count - 1 - i) * hour, value: value))
        }
        let positions = [
            PortfolioWidgetPosition(symbol: "BTC", value: 7_420.10, sharePercent: 60.1, change24hPercent: 1.8),
            PortfolioWidgetPosition(symbol: "ETH", value: 2_910.55, sharePercent: 23.6, change24hPercent: -0.9),
            PortfolioWidgetPosition(symbol: "SOL", value: 1_215.02, sharePercent: 9.8, change24hPercent: 3.2),
            PortfolioWidgetPosition(symbol: "ADA", value: 800.00, sharePercent: 6.5, change24hPercent: 0),
        ]
        let snapshot = PortfolioWidgetSnapshot(total: 12_345.67, changeAmount: 182.4, changePercent: 1.5,
                                               currency: settings.portfolioCurrency,
                                               updatedAt: now, empty: false, totalUsdt: 13_890.12,
                                               positions: positions, otherPositions: 0, history: history)
        return PortfolioEntry(date: Date(), snapshot: snapshot, locked: false, accent: settings.accentColor,
                              display: display, theme: theme,
                              priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                              priceColorsInverted: settings.priceColorsInverted)
    }
}

struct PortfolioProvider: AppIntentTimelineProvider {
    typealias Entry = PortfolioEntry
    typealias Intent = PortfolioWidgetIntent

    func placeholder(in context: Context) -> PortfolioEntry {
        .sample()
    }

    func snapshot(for configuration: PortfolioWidgetIntent, in context: Context) async -> PortfolioEntry {
        let entry = PortfolioEntry.current(display: configuration.display, theme: configuration.theme)
        let empty = entry.snapshot?.empty ?? true
        // Galerie: leer oder mit Portfolio-Sperre das Beispiel (keine echten Werte, also auch gesperrt unbedenklich)
        if context.isPreview && (empty || entry.locked) {
            return .sample(display: configuration.display, theme: configuration.theme)
        }
        return entry
    }

    func timeline(for configuration: PortfolioWidgetIntent, in context: Context) async -> Timeline<PortfolioEntry> {
        // Alter Stand: selbst nachrechnen (Kurse 60 s zwischengespeichert), damit
        // das Widget nicht vom Öffnen der App abhängt. Gesperrt: nichts holen.
        let settings = SharedStorage.loadSettings()
        let snapshot = PortfolioWidgetStore.load()
        let age = TimeUtils.nowMillis - (snapshot?.updatedAt ?? 0)
        if !PortfolioLockPolicy.widgetLocked(lockSetting: settings.appLock) && age >= WidgetRefresh.staleAfterMillis {
            await PortfolioWidgetStore.refresh(reload: false)
        }
        let entry = PortfolioEntry.current(display: configuration.display, theme: configuration.theme)
        // Zweiter Eintrag, sobald die Momentaufnahme veraltet («veraltet · 06:42»)
        var times: [Int64] = []
        if !entry.locked, let current = entry.snapshot, !current.empty { times = [current.updatedAt] }
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        return Timeline(entries: entries, policy: .after(WidgetData.nextReload))
    }
}

// MARK: Widget

struct PortfolioWidget: Widget {
    var body: some WidgetConfiguration {
        // Gleiche `kind` wie früher (StaticConfiguration): abgelegte Widgets bleiben und
        // erhalten die Standardwerte der Einrichtung.
        AppIntentConfiguration(kind: PortfolioWidgetStore.kind,
                               intent: PortfolioWidgetIntent.self,
                               provider: PortfolioProvider()) { entry in
            PortfolioWidgetView(entry: entry)
        }
        .configurationDisplayName(L("widget_portfolio_name"))
        .description(L("widget_portfolio_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular])
        .contentMarginsDisabled()
    }
}

// MARK: Ansicht

struct PortfolioWidgetView: View {
    let entry: PortfolioEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        switch family {
        case .accessoryRectangular:
            PortfolioRectangularView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(PortfolioWidgetText.appURL)
        default:
            WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                         highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
                PortfolioHomeView(entry: entry, palette: palette, family: family)
                    .widgetURL(PortfolioWidgetText.appURL)
            }
        }
    }
}
