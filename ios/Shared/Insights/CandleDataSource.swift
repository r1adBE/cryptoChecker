import Foundation

/// Kerze (OHLCV) eines beliebigen Intervalls — wie `HourCandle` in der Android-Fassung.
/// Listen sind zeitlich aufsteigend; die letzte Kerze läuft in der Regel noch.
struct MarketCandle: Sendable, Equatable {
    /// Startzeit (Epoch-ms, UTC).
    let openTime: Int64
    let open: Double
    let high: Double
    let low: Double
    let close: Double
    /// Gehandelte Menge in der Basiswährung.
    let volume: Double
}

/// Kerzenintervall, unabhängig von der Börse.
enum CandleInterval: String, Sendable, CaseIterable {
    case h1, h4, d1, w1

    var binanceCode: String {
        switch self {
        case .h1: return "1h"
        case .h4: return "4h"
        case .d1: return "1d"
        case .w1: return "1w"
        }
    }

    /// Länge eines Intervalls in ms.
    var millis: Int64 {
        switch self {
        case .h1: return 60 * 60_000
        case .h4: return 4 * 60 * 60_000
        case .d1: return 24 * 60 * 60_000
        case .w1: return 7 * 24 * 60 * 60_000
        }
    }
}

/// Quellen, die mit 451/403 geantwortet haben (Geo-Sperre, z. B. Binance in den USA).
/// Sie werden 6 h übersprungen. Geteilt von Kerzen und Futures-Kennzahlen;
/// Schlüssel ist der Hostname.
enum BlockedSources {
    private static let blockMillis: Int64 = 6 * 60 * 60_000
    private static let lock = NSLock()
    nonisolated(unsafe) private static var blockedUntil: [String: Int64] = [:]

    static func isBlocked(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let until = blockedUntil[key] else { return false }
        if TimeUtils.nowMillis < until { return true }
        blockedUntil[key] = nil
        return false
    }

    /// Wertet einen Fehler aus; true = Sperr-Antwort, Quelle ist jetzt vermerkt.
    @discardableResult
    static func noteFailure(_ key: String, _ error: Error) -> Bool {
        guard let http = error as? HttpMarketError, http.httpCode == 451 || http.httpCode == 403 else { return false }
        lock.lock(); defer { lock.unlock() }
        blockedUntil[key] = TimeUtils.nowMillis + blockMillis
        return true
    }
}

/// Kerzen mit Ausweich-Kette («Weiche») — wie `CandleDataSource.kt`. Reihenfolge:
///  1. data-api.binance.vision (öffentlicher Binance-Spiegel, Spot)
///  2. api.binance.com (Spot)
///  3. fapi.binance.com (USDⓈ-M-Perpetual)
///  4. api.binance.us (Binance.US; USDT, sonst USD)
///  5. Coinbase Exchange (USD, sonst USDC; 4 h und 1 Woche zusammengesetzt)
///
/// Die erste Quelle mit mindestens zwei LAUFENDEN Kerzen gewinnt (endet die Reihe vor
/// langem, z. B. Spot nach einem Delisting, ist die nächste Quelle dran, siehe
/// `CandleSeries.isLive`) und wird je Paar und Intervall eine Stunde lang zuerst gefragt.
/// Für Futures-Paare kommt USDⓈ-M zuerst (`preferFutures`).
/// Gesperrte Quellen siehe `BlockedSources`.
enum CandleDataSource {

    private enum Source: String, CaseIterable {
        case binanceVision = "data-api.binance.vision"
        case binanceSpot = "api.binance.com"
        case binanceFutures = "fapi.binance.com"
        case binanceUS = "api.binance.us"
        case coinbase = "api.exchange.coinbase.com"
    }

    private static let maxLimit = 1000
    private static let workingMillis: Int64 = 60 * 60_000
    private static let hourMillis: Int64 = 60 * 60_000
    private static let weekMillis: Int64 = 7 * 24 * 60 * 60_000
    /// 1.1.1970 war ein Donnerstag; Binance-Wochen beginnen Montag 00:00 UTC (5.1.1970).
    private static let mondayOffsetMillis: Int64 = 4 * 24 * 60 * 60_000

    /// Eigene Sitzung mit ~8 s Zeitgrenze je Anfrage.
    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 8
        c.timeoutIntervalForResource = 10
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "cryptoChecker-iOS/16", "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    // Zuletzt erfolgreiche Quelle je «BASE|QUOTE|INTERVALL»
    private static let lock = NSLock()
    nonisolated(unsafe) private static var working: [String: (source: Source, time: Int64)] = [:]

    private static func preferredSource(_ key: String) -> Source? {
        lock.lock(); defer { lock.unlock() }
        guard let entry = working[key] else { return nil }
        let age = TimeUtils.nowMillis - entry.time
        return age >= 0 && age < workingMillis ? entry.source : nil
    }

    private static func remember(_ key: String, _ source: Source?) {
        lock.lock(); defer { lock.unlock() }
        if let source {
            working[key] = (source: source, time: TimeUtils.nowMillis)
        } else {
            working[key] = nil
        }
    }

    /// Bis zu `limit` Kerzen, aufsteigend; nil, wenn keine Quelle das Paar liefert.
    /// Coinbase liefert höchstens 300 Rohkerzen je Anfrage; für längere Reihen kommt eine
    /// ältere Seite dazu (`CandleSeries.coinbaseOlderWindow`), also bis 600 Rohkerzen.
    /// `preferFutures` = Futures-Paar (Perpetual usw.): zuerst die Kerzen von
    /// fapi.binance.com, Spot erst danach.
    static func candles(base: String, quote: String, interval: CandleInterval, limit: Int,
                        preferFutures: Bool = false) async -> [MarketCandle]? {
        await candlesSourced(base: base, quote: quote, interval: interval, limit: limit, preferFutures: preferFutures)?.value
    }

    /// Wie `candles`, dazu der Anbieter, der geliefert hat («Binance», «Binance.US», «Coinbase»;
    /// siehe `DataFreshness.candleProvider`) — für die Herkunftsangabe im Markt-Tab.
    static func candlesSourced(base: String, quote: String, interval: CandleInterval, limit: Int,
                               preferFutures: Bool = false) async -> Sourced<[MarketCandle]>? {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        guard isAsset(b), isAsset(q) else { return nil }
        let n = min(max(limit, 2), maxLimit)
        let memoKey = "\(b)|\(q)|\(interval.rawValue)|\(preferFutures ? "F" : "S")"

        var order = Source.allCases
        if preferFutures {
            order = [.binanceFutures] + order.filter { $0 != .binanceFutures }
        }
        if let preferred = preferredSource(memoKey) {
            order = [preferred] + order.filter { $0 != preferred }
        }
        for source in order {
            if Task.isCancelled { return nil }
            if BlockedSources.isBlocked(source.rawValue) { continue }
            guard let result = await fetch(from: source, base: b, quote: q, interval: interval, limit: n),
                  result.count >= 2 else { continue }
            // Reihe, die vor langem endet (Paar dort nicht mehr gehandelt): nächste Quelle
            let live = CandleSeries.isLive(result, intervalMillis: interval.millis, now: TimeUtils.nowMillis)
            if live {
                remember(memoKey, source)
                return Sourced(value: result, provider: DataFreshness.candleProvider(host: source.rawValue))
            }
        }
        remember(memoKey, nil)
        return nil
    }

    private static func fetch(from source: Source, base b: String, quote q: String,
                              interval: CandleInterval, limit: Int) async -> [MarketCandle]? {
        let binanceSymbol = b + binanceQuote(q)
        switch source {
        case .binanceVision:
            return await binance(source, endpoint: "https://data-api.binance.vision/api/v3/klines",
                                 symbols: [binanceSymbol], interval: interval, limit: limit)
        case .binanceSpot:
            return await binance(source, endpoint: "https://api.binance.com/api/v3/klines",
                                 symbols: [binanceSymbol], interval: interval, limit: limit)
        case .binanceFutures:
            return await binance(source, endpoint: "https://fapi.binance.com/fapi/v1/klines",
                                 symbols: [binanceSymbol], interval: interval, limit: limit)
        case .binanceUS:
            // Binance.US führt viele Paare nur gegen USD
            var symbols = [binanceSymbol]
            if binanceQuote(q) == "USDT" { symbols.append(b + "USD") }
            return await binance(source, endpoint: "https://api.binance.us/api/v3/klines",
                                 symbols: symbols, interval: interval, limit: limit)
        case .coinbase:
            return await coinbase(base: b, quote: q, interval: interval, limit: limit)
        }
    }

    private static func binance(_ source: Source, endpoint: String, symbols: [String],
                                interval: CandleInterval, limit: Int) async -> [MarketCandle]? {
        for symbol in symbols {
            if Task.isCancelled || BlockedSources.isBlocked(source.rawValue) { return nil }
            let url = "\(endpoint)?symbol=\(symbol)&interval=\(interval.binanceCode)&limit=\(limit)"
            guard let text = await get(source, url),
                  let candles = try? parseBinance(text), candles.count >= 2 else { continue }
            return candles
        }
        return nil
    }

    private static func coinbase(base b: String, quote q: String, interval: CandleInterval, limit: Int) async -> [MarketCandle]? {
        // Erlaubt sind nur 60, 300, 900, 3600, 21600, 86400 s
        let granularity: Int
        switch interval {
        case .h1, .h4: granularity = 3600
        case .d1, .w1: granularity = 86400
        }
        let quotes = ["USD", "USDT", "USDC"].contains(q) ? ["USD", "USDC"] : [q]
        for cq in quotes {
            if Task.isCancelled || BlockedSources.isBlocked(Source.coinbase.rawValue) { return nil }
            let url = "https://api.exchange.coinbase.com/products/\(b)-\(cq)/candles?granularity=\(granularity)"
            guard let text = await get(.coinbase, url), var raw = try? parseCoinbase(text) else { continue }
            // Je Anfrage höchstens 300 Kerzen: für lange Reihen (1 Jahr) eine ältere Seite dazu
            let rawPerCandle: Int
            switch interval {
            case .h1, .d1: rawPerCandle = 1
            case .h4: rawPerCandle = 4
            case .w1: rawPerCandle = 7
            }
            if let first = raw.first,
               let older = CandleSeries.coinbaseOlderWindow(firstOpen: first.openTime, fetched: raw.count,
                                                            needed: limit * rawPerCandle,
                                                            granularityMillis: Int64(granularity) * 1000) {
                let olderUrl = url + "&start=" + isoTime(older.lowerBound) + "&end=" + isoTime(older.upperBound)
                if let olderText = await get(.coinbase, olderUrl), let olderRaw = try? parseCoinbase(olderText) {
                    raw = CandleSeries.mergeAscending(older: olderRaw, newer: raw)
                }
            }
            let combined: [MarketCandle]
            switch interval {
            case .h1, .d1: combined = raw
            case .h4: combined = aggregate(raw, bucketMillis: 4 * hourMillis, offsetMillis: 0, fullCount: 4)
            case .w1: combined = aggregate(raw, bucketMillis: weekMillis, offsetMillis: mondayOffsetMillis, fullCount: 7)
            }
            let candles = Array(combined.suffix(limit))
            if candles.count >= 2 { return candles }
        }
        return nil
    }

    /// Zeitpunkt für Coinbase `start`/`end`: ISO 8601 in UTC, z. B. «2025-10-07T00:00:00Z».
    private static func isoTime(_ millis: Int64) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    /// Eine Anfrage; nil bei Fehler. 451/403 sperrt die Quelle (siehe `BlockedSources`).
    private static func get(_ source: Source, _ url: String) async -> String? {
        do {
            return try await MarketHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(source.rawValue, error)
            return nil
        }
    }

    private static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    /// Binance führt kein USD, sondern USDT.
    private static func binanceQuote(_ q: String) -> String { q == "USD" ? "USDT" : q }

    /// Binance-Kline: [openTime, open, high, low, close, volume, ...], älteste zuerst.
    static func parseBinance(_ text: String) throws -> [MarketCandle] {
        let array = try JArray(string: text)
        var out: [MarketCandle] = []
        out.reserveCapacity(array.count)
        for i in 0..<array.count {
            let k = try array.array(i)
            let candle = MarketCandle(
                openTime: try k.long(0),
                open: try k.double(1),
                high: try k.double(2),
                low: try k.double(3),
                close: try k.double(4),
                volume: try k.double(5)
            )
            if candle.close > 0 { out.append(candle) }
        }
        return out.sorted { $0.openTime < $1.openTime }
    }

    /// Coinbase: [time (s), low, high, open, close, volume], NEUESTE zuerst → aufsteigend sortieren.
    static func parseCoinbase(_ text: String) throws -> [MarketCandle] {
        let array = try JArray(string: text)
        var byTime: [Int64: MarketCandle] = [:]
        for i in 0..<array.count {
            let k = try array.array(i)
            let candle = MarketCandle(
                openTime: try k.long(0) * 1000,
                open: try k.double(3),
                high: try k.double(2),
                low: try k.double(1),
                close: try k.double(4),
                volume: try k.double(5)
            )
            if candle.close > 0 { byTime[candle.openTime] = candle }
        }
        return byTime.values.sorted { $0.openTime < $1.openTime }
    }

    /// Fasst aufsteigende Kerzen zu Blöcken von `bucketMillis` zusammen (Beginn bei `offsetMillis`).
    /// Der erste Block ist meist angeschnitten (Anfang des Abfragefensters) und fällt weg,
    /// wenn er weniger als `fullCount` Kerzen hat; der letzte läuft noch und bleibt.
    static func aggregate(_ candles: [MarketCandle], bucketMillis: Int64, offsetMillis: Int64, fullCount: Int) -> [MarketCandle] {
        var groups: [(bucket: Int64, items: [MarketCandle])] = []
        for c in candles {
            let bucket = (c.openTime - offsetMillis) / bucketMillis
            if let last = groups.last, last.bucket == bucket {
                groups[groups.count - 1].items.append(c)
            } else {
                groups.append((bucket: bucket, items: [c]))
            }
        }
        if let first = groups.first, first.items.count < fullCount { groups.removeFirst() }
        return groups.compactMap { group in
            guard let first = group.items.first, let last = group.items.last else { return nil }
            return MarketCandle(
                openTime: group.bucket * bucketMillis + offsetMillis,
                open: first.open,
                high: group.items.map(\.high).max() ?? first.high,
                low: group.items.map(\.low).min() ?? first.low,
                close: last.close,
                volume: group.items.reduce(0.0) { $0 + $1.volume }
            )
        }
    }
}
