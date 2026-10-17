import Foundation

/// Tagesschlüsse eines Coins und wie viele Tage bis heute dafür abgefragt wurden (`span`);
/// `ranges` = Eröffnung/Hoch/Tief derselben Kerzen (nil bei Einträgen älterer Versionen — Kerzen
/// dann ohne Docht, bis der Eintrag nach 12 h neu geholt wird).
private struct PortfolioCoinCloses: Codable {
    let closes: [Int: Double]
    let span: Int
    var ranges: [Int: PortfolioDayRange]?
}

/// Tageskerzen für den Wertverlauf: Schluss je Coin und UTC-Tag (`closes`) und, soweit vorhanden,
/// Eröffnung/Hoch/Tief derselben Kerzen (`ranges`, für die Kerzen-Ansicht) — wie `PortfolioDailyPrices` (Android).
/// `own`: Reihe von der eigenen Börse (USDT; vor `binanceBefore` Binance angeglichen); `routed` = Coins mit
/// eigener Börse (Paar gefunden oder Weg nicht lesbar) — ohne eigene Reihe skaliert sie
/// `PortfolioExchangeRoute.historyCloses` oder lässt sie weg (nie still Binance).
struct PortfolioDailyPrices: Sendable {
    var closes: [String: [Int: Double]] = [:]
    var ranges: [String: [Int: PortfolioDayRange]] = [:]
    var own: Set<String> = []
    var binanceBefore: [String: Int] = [:]
    var routed: Set<String> = []
}

/// Eigene Tageskerzen eines Coins (USDT) und für welchen Weg (`route` = Schlüssel des Paars).
private struct PortfolioOwnCloses: Codable {
    let route: String
    let entry: PortfolioCoinCloses
}

/// Tagesschlusskurse für den Wertverlauf im Portfolio — wie `PortfolioHistorySource.kt`:
/// Binance-Tageskerzen `<BASE>USDT` — mindestens `PortfolioHistory.maxDays` Tage, für «Seit 1. Kauf»
/// so viele wie nötig (`PortfolioHistory.candleDays`) in Stücken von höchstens
/// `PortfolioHistory.maxCandlesPerRequest` (`PortfolioHistory.candleChunks`) —
/// data-api.binance.vision, sonst api.binance.com. Zwischenspeicher je Coin 12 h auf dem
/// Gerät (`CycleCache`, Eintrag «phist_<COIN>», samt abgefragter Spanne) und im Speicher; ein
/// Eintrag mit kürzerer Spanne als verlangt wird neu geholt. Ein Coin, den Binance nicht führt
/// (HTTP 400), wird als «ohne Verlauf» ebenfalls 12 h gemerkt. Nur USDT braucht keine Abfrage;
/// andere Stablecoins (z. B. USDCUSDT) werden wie jeder Coin geholt — fehlt ihr Kurs, fehlen sie
/// im Verlauf wie jeder Coin (`PortfolioStables`, `PortfolioHistory`). Von denselben Kerzen werden auch Eröffnung, Hoch und
/// Tief behalten (keine zusätzliche Abfrage).
///
/// Coins mit eigener Börse (`PortfolioExchangeRouteStore`): Tageskerzen dieser Börse (`OwnCandleDataSource`,
/// höchstens 1000), in USDT umgerechnet (Fiat mit den Devisen-Tageskursen, `HistoricPriceSource.usdToSeries`);
/// ebenfalls 12 h gemerkt («phisto_<COIN>»).
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
    /// Coin → (eigene Tageskerzen in USDT, Abfragezeit).
    nonisolated(unsafe) private static var ownMemory: [String: (entry: PortfolioOwnCloses, at: Int64)] = [:]
    /// Eigene Tageskerzen höchstens so viele (Grenze der eigenen Kerzen); davor Binance angeglichen.
    private static let maxOwnDays = 1000

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 15
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Schlusskurse je Coin (Grossschreibung) für mindestens die letzten `days` Tage. Coins
    /// ohne Verlauf (kein Paar, Netz weg und nichts gespeichert) fehlen in der Rückgabe.
    static func dailyCloses(_ coins: [String], days: Int = PortfolioHistory.maxDays) async -> [String: [Int: Double]] {
        await dailyPrices(coins, days: days).closes
    }

    /// Wie `dailyCloses`, zusätzlich mit Eröffnung/Hoch/Tief der Tageskerzen. `entered` = Coins mit eingetragener
    /// Spot-Börse (`PortfolioRouteResolver.enteredCoins`): ohne gespeicherten Weg gelten sie als «Weg nicht lesbar»
    /// (`routed` ohne eigene Reihe) statt still Binance — wie Android `HistoryRoutes.unresolved`.
    static func dailyPrices(_ coins: [String], days: Int = PortfolioHistory.maxDays,
                            entered: Set<String> = []) async -> PortfolioDailyPrices {
        let requested = Set(coins.map { PortfolioCalculator.normalizeCoin($0) }.filter { PortfolioStables.needsQuote($0) })
        // Dazu die Basis gekapselter Coins: ohne eigene Tageskerzen gilt deren Reihe (`withWrapped`) — wie Android
        let symbols = Array(Set(ChainAssets.withBases(Array(requested))))
            .filter { isAsset($0) && PortfolioStables.needsQuote($0) }
            .sorted()
        let span = max(days, PortfolioHistory.maxDays)
        var binance: [String: PortfolioCoinCloses] = [:]
        // Höchstens `parallel` Abfragen gleichzeitig
        var index = 0
        while index < symbols.count {
            let batch = symbols[index..<min(index + parallel, symbols.count)]
            index += parallel
            await withTaskGroup(of: (String, PortfolioCoinCloses?).self) { group in
                for coin in batch {
                    group.addTask {
                        let value = await closes(of: coin, span: span)
                        return (coin, value)
                    }
                }
                for await (coin, entry) in group {
                    guard let entry, !entry.closes.isEmpty else { continue }
                    binance[coin] = entry
                }
            }
        }
        // Eingetragene Börse: deren eigene Tageskerzen statt Binance (Wege aus der App Group)
        let wanted = Set(symbols)
        let stored = PortfolioExchangeRouteStore.load()
        let routes = stored.routes.filter { wanted.contains($0.key) }
        // Eingetragene Börse ohne Paar bzw. ohne gespeicherten Weg: nicht still Binance (`historyCloses`)
        let unresolved: Set<String> = Set(stored.unresolved.keys).union(entered).intersection(wanted)
            .subtracting(routes.keys)
        var own: [String: PortfolioCoinCloses] = [:]
        let routeList = Array(routes)
        index = 0
        while index < routeList.count {
            let batch = routeList[index..<min(index + parallel, routeList.count)]
            index += parallel
            await withTaskGroup(of: (String, PortfolioCoinCloses?).self) { group in
                for (coin, route) in batch {
                    group.addTask {
                        let value = await ownCloses(of: coin, route: route, span: span)
                        return (coin, value)
                    }
                }
                for await (coin, entry) in group {
                    guard let entry, !entry.closes.isEmpty else { continue }
                    own[coin] = entry
                }
            }
        }
        let routed: Set<String> = Set(routes.keys).union(unresolved)
        let result = withWrapped(mergeOwn(binance: binance, own: own, routed: routed), requested: requested)
        // Portfolio-Widget (7 T / 30 T / 1 J): dieselben Kurse in die App Group spiegeln — das Widget
        // fragt selbst nicht ab (wie Android `PortfolioHistorySource.cachedDailyPrices`)
        PortfolioWidgetDailyStore.save(closes: result.closes, ranges: result.ranges,
                                       own: result.own, binanceBefore: result.binanceBefore)
        return result
    }

    /// Gekapselte Coins ohne eigene Tageskerzen (z. B. BTC.b): Reihe der Basis (`ChainAssets.priceViaBase`) — wie ihr
    /// Kurs; nur die verlangten Coins bleiben (die Basis wurde evtl. nur dafür geholt). Wie Android.
    private static func withWrapped(_ prices: PortfolioDailyPrices, requested: Set<String>) -> PortfolioDailyPrices {
        var out = prices
        for (coin, base) in ChainAssets.priceViaBase(Array(requested), available: Set(prices.closes.keys)) {
            if let c = prices.closes[base] { out.closes[coin] = c }
            if let r = prices.ranges[base] { out.ranges[coin] = r }
        }
        out.closes = out.closes.filter { requested.contains($0.key) }
        out.ranges = out.ranges.filter { requested.contains($0.key) }
        return out
    }

    /// Binance-Reihen mit den eigenen zusammenführen: eigene Reihe ersetzt Binance, reicht sie nicht so weit
    /// zurück, davor Binance angeglichen (`PortfolioExchangeRoute.stitch`) — wie Android `mergeOwn`.
    private static func mergeOwn(binance: [String: PortfolioCoinCloses], own: [String: PortfolioCoinCloses],
                                 routed: Set<String>) -> PortfolioDailyPrices {
        var result = PortfolioDailyPrices()
        for (coin, entry) in binance where own[coin] == nil {
            result.closes[coin] = entry.closes
            if let ranges = entry.ranges, !ranges.isEmpty { result.ranges[coin] = ranges }
        }
        for (coin, entry) in own {
            let stitched = PortfolioExchangeRoute.stitch(
                PortfolioCoinDaily(closes: entry.closes, ranges: entry.ranges ?? [:]),
                binance: binance[coin].map { PortfolioCoinDaily(closes: $0.closes, ranges: $0.ranges ?? [:]) })
            result.closes[coin] = stitched.daily.closes
            if !stitched.daily.ranges.isEmpty { result.ranges[coin] = stitched.daily.ranges }
            if let first = stitched.binanceBefore { result.binanceBefore[coin] = first }
        }
        result.own = Set(own.keys)
        result.routed = routed
        return result
    }

    private static func cachedOwn(_ coin: String) -> (entry: PortfolioOwnCloses, at: Int64)? {
        lock.lock(); defer { lock.unlock() }
        return ownMemory[coin]
    }

    private static func rememberOwn(_ coin: String, _ entry: PortfolioOwnCloses, at: Int64) {
        lock.lock(); defer { lock.unlock() }
        ownMemory[coin] = (entry: entry, at: at)
    }

    /// Eigene Tageskerzen (USDT) des Wegs `route`: Speicher, Gerät, sonst Abfrage; nil = keine.
    private static func ownCloses(of coin: String, route: PortfolioRoute, span: Int) async -> PortfolioCoinCloses? {
        let pair = CandlePair(marketKey: route.marketKey, marketName: route.marketName, base: route.base,
                              quote: route.quote, pairId: route.pairId)
        let key = pair.cacheKey
        let now = TimeUtils.nowMillis
        if let hit = cachedOwn(coin), hit.entry.route == key, hit.entry.entry.span >= span,
           CycleCachePolicy.isFresh(savedAt: hit.at, now: now, ttl: ttl) {
            return hit.entry.entry
        }
        let stored = CycleCache.read(ownCacheKey(coin), as: PortfolioOwnCloses.self).flatMap { $0.value.route == key ? $0 : nil }
        if let stored, stored.value.entry.span >= span, CycleCachePolicy.isFresh(savedAt: stored.savedAt, now: now, ttl: ttl) {
            rememberOwn(coin, stored.value, at: stored.savedAt)
            return stored.value.entry
        }
        if let fresh = await fetchOwn(pair, span: span, now: now) {
            let value = PortfolioOwnCloses(route: key, entry: fresh)
            rememberOwn(coin, value, at: now)
            CycleCache.write(ownCacheKey(coin), value, savedAt: now)
            return fresh
        }
        // Nicht erreichbar: älterer Stand desselben Wegs ist besser als keiner
        if let stored { return stored.value.entry }
        if let hit = cachedOwn(coin), hit.entry.route == key { return hit.entry.entry }
        return nil
    }

    /// Tageskerzen der eigenen Börse holen und in USDT umrechnen; nil = keine (dann Ausweich).
    private static func fetchOwn(_ pair: CandlePair, span: Int, now: Int64) async -> PortfolioCoinCloses? {
        guard let candles = await OwnCandleDataSource.shared.candles(pair, interval: .d1, limit: min(span, maxOwnDays))?.value
        else { return nil }
        var closes: [Int: Double] = [:]
        var ranges: [Int: PortfolioDayRange] = [:]
        for candle in candles {
            let day = PortfolioHistory.epochDay(utcMillis: candle.openTime)
            closes[day] = candle.close
            let range = PortfolioDayRange(open: candle.open, high: candle.high, low: candle.low)
            if range.valid { ranges[day] = range }
        }
        guard let first = closes.keys.min() else { return nil }
        let quote = pair.quote.uppercased()
        var rates: [Int: Double] = [:]
        if quote != "USDT" && quote != "USD" {
            let today = LocalDay(epochDay: PortfolioHistory.epochDay(utcMillis: now))
            let from = LocalDay(epochDay: first - PortfolioHistoryFx.leadDays)
            guard let series = await HistoricPriceSource.usdToSeries(quote, from: from, to: today) else { return nil }
            rates = series
        }
        guard let usdt = PortfolioExchangeRoute.ownDailyUsdt(PortfolioCoinDaily(closes: closes, ranges: ranges),
                                                             quote: quote, usdToByDay: rates) else { return nil }
        return PortfolioCoinCloses(closes: usdt.closes, span: span, ranges: usdt.ranges)
    }

    private static func cached(_ coin: String) -> (entry: PortfolioCoinCloses, at: Int64)? {
        lock.lock(); defer { lock.unlock() }
        return memory[coin]
    }

    private static func remember(_ coin: String, _ entry: PortfolioCoinCloses, at: Int64) {
        lock.lock(); defer { lock.unlock() }
        memory[coin] = (entry: entry, at: at)
    }

    private static func closes(of coin: String, span: Int) async -> PortfolioCoinCloses? {
        let now = TimeUtils.nowMillis
        if let hit = cached(coin), hit.entry.span >= span,
           CycleCachePolicy.isFresh(savedAt: hit.at, now: now, ttl: ttl) {
            return hit.entry
        }
        // Ältere Einträge (nur Schlüsse, ohne Spanne) sind nicht lesbar und werden neu geholt
        let stored = CycleCache.read(cacheKey(coin), as: PortfolioCoinCloses.self)
        if let stored, stored.value.span >= span, CycleCachePolicy.isFresh(savedAt: stored.savedAt, now: now, ttl: ttl) {
            remember(coin, stored.value, at: stored.savedAt)
            return stored.value
        }
        if let fresh = await fetch(coin, span: span, now: now) {
            remember(coin, fresh, at: now)
            CycleCache.write(cacheKey(coin), fresh, savedAt: now)
            return fresh
        }
        // Netz weg: älterer (oder kürzerer) Stand ist besser als keiner
        return stored?.value ?? cached(coin)?.entry
    }

    /// Kerzen der letzten `span` Tage holen, neueste Stücke zuerst; leere Karte = Binance kennt das
    /// Paar nicht, nil = nicht erreichbar. Beginnt ein Stück später als verlangt (Coin damals noch
    /// nicht gelistet), werden die älteren nicht mehr abgefragt.
    private static func fetch(_ coin: String, span: Int, now: Int64) async -> PortfolioCoinCloses? {
        let today = PortfolioHistory.epochDay(utcMillis: now)
        var out: [Int: Double] = [:]
        var ranges: [Int: PortfolioDayRange] = [:]
        for chunk in PortfolioHistory.candleChunks(days: span, todayUtcDay: today).reversed() {
            guard let candles = await fetchChunk(coin, days: chunk) else { return nil }
            if candles.isEmpty && out.isEmpty { return PortfolioCoinCloses(closes: [:], span: span, ranges: nil) }
            for (day, candle) in candles {
                out[day] = candle.close
                let range = PortfolioDayRange(open: candle.open, high: candle.high, low: candle.low)
                if range.valid { ranges[day] = range }
            }
            guard let earliest = candles.keys.min(), earliest <= chunk.lowerBound else { break }
        }
        return PortfolioCoinCloses(closes: out, span: span, ranges: ranges)
    }

    /// Ein Stück Tageskerzen (UTC-Tage `days`); leere Karte = kein Paar oder keine Kerzen, nil = nicht erreichbar.
    private static func fetchChunk(_ coin: String, days: ClosedRange<Int>) async -> [Int: MarketCandle]? {
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
            var out: [Int: MarketCandle] = [:]
            for candle in candles {
                out[PortfolioHistory.epochDay(utcMillis: candle.openTime)] = candle
            }
            return out
        }
        return nil
    }

    private static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 15 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    private static func cacheKey(_ coin: String) -> String { "phist_" + coin }

    private static func ownCacheKey(_ coin: String) -> String { "phisto_" + coin }
}
