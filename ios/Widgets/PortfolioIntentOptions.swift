import AppIntents

// Auswahl «Ansicht» und «Buchungen» des Portfolio-Widgets (`PortfolioWidgetIntent`); eigene Datei neben
// `WidgetIntents.swift` (Zeilengrenze).

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

// MARK: Buchungen (Portfolio-Widget, Liste) — wie `PortfolioWidgetEntryFilter` (Android)

/// Welche Buchungen die Liste unter den Coins zeigt, falls Platz bleibt (voreingestellt «Aus»).
enum PortfolioEntriesOption: String, AppEnum {
    case off, buys, sells, both

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "widget_portfolio_entries"

    static let caseDisplayRepresentations: [PortfolioEntriesOption: DisplayRepresentation] = [
        .off: "widget_portfolio_entries_off",
        .buys: "widget_portfolio_entries_buys",
        .sells: "widget_portfolio_entries_sells",
        .both: "widget_portfolio_entries_both",
    ]

    /// Gleiche Wahl in der geteilten Rechnung.
    var filter: PortfolioWidgetEntryFilter { PortfolioWidgetEntryFilter.from(key: rawValue) }
}
