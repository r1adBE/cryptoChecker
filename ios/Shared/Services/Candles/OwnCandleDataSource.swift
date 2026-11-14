import Foundation
import os

/// Pausen nach «zu viele Anfragen» (429/418 oder Fehlertext in der Antwort) je Host der eigenen
/// Kerzen — für Hosts ohne gemeinsame Gruppe (`ExchangeBackoff.sharedGroup`; Binance pausiert über
/// `BlockedSources`). Dauer wie dort (`ExchangeBackoff.groupPause`). Wie `CandleHostPauses` (Android).
enum CandleHostPauses {
    private static let pauses = OSAllocatedUnfairLock(initialState: [String: ExchangeBackoff.State]())

    static func isPaused(_ host: String, now: Int64 = EpochTime.nowMillis) -> Bool {
        let state = pauses.withLock { $0[host] }
        return ExchangeBackoff.isPaused(state, now: now)
    }

    static func pause(_ host: String, httpCode: Int, retryAfterSeconds: Int64?, now: Int64 = EpochTime.nowMillis) {
        pauses.withLock {
            $0[host] = ExchangeBackoff.groupPause($0[host], now: now, httpCode: httpCode,
                                                  retryAfterSeconds: retryAfterSeconds)
        }
    }
}

/// Kerzen von der EIGENEN Börse eines Paars (`ExchangeCandleRegistry`). Gleiche Regeln wie die
/// Ausweich-Kette (`CandleDataSource`): ~8 s Zeitgrenze je Anfrage, 451/403 sperrt den Host 6 h
/// (`BlockedSources`), 429/418 pausiert (Binance gemeinsam, sonst `CandleHostPauses`); nur LAUFENDE
/// Reihen (`CandleSeries.isLive`). Kurz im Speicher (Erfolg 60 s, Fehlschlag 2 Min. — die Aufrufer
/// halten ihre Ergebnisse länger), höchstens vier Abrufe gleichzeitig. Anbieter = Name der Börse
/// (`OwnCandlePolicy.provider`). Wie `OwnCandleDataSource.kt`.
actor OwnCandleDataSource {
    static let shared = OwnCandleDataSource()

    private static let maxLimit = 1000
    private static let maxParallel = 4
    private static let cacheMillis: Int64 = 60_000
    private static let failureMillis: Int64 = 2 * 60_000

    /// Eigene Sitzung mit ~8 s Zeitgrenze je Anfrage (wie die Ausweich-Kette).
    private static let session: URLSession = {
        let c = PrivateSessionConfig.make(timeout: 8, resource: 10)
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Ergebnis eines Abrufs: Kerzen oder nil; `unreachable` = eine Anfrage scheiterte vorübergehend (Netz, Zeitgrenze,
    /// 5xx, Pause — `HistoricCloseChain.fetchFailure`); nil ohne `unreachable` = keine Reihe. Wie Android `Load`.
    struct Load: Sendable {
        var value: Sourced<[MarketCandle]>?
        var unreachable = false
    }

    // Begrenzt: Schlüssel je Paar, Intervall und Anzahl — sonst wüchse er über die Prozesslebensdauer
    private var cache = BoundedCache<String, (time: Int64, value: Load)>()
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Hat die Börse von `pair` eigene Kerzen in diesem Intervall?
    static func supports(_ pair: CandlePair, interval: CandleInterval) -> Bool {
        ExchangeCandleRegistry.supports(pair, interval: interval)
    }

    /// Bis zu `limit` eigene Kerzen, aufsteigend; nil ohne Quelle oder ohne laufende Reihe.
    func candles(_ pair: CandlePair, interval: CandleInterval, limit: Int) async -> Sourced<[MarketCandle]>? {
        await load(pair, interval: interval, limit: limit).value
    }

    /// Wie `candles`, dazu ob eine Anfrage vorübergehend scheiterte (`Load.unreachable`, Tagesschlüsse).
    func load(_ pair: CandlePair, interval: CandleInterval, limit: Int) async -> Load {
        guard ExchangeCandleRegistry.intervals.contains(interval),
              let source = ExchangeCandleRegistry.source(for: pair) else { return Load() }
        let n = min(max(limit, 2), Self.maxLimit)
        let key = "\(pair.cacheKey)|\(interval.rawValue)|\(n)"
        if let entry = cache.get(key) {
            let age = EpochTime.nowMillis - entry.time
            let ttl = entry.value.value == nil ? Self.failureMillis : Self.cacheMillis
            if age >= 0 && age < ttl { return entry.value }
        }
        await acquire()
        let result = await Self.fetch(source, pair: pair, interval: interval, limit: n)
        release()
        // Abgebrochen (Blatt geschlossen): nichts merken, sonst gälte es 2 Min. als Fehlschlag
        if Task.isCancelled { return result }
        cache.set(key, (EpochTime.nowMillis, result))
        return result
    }

    /// Aktualisieren von Hand: nichts aus dem Speicher.
    func invalidate(_ pair: CandlePair) {
        let prefix = pair.cacheKey + "|"
        cache.removeKeys { $0.hasPrefix(prefix) }
    }

    /// Nach unten ziehen in der Merkliste: alles neu laden.
    func invalidateAll() { cache.removeAll() }

    private static func fetch(_ source: any ExchangeCandleSource, pair: CandlePair, interval: CandleInterval,
                              limit: Int) async -> Load {
        let unreachable = OSAllocatedUnfairLock(initialState: false)
        let http = CandleHTTP(get: { url in await get(source, url, json: nil, unreachable: unreachable) },
                              post: { url, json in await get(source, url, json: json, unreachable: unreachable) },
                              now: EpochTime.nowMillis)
        let fetched = await source.fetch(pair, interval: interval, limit: limit, http: http)
        let failed = unreachable.withLock { $0 }
        guard let candles = fetched, candles.count >= 2,
              CandleSeries.isLive(candles, intervalMillis: interval.millis, now: EpochTime.nowMillis) else {
            return Load(value: nil, unreachable: failed)
        }
        return Load(value: Sourced(value: candles, provider: OwnCandlePolicy.provider(pair)), unreachable: failed)
    }

    /// Eine Anfrage (GET, mit `json` POST); nil bei Fehler, Sperre oder Pause. `unreachable` wird gesetzt, wenn der
    /// Fehler vorübergehend war (Netz, Zeitgrenze, 5xx, Pause) — nicht bei «keine Daten» oder Geo-Sperre.
    private static func get(_ source: any ExchangeCandleSource, _ url: String, json: String?,
                            unreachable: OSAllocatedUnfairLock<Bool>) async -> String? {
        let host = hostOf(url)
        if Task.isCancelled { return nil }
        if BlockedSources.groupPausedUntil(host) != nil || CandleHostPauses.isPaused(host) {
            unreachable.withLock { $0 = true }
            return nil
        }
        if BlockedSources.isBlocked(host) { return nil }
        let post = json.map { PostRequest(body: $0, headers: ["Content-Type": "application/json"]) }
        let body: String
        do {
            body = try await ExchangeHTTP.call(url, post: post, session: session)
        } catch {
            let http = error as? HttpExchangeError
            if HistoricCloseChain.fetchFailure(httpCode: http?.httpCode) == .TRANSIENT { unreachable.withLock { $0 = true } }
            if !BlockedSources.noteFailure(host, error), let http, ExchangeBackoff.isRateLimitCode(http.httpCode) {
                CandleHostPauses.pause(host, httpCode: http.httpCode, retryAfterSeconds: http.retryAfterSeconds)
            }
            return nil
        }
        if source.isRateLimited(body) {
            if ExchangeBackoff.sharedGroup(host) == nil { CandleHostPauses.pause(host, httpCode: 429, retryAfterSeconds: nil) }
            unreachable.withLock { $0 = true }
            return nil
        }
        return body
    }

    /// Hostname einer Adresse («https://api.kraken.com/0/…» → «api.kraken.com»).
    static func hostOf(_ url: String) -> String {
        var rest = Substring(url)
        if let range = rest.range(of: "://") { rest = rest[range.upperBound...] }
        let host = rest.prefix { $0 != "/" && $0 != "?" && $0 != ":" }
        return host.lowercased()
    }

    // Einfache Zählsperre: höchstens `maxParallel` Abrufe gleichzeitig.
    private func acquire() async {
        if running < Self.maxParallel {
            running += 1
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
        // Der Platz wurde von `release()` direkt übergeben — `running` bleibt gleich.
    }

    private func release() {
        if waiters.isEmpty {
            running -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}
