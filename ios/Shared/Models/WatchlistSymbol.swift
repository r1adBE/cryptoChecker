import Foundation

/// Die kleinen Symbole unter einem Paar der Merkliste, in der Reihenfolge der Zeile: zuerst die Art (`AssetKind`:
/// Raute, Bank, «α»), dann die Kursmitteilung (Glocke an / durchgestrichen aus), zuletzt die Alarmglocke mit Wellen und
/// der Zahl der aktiven Alarme. Das Blatt «Symbole erklärt» zeigt genau diese Liste — wie Android `WatchlistSymbol`.
enum WatchlistSymbol: String, CaseIterable, Sendable {
    case TOKEN, TRADFI, ALPHA, PRICE_NOTIFICATION_ON, PRICE_NOTIFICATION_OFF, ACTIVE_ALARMS

    /// Art des Paars, für die das Symbol steht; nil bei den Glocken.
    var kind: AssetKind? {
        switch self {
        case .TOKEN: return .TOKEN
        case .TRADFI: return .TRADFI
        case .ALPHA: return .ALPHA
        case .PRICE_NOTIFICATION_ON, .PRICE_NOTIFICATION_OFF, .ACTIVE_ALARMS: return nil
        }
    }

    /// Text der Zeile im Blatt (Schlüssel der Übersetzung) — dieselben wie Android.
    var textKey: String {
        switch self {
        case .TOKEN: return "asset_kind_token"
        case .TRADFI: return "asset_kind_tradfi"
        case .ALPHA: return "asset_kind_alpha"
        case .PRICE_NOTIFICATION_ON: return "a11y_price_notification_on"
        case .PRICE_NOTIFICATION_OFF: return "a11y_price_notification_off"
        case .ACTIVE_ALARMS: return "watchlist_symbols_legend_alarms"
        }
    }
}
