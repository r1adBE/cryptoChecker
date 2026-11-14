import Foundation

/// Die kleinen Symbole einer Zeile der Merkliste, in der Reihenfolge der Zeile: der Stern (Favorit), die Art (`AssetKind`:
/// Raute, Bank, «α»), die Kursmitteilung (Glocke an / durchgestrichen aus), die Alarmglocke mit Wellen und der Zahl der
/// aktiven Alarme, zuletzt Notiz und Warn-Dreieck (Kurs veraltet bzw. Börse nicht erreichbar). Das Blatt «Symbole erklärt» zeigt genau diese Liste — wie Android `WatchlistSymbol`.
enum WatchlistSymbol: String, CaseIterable, Sendable {
    case FAVORITE, TOKEN, TRADFI, ALPHA, PRICE_NOTIFICATION_ON, PRICE_NOTIFICATION_OFF, ACTIVE_ALARMS, NOTE, WARNING

    /// Art des Paars, für die das Symbol steht; nil bei den übrigen (Stern, Glocken, Notiz, Warnung).
    var kind: AssetKind? {
        switch self {
        case .TOKEN: return .TOKEN
        case .TRADFI: return .TRADFI
        case .ALPHA: return .ALPHA
        case .FAVORITE, .PRICE_NOTIFICATION_ON, .PRICE_NOTIFICATION_OFF, .ACTIVE_ALARMS, .NOTE, .WARNING: return nil
        }
    }

    /// Text der Zeile im Blatt (Schlüssel der Übersetzung) — dieselben wie Android.
    var textKey: String {
        switch self {
        case .FAVORITE: return "a11y_favorite"
        case .TOKEN: return "asset_kind_token"
        case .TRADFI: return "asset_kind_tradfi"
        case .ALPHA: return "asset_kind_alpha"
        case .PRICE_NOTIFICATION_ON: return "a11y_price_notification_on"
        case .PRICE_NOTIFICATION_OFF: return "a11y_price_notification_off"
        case .ACTIVE_ALARMS: return "watchlist_symbols_legend_alarms"
        case .NOTE: return "note_title"
        case .WARNING: return "watchlist_symbols_legend_warning"
        }
    }
}
