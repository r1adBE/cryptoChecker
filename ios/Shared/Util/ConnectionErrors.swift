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

    /// Gespeicherter Fehler → Anzeige. Nie roh: HTTP-Codes, Ausnahmetexte und Server-Antworten
    /// werden zu einem verständlichen Satz (Details nur im Bericht «Letzte Aktualisierung»).
    static func display(_ stored: String) -> String {
        if isNotTraded(stored) { return L("watch_not_traded") }
        if isOffline(stored) { return L("watch_error_offline") }
        if isRetryable(stored) { return L("error_market_unreachable_short") }
        // Schon übersetzte, eigene Texte (z. B. «Keine Kursdaten») so lassen
        let known = [L("market_data_empty_error"), L("market_unavailable_error"), L("something_went_wrong")]
        if known.contains(stored) { return stored }
        switch RefreshReportLogic.classify(stored) {
        case .NO_DATA: return L("market_data_empty_error")
        case .UNAVAILABLE: return L("market_unavailable_error")
        default: return L("something_went_wrong")
        }
    }

    /// Börse gerade nicht erreichbar (Netz, Zeitüberschreitung, HTTP-Fehler, zu viele Anfragen):
    /// ein neuer Versuch kann helfen. Nicht bei «nicht mehr gehandelt» oder unbekanntem Paar.
    static func isRetryable(_ stored: String?) -> Bool {
        guard let stored, !isNotTraded(stored) else { return false }
        if isOffline(stored) { return true }
        switch RefreshReportLogic.classify(stored) {
        case .TIMEOUT, .OFFLINE, .RATE_LIMIT, .SERVER: return true
        default: return false
        }
    }

    /// Fehler einer Abfrage direkt → Anzeige (z. B. im Hinzufügen-Tab).
    static func friendly(_ error: Error) -> String {
        display(describe(error))
    }
}
