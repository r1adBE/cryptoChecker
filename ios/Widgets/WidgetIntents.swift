import AppIntents
import WidgetKit

// MARK: Hintergrund je Widget — wie `WidgetTheme` (System / Dunkel / Hell)

enum WidgetThemeOption: String, AppEnum {
    case system, dark, light

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_background"

    static let caseDisplayRepresentations: [WidgetThemeOption: DisplayRepresentation] = [
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

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_chart_range"

    static let caseDisplayRepresentations: [WidgetChartRangeOption: DisplayRepresentation] = [
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

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_chart_type"

    static let caseDisplayRepresentations: [WidgetChartTypeOption: DisplayRepresentation] = [
        .candles: "widget_chart_candles",
        .line: "widget_chart_line",
    ]
}

// MARK: Anzeige (Portfolio-Widget) — wie `PortfolioDisplay` (Android)

/// Nur USDT, nur die Umrechnungswährung oder Umrechnung mit «≈ … USDT» darunter.
enum PortfolioDisplayOption: String, AppEnum {
    case usdt, conversion, conversionUsdt

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_portfolio_display"

    static let caseDisplayRepresentations: [PortfolioDisplayOption: DisplayRepresentation] = [
        .usdt: "widget_portfolio_display_usdt",
        .conversion: "widget_portfolio_display_conversion",
        .conversionUsdt: "widget_portfolio_display_conversion_usdt",
    ]

    /// Gleiche Anzeige in der geteilten Rechnung.
    var display: PortfolioWidgetDisplay {
        switch self {
        case .usdt: .usdt
        case .conversion: .conversion
        case .conversionUsdt: .conversionUsdt
        }
    }
}

// MARK: Ansicht (Portfolio-Widget) — wie `PortfolioWidgetView` (Android)

/// Übersicht (wie die Gesamtkarte im Portfolio-Tab), Chart (bisher, Standard) oder Liste der Positionen.
enum PortfolioViewOption: String, AppEnum {
    case overview, chart, list

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_portfolio_view"

    static let caseDisplayRepresentations: [PortfolioViewOption: DisplayRepresentation] = [
        .overview: "widget_portfolio_view_overview",
        .chart: "widget_portfolio_view_chart",
        .list: "widget_portfolio_view_list",
    ]

    /// Gleiche Ansicht in der geteilten Rechnung.
    var view: PortfolioWidgetMode {
        switch self {
        case .overview: .overview
        case .chart: .chart
        case .list: .list
        }
    }
}

// MARK: Zeitraum (Portfolio-Widget) — wie `PortfolioChartRange` (Android)

/// Zeitraum für Veränderung und Chart: 24 h (Stundenverlauf) oder 7 T / 30 T / 1 J (Tagesverlauf).
enum PortfolioChartRangeOption: String, AppEnum {
    case day, week, month, year

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_portfolio_period"

    static let caseDisplayRepresentations: [PortfolioChartRangeOption: DisplayRepresentation] = [
        .day: "widget_range_24h",
        .week: "widget_range_7d",
        .month: "widget_range_30d",
        .year: "widget_range_1y",
    ]

    /// Gleicher Zeitraum in der geteilten Rechnung.
    var range: PortfolioWidgetRange {
        switch self {
        case .day: .day
        case .week: .week
        case .month: .month
        case .year: .year
        }
    }
}

// MARK: Paar-Auswahl (Einzel-Widget)

/// Ein Paar der Merkliste als wählbarer Wert in der Widget-Einrichtung.
struct WidgetWatchEntity: AppEntity {
    /// `Watch.id` als Text.
    let id: String
    let title: String
    let subtitle: String

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "single_widget_choose"
    static let defaultQuery = WidgetWatchQuery()

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
    /// Angezeigter Name, wenn die Gruppe inzwischen anders heisst (`WidgetGroupSelection`); sonst nil.
    var renamedTo: String? = nil

    /// Länger als ein Gruppenname sein darf (24), kann also nie mit einer Gruppe kollidieren.
    static let allId = "cryptochecker.widget.group.all"
    /// «FAV»: die Favoriten (`WatchFilter.favorites`).
    static let favoritesId = WatchFilter.favorites
    /// «Token»: alle Paare ausser «TradFi» (`WatchFilter.tokens`).
    static let tokensId = WatchFilter.tokens
    /// «Alpha»: nur Alpha-Tokens (`WatchFilter.alpha`).
    static let alphaId = WatchFilter.alpha

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "group_title"
    static let defaultQuery = WidgetGroupQuery()

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)")
    }

    /// Anzeigename: «Alle», «Favoriten», «Token», «α Alpha-Tokens» (wie der Chip der App) oder der
    /// Gruppenname.
    private var title: String {
        if isAll { return L("group_all") }
        if id == Self.favoritesId { return L("group_favorites") }
        if id == Self.tokensId { return L("group_tokens") }
        if id == Self.alphaId { return "α " + L("group_alpha") }
        return renamedTo ?? id
    }

    var isAll: Bool { id == Self.allId }

    /// Gruppenname für den Filter; nil = alle.
    var groupName: String? { isAll ? nil : id }

    static var all: WidgetGroupEntity { WidgetGroupEntity(id: allId) }
    static var favorites: WidgetGroupEntity { WidgetGroupEntity(id: favoritesId) }
    static var tokens: WidgetGroupEntity { WidgetGroupEntity(id: tokensId) }
    static var alpha: WidgetGroupEntity { WidgetGroupEntity(id: alphaId) }
}

/// «Alle», «FAV», «Token» (nur mit TradFi- und anderen Paaren), «Alpha» (nur mit Alpha- und anderen
/// Paaren) und die vorhandenen Gruppen — gleiche Reihenfolge wie die Chips der App und wie
/// `WidgetGroupSelection.options` (Android). Bei jedem Öffnen der Einrichtung frisch gelesen.
struct WidgetGroupQuery: EntityQuery {
    init() {}

    func entities(for identifiers: [String]) async throws -> [WidgetGroupEntity] {
        // Auch Gruppen, die es nicht mehr gibt, zurückgeben: Das Widget zeigt dann
        // alle Paare (siehe `WidgetData.watches(group:)`), die Einrichtung bleibt lesbar.
        // Umbenannte Gruppen zeigen den neuen Namen (das Widget folgt ihm ebenso).
        let groupNames = SharedStorage.loadSnapshot().watches.map(\.groupName)
        let renames = WidgetGroupSelection.renames
        return identifiers.map { id in
            var entity = WidgetGroupEntity(id: id)
            if let resolved = WidgetGroupSelection.resolve(id, groupNames: groupNames, renames: renames), resolved != id {
                entity.renamedTo = resolved
            }
            return entity
        }
    }

    func suggestedEntities() async throws -> [WidgetGroupEntity] {
        let watches = SharedStorage.loadSnapshot().watches
        let tradFiPairs = CoinLogoStore.tradFiPairs
        let alphaSymbols = CoinLogoStore.alphaSymbols
        let alphaFlags = watches.map { $0.assetKind(tradFiPairs: tradFiPairs, alphaSymbols: alphaSymbols) == .ALPHA }
        let options = WidgetGroupSelection.options(groupNames: watches.map(\.groupName), alphaFlags: alphaFlags)
        // nil = «Alle»; als Closure: `init(id:)` lässt sich seit `renamedTo` (Standardwert) nicht direkt referenzieren
        return options.map { option in option.map { WidgetGroupEntity(id: $0) } ?? .all }
    }

    func defaultResult() async -> WidgetGroupEntity? { .all }
}

// MARK: Einrichtung

/// Merkliste-Widget: Hintergrund und optional eine Gruppe; die Farbe folgt der App.
struct WatchlistWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "widget_configure_title"
    static let description = IntentDescription("widget_description")

    @Parameter(title: "widget_background", default: .system)
    var theme: WidgetThemeOption

    /// nil oder «Alle» = alle Paare.
    @Parameter(title: "group_title", description: "widget_group_hint")
    var group: WidgetGroupEntity?

    init() {}
}

/// Einzel-Widget: Paar, Chart-Zeitraum, Chart-Art und Hintergrund.
struct SingleWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "single_widget_title"
    static let description = IntentDescription("single_widget_description")

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

/// Portfolio-Widget: Ansicht, Anzeige, Zeitraum, Chart-Art und Hintergrund (wie die Einrichtung in Android).
/// Standard: Chart, «Umrechnung» (bei einem USD-Stablecoin als Umrechnungswährung zeigt das Widget ohnehin «Nur USDT»,
/// `PortfolioWidgetChart.effectiveDisplay`), 24 h, Linie. Bereits abgelegte Widgets ohne Ansicht zeigen
/// den Chart wie bisher; mit früherer Wahl «Umrechnung + USDT» behalten sie diese. Die Chart-Art
/// erscheint nur bei der Ansicht «Chart», der Zeitraum nicht bei «Liste» (`parameterSummary`).
struct PortfolioWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "widget_portfolio_name"
    static let description = IntentDescription("widget_portfolio_description")

    @Parameter(title: "widget_portfolio_view", default: .chart)
    var view: PortfolioViewOption

    @Parameter(title: "widget_portfolio_display", description: "widget_portfolio_display_hint", default: .conversion)
    var display: PortfolioDisplayOption

    @Parameter(title: "widget_portfolio_period", default: .day)
    var range: PortfolioChartRangeOption

    @Parameter(title: "widget_chart_type", default: .line)
    var chartType: WidgetChartTypeOption

    @Parameter(title: "widget_background", default: .system)
    var theme: WidgetThemeOption

    /// Chart-Art nur bei der Ansicht «Chart», Zeitraum nicht bei «Liste» (`PortfolioWidgetMode.usesRange`;
    /// die Wahl bleibt gespeichert). Dokumentierte Form `Summary("…") { … }` (wie WWDC23 «Explore
    /// enhancements to App Intents»): die Ansicht in der Zeile, die übrigen Parameter darunter — gleiche
    /// Reihenfolge wie zuvor; verschachteltes `When` im `otherwise`.
    static var parameterSummary: some ParameterSummary {
        When(\.$view, .equalTo, PortfolioViewOption.chart) {
            Summary("\(\.$view)") {
                \.$display
                \.$range
                \.$chartType
                \.$theme
            }
        } otherwise: {
            When(\.$view, .equalTo, PortfolioViewOption.list) {
                Summary("\(\.$view)") {
                    \.$display
                    \.$theme
                }
            } otherwise: {
                Summary("\(\.$view)") {
                    \.$display
                    \.$range
                    \.$theme
                }
            }
        }
    }

    init() {}
}

// MARK: Aktualisieren-Knopf

/// Holt alle Kurse (mit Kurs-Mitteilungen und Kursalarmen) und zeichnet die Widgets neu. Alarme mit
/// Zusatzdaten prüfen App und Hintergrund-Aufgabe — warum, siehe `WidgetRefresh`.
/// Gleiche 15-s-Sperre wie die App (`WidgetRefreshState`, wie `PriceWidgetProvider` in Android): Läuft schon
/// eine, nichts tun; eben erst fertig, nichts holen, sondern die Merkliste-Widgets mit Countdown zeichnen.
struct RefreshPricesIntent: AppIntent {
    static let title: LocalizedStringResource = "action_refresh"
    static let isDiscoverable: Bool = false

    init() {}

    func perform() async throws -> some IntentResult {
        let tap = WidgetRefreshState.onTap(running: WidgetRefresh.isRunning,
                                           lastFinishedAt: SharedStorage.lastRefreshAt,
                                           now: TimeUtils.nowMillis)
        switch tap {
        case .start:
            await WidgetRefresh.run()
            WidgetCenter.shared.reloadAllTimelines()
        case .running:
            break
        case .blocked(let until):
            // Tipp auf den laufenden Countdown: der steht schon (samt Eintrag zum Ende)
            guard WidgetRefresh.blockedUntil != until else { break }
            WidgetRefresh.blockedUntil = until
            WidgetData.reloadKinds([WidgetData.watchlistKind], reason: "Countdown")
        }
        return .result()
    }
}
