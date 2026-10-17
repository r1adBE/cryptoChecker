import Foundation

/// Portfolio ausgeschaltet (ab Werk seit Build 44, `AppSettings.portfolioEnabled`): was trotzdem geht
/// und was das Portfolio-Widget dann zeigt — Spiegel von `PortfolioOff.kt`.
enum PortfolioOff {
    /// Schaltet das Speichern einer Transaktion das Portfolio ein? Nur eine neue (`isNew`, id 0),
    /// solange es aus ist — «Zum Portfolio hinzufügen» steht auch mit ausgeschaltetem Portfolio im
    /// Aktionsblatt. Abbrechen speichert nichts und schaltet nichts ein.
    static func enableOnSave(isNew: Bool, portfolioEnabled: Bool) -> Bool { isNew && !portfolioEnabled }

    /// Build 46: Nach dem Speichern der ERSTEN Transaktion (vorher keine) mit ausgeschaltetem Portfolio fragen
    /// «Portfolio-Tab einschalten?» statt still einzuschalten (Merkliste, Markt). «Später» behält die Transaktion,
    /// der Tab bleibt aus; danach wird nicht mehr gefragt — wie `PortfolioOff.kt`.
    static func askEnableOnSave(isNew: Bool, portfolioEnabled: Bool, hadTransactions: Bool) -> Bool {
        enableOnSave(isNew: isNew, portfolioEnabled: portfolioEnabled) && !hadTransactions
    }

    /// «Zum Portfolio hinzufügen» im Aktionsblatt: immer, auch mit Portfolio aus — nur nicht in der Vorschau.
    static func offerAddToPortfolio(preview: Bool) -> Bool { !preview }

    /// Was ein abgelegtes Portfolio-Widget zeigt.
    enum WidgetState: Equatable, Sendable {
        /// Portfolio aus: «Portfolio ist aus» und wo man es einschaltet.
        case off
        /// Portfolio-Sperre an: Schloss und «Gesperrt – in der App entsperren».
        case locked
        /// Noch keine Aufnahme bzw. keine Bestände.
        case empty
        /// Werte.
        case values
    }

    /// Zustand des Widgets; «aus» geht vor der Sperre (zeigt ohnehin keine Werte).
    static func widgetState(portfolioEnabled: Bool, locked: Bool, empty: Bool) -> WidgetState {
        if !portfolioEnabled { return .off }
        if locked { return .locked }
        return empty ? .empty : .values
    }

    /// Wohin das Tippen auf das Portfolio-Widget führt.
    enum WidgetTarget: Equatable, Sendable {
        /// Portfolio-Tab.
        case portfolioTab
        /// Einstellungen › Erweitert › Portfolio (dort der Schalter).
        case portfolioSettings
    }

    /// Ziel beim Öffnen aus dem Widget — entschieden beim Tippen (das Widget kann einen alten Stand zeigen).
    static func widgetTarget(portfolioEnabled: Bool) -> WidgetTarget {
        portfolioEnabled ? .portfolioTab : .portfolioSettings
    }
}
