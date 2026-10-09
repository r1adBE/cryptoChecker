import AppIntents
import WidgetKit

// MARK: Hintergrund je Widget — wie `WidgetTheme` (System / Dunkel / Hell)

enum WidgetThemeOption: String, AppEnum {
    case system, dark, light

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "widget_background"

    static var caseDisplayRepresentations: [WidgetThemeOption: DisplayRepresentation] = [
        .system: "theme_system",
        .dark: "theme_dark",
        .light: "theme_light",
    ]

    /// Dunkel für dieses Widget? «System» folgt dem Gerät.
    func isDark(systemDark: Bool) -> Bool {
        switch self {
        case .system: systemDark
        case .dark: true
        case .light: false
        }
    }
}

// MARK: Chart-Zeitraum (Einzel-Widget) — wie `WidgetChartRange`

/// Zeitraum des Mini-Charts: Kerzenintervall und Anzahl.
/// 24 h = 24 × 1 h, 7 Tage = 42 × 4 h, 30 Tage = 30 × 1 Tag.
enum WidgetChartRangeOption: String, AppEnum {
    case day, week, month

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "widget_chart_range"

    static var caseDisplayRepresentations: [WidgetChartRangeOption: DisplayRepresentation] = [
        .day: "widget_range_24h",
        .week: "widget_range_7d",
        .month: "widget_range_30d",
    ]

    // Kerzenintervall, Anzahl und Kerzenlänge wie der geteilte Chart (`PriceChartRange`)
    var candleInterval: CandleInterval { chartRange.candleInterval }

    var limit: Int { chartRange.limit }

    /// Dauer einer Kerze in ms (für die senkrechten Zeit-Linien).
    var candleMillis: Int64 { chartRange.candleMillis }

    /// Kurzform neben der Uhrzeit, z. B. «14:05 · 7T».
    var shortLabel: String { L(chartRange.shortLabelKey) }
}

// MARK: Chart-Art (Einzel-Widget) — wie `WidgetChartType`

/// Kerzen (Standard, auch für bestehende Widgets ohne Wert) oder Linie.
enum WidgetChartTypeOption: String, AppEnum {
    case candles, line

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "widget_chart_type"

    static var caseDisplayRepresentations: [WidgetChartTypeOption: DisplayRepresentation] = [
        .candles: "widget_chart_candles",
        .line: "widget_chart_line",
    ]
}

// MARK: Anzeige (Portfolio-Widget) — wie `WidgetPrefs.getPortfolioShowUsdt`

/// Nur die Umrechnungswährung oder zusätzlich «≈ … USDT» unter dem Gesamtwert.
enum PortfolioDisplayOption: String, AppEnum {
    case conversion, conversionUsdt

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "widget_portfolio_display"

    static var caseDisplayRepresentations: [PortfolioDisplayOption: DisplayRepresentation] = [
        .conversion: "widget_portfolio_display_conversion",
        .conversionUsdt: "widget_portfolio_display_conversion_usdt",
    ]

    var showsUsdt: Bool { self == .conversionUsdt }
}

// MARK: Paar-Auswahl (Einzel-Widget)

/// Ein Paar der Merkliste als wählbarer Wert in der Widget-Einrichtung.
struct WidgetWatchEntity: AppEntity {
    /// `Watch.id` als Text.
    let id: String
    let title: String
    let subtitle: String

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "single_widget_choose"
    static var defaultQuery = WidgetWatchQuery()

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(subtitle)")
    }

    init(watch: Watch) {
        id = "\(watch.id)"
        title = watch.displayName
        subtitle = watch.marketName
    }

    var watchId: Int64? { Int64(id) }
}

/// Liest die Paare aus dem gemeinsamen Speicher — gleiche Reihenfolge wie die App.
struct WidgetWatchQuery: EntityQuery {
    init() {}

    func entities(for identifiers: [String]) async throws -> [WidgetWatchEntity] {
        let watches = WidgetData.sortedWatches()
        return identifiers.compactMap { id in
            watches.first { "\($0.id)" == id }.map(WidgetWatchEntity.init(watch:))
        }
    }

    func suggestedEntities() async throws -> [WidgetWatchEntity] {
        WidgetData.sortedWatches().map(WidgetWatchEntity.init(watch:))
    }

    /// Beim Ablegen gleich das oberste Paar vorauswählen.
    func defaultResult() async -> WidgetWatchEntity? {
        WidgetData.sortedWatches().first.map(WidgetWatchEntity.init(watch:))
    }
}

// MARK: Gruppen-Auswahl (Merkliste-Widget)

/// Eine Gruppe der Merkliste oder «Alle» als wählbarer Wert in der Widget-Einrichtung.
struct WidgetGroupEntity: AppEntity {
    /// Gruppenname; `allId` = alle Paare.
    let id: String

    /// Länger als ein Gruppenname sein darf (24), kann also nie mit einer Gruppe kollidieren.
    static let allId = "cryptochecker.widget.group.all"
    /// «FAV»: die Favoriten (`WatchFilter.favorites`).
    static let favoritesId = WatchFilter.favorites

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "group_title"
    static var defaultQuery = WidgetGroupQuery()

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(isAll ? L("group_all") : (id == Self.favoritesId ? WatchFilter.favoritesLabel : id))")
    }

    var isAll: Bool { id == Self.allId }

    /// Gruppenname für den Filter; nil = alle.
    var groupName: String? { isAll ? nil : id }

    static var all: WidgetGroupEntity { WidgetGroupEntity(id: allId) }
    static var favorites: WidgetGroupEntity { WidgetGroupEntity(id: favoritesId) }
}

/// «Alle», «FAV» und die vorhandenen Gruppen — gleiche Reihenfolge wie die Chips der App.
struct WidgetGroupQuery: EntityQuery {
    init() {}

    func entities(for identifiers: [String]) async throws -> [WidgetGroupEntity] {
        // Auch Gruppen, die es nicht mehr gibt, zurückgeben: Das Widget zeigt dann
        // alle Paare (siehe `WidgetData.watches(group:)`), die Einrichtung bleibt lesbar.
        identifiers.map(WidgetGroupEntity.init(id:))
    }

    func suggestedEntities() async throws -> [WidgetGroupEntity] {
        [WidgetGroupEntity.all, .favorites] + WidgetData.groups().map(WidgetGroupEntity.init(id:))
    }

    func defaultResult() async -> WidgetGroupEntity? { .all }
}

// MARK: Einrichtung

/// Merkliste-Widget: Hintergrund und optional eine Gruppe; die Farbe folgt der App.
struct WatchlistWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "widget_configure_title"
    static var description = IntentDescription("widget_description")

    @Parameter(title: "widget_background", default: .system)
    var theme: WidgetThemeOption

    /// nil oder «Alle» = alle Paare.
    @Parameter(title: "group_title", description: "widget_group_hint")
    var group: WidgetGroupEntity?

    init() {}
}

/// Einzel-Widget: Paar, Chart-Zeitraum, Chart-Art und Hintergrund.
struct SingleWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "single_widget_title"
    static var description = IntentDescription("single_widget_description")

    @Parameter(title: "single_widget_choose")
    var watch: WidgetWatchEntity?

    /// Bestehende Widgets ohne Wert zeigen wie bisher 24 h.
    @Parameter(title: "widget_chart_range", default: .day)
    var range: WidgetChartRangeOption

    /// Bestehende Widgets ohne Wert zeigen Kerzen (neuer Standard).
    @Parameter(title: "widget_chart_type", default: .candles)
    var chartType: WidgetChartTypeOption

    @Parameter(title: "widget_background", default: .system)
    var theme: WidgetThemeOption

    init() {}
}

/// Portfolio-Widget: Anzeige (≈ USDT) und Hintergrund. Bereits abgelegte Widgets
/// (früher ohne Einrichtung) erhalten die Standardwerte: «Umrechnung + USDT», System.
struct PortfolioWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "widget_portfolio_name"
    static var description = IntentDescription("widget_portfolio_description")

    @Parameter(title: "widget_portfolio_display", description: "widget_portfolio_display_hint", default: .conversionUsdt)
    var display: PortfolioDisplayOption

    @Parameter(title: "widget_background", default: .system)
    var theme: WidgetThemeOption

    init() {}
}

// MARK: Aktualisieren-Knopf

/// Holt alle Kurse (mit Alarmen und Mitteilungen) und zeichnet die Widgets neu.
struct RefreshPricesIntent: AppIntent {
    static var title: LocalizedStringResource = "action_refresh"
    static var isDiscoverable: Bool = false

    init() {}

    func perform() async throws -> some IntentResult {
        await WidgetRefresh.run()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
