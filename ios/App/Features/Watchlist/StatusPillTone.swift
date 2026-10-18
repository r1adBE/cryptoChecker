import Foundation

/// Farbe der Statuszeile über der Merkliste (reine Logik, wie `StatusPillTone.kt`). Gleiche Reihenfolge wie ihr Text:
/// keine Verbindung (alle Paare scheitern am Netz) ist ein Fehler, «x veraltet» eine Warnung in Bernstein wie die Zeile
/// des veralteten Paars, gescheiterte Paare wieder ein Fehler. Kein gehandeltes Paar bzw. Gerät offline: neutral.
enum StatusPillTone: Equatable {
    case neutral, error, warning, normal

    static func of(none: Bool, deviceOffline: Bool, offline: Bool, stale: Int, failed: Int) -> StatusPillTone {
        if none || deviceOffline { return .neutral }
        if offline { return .error }
        if stale > 0 { return .warning }
        if failed > 0 { return .error }
        return .normal
    }
}
