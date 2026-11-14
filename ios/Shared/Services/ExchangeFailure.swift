import Foundation

/// Art eines gescheiterten Börsenabrufs, festgestellt dort, wo der Fehler entsteht (`ExchangeFailure.of`). Wandert im
/// Speicher neben dem Fehlertext mit (`BulkTickers.exchangeFailure`, `PriceRefresher.Fetched.exchangeFailure`);
/// gespeichert wird nur der Text (`StoredError.sanitize`).
///
/// Warum neben dem Text: Die Einordnung (`RefreshReportLogic.classify`) müsste sonst Stichwörter («ssl», «eof»,
/// «timeout» …) im Text suchen. Mit der Art entscheidet der Typ; die Stichwortsuche bleibt nur für Texte ohne Art
/// (gespeicherte Fehlertexte, eigene Marker, Meldungen der Börse). Wie `ExchangeFailure.kt` (Android).
///
/// Nur Foundation: auch im Widget nutzbar.
enum ExchangeFailure: Equatable, Sendable {
    /// Zeitüberschreitung beim Verbinden oder Lesen.
    case timeout
    /// Keine Verbindung: Host unbekannt, Verbindung abgelehnt/abgebrochen, Netz weg.
    case offline
    /// TLS/SSL gescheitert — zählt als «kein Netz».
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
    case message(String)
    /// Nichts davon.
    case other

    /// Ursache für Bericht, Pause und Anzeige; nil = die Art legt sie nicht fest, dann entscheidet der Text.
    /// parse, message und other tragen ihre Aussage im Text: dort entscheidet der Text.
    var refreshFailure: RefreshFailure? {
        switch self {
        case .timeout: return .TIMEOUT
        case .offline, .ssl: return .OFFLINE
        case .httpStatus(let code): return ExchangeBackoff.isRateLimitCode(code) ? .RATE_LIMIT : .SERVER
        case .rateLimited: return .RATE_LIMIT
        case .unknownInstrument: return .OTHER
        case .parse, .message, .other: return nil
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

    /// Vorübergehende Störung (Zeit, Netz, TLS, Bremse, HTTP 408, 5xx): sagt nichts über den Inhalt, ein neuer Versuch
    /// kann helfen. Wie Android `ExchangeFailure.isTransient` (Fälle in `exchange_failure.json`, «pairListAborts»).
    var isTransient: Bool {
        switch self {
        case .timeout, .offline, .ssl, .rateLimited: return true
        case .httpStatus(let code): return code == 408 || code >= 500
        default: return false
        }
    }

    /// Paarliste in mehreren Anfragen (BitFlyer je Region, Deribit je Abrechnungswährung): Scheitert Anfrage `call`,
    /// bricht die ganze Liste ab (die gespeicherte bleibt), wenn es die erste ist, jede nötig ist oder die Störung
    /// vorübergehend war — sonst würde eine halbe Liste mit neuem Datum gespeichert. Eine bleibend fehlende
    /// Teilliste (4xx, unlesbar) wird übersprungen. Wie Android `pairListCallAborts`.
    static func pairListCallAborts(call: Int, allCallsRequired: Bool, failure: ExchangeFailure) -> Bool {
        call == 0 || allCallsRequired || failure.isTransient
    }

    /// Ladeversuch einer Paarliste merken (danach Stunden Ruhe)? Nur nach einem Ergebnis: geladen (`failure` nil) oder eine
    /// bleibende Antwort der Börse. Eine vorübergehende Störung (`isTransient`: Netz, Zeit, Bremse, 5xx) sperrt nicht — der
    /// nächste Abruf versucht es wieder. Wie Android `remembersPairListAttempt`.
    static func remembersPairListAttempt(_ failure: ExchangeFailure?) -> Bool {
        guard let failure else { return true }
        return !failure.isTransient
    }

    /// Eigene Kennungen, die der Text einordnet (keine Meldung der Börse).
    private static let ownMarkers: Set<String> = [
        "Response data is empty", "Parsed ticker has no data", "Unknown error (empty)", "Exchange unavailable",
    ]

    /// Art eines Fehlers. Nur Typen mit eindeutiger Aussage bekommen eine feste Art; der Rest wird `.other` (bzw.
    /// `.message`) und bleibt bei der Einordnung über den Text. URL-Fehler wie `ConnectionErrors.describe`:
    /// dieselben Codes, die dort «kein Netz» werden, hier `.offline` — ausser Zeit (`.timeout`) und TLS (`.ssl`).
    static func of(_ error: Error) -> ExchangeFailure {
        if let http = error as? HttpExchangeError {
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
                 .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed, .cannotLoadFromNetwork,
                 .badServerResponse, .cannotParseResponse, .cannotDecodeContentData, .cannotDecodeRawData,
                 .resourceUnavailable:
                return .offline
            default:
                return .other
            }
        }
        // «Kein Eintrag für das Paar» in gültiger Antwort: Aussage über das Paar (`NotTraded.isMissingEntry`); der
        // angezeigte Text ist übersetzt, darum hier am Typ erkannt. Wie Android.
        if let json = error as? JSONError, NotTraded.isMissingEntry(json.message) { return .unknownInstrument }
        if error is JSONError || error is DecodingError || error is NoTickerDataError { return .parse }
        if let friendly = error as? UserFriendlyExchangeError { return fromExchangeMessage(friendly.message) }
        return .other
    }

    /// Meldung der Börse: eigene Kennungen → `.other`; «Paar unbekannt» → `.unknownInstrument`; sonst `.message`.
    static func fromExchangeMessage(_ message: String) -> ExchangeFailure {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || ownMarkers.contains(text) { return .other }
        if RefreshReportLogic.classify(text) == .OTHER && NotTraded.isUnknownInstrument(text) {
            return .unknownInstrument
        }
        return .message(message)
    }
}
