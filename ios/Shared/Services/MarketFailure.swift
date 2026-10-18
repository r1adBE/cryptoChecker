import Foundation

/// Art eines gescheiterten Börsenabrufs, festgestellt dort, wo der Fehler entsteht (`MarketFailure.of`). Wandert im
/// Speicher neben dem Fehlertext mit (`BulkTickers.marketFailure`, `PriceRefresher.Fetched.marketFailure`);
/// gespeichert wird weiterhin nur der Text (`StoredError.sanitize`).
///
/// Warum neben dem Text: Die Einordnung (`RefreshReportLogic.classify`) suchte bisher Stichwörter («ssl», «eof»,
/// «timeout» …) im Text. Mit der Art entscheidet der Typ; die Stichwortsuche bleibt nur für Texte ohne Art
/// (gespeicherte ältere Fehler, eigene Marker, Meldungen der Börse). Wie `MarketFailure.kt` (Android).
///
/// Nur Foundation: auch im Widget nutzbar.
enum MarketFailure: Equatable, Sendable {
    /// Zeitüberschreitung beim Verbinden oder Lesen.
    case timeout
    /// Keine Verbindung: Host unbekannt, Verbindung abgelehnt/abgebrochen, Netz weg.
    case offline
    /// TLS/SSL gescheitert — zählt wie bisher als «kein Netz».
    case ssl
    /// HTTP-Fehlercode ohne Bremse (z. B. 400, 404, 500).
    case httpStatus(Int)
    /// Bremse der Börse: 429 (auch erkannt über `RateLimitSignal`) oder 418 «gesperrt».
    case rateLimited(retryAfterSeconds: Int64?, code: Int)
    /// Antwort unlesbar (JSON, Format).
    case parse
    /// Die Börse sagt ausdrücklich, dass sie das Paar nicht kennt (`NotTraded`).
    case unknownInstrument
    /// Andere Fehlermeldung der Börse.
    case exchange(String)
    /// Nichts davon.
    case other

    /// Ursache für Bericht, Pause und Anzeige; nil = die Art legt sie nicht fest, dann entscheidet der Text.
    /// parse, exchange und other tragen ihre Aussage im Text: dort bleibt die Einordnung wie bisher.
    var refreshFailure: RefreshFailure? {
        switch self {
        case .timeout: return .TIMEOUT
        case .offline, .ssl: return .OFFLINE
        case .httpStatus(let code): return ExchangeBackoff.isRateLimitCode(code) ? .RATE_LIMIT : .SERVER
        case .rateLimited: return .RATE_LIMIT
        case .unknownInstrument: return .OTHER
        case .parse, .exchange, .other: return nil
        }
    }

    /// HTTP-Status der Art (Fehlercode oder Bremse), sonst nil.
    var httpStatus: Int? {
        switch self {
        case .httpStatus(let code): return code
        case .rateLimited(_, let code): return code
        default: return nil
        }
    }

    /// Eigene Kennungen, die der Text einordnet (keine Meldung der Börse).
    private static let ownMarkers: Set<String> = [
        "Response data is empty", "Parsed ticker has no data", "Unknown error (empty)", "Market unavailable",
    ]

    /// Art eines Fehlers. Nur Typen mit eindeutiger Aussage bekommen eine feste Art; der Rest wird `.other` (bzw.
    /// `.exchange`) und bleibt bei der Einordnung über den Text. URL-Fehler wie `ConnectionErrors.describe`:
    /// dieselben Codes, die dort «kein Netz» werden, hier `.offline` — ausser Zeit (`.timeout`) und TLS (`.ssl`).
    static func of(_ error: Error) -> MarketFailure {
        if let http = error as? HttpMarketError {
            return ExchangeBackoff.isRateLimitCode(http.httpCode)
                ? .rateLimited(retryAfterSeconds: http.retryAfterSeconds, code: http.httpCode)
                : .httpStatus(http.httpCode)
        }
        if let url = error as? URLError {
            switch url.code {
            case .timedOut:
                return .timeout
            case .secureConnectionFailed:
                return .ssl
            case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost,
                 .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed, .cannotLoadFromNetwork:
                return .offline
            default:
                return .other
            }
        }
        if error is JSONError || error is DecodingError { return .parse }
        if let friendly = error as? UserFriendlyMarketError { return fromExchangeMessage(friendly.message) }
        return .other
    }

    /// Meldung der Börse: eigene Kennungen → `.other`; «Paar unbekannt» → `.unknownInstrument`; sonst `.exchange`.
    static func fromExchangeMessage(_ message: String) -> MarketFailure {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || ownMarkers.contains(text) { return .other }
        if RefreshReportLogic.classify(text) == .OTHER && NotTraded.isUnknownInstrument(text) {
            return .unknownInstrument
        }
        return .exchange(message)
    }
}
