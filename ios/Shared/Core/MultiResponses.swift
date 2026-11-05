import Foundation
import os

/// Kurse einer `MultiResponseSource` (Referenzkurse von NBU und NBP): alle Antworten zusammen holen — eine Anfrage je
/// Adresse, egal wie viele Paare —, bis `validUntil` im Speicher behalten (NBP: bis zur nächsten Tabelle B) und
/// verbunden als Antwort von Anfrage 0 lesen. Einzel- und Sammelkurs teilen sich die Antworten; nur im Speicher. Wie
/// Android `MultiResponses.kt`.
enum MultiResponses {
    private struct Entry: Sendable {
        let addresses: [String]
        let body: String
        let until: Int64
    }

    /// Börsen-Schlüssel → zuletzt gelesene Antworten.
    private static let cache = OSAllocatedUnfairLock<[String: Entry]>(initialState: [:])

    /// Verbundene Antworten: aus dem Speicher, solange gültig und für dieselben Adressen, sonst neu geholt (`fetch`:
    /// GET einer Adresse, in Tests ersetzt).
    static func body(_ source: MultiResponseSource, now: Int64 = EpochTime.nowMillis,
                     fetch: (String) async throws -> String = get) async throws -> String {
        let addresses = source.responseAddresses(now: now)
        if let entry = cache.withLock({ $0[source.key] }), entry.addresses == addresses, now < entry.until {
            return entry.body
        }
        var bodies: [String] = []
        for address in addresses {
            bodies.append(try await fetch(address))
        }
        let joined = MultiResponse.join(bodies)
        let until = source.validUntil(bodies: bodies, now: now)
        let key = source.key
        cache.withLock { $0[key] = until > now ? Entry(addresses: addresses, body: joined, until: until) : nil }
        return joined
    }

    /// Sammelabfrage: alle Kurse aus den verbundenen Antworten; scheitert sie, der Fehlertext wie bei jeder Börse.
    static func bulk(_ source: MultiResponseSource) async -> BulkTickers {
        do {
            let parsed = try source.parseBulkTickersDetailed(call: 0, body: try await body(source))
            return BulkTickers(tickers: parsed.tickers, failed: parsed.failed.subtracting(parsed.tickers.keys))
        } catch {
            return BulkTickers(error: ConnectionErrors.describe(error), exchangeFailure: ExchangeFailure.of(error))
        }
    }

    /// Einzelkurs aus denselben Antworten; ohne gültigen Kurs «keine Kursdaten» wie bei jeder Börse.
    static func ticker(_ source: MultiResponseSource, info: PairQuery) async throws -> Ticker {
        var ticker = Ticker()
        try source.readTicker(call: 0, body: try await body(source), ticker: &ticker, info: info)
        guard ticker.last.isFinite, ticker.last > 0 else { throw NoTickerDataError() }
        return ticker
    }

    /// GET einer Adresse für `body` (Sammel-Session wie die übrigen Sammelabfragen).
    static func get(_ url: String) async throws -> String {
        try await ExchangeHTTP.call(url, session: ExchangeHTTP.tickerBulkSession)
    }

    /// Für Tests: Speicher leeren.
    static func clear() { cache.withLock { $0 = [:] } }
}
