import Foundation

/// Tagesschlüsse eines Coins und wie viele Tage bis heute dafür abgefragt wurden (`span`).
private struct PortfolioCoinCloses: Codable {
    let closes: [Int: Double]
    let span: Int
}

/// Tagesschlusskurse für den Wertverlauf im Portfolio — wie `PortfolioHistorySource.kt`:
/// Binance-Tageskerzen `<BASE>USDT` — mindestens `PortfolioHistory.maxDays` Tage, für «Seit 1. Kauf»
/// so viele wie nötig (`PortfolioHistory.candleDays`) in Stücken von höchstens
/// `PortfolioHistory.maxCandlesPerRequest` (`PortfolioHistory.candleChunks`) —
/// data-api.binance.vision, sonst api.binance.com. Zwischenspeicher je Coin 12 h auf dem
/// Gerät (`CycleCache`, Eintrag «phist_<COIN>», samt abgefragter Spanne) und im Speicher; ein
/// Eintrag mit kürzerer Spanne als verlangt wird neu geholt. Ein Coin, den Binance nicht führt
/// (HTTP 400), wird als «ohne Verlauf» ebenfalls 12 h gemerkt. Nur USDT braucht keine Abfrage;
/// andere Stablecoins (z. B. USDCUSDT) werden wie jeder Coin geholt — fehlt ihr Kurs, gilt 1
/// (`PortfolioStables`, `PortfolioHistory`).
enum PortfolioHistorySource {
    private static let ttl: Int64 = 12 * 60 * 60_000
    private static let parallel = 4
    private static let dayMillis: Int64 = 86_400_000

    private static let hosts: [(host: String, endpoint: String)] = [
        (host: "data-api.binance.vision", endpoint: "https://data-api.binance.vision/api/v3/klines"),
        (host: "api.binance.com", endpoint: "https://api.binance.com/api/v3/klines"),
    ]

    private static let lock = NSLock()
    /// Coin → (Schlüsse samt Spanne, Abfragezeit); leere Karte = kein Binance-Paar.
    nonisolated(unsafe) private static var memory: [String: (entry: PortfolioCoinCloses, at: Int64)] = [:]

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 15
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "cryptoChecker-iOS/16", "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Schlusskurse je Coin (Grossschreibung) für mindestens die letzten `days` Tage. Coins
    /// ohne Verlauf (kein Paar, Netz weg und nichts gespeichert) fehlen in der Rückgabe.
    static func dailyCloses(_ coins: [String], days: Int = PortfolioHistory.maxDays) async -> [String: [Int: Double]] {
        let symbols = Array(Set(coins.map { PortfolioCalculator.normalizeCoin($0) }))
            .filter { isAsset($0) && PortfolioStables.needsQuote($0) }
            .sorted()
        let span = max(days, PortfolioHistory.maxDays)
        var result: [String: [Int: Double]] = [:]
        // Höchstens `parallel` Abfragen gleichzeitig
        var index = 0
        while index < symbols.count {
            let batch = symbols[index..<min(index + parallel, symbols.count)]
            index += parallel
            await withTaskGroup(of: (String, [Int: Double]?).self) { group in
                for coin in batch {
                    group.addTask {
                        let value = await closes(of: coin, span: span)
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

    private static func cached(_ coin: String) -> (entry: PortfolioCoinCloses, at: Int64)? {
        lock.lock(); defer { lock.unlock() }
        return memory[coin]
    }

    private static func remember(_ coin: String, _ entry: PortfolioCoinCloses, at: Int64) {
        lock.lock(); defer { lock.unlock() }
        memory[coin] = (entry: entry, at: at)
    }

    private static func closes(of coin: String, span: Int) async -> [Int: Double]? {
        let now = TimeUtils.nowMillis
        if let hit = cached(coin), hit.entry.span >= span,
           CycleCachePolicy.isFresh(savedAt: hit.at, now: now, ttl: ttl) {
            return hit.entry.closes
        }
        // Ältere Einträge (nur Schlüsse, ohne Spanne) sind nicht lesbar und werden neu geholt
        let stored = CycleCache.read(cacheKey(coin), as: PortfolioCoinCloses.self)
        if let stored, stored.value.span >= span, CycleCachePolicy.isFresh(savedAt: stored.savedAt, now: now, ttl: ttl) {
            remember(coin, stored.value, at: stored.savedAt)
            return stored.value.closes
        }
        if let fresh = await fetch(coin, span: span, now: now) {
            let entry = PortfolioCoinCloses(closes: fresh, span: span)
            remember(coin, entry, at: now)
            CycleCache.write(cacheKey(coin), entry, savedAt: now)
            return fresh
        }
        // Netz weg: älterer (oder kürzerer) Stand ist besser als keiner
        return stored?.value.closes ?? cached(coin)?.entry.closes
    }

    /// Kerzen der letzten `span` Tage holen, neueste Stücke zuerst; leere Karte = Binance kennt das
    /// Paar nicht, nil = nicht erreichbar. Beginnt ein Stück später als verlangt (Coin damals noch
    /// nicht gelistet), werden die älteren nicht mehr abgefragt.
    private static func fetch(_ coin: String, span: Int, now: Int64) async -> [Int: Double]? {
        let today = PortfolioHistory.epochDay(utcMillis: now)
        var out: [Int: Double] = [:]
        for chunk in PortfolioHistory.candleChunks(days: span, todayUtcDay: today).reversed() {
            guard let candles = await fetchChunk(coin, days: chunk) else { return nil }
            if candles.isEmpty && out.isEmpty { return [:] }
            out.merge(candles) { _, new in new }
            guard let earliest = candles.keys.min(), earliest <= chunk.lowerBound else { break }
        }
        return out
    }

    /// Ein Stück Tageskerzen (UTC-Tage `days`); leere Karte = kein Paar oder keine Kerzen, nil = nicht erreichbar.
    private static func fetchChunk(_ coin: String, days: ClosedRange<Int>) async -> [Int: Double]? {
        let startMillis = Int64(days.lowerBound) * dayMillis
        let endMillis = Int64(days.upperBound + 1) * dayMillis - 1
        let limit = days.count
        for source in hosts {
            if BlockedSources.isBlocked(source.host) { continue }
            let url = "\(source.endpoint)?symbol=\(coin)USDT&interval=1d&startTime=\(startMillis)&endTime=\(endMillis)&limit=\(limit)"
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
