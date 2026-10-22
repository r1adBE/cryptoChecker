import Foundation

/// Welche Anzeige und welcher Zeitraum je Ansicht angeboten werden und wirken — wie Android `PortfolioWidgetOptions`
/// (Paritäts-Fixture `widget_portfolio_options.json`). Gespeichert bleibt die Wahl unverändert, damit ein Wechsel der
/// Ansicht nichts verliert; umgedeutet wird nur beim Zeichnen.
///  - Liste: hat keine USDT-Zeile, darum nur «Nur USDT» und «Umrechnung»; «Umrechnung + USDT» wirkt wie «Umrechnung».
///    Den Zeitraum zeigt die Einrichtung nicht.
///  - Übersicht: «Heute» und «7 T» stehen fest als Kennzahlen darunter, darum nur 30 T und 1 J; 24 h und 7 T wirken
///    wie 30 T (`overviewDefaultRange`).
///  - Chart: alle Anzeigen und Zeiträume.
/// Die Einrichtung (App Intents) kann einzelne Werte einer Auswahl nicht je Ansicht ausblenden; sie nennt die Regel
/// deshalb in der Beschreibung der Parameter (`PortfolioWidgetIntent`).
enum PortfolioWidgetOptions {
    /// Übersicht ohne passende Wahl (auch neue Widgets mit 24 h als Standard).
    static let overviewDefaultRange: PortfolioWidgetRange = .month

    /// Angebotene Anzeigen der Ansicht, in dieser Reihenfolge.
    static func displays(_ view: PortfolioWidgetMode) -> [PortfolioWidgetDisplay] {
        switch view {
        case .list: [.usdt, .conversion]
        case .overview, .chart: PortfolioWidgetDisplay.allCases
        }
    }

    /// Angebotene Zeiträume der Ansicht; leer = kein Zeitraum.
    static func ranges(_ view: PortfolioWidgetMode) -> [PortfolioWidgetRange] {
        switch view {
        case .overview: [.month, .year]
        case .chart: PortfolioWidgetRange.allCases
        case .list: []
        }
    }

    /// Wirksame Anzeige der gespeicherten Wahl in der Ansicht.
    static func effectiveDisplay(_ view: PortfolioWidgetMode, _ chosen: PortfolioWidgetDisplay) -> PortfolioWidgetDisplay {
        displays(view).contains(chosen) ? chosen : .conversion
    }

    /// Wirksamer Zeitraum der gespeicherten Wahl in der Ansicht (Liste: unverändert).
    static func effectiveRange(_ view: PortfolioWidgetMode, _ chosen: PortfolioWidgetRange) -> PortfolioWidgetRange {
        switch view {
        case .overview: ranges(view).contains(chosen) ? chosen : overviewDefaultRange
        case .chart, .list: chosen
        }
    }
}
