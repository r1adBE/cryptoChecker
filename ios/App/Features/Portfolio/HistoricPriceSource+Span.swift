import Foundation

// Kerzen für die Kette der Tagesschlüsse (Teil von `HistoricPriceSource`): je Tag eine Abfrage (`HistoricDayFetch`) oder
// für viele Tage desselben Coins je Quelle in Stücken (`HistoricSpanFetch`, Steuer-Export). Eigene Datei, weil die
// Abfrageart vom Ablauf der Kette getrennt lesbar sein soll; beide Arten liefern je UTC-Tag dieselbe Kerze, die Kette und
// ihre Prüfungen (`HistoricPriceSource.closeUsdt`) sind dieselben. Wie HistoricPriceSourceSpan.kt (Android).

/// Kerzen der Binance-Gruppe und von Coinbase für `HistoricPriceSource.closeUsdt`, die eigenen Tageskerzen der übrigen
/// Börsen (`seriesClose`) und der Devisenkurs eines Tags (eigene Börse in Fiat). Schluss = Kerze, die genau bei `start`
/// beginnt (gültig, > 0); nil = keine (auch: Abfrage gescheitert).
protocol HistoricCloseFetch: Sendable {
    func binanceDay(host: String, endpoint: String, symbol: String, start: Int64) async -> Double?
    /// Jüngster Schluss derselben Reihe (Identitätsprüfung).
    func binanceLatest(host: String, endpoint: String, symbol: String) async -> Double?
    /// Coinbase-Produkt `product` (z. B. «ETH-USD»), Kerze des Tags `day`.
    func coinbaseDay(product: String, day: CutoffDay, start: Int64) async -> Double?
    /// Jüngster Schluss desselben Produkts (ohne Zeitfenster = die neuesten Kerzen).
    func coinbaseLatest(product: String) async -> Double?
    func seriesClose(_ pair: CandlePair, start: Int64) async -> (close: Double, latest: Double?)?
    func usdTo(_ currency: String, day: CutoffDay) async -> HistoricFx?
}

/// Je Tag eine Abfrage (Stichtag, Vorschlag beim Erfassen): Binance mit `startTime` und `limit=1`, Coinbase genau über den
/// Tag. `get`, `series`, `fx`: Netz bzw. eigene Kerzen und Frankfurter (Tests: gespielter Markt).
struct HistoricDayFetch: HistoricCloseFetch {
    var get: @Sendable (String, String) async -> String? = { await HistoricPriceSource.get($0, $1) }
    var series: @Sendable (CandlePair, Int64) async -> (close: Double, latest: Double?)? = {
        await HistoricPriceSource.seriesClose($0, start: $1)
    }
    var fx: @Sendable (String, CutoffDay) async -> HistoricFx? = { await HistoricPriceSource.usdTo($0, day: $1) }

    func binanceDay(host: String, endpoint: String, symbol: String, start: Int64) async -> Double? {
        guard let body = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&startTime=\(start)&limit=1"),
              let candles = try? CandleDataSource.parseBinance(body)
        else { return nil }
        return HistoricPriceSource.closeOf(candles, start: start)
    }

    func binanceLatest(host: String, endpoint: String, symbol: String) async -> Double? {
        guard let recent = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&limit=1") else { return nil }
        return (try? CandleDataSource.parseBinance(recent))?.last?.close
    }

    func coinbaseDay(product: String, day: CutoffDay, start: Int64) async -> Double? {
        guard let body = await get(HistoricPriceSource.coinbaseHost, Self.coinbaseUrl(product, from: day, to: day)),
              let candles = try? CandleDataSource.parseCoinbase(body)
        else { return nil }
        return HistoricPriceSource.closeOf(candles, start: start)
    }

    func coinbaseLatest(product: String) async -> Double? {
        guard let recent = await get(HistoricPriceSource.coinbaseHost, Self.coinbaseUrl(product, from: nil, to: nil)) else {
            return nil
        }
        return (try? CandleDataSource.parseCoinbase(recent))?.max(by: { $0.openTime < $1.openTime })?.close
    }

    func seriesClose(_ pair: CandlePair, start: Int64) async -> (close: Double, latest: Double?)? { await series(pair, start) }

    func usdTo(_ currency: String, day: CutoffDay) async -> HistoricFx? { await fx(currency, day) }

    /// Coinbase-Tageskerzen von `from` bis `to` (ISO 8601, UTC, ganze Tage); ohne Fenster die neuesten.
    static func coinbaseUrl(_ product: String, from: CutoffDay?, to: CutoffDay?) -> String {
        let base = "https://\(HistoricPriceSource.coinbaseHost)/products/\(product)/candles?granularity=86400"
        guard let from, let to else { return base }
        return base + "&start=\(from.iso)T00:00:00Z&end=\(to.iso)T23:59:59Z"
    }
}

/// Viele Tage EINES Coins (Steuer-Export): je Quelle die Kerzen der verlangten Tage in Stücken (Binance höchstens
/// `binanceSpanDays`, Coinbase `coinbaseSpanDays` Tage je Abfrage, Stücke nur dort, wo Tage verlangt sind) und der jüngste
/// Schluss einmal je Reihe; erst geholt, wenn die Kette diese Quelle für einen Tag braucht. Je Tag gilt die Kerze, die
/// genau an ihm beginnt — dieselbe, die `HistoricDayFetch` einzeln holt. Ein Tag ausserhalb der Stücke fragt einzeln.
actor HistoricSpanFetch: HistoricCloseFetch {
    /// Binance-Tageskerzen je Abfrage (Grenze von Spot und Binance.US: 1000).
    static let binanceSpanDays = 1000
    /// Coinbase-Tageskerzen je Abfrage (Grenze: 300).
    static let coinbaseSpanDays = 300

    private let day: HistoricDayFetch
    private let binanceSpans: [ClosedRange<Int>]
    private let coinbaseSpans: [ClosedRange<Int>]
    /// «Quelle|Reihe|erster Tag des Stücks» → Beginn der Kerze → Schluss.
    private var loaded: [String: [Int64: Double]] = [:]
    /// «Quelle|Reihe» → jüngster Schluss (auch nil = keiner).
    private var latest: [String: Double?] = [:]
    private var fxMemo: [String: HistoricFx?] = [:]

    /// `days`: verlangte Tage (epochDay) dieses Coins.
    init(days: Set<Int>, day: HistoricDayFetch) {
        let sorted = days.sorted()
        self.day = day
        binanceSpans = Self.spans(sorted, size: Self.binanceSpanDays)
        coinbaseSpans = Self.spans(sorted, size: Self.coinbaseSpanDays)
    }

    /// Stücke über die sortierten Tage `days`: jedes beginnt beim ersten noch nicht gedeckten Tag und endet beim letzten
    /// verlangten Tag innerhalb von höchstens `size` Tagen (nie über den Bedarf hinaus, nie in der Zukunft).
    static func spans(_ days: [Int], size: Int) -> [ClosedRange<Int>] {
        var out: [ClosedRange<Int>] = []
        for d in days {
            if let open = out.last, d < open.lowerBound + size {
                out[out.count - 1] = open.lowerBound...max(open.upperBound, d)
            } else {
                out.append(d...d)
            }
        }
        return out
    }

    func binanceDay(host: String, endpoint: String, symbol: String, start: Int64) async -> Double? {
        guard let span = Self.span(of: start, in: binanceSpans) else {
            return await day.binanceDay(host: host, endpoint: endpoint, symbol: symbol, start: start)
        }
        let key = "\(host)|\(symbol)|\(span.lowerBound)"
        if loaded[key] == nil {
            let from = LocalDay(epochDay: span.lowerBound).epochMillisUTC
            let to = LocalDay(epochDay: span.upperBound).epochMillisUTC
            let url = "\(endpoint)?symbol=\(symbol)&interval=1d&startTime=\(from)&endTime=\(to)&limit=\(Self.binanceSpanDays)"
            let body = await day.get(host, url)
            loaded[key] = Self.byOpenTime(body.flatMap { try? CandleDataSource.parseBinance($0) })
        }
        return Self.valid(loaded[key]?[start])
    }

    func binanceLatest(host: String, endpoint: String, symbol: String) async -> Double? {
        let key = "\(host)|\(symbol)"
        if let hit = latest[key] { return hit }
        let value = await day.binanceLatest(host: host, endpoint: endpoint, symbol: symbol)
        latest[key] = .some(value)
        return value
    }

    func coinbaseDay(product: String, day cutoffDay: CutoffDay, start: Int64) async -> Double? {
        guard let span = Self.span(of: start, in: coinbaseSpans) else {
            return await day.coinbaseDay(product: product, day: cutoffDay, start: start)
        }
        let key = "cb|\(product)|\(span.lowerBound)"
        if loaded[key] == nil {
            let url = HistoricDayFetch.coinbaseUrl(product, from: Self.cutoffDay(span.lowerBound),
                                                   to: Self.cutoffDay(span.upperBound))
            let body = await day.get(HistoricPriceSource.coinbaseHost, url)
            loaded[key] = Self.byOpenTime(body.flatMap { try? CandleDataSource.parseCoinbase($0) })
        }
        return Self.valid(loaded[key]?[start])
    }

    func coinbaseLatest(product: String) async -> Double? {
        let key = "cb|\(product)"
        if let hit = latest[key] { return hit }
        let value = await day.coinbaseLatest(product: product)
        latest[key] = .some(value)
        return value
    }

    func seriesClose(_ pair: CandlePair, start: Int64) async -> (close: Double, latest: Double?)? {
        await day.seriesClose(pair, start: start)
    }

    func usdTo(_ currency: String, day cutoffDay: CutoffDay) async -> HistoricFx? {
        let key = "\(currency)|\(cutoffDay.iso)"
        if let hit = fxMemo[key] { return hit }
        let value = await day.usdTo(currency, day: cutoffDay)
        fxMemo[key] = .some(value)
        return value
    }

    private static func span(of start: Int64, in spans: [ClosedRange<Int>]) -> ClosedRange<Int>? {
        let d = LocalDay(epochMillisUTC: start)
        guard d.epochMillisUTC == start else { return nil }
        return spans.first { $0.contains(d.epochDay) }
    }

    private static func cutoffDay(_ epochDay: Int) -> CutoffDay {
        let d = LocalDay(epochDay: epochDay)
        return CutoffDay(year: d.year, month: d.month, day: d.day)
    }

    /// Erste Kerze je Beginn (wie `HistoricPriceSource.closeOf`); keine Antwort = keine Kerzen.
    private static func byOpenTime(_ candles: [MarketCandle]?) -> [Int64: Double] {
        var out: [Int64: Double] = [:]
        for c in candles ?? [] where out[c.openTime] == nil { out[c.openTime] = c.close }
        return out
    }

    private static func valid(_ close: Double?) -> Double? {
        guard let close, close > 0, close.isFinite else { return nil }
        return close
    }
}

extension HistoricPriceSource {
    /// Tagesschlüsse vieler (Coin, Tag) für den Steuer-Export: je Coin die Tage (epochDay) aus `wanted` mit derselben
    /// Kette und Prüfung wie `dailyCloseUsdt` (gleicher Schluss je UTC-Tag, gleiche Reihenfolge der Quellen), die Kerzen
    /// aber je Quelle in Stücken (`HistoricSpanFetch`); eigene Börsen und Namen der Merkliste einmal gelesen, höchstens
    /// `parallel` Coins gleichzeitig. Schlüssel wie übergeben. `current`: Bezugskurse wie bei `dailyCloseUsdt`.
    static func dayClosesUsdt(_ wanted: [String: Set<Int>], current: [String: Double],
                              fetch: HistoricDayFetch = HistoricDayFetch()) async -> [String: [Int: CloseResult]] {
        let coins = wanted.keys.sorted()
        let bases = Dictionary(uniqueKeysWithValues: coins.map { ($0, PortfolioCalculator.normalizeCoin($0)) })
        var assets: [String] = []
        for coin in coins {
            if let b = bases[coin], PortfolioStables.needsQuote(b), isAsset(b), !assets.contains(b) { assets.append(b) }
        }
        let own = ownRoutes(assets)
        let names = watchNames(assets)
        let job: @Sendable (String) async -> (String, [Int: CloseResult]) = { coin in
            let b = bases[coin] ?? coin
            let days = wanted[coin] ?? []
            let span = HistoricSpanFetch(days: days, day: fetch)
            var byDay: [Int: CloseResult] = [:]
            for epochDay in days.sorted() {
                let d = LocalDay(epochDay: epochDay)
                byDay[epochDay] = await closeUsdt(b, day: CutoffDay(year: d.year, month: d.month, day: d.day),
                                                  current: current[coin], own: own[b], fetch: span, names: names)
            }
            return (coin, byDay)
        }
        return await withTaskGroup(of: (String, [Int: CloseResult]).self, returning: [String: [Int: CloseResult]].self) { group in
            var out: [String: [Int: CloseResult]] = [:]
            var pending = coins.makeIterator()
            for _ in 0..<parallel {
                guard let coin = pending.next() else { break }
                group.addTask { await job(coin) }
            }
            for await (coin, byDay) in group {
                out[coin] = byDay
                if let coin = pending.next() { group.addTask { await job(coin) } }
            }
            return out
        }
    }
}
