import Foundation

/// Ausweg eines Einzelkurses, dessen Anfrage mit einem Serverfehler (HTTP 5xx) scheitert: Nennt die Börse eine
/// Ausweichadresse (`Market.tickerFallbackAddress`, One Trading: die Sammelliste), kommt der Kurs von dort. Bringt
/// auch das keinen gültigen Kurs (Paar fehlt, Netz, unlesbar), bleibt der ursprüngliche Serverfehler — er bestätigt
/// wie bisher nie «nicht mehr gehandelt» (NotTraded). 4xx (sagt etwas über das Paar) und Bremsen (429/418) nie.
/// Zweiter Weg: `loadAfterMissing` für «Paar unbekannt» (DexScreener → GeckoTerminal). Android: `SingleTickerFallback.kt`.
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
            // Bremst die Börse den Ausweg (429/418), gilt die Bremse: sonst fragt die App ohne Pause weiter an — nur
            // beim selben Host wie die Kursadresse; ein fremder Anbieter (GeckoTerminal) pausiert die Börse nicht
            if let http = error as? HttpMarketError, ExchangeBackoff.isRateLimitCode(http.httpCode),
               sameHost(address, market.tickerAddress(call: call, info: info)) { throw error }
            HttpLog.add("Ausweg des Einzelkurses ohne Kurs (\(market.key)): \(error.localizedDescription)")
            throw serverError
        }
        // Wie der gewöhnliche Einzelkurs: ohne gültigen Kurs kein Erfolg
        guard draft.last.isFinite, draft.last > 0 else { throw serverError }
        return draft
    }

    /// Wie `load`, aber für einen Lesefehler des Einzelkurses, den die Börse als «Pool unbekannt» kennzeichnet
    /// (`Market.tickerFallbackOnParseError`, DexScreener → GeckoTerminal). Ohne Ausweg oder ohne gültigen Kurs
    /// bleibt `missing` — auch bei einer Bremse (429/418) der Ausweichadresse; nur ein Abbruch geht durch.
    /// Android: `loadAfterMissing`.
    static func loadAfterMissing(market: Market, call: Int, info: PairQuery, base: Ticker, missing: Error,
                                 load: (String) async throws -> String) async throws -> Ticker {
        guard market.tickerFallbackOnParseError(call: call, error: missing),
              let address = market.tickerFallbackAddress(call: call, info: info), !address.isEmpty else {
            throw missing
        }
        var draft = base
        do {
            let body = try await load(address)
            try market.readTickerFallback(call: call, body: body, ticker: &draft, info: info)
        } catch {
            if error is CancellationError || Task.isCancelled { throw error }
            // Auch eine Bremse (429/418) des fremden Anbieters bleibt dort: sie darf die Börse nicht pausieren
            HttpLog.add("Ausweg nach «nicht gefunden» ohne Kurs (\(market.key)): \(error.localizedDescription)")
            throw missing
        }
        guard draft.last.isFinite, draft.last > 0 else { throw missing }
        return draft
    }

    /// Gleicher Host (ohne Gross/Klein)? Unlesbare Adresse → true (im Zweifel gilt die Bremse). Wie Android `sameHost`.
    static func sameHost(_ first: String, _ second: String) -> Bool {
        guard let a = URL(string: first)?.host?.lowercased(), let b = URL(string: second)?.host?.lowercased() else {
            return true
        }
        return a == b
    }
}
