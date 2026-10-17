import Foundation

/// Pausen nach «zu viele Anfragen» (429/418 oder Fehlertext in der Antwort) je Host der eigenen
/// Kerzen — für Hosts ohne gemeinsame Gruppe (`ExchangeBackoff.sharedGroup`; Binance pausiert über
/// `BlockedSources`). Dauer wie dort (`ExchangeBackoff.groupPause`). Wie `CandleHostPauses` (Android).
enum CandleHostPauses {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var pauses: [String: ExchangeBackoff.State] = [:]

    static func isPaused(_ host: String, now: Int64 = TimeUtils.nowMillis) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return ExchangeBackoff.isPaused(pauses[host], now: now)
    }

    static func pause(_ host: String, httpCode: Int, retryAfterSeconds: Int64?, now: Int64 = TimeUtils.nowMillis) {
        lock.lock(); defer { lock.unlock() }
        pauses[host] = ExchangeBackoff.groupPause(pauses[host], now: now, httpCode: httpCode, retryAfterSeconds: retryAfterSeconds)
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
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 8
        c.timeoutIntervalForResource = 10
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    private var cache: [String: (time: Int64, value: Sourced<[MarketCandle]>?)] = [:]
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Hat die Börse von `pair` eigene Kerzen in diesem Intervall?
    static func supports(_ pair: CandlePair, interval: CandleInterval) -> Bool {
        ExchangeCandleRegistry.supports(pair, interval: interval)
    }

    /// Bis zu `limit` eigene Kerzen, aufsteigend; nil ohne Quelle oder ohne laufende Reihe.
    func candles(_ pair: CandlePair, interval: CandleInterval, limit: Int) async -> Sourced<[MarketCandle]>? {
        guard ExchangeCandleRegistry.intervals.contains(interval),
              let source = ExchangeCandleRegistry.source(for: pair) else { return nil }
        let n = min(max(limit, 2), Self.maxLimit)
        let key = "\(pair.cacheKey)|\(interval.rawValue)|\(n)"
        if let entry = cache[key] {
            let age = TimeUtils.nowMillis - entry.time
            let ttl = entry.value == nil ? Self.failureMillis : Self.cacheMillis
            if age >= 0 && age < ttl { return entry.value }
        }
        await acquire()
        let result = await Self.fetch(source, pair: pair, interval: interval, limit: n)
        release()
        // Abgebrochen (Blatt geschlossen): nichts merken, sonst gälte es 2 Min. als Fehlschlag
        if Task.isCancelled { return result }
        cache[key] = (TimeUtils.nowMillis, result)
        return result
    }

    /// Aktualisieren von Hand: nichts aus dem Speicher.
    func invalidate(_ pair: CandlePair) {
        let prefix = pair.cacheKey + "|"
        cache = cache.filter { !$0.key.hasPrefix(prefix) }
    }

    /// Nach unten ziehen in der Merkliste: alles neu laden.
    func invalidateAll() { cache.removeAll() }

    private static func fetch(_ source: any ExchangeCandleSource, pair: CandlePair, interval: CandleInterval,
                              limit: Int) async -> Sourced<[MarketCandle]>? {
        let http = CandleHTTP(get: { url in await get(source, url, json: nil) },
                              post: { url, json in await get(source, url, json: json) },
                              now: TimeUtils.nowMillis)
        guard let candles = await source.fetch(pair, interval: interval, limit: limit, http: http),
              candles.count >= 2,
              CandleSeries.isLive(candles, intervalMillis: interval.millis, now: TimeUtils.nowMillis) else { return nil }
        return Sourced(value: candles, provider: OwnCandlePolicy.provider(pair))
    }

    /// Eine Anfrage (GET, mit `json` POST); nil bei Fehler, Sperre oder Pause.
    private static func get(_ source: any ExchangeCandleSource, _ url: String, json: String?) async -> String? {
        let host = hostOf(url)
        if Task.isCancelled || BlockedSources.isBlocked(host) || CandleHostPauses.isPaused(host) { return nil }
        let post = json.map { PostRequestInfo(body: $0, headers: ["Content-Type": "application/json"]) }
        let body: String
        do {
            body = try await MarketHTTP.call(url, post: post, session: session)
        } catch {
            if !BlockedSources.noteFailure(host, error), let http = error as? HttpMarketError,
               ExchangeBackoff.isRateLimitCode(http.httpCode) {
                CandleHostPauses.pause(host, httpCode: http.httpCode, retryAfterSeconds: http.retryAfterSeconds)
            }
            return nil
        }
        if source.isRateLimited(body) {
            if ExchangeBackoff.sharedGroup(host) == nil { CandleHostPauses.pause(host, httpCode: 429, retryAfterSeconds: nil) }
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
