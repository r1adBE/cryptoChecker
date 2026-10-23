import Foundation

/// Ausweg eines Einzelkurses, dessen Anfrage mit einem Serverfehler (HTTP 5xx) scheitert: Nennt die Börse eine
/// Ausweichadresse (`Market.tickerFallbackAddress`, One Trading: die Sammelliste), kommt der Kurs von dort. Bringt
/// auch das keinen gültigen Kurs (Paar fehlt, Netz, unlesbar), bleibt der ursprüngliche Serverfehler — er bestätigt
/// wie bisher nie «nicht mehr gehandelt» (NotTraded). 4xx (sagt etwas über das Paar) und Bremsen (429/418) nie.
/// Android: `SingleTickerFallback.kt`.
enum SingleTickerFallback {
    /// Nur Serverfehler lösen den Ausweg aus.
    static func applies(httpCode: Int) -> Bool { (500...599).contains(httpCode) }

    /// Kurs über die Ausweichadresse, aufbauend auf `base`; `load` holt die Adresse (GET). Ohne Ausweg oder ohne
    /// gültigen Kurs wird `serverError` geworfen; ein Abbruch geht durch.
    static func load(market: Market, call: Int, info: PairQuery, base: Ticker, serverError: HttpMarketError,
                     load: (String) async throws -> String) async throws -> Ticker {
        guard applies(httpCode: serverError.httpCode),
              let address = market.tickerFallbackAddress(call: call, info: info), !address.isEmpty else {
            throw serverError
        }
        var draft = base
        do {
            let body = try await load(address)
            try market.readTickerFallback(call: call, body: body, ticker: &draft, info: info)
        } catch {
            if error is CancellationError || Task.isCancelled { throw error }
            HttpLog.add("Ausweg des Einzelkurses ohne Kurs (\(market.key)): \(error.localizedDescription)")
            throw serverError
        }
        // Wie der gewöhnliche Einzelkurs: ohne gültigen Kurs kein Erfolg
        guard draft.last.isFinite, draft.last > 0 else { throw serverError }
        return draft
    }
}
