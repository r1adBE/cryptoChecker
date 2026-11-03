import Foundation

/// Börsen-Logos für die Listen («Paar hinzufügen»): Bild-Adressen aus der öffentlichen Börsenliste von CoinGecko
/// (`ExchangeLogos`; Seite 1 kommt aus dem Abruf der Reihenfolge, `offerFirstPage`), weitere Seiten nur, solange
/// zugeordnete Börsen fehlen, für in der Liste fehlende Börsen die Einzelseite — Pause zwischen den Abfragen
/// (`CoinLogos.pagePauseMillis`), «429» einmal nach der Pause wiederholt. Fehlte etwas, frühestens nach
/// `ExchangeLogos.retryMillis` erneut, Gefundenes bleibt. Bilder über die Pipeline der Coin-Logos
/// (`CoinLogoStore.downloadMissing`: Grenzen, Datei-Speicher, Tagessperre, Pausen) in `ExchangeLogos.order` — für alle
/// gleich, unabhängig von der Merkliste. Nur mit Schalter «In der App» aufgerufen. Wie Android `ExchangeLogoStore`.
@MainActor
enum ExchangeLogoStore {
    nonisolated static let foundKey = "exchangeLogos.found"
    nonisolated static let completeKey = "exchangeLogos.complete"
    nonisolated static let attemptKey = "exchangeLogos.attempt"

    /// Seite 1 aus dem Abruf der Reihenfolge in dieser Sitzung (spart die zweite gleiche Abfrage).
    private static var firstPage: String?
    /// In dieser Sitzung schon versucht.
    private static var triedThisSession = false
    /// Läuft schon (zweiter Explorer): nicht doppelt.
    private static var running = false

    static func offerFirstPage(_ body: String) { firstPage = body }

    /// CoinGecko-Abfrage; bei «429» einmal nach der verlangten Pause (`CoinLogos.rateLimitWaitMillis`) wiederholen.
    nonisolated static func callCoinGecko(_ url: String) async throws -> String {
        do {
            return try await ExchangeHTTP.call(url, session: ExchangeHTTP.bulkSession)
        } catch let error as HttpExchangeError where error.httpCode == 429 {
            let wait = CoinLogos.rateLimitWaitMillis(retryAfterSeconds: error.retryAfterSeconds)
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
            return try await ExchangeHTTP.call(url, session: ExchangeHTTP.bulkSession)
        }
    }

    /// Bild-Adressen holen, falls fällig, dann die fehlenden Logos laden (vorhandene: kein Abruf).
    static func ensure(defaults: UserDefaults = .standard) async {
        guard !running else { return }
        running = true
        defer { running = false }
        var found = saved(defaults)
        let due = found.isEmpty || ExchangeLogos.retryDue(complete: defaults.bool(forKey: completeKey),
                                                          attemptAt: Int64(defaults.double(forKey: attemptKey)),
                                                          now: EpochTime.nowMillis)
        if due, !triedThisSession {
            triedThisSession = true
            found = await fetch(found, defaults: defaults)
        }
        guard !found.isEmpty else { return }
        _ = await CoinLogoStore.downloadMissing(ExchangeLogos.images(found), order: ExchangeLogos.order)
    }

    /// Seiten und Einzelseiten lesen; Ergebnis (Kennung → Adresse) samt Vollständigkeit gespeichert.
    private static func fetch(_ saved: [String: String], defaults: UserDefaults) async -> [String: String] {
        var found = saved
        var complete = true
        var page = 1
        do {
            var listEnded = false
            while true {
                if page > 1 { try? await Task.sleep(nanoseconds: UInt64(CoinLogos.pagePauseMillis) * 1_000_000) }
                let cached = page == 1 ? firstPage : nil
                let body: String
                if let cached { body = cached } else { body = try await callCoinGecko(ExchangeLogos.pageURL(page)) }
                let parsed = try ExchangeLogos.parsePage(body)
                for (id, url) in parsed.images { found[id] = url }
                let missing = ExchangeLogos.missing(found).count
                if !ExchangeLogos.needsNextPage(page: page, rows: parsed.rows, missing: missing) {
                    listEnded = missing == 0 || parsed.rows < ExchangeLogos.perPage || page >= ExchangeLogos.maxPages
                    break
                }
                page += 1
            }
            // Bei CoinGecko inaktiv, also nicht in der Liste: Einzelseite
            if listEnded {
                for id in ExchangeLogos.missing(found) {
                    try? await Task.sleep(nanoseconds: UInt64(CoinLogos.pagePauseMillis) * 1_000_000)
                    if let url = try await detail(id) { found[id] = url }
                }
            }
        } catch {
            complete = false
            HttpLog.add("Börsen-Logos: Liste unvollständig (Seite \(page)): \(error.localizedDescription)")
        }
        defaults.set(found, forKey: foundKey)
        defaults.set(complete, forKey: completeKey)
        defaults.set(Double(EpochTime.nowMillis), forKey: attemptKey)
        return found
    }

    /// Bild der Einzelseite; 404 = Börse fehlt bei CoinGecko (gilt als erledigt), andere Fehler → Fehler.
    private static func detail(_ id: String) async throws -> String? {
        do {
            return try ExchangeLogos.parseDetail(try await callCoinGecko(ExchangeLogos.detailURL(id)))
        } catch let error as HttpExchangeError where error.httpCode == 404 {
            return nil
        }
    }

    private static func saved(_ defaults: UserDefaults) -> [String: String] {
        let raw = defaults.dictionary(forKey: foundKey) as? [String: String] ?? [:]
        let ids = Set(ExchangeLogos.ids)
        return raw.filter { ids.contains($0.key) && CoinLogos.isAllowedURL($0.value) }
    }
}
