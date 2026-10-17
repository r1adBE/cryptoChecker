import Foundation

/// «Zu viele Anfragen» auch ohne HTTP 429/418 erkennen — für Kurse und Kerzen gleich: Kraken meldet es mit
/// HTTP 200 («EGeneral:Too many requests», «EAPI:Rate limit exceeded»), Bybit mit HTTP 403 («access too
/// frequent») oder retCode 10006 («Too many visits!»), OKX mit code 50011. Die HTTP-Schicht reicht so eine
/// Antwort als `httpCodeForRateLimit` weiter; dann greifen Pause je Börse (`ExchangeBackoff`), `SingleRateGate`,
/// Bericht und «keine ungefilterte Sammelabfrage» wie bei einem echten 429. Wie `RateLimitSignal.kt`.
enum RateLimitSignal {
    /// Status, als den ein so erkanntes Rate-Limit weitergereicht wird.
    static let httpCodeForRateLimit = 429

    /// Antworten mit HTTP 2xx nur bis zu dieser Länge prüfen: Fehlermeldungen sind kurz, Kurslisten nicht.
    static let maxOkBody = 4096

    private static let texts = [
        "too many requests", "rate limit exceeded", "rate limit reached", "too many visits", "access too frequent",
    ]

    /// Fehlercodes im JSON (ohne Leerzeichen verglichen): Bybit retCode 10006, OKX code 50011.
    private static let codes = ["\"retcode\":10006", "\"code\":\"50011\""]

    /// Meldet `text` (Fehlertext oder Antwort) «zu viele Anfragen»?
    static func inText(_ text: String?) -> Bool {
        guard let text, !text.isEmpty else { return false }
        let lower = text.lowercased()
        if texts.contains(where: { lower.contains($0) }) { return true }
        let compact = String(lower.filter { !$0.isWhitespace })
        return codes.contains(where: { compact.contains($0) })
    }

    /// Status zum Weiterreichen: 429/418 bleiben, ein Rate-Limit im Text `body` wird `httpCodeForRateLimit`;
    /// nil = kein Rate-Limit. Bei 2xx nur kurze Antworten (≤ `maxOkBody`), sonst nur ihr Anfang.
    static func httpCode(status: Int, body: String?) -> Int? {
        if ExchangeBackoff.isRateLimitCode(status) { return status }
        guard let body, !body.isEmpty else { return nil }
        if (200..<300).contains(status) && body.utf8.count > maxOkBody { return nil }
        return inText(String(body.prefix(maxOkBody))) ? httpCodeForRateLimit : nil
    }

    /// Antwort mit HTTP 200, die trotzdem «zu viele Anfragen» meldet (Kerzenquellen).
    static func inOkBody(_ body: String) -> Bool { httpCode(status: 200, body: body) != nil }
}
