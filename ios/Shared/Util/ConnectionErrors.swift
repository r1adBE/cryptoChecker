import Foundation

/// Netzwerkfehler erkennen und verständlich anzeigen («Keine Verbindung»).
enum ConnectionErrors {
    /// Gespeicherter Fehlertext für «kein Netz» — sprachunabhängig, die Anzeige übersetzt ihn.
    static let offlineMarker = "NETWORK_OFFLINE"

    /// Fehler → zu speichernder Text: Netzwerkprobleme werden zum Marker.
    static func describe(_ error: Error) -> String {
        if let e = error as? URLError {
            switch e.code {
            case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .timedOut,
                 .networkConnectionLost, .dnsLookupFailed, .internationalRoamingOff,
                 .dataNotAllowed, .secureConnectionFailed, .cannotLoadFromNetwork:
                return offlineMarker
            default:
                break
            }
        }
        return error.localizedDescription
    }

    static func isOffline(_ stored: String?) -> Bool { stored == offlineMarker }

    /// Paar wird an der Börse nicht mehr gehandelt — ein Zustand, kein Fehler.
    static func isNotTraded(_ stored: String?) -> Bool { NotTraded.isMarker(stored) }

    /// Gespeicherter Fehler → Anzeige.
    static func display(_ stored: String) -> String {
        if isNotTraded(stored) { return L("watch_not_traded") }
        return isOffline(stored) ? L("watch_error_offline") : stored
    }
}
