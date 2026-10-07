import Foundation

/// Tageskerze: Datum (UTC), Hoch und Schlusskurs.
struct InsightsCandle: Sendable {
    let date: LocalDay
    let high: Double
    let close: Double
}

/// Daten für den Markt-Tab über Bitcoin hinaus — wie `InsightsDataSource.kt`:
///  - Kursverlauf beliebiger Coins (Ausweich-Kette Binance/Binance.US/Coinbase, sonst Bybit) für das Coin-Modell
///  - Fear & Greed Index (alternative.me)
///  - Bitcoin-Dominanz und Gesamtmarkt (CoinGecko, eine Abfrage) und Altcoin-Saison (Ausweich-Kette)
///  - Bitcoin-Kursverlauf seit 2016 für den Zyklus-Vergleich (Coin Metrics, sonst Binance,
///    zuletzt die Ausweich-Kette mit kürzerem Verlauf)
/// Alles frei und ohne Schlüssel.
enum InsightsDataSource {

    /// Grosse Altcoins für die Altcoin-Saison (ohne Stablecoins).
    static let alts: [String] = [
        "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT",
        "TON", "SHIB", "LTC", "BCH", "UNI", "NEAR", "APT", "ICP", "ETC", "XLM",
    ]

    // MARK: Coin

    static func fetchCoin(symbol: String) async throws -> CoinInputs {
        let base = symbol.uppercased()
        async let dailyJob = klines(base, daily: true)
        async let weeklyJob = optionalKlines(base, daily: false, limit: 1000)
        async let btcJob = optionalKlines("BTC", daily: true, limit: 120, skip: base == "BTC")

        let daily = try await dailyJob
        let weeklyResult = await weeklyJob
        let btcResult = await btcJob
        guard !daily.isEmpty else { throw JSONError(message: "Keine Kursdaten für \(base)") }
        let weekly = weeklyResult ?? []
        let closes = daily.map(\.close)
        let weeklyCloses = weekly.map(\.close)
        let athCandle = (weekly + daily).max(by: { $0.high < $1.high })
        let lastClose = closes[closes.count - 1]

        // Stärke gegenüber Bitcoin: Verhältnis heute vs. vor 90 Tagen
        var vsBtc: Double? = nil
        if let btc = btcResult {
            let btcCloses = btc.map(\.close)
            let c0 = element(closes, closes.count - 91)
            let b0 = element(btcCloses, btcCloses.count - 91)
            let b1 = btcCloses.last
            if let c0, let b0, let b1, c0 > 0, b0 > 0, b1 > 0 {
                vsBtc = ((lastClose / c0) / (b1 / b0) - 1.0) * 100.0
            }
        }

        let weeklyDays = weekly.first.map { $0.date.days(until: .todayUTC()) } ?? 0
        let historyDays = max(daily.count, weeklyDays)

        return CoinInputs(
            price: lastClose,
            sma50d: Indicators.smaOfLast(closes, 50),
            sma200d: Indicators.smaOfLast(closes, 200),
            sma111d: Indicators.smaOfLast(closes, 111),
            sma350d: Indicators.smaOfLast(closes, 350),
            price30dAgo: element(closes, closes.count - 31),
            sma200w: Indicators.smaOfLast(weeklyCloses, 200),
            ath: athCandle?.high,
            athDate: athCandle?.date,
            rsiDaily: Indicators.rsi(closes),
            rsiWeekly: Indicators.rsi(weeklyCloses),
            vsBtc90d: vsBtc,
            historyDays: historyDays
        )
    }

    private static func element(_ values: [Double], _ index: Int) -> Double? {
        values.indices.contains(index) ? values[index] : nil
    }

    private static func optionalKlines(_ base: String, daily: Bool, limit: Int, skip: Bool = false) async -> [InsightsCandle]? {
        if skip { return nil }
        return try? await klines(base, daily: daily, limit: limit)
    }

    /// Erst die Ausweich-Kette (Binance, Binance.US, Coinbase — siehe `CandleDataSource`),
    /// sonst Bybit (z. B. für Coins, die keine dieser Quellen führt).
    static func klines(_ base: String, daily: Bool, limit: Int = 1000) async throws -> [InsightsCandle] {
        do {
            return try await chainKlines(base, interval: daily ? .d1 : .w1, limit: limit)
        } catch {
            if error is CancellationError || Task.isCancelled { throw error }
            return try await bybitKlines(symbol: "\(base)USDT", interval: daily ? "D" : "W", limit: limit)
        }
    }

    /// Kerzen über `CandleDataSource`; wirft, wenn keine Quelle liefert.
    static func chainKlines(_ base: String, interval: CandleInterval, limit: Int) async throws -> [InsightsCandle] {
        guard let candles = await CandleDataSource.candles(base: base, quote: "USDT", interval: interval, limit: limit) else {
            if Task.isCancelled { throw CancellationError() }
            throw JSONError(message: "Keine Kerzen für \(base)")
        }
        return candles.map { InsightsCandle(date: LocalDay(epochMillisUTC: $0.openTime), high: $0.high, close: $0.close) }
    }

    static func binanceKlines(symbol: String, interval: String, limit: Int, startTime: Int64? = nil) async throws -> [InsightsCandle] {
        var url = "https://data-api.binance.vision/api/v3/klines?symbol=\(symbol)&interval=\(interval)&limit=\(limit)"
        if let startTime { url += "&startTime=\(startTime)" }
        let text = try await MarketHTTP.call(url, session: MarketHTTP.bulkSession)
        return try parseBinanceKlines(text)
    }

    /// Binance-Kerze: [openTime, open, high, low, close, ...]
    static func parseBinanceKlines(_ text: String) throws -> [InsightsCandle] {
        let array = try JArray(string: text)
        var out: [InsightsCandle] = []
        out.reserveCapacity(array.count)
        for i in 0..<array.count {
            let k = try array.array(i)
            let highText = try k.string(2)
            let closeText = try k.string(4)
            guard let high = Double(highText), let close = Double(closeText) else {
                throw JSONError(message: "Kerze \(i) ohne Kurs")
            }
            out.append(InsightsCandle(date: LocalDay(epochMillisUTC: try k.long(0)), high: high, close: close))
        }
        return out
    }

    /// Bybit liefert die neueste Kerze zuerst — umdrehen.
    private static func bybitKlines(symbol: String, interval: String, limit: Int) async throws -> [InsightsCandle] {
        let url = "https://api.bybit.com/v5/market/kline?category=spot&symbol=\(symbol)&interval=\(interval)&limit=\(limit)"
        let list = try JObject(string: try await MarketHTTP.call(url, session: MarketHTTP.bulkSession))
            .object("result").array("list")
        var out: [InsightsCandle] = []
        for i in 0..<list.count {
            let k = try list.array(i)
            let startText = try k.string(0)
            let highText = try k.string(2)
            let closeText = try k.string(4)
            guard let start = Int64(startText),
                  let high = Double(highText),
                  let close = Double(closeText) else {
                throw JSONError(message: "Kerze \(i) ohne Kurs")
            }
            out.append(InsightsCandle(date: LocalDay(epochMillisUTC: start), high: high, close: close))
        }
        return out.reversed()
    }

    // MARK: Fear & Greed

    static func fearGreed() async throws -> FearGreed {
        let data = try JObject(string: try await MarketHTTP.call("https://api.alternative.me/fng/?limit=31")).array("data")
        func valueAt(_ i: Int) -> Int? {
            guard i < data.count, let o = try? data.object(i) else { return nil }
            return Int(o.optString("value").trimmingCharacters(in: .whitespaces))
        }
        guard let value = valueAt(0) else { throw JSONError(message: "Fear & Greed ohne Wert") }
        return FearGreed(value: value, yesterday: valueAt(1), weekAgo: valueAt(7), monthAgo: valueAt(30))
    }

    // MARK: Dominanz & Altcoin-Saison

    /// Eine Abfrage für Dominanz und Gesamtmarkt (Marktkapitalisierung, Volumen, 24-h-Veränderung).
    static func global() async throws -> CoinGeckoGlobal {
        try parseGlobal(try await MarketHTTP.call("https://api.coingecko.com/api/v3/global"))
    }

    /// Fehlt ein Teil, ist nur er nil; wirft nur ohne `data`.
    static func parseGlobal(_ text: String) throws -> CoinGeckoGlobal {
        let data = try JObject(string: text).object("data")
        var dominance: Dominance?
        if let pct = data.optObject("market_cap_percentage"), let btc = try? pct.double("btc") {
            let eth = pct.optDouble("eth")
            dominance = Dominance(btc: btc, eth: eth.isNaN ? nil : eth)
        }
        let cap = currencyMap(data.optObject("total_market_cap"))
        let volume = currencyMap(data.optObject("total_volume"))
        let change = data.optDouble("market_cap_change_percentage_24h_usd")
        let market: GlobalMarket? = cap.isEmpty || volume.isEmpty ? nil
            : GlobalMarket(totalMarketCap: cap, totalVolume: volume, change24hPercent: change.isFinite ? change : nil)
        return CoinGeckoGlobal(dominance: dominance, market: market)
    }

    /// `{"usd": 3.4e12, "chf": …}` → Werte je Währung (Kleinbuchstaben), nur endliche > 0.
    private static func currencyMap(_ object: JObject?) -> [String: Double] {
        guard let object else { return [:] }
        var out: [String: Double] = [:]
        for (key, value) in object.raw {
            if let d = JSONValue.double(value), d.isFinite, d > 0 { out[key.lowercased()] = d }
        }
        return out
    }

    static func altSeason() async throws -> AltSeason {
        let btc = try await chainKlines("BTC", interval: .d1, limit: 91).map(\.close)
        guard let btcChange = change90(btc) else { throw JSONError(message: "BTC-Verlauf fehlt") }

        // Höchstens fünf Abfragen gleichzeitig (wie die Semaphore in Android).
        let results: [Double] = await withTaskGroup(of: Double?.self) { group in
            var pending = alts.makeIterator()
            for _ in 0..<5 {
                guard let alt = pending.next() else { break }
                group.addTask { await InsightsDataSource.altChange(alt) }
            }
            var out: [Double] = []
            while let result = await group.next() {
                if let result { out.append(result) }
                if let alt = pending.next() {
                    group.addTask { await InsightsDataSource.altChange(alt) }
                }
            }
            return out
        }
        return AltSeason(outperformers: results.filter { $0 > btcChange }.count, total: results.count)
    }

    private static func altChange(_ alt: String) async -> Double? {
        guard let candles = try? await chainKlines(alt, interval: .d1, limit: 91) else { return nil }
        return change90(candles.map(\.close))
    }

    private static func change90(_ closes: [Double]) -> Double? {
        guard closes.count >= 91 else { return nil }
        let first = closes[closes.count - 91]
        guard first > 0, let last = closes.last else { return nil }
        return last / first - 1.0
    }

    // MARK: Zyklus-Vergleich

    static func cycleHistory() async throws -> CycleHistory {
        var prices: [LocalDay: Double] = [:]
        if let cm = try? await coinMetricsPrices(), !cm.isEmpty {
            prices = cm
        } else if let binance = try? await binanceBtcHistory(), !binance.isEmpty {
            prices = binance
        } else {
            // Letzter Ersatz: so viele Tageskerzen, wie die Ausweich-Kette liefert (Coinbase: ~300 Tage)
            for c in try await chainKlines("BTC", interval: .d1, limit: 1000) { prices[c.date] = c.close }
        }
        let sortedDays = prices.keys.sorted()

        let halvings = BitcoinCycle.halvings.filter { $0.year >= 2016 }
        var series: [CycleSeries] = []
        for halving in halvings {
            // Nur Zyklen, deren Daten am Halving-Tag (± 1 Woche) beginnen
            guard let firstDay = sortedDays.first(where: { !$0.isBefore(halving) }),
                  let startPrice = prices[firstDay], startPrice > 0 else { continue }
            if halving.days(until: firstDay) > 7 { continue }
            let extremes = cycleMarkers(prices, halving: halving, startPrice: startPrice)
            // Wochenpunkte, dazu die Tage aller Marken, damit die Linie genau durch sie läuft
            var days = Set(stride(from: 0, through: 1440, by: 7))
            for m in [extremes.top, extremes.bottom, extremes.secondTop, extremes.secondBottom] {
                if let m { days.insert(m.day) }
            }
            var points: [CyclePoint] = []
            for day in days.sorted() {
                if let p = prices[halving.plusDays(day)] {
                    points.append(CyclePoint(day: day, multiple: p / startPrice))
                }
            }
            if points.count >= 2 {
                series.append(CycleSeries(halving: halving, points: points, top: extremes.top, bottom: extremes.bottom,
                                          secondTop: extremes.secondTop, secondBottom: extremes.secondBottom))
            }
        }
        return CycleHistory(series: series)
    }

    /// Hoch, Tief und Doppel-Top/-Bottom aus den Tageskursen 0–1440 Tage nach dem Halving.
    private static func cycleMarkers(
        _ prices: [LocalDay: Double],
        halving: LocalDay,
        startPrice: Double
    ) -> CycleExtremesResult {
        var daily: [(day: Int, price: Double)] = []
        for day in 0...1440 {
            if let p = prices[halving.plusDays(day)], p > 0 { daily.append((day, p)) }
        }
        return CycleExtremes.find(daily, halving: halving, startPrice: startPrice)
    }

    /// Tagesschlusskurse BTC in USD seit Mitte 2016 (Coin Metrics, ein Aufruf).
    private static func coinMetricsPrices() async throws -> [LocalDay: Double] {
        let url = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
            "?assets=btc&metrics=PriceUSD&frequency=1d&start_time=2016-06-01&page_size=10000"
        let rows = try JObject(string: try await MarketHTTP.call(url, session: MarketHTTP.bulkSession)).array("data")
        var map: [LocalDay: Double] = [:]
        for r in rows.objects {
            guard let date = LocalDay(iso: r.optString("time")),
                  let price = Double(r.optString("PriceUSD")) else { continue }
            map[date] = price
        }
        return map
    }

    /// Ersatz: Binance-Tageskerzen ab August 2017, in Blöcken zu 1000 Tagen.
    private static func binanceBtcHistory() async throws -> [LocalDay: Double] {
        var map: [LocalDay: Double] = [:]
        var start = LocalDay(2017, 8, 17).epochMillisUTC
        for _ in 0..<5 {
            let batch = try await binanceKlines(symbol: "BTCUSDT", interval: "1d", limit: 1000, startTime: start)
            guard let last = batch.last else { return map }
            for c in batch { map[c.date] = c.close }
            start = last.date.plusDays(1).epochMillisUTC
            if batch.count < 1000 { return map }
        }
        return map
    }
}
