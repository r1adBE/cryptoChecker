import Foundation

/// Tagesschlusskurse für den Wertverlauf im Portfolio — wie `PortfolioHistorySource.kt`:
/// Binance-Tageskerzen `<BASE>USDT` (bis `PortfolioHistory.maxDays`), eine Abfrage je Coin —
/// data-api.binance.vision, sonst api.binance.com. Zwischenspeicher je Coin 12 h auf dem
/// Gerät (`CycleCache`, Eintrag «phist_<COIN>») und im Speicher. Ein Coin, den Binance nicht
/// führt (HTTP 400), wird als «ohne Verlauf» ebenfalls 12 h gemerkt. Stablecoins brauchen
/// keine Abfrage (Kurs 1, siehe `PortfolioHistory`).
enum PortfolioHistorySource {
    private static let ttl: Int64 = 12 * 60 * 60_000
    private static let parallel = 4

    private static let hosts: [(host: String, endpoint: String)] = [
        (host: "data-api.binance.vision", endpoint: "https://data-api.binance.vision/api/v3/klines"),
        (host: "api.binance.com", endpoint: "https://api.binance.com/api/v3/klines"),
    ]

    private static let lock = NSLock()
    /// Coin → (UTC-Tag → Schluss, Abfragezeit); leere Karte = kein Binance-Paar.
    nonisolated(unsafe) private static var memory: [String: (closes: [Int: Double], at: Int64)] = [:]

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 15
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "cryptoChecker-iOS/16", "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Schlusskurse je Coin (Grossschreibung). Coins ohne Verlauf (kein Paar, Netz weg
    /// und nichts gespeichert) fehlen in der Rückgabe.
    static func dailyCloses(_ coins: [String]) async -> [String: [Int: Double]] {
        let symbols = Array(Set(coins.map { PortfolioCalculator.normalizeCoin($0) }))
            .filter { isAsset($0) && !PortfolioHistory.isStable($0) }
            .sorted()
        var result: [String: [Int: Double]] = [:]
        // Höchstens `parallel` Abfragen gleichzeitig
        var index = 0
        while index < symbols.count {
            let batch = symbols[index..<min(index + parallel, symbols.count)]
            index += parallel
            await withTaskGroup(of: (String, [Int: Double]?).self) { group in
                for coin in batch {
                    group.addTask {
                        let value = await closes(of: coin)
                        return (coin, value)
                    }
                }
                for await (coin, values) in group {
                    if let values, !values.isEmpty { result[coin] = values }
                }
            }
        }
        return result
    }

    private static func cached(_ coin: String) -> (closes: [Int: Double], at: Int64)? {
        lock.lock(); defer { lock.unlock() }
        return memory[coin]
    }

    private static func remember(_ coin: String, _ closes: [Int: Double], at: Int64) {
        lock.lock(); defer { lock.unlock() }
        memory[coin] = (closes: closes, at: at)
    }

    private static func closes(of coin: String) async -> [Int: Double]? {
        let now = TimeUtils.nowMillis
        if let hit = cached(coin), CycleCachePolicy.isFresh(savedAt: hit.at, now: now, ttl: ttl) {
            return hit.closes
        }
        let stored = CycleCache.read(cacheKey(coin), as: [Int: Double].self)
        if let stored, CycleCachePolicy.isFresh(savedAt: stored.savedAt, now: now, ttl: ttl) {
            remember(coin, stored.value, at: stored.savedAt)
            return stored.value
        }
        if let fresh = await fetch(coin) {
            remember(coin, fresh, at: now)
            CycleCache.write(cacheKey(coin), fresh, savedAt: now)
            return fresh
        }
        // Netz weg: älterer Stand ist besser als keiner
        return stored?.value ?? cached(coin)?.closes
    }

    /// Kerzen holen; leere Karte = Binance kennt das Paar nicht, nil = nicht erreichbar.
    private static func fetch(_ coin: String) async -> [Int: Double]? {
        for source in hosts {
            if BlockedSources.isBlocked(source.host) { continue }
            let url = "\(source.endpoint)?symbol=\(coin)USDT&interval=1d&limit=\(PortfolioHistory.maxDays)"
            let body: String
            do {
                body = try await MarketHTTP.call(url, session: session)
            } catch {
                // «Invalid symbol»: Paar gibt es nicht — Spiegel und Spot führen dieselben Paare
                if let http = error as? HttpMarketError, http.httpCode == 400 { return [:] }
                BlockedSources.noteFailure(source.host, error)
                continue
            }
            guard let candles = try? CandleDataSource.parseBinance(body) else { continue }
            var out: [Int: Double] = [:]
            for candle in candles {
                out[PortfolioHistory.epochDay(utcMillis: candle.openTime)] = candle.close
            }
            return out
        }
        return nil
    }

    private static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 15 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    private static func cacheKey(_ coin: String) -> String { "phist_" + coin }
}
