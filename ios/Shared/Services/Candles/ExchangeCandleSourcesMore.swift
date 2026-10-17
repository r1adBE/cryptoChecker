import Foundation

// Eigene Kerzen, zweite Runde: weitere Börsen mit öffentlichem Endpunkt ohne Schlüssel (Stand Oktober
// 2026, je aus der Dokumentation der Börse). Kennung des Paars immer wie beim Kursabruf (ohne Kennung
// keine eigenen Kerzen). Wie ExchangeCandleSourcesMore.kt.

/// Zeit- und Kennungs-Helfer der zweiten Runde.
enum CandleTime {
    static let hour: Int64 = 3_600_000

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        var q = a / b
        if a % b != 0 && (a < 0) != (b < 0) { q -= 1 }
        return q
    }

    /// Beginn des laufenden Intervalls (UTC-Raster).
    static func aligned(_ now: Int64, step: Int64) -> Int64 { floorDiv(now, step) * step }

    /// Beginn eines Fensters, das `count` Kerzen bis zur laufenden umfasst.
    static func windowStart(_ now: Int64, step: Int64, count: Int) -> Int64 {
        aligned(now, step: step) - Int64(max(count - 1, 0)) * step
    }

    /// Kennung des Paars mit zusätzlich erlaubten Zeichen `extra` (z. B. «:» bei Bitfinex); sonst nil.
    static func pairId(_ pair: CandlePair, extra: String = "") -> String? {
        let t = (pair.pairId ?? "").trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.allSatisfy({ ($0.isASCII && ($0.isLetter || $0.isNumber)) || $0 == "-" || $0 == "_" || extra.contains($0) })
        else { return nil }
        return t
    }

    /// Tage seit 1970 für ein Datum (proleptisch gregorianisch, ohne Zeitzone).
    private static func daysFromCivil(_ y0: Int64, _ m: Int64, _ d: Int64) -> Int64 {
        let y = m <= 2 ? y0 - 1 : y0
        let era = floorDiv(y, 400)
        let yoe = y - era * 400
        let mp = (m + 9) % 12
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /// «2025-10-14T00:00:00», «…T13:30:00.000Z» (UTC) → Epoch-ms; nil, wenn nicht lesbar.
    static func parseIso(_ s: String?) -> Int64? {
        guard var t = s?.trimmingCharacters(in: .whitespaces) else { return nil }
        if t.hasSuffix("Z") { t.removeLast() }
        let chars = Array(t)
        guard chars.count >= 19, chars[4] == "-", chars[7] == "-", chars[10] == "T" || chars[10] == " ",
              chars[13] == ":", chars[16] == ":" else { return nil }
        func int(_ from: Int, _ to: Int) -> Int64? {
            let part = chars[from..<to]
            guard part.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            return Int64(String(part))
        }
        guard let y = int(0, 4), let mo = int(5, 7), let d = int(8, 10), let h = int(11, 13),
              let mi = int(14, 16), let se = int(17, 19) else { return nil }
        var ms: Int64 = 0
        if chars.count > 19 {
            guard chars[19] == ".", chars.count > 20 else { return nil }
            let frac = chars[20...]
            guard frac.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            let three = String(frac.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0)
            ms = Int64(three) ?? 0
        }
        let days = daysFromCivil(y, mo, d)
        return ((days * 24 + h) * 60 + mi) * 60_000 + se * 1000 + ms
    }

    /// Epoch-ms → «2025-10-14T00:00:00.000Z» (UTC).
    static func formatIso(_ millis: Int64) -> String {
        let days = floorDiv(millis, 86_400_000)
        let rest = millis - days * 86_400_000
        let z = days + 719468
        let era = floorDiv(z, 146097)
        let doe = z - era * 146097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        func pad(_ v: Int64, _ n: Int) -> String {
            let s = String(v)
            return String(repeating: "0", count: max(n - s.count, 0)) + s
        }
        return "\(pad(y, 4))-\(pad(m, 2))-\(pad(d, 2))T\(pad(rest / 3_600_000, 2)):\(pad(rest / 60_000 % 60, 2)):"
            + "\(pad(rest / 1000 % 60, 2)).\(pad(rest % 1000, 3))Z"
    }

    /// 4 h aus Stundenkerzen (UTC-Raster).
    static func fourHours(_ hourly: [MarketCandle]) -> [MarketCandle] {
        CandleDataSource.aggregate(hourly.sorted { $0.openTime < $1.openTime }, bucketMillis: 4 * hour, offsetMillis: 0, fullCount: 4)
    }

    /// Rohkerzen für `limit` Kerzen (4 h aus Stundenkerzen: viermal so viele und ein Block Reserve).
    static func rawCount(_ interval: CandleInterval, _ limit: Int) -> Int { interval == .h4 ? limit * 4 + 4 : limit }

    /// Menge in der Basis aus dem Quote-Umsatz (nur genähert) — wenn die Quelle nur Kontrakte zählt.
    static func baseFromQuote(_ quote: Double, close: Double) -> Double {
        quote.isFinite && quote >= 0 && close.isFinite && close > 0 ? quote / close : .nan
    }

    /// Spalte `name` einer Antwort mit parallelen Listen (MEXC Futures, Deribit).
    static func column(_ o: JObject, _ name: String, _ i: Int) -> Double {
        guard let a = o.optArray(name) else { return .nan }
        return CandleParse.num(a, i)
    }

    static func candle(_ t: Int64, o: Double, h: Double, l: Double, c: Double, v: Double, qv: Double? = nil) -> MarketCandle {
        MarketCandle(openTime: t, open: o, high: h, low: l, close: c, volume: v, quoteVolume: qv)
    }
}

/// Bitfinex: `/v2/candles/trade:{1h|1D}:{tSYMBOL}/hist?limit&sort=-1` — [MTS, OPEN, CLOSE, HIGH, LOW, VOLUME]; 4 h aus 1 h.
struct BitfinexCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Bitfinex"]
    private static func id(_ pair: CandlePair) -> String? {
        guard let id = CandleTime.pairId(pair, extra: ":"), id.hasPrefix("t") else { return nil }
        return id
    }

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && Self.id(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = Self.id(pair) else { return nil }
        let tf: String
        switch interval {
        case .h1, .h4: tf = "1h"
        case .d1: tf = "1D"
        case .w1: return nil
        }
        let n = min(CandleTime.rawCount(interval, limit), 10_000)
        guard let body = await http("https://api-pub.bitfinex.com/v2/candles/trade:\(tf):\(id)/hist?limit=\(n)&sort=-1"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(interval == .h4 ? CandleTime.fourHours(raw) : raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        try JArray(string: text).arrays.map { k in
            CandleTime.candle(JSONValue.int64(CandleParse.num(k, 0)), o: CandleParse.num(k, 1), h: CandleParse.num(k, 3),
                              l: CandleParse.num(k, 4), c: CandleParse.num(k, 2), v: CandleParse.num(k, 5))
        }
    }
}

/// Bitget (Unified, `/api/v3/market/candles?category={SPOT|USDT-FUTURES}&symbol&interval={1H|4H|1D}&limit`,
/// höchstens 100 je Seite, ältere Seiten mit `endTime`) — [ts, open, high, low, close, Menge, Umsatz].
/// Spot für «Bitget», USDT-Perpetuals (auch TradFi/RWA) für «BitgetFutures».
struct BitgetCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Bitget", "BitgetFutures"]
    private static let page = 100
    private static let maxPages = 4

    private static func category(_ pair: CandlePair) -> String? {
        if pair.marketKey == "BitgetFutures" && pair.contractType == .perpetual { return "USDT-FUTURES" }
        if pair.marketKey == "Bitget" && pair.contractType == .none { return "SPOT" }
        return nil
    }

    func supports(_ pair: CandlePair) -> Bool { Self.category(pair) != nil && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let category = Self.category(pair), let id = CandleTime.pairId(pair) else { return nil }
        let code: String
        switch interval {
        case .h1: code = "1H"
        case .h4: code = "4H"
        case .d1: code = "1D"
        case .w1: return nil
        }
        let base = "https://api.bitget.com/api/v3/market/candles?category=\(category)&symbol=\(id)&interval=\(code)"
        var all: [MarketCandle] = []
        var end: Int64? = nil
        for _ in 0..<Self.maxPages {
            let want = min(max(limit - all.count, 1), Self.page)
            let url = "\(base)&limit=\(want)" + (end.map { "&endTime=\($0)" } ?? "")
            guard let body = await http(url), let rows = try? Self.parse(body) else { break }
            all = rows + all
            if rows.count < want || all.count >= limit { break }
            guard let oldest = rows.map(\.openTime).min() else { break }
            end = oldest - 1
        }
        let candles = CandleParse.finish(all, limit: limit)
        return candles.isEmpty ? nil : candles
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optString("code") == "00000", let data = root.optArray("data") else { return nil }
        return data.arrays.map { k in
            CandleTime.candle(JSONValue.int64(CandleParse.num(k, 0)), o: CandleParse.num(k, 1), h: CandleParse.num(k, 2),
                              l: CandleParse.num(k, 3), c: CandleParse.num(k, 4), v: CandleParse.num(k, 5),
                              qv: CandleParse.quoteVolume(CandleParse.num(k, 6)))
        }
    }
}

/// Gate.io Spot: `/api/v4/spot/candlesticks?currency_pair&interval={1h|4h|1d}&limit` — [t (s), Umsatz, close, high, low, open, Menge].
struct GateIoCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["GateIo"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair), let code = CandleParse.binanceInterval(interval),
              let body = await http("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=\(id)&interval=\(code)&limit=\(min(limit, 1000))"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        try JArray(string: text).arrays.map { k in
            CandleTime.candle(JSONValue.int64(CandleParse.num(k, 0)) &* 1000, o: CandleParse.num(k, 5), h: CandleParse.num(k, 3),
                              l: CandleParse.num(k, 4), c: CandleParse.num(k, 2), v: CandleParse.num(k, 6),
                              qv: CandleParse.quoteVolume(CandleParse.num(k, 1)))
        }
    }
}

/// KuCoin Spot: `/api/v1/market/candles?type={1hour|4hour|1day}&symbol&startAt&endAt` (Sekunden, höchstens
/// 1500), NEUESTE zuerst — [time (s), open, close, high, low, Menge, Umsatz].
struct KucoinCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Kucoin"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let type: String
        switch interval {
        case .h1: type = "1hour"
        case .h4: type = "4hour"
        case .d1: type = "1day"
        case .w1: return nil
        }
        let start = CandleTime.windowStart(http.now, step: interval.millis, count: limit) / 1000
        guard let body = await http("https://api.kucoin.com/api/v1/market/candles?type=\(type)&symbol=\(id)&startAt=\(start)&endAt=\(http.now / 1000)"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optString("code") == "200000", let data = root.optArray("data") else { return nil }
        return data.arrays.map { k in
            CandleTime.candle(JSONValue.int64(CandleParse.num(k, 0)) &* 1000, o: CandleParse.num(k, 1), h: CandleParse.num(k, 3),
                              l: CandleParse.num(k, 4), c: CandleParse.num(k, 2), v: CandleParse.num(k, 5),
                              qv: CandleParse.quoteVolume(CandleParse.num(k, 6)))
        }
    }
}

/// MEXC Spot: `/api/v3/klines?symbol&interval={60m|4h|1d}&limit` (höchstens 500) — Binance-Aufbau.
struct MexcCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Mexc"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let code: String
        switch interval {
        case .h1: code = "60m"
        case .h4: code = "4h"
        case .d1: code = "1d"
        case .w1: return nil
        }
        guard let body = await http("https://api.mexc.com/api/v3/klines?symbol=\(id)&interval=\(code)&limit=\(min(limit, 500))"),
              let raw = try? CandleParse.binance(body, coinM: false) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }
}

/// MEXC Futures: `contract.mexc.com/api/v1/contract/kline/{symbol}?interval={Min60|Hour4|Day1}&start&end`
/// (Sekunden) — parallele Listen time/open/close/high/low/vol/amount; `vol` zählt Kontrakte, `amount` ist
/// der Umsatz (Menge daraus genähert). Auch TradFi-Kontrakte.
struct MexcFuturesCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["MexcFutures"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType.isPerpetual && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let code: String
        switch interval {
        case .h1: code = "Min60"
        case .h4: code = "Hour4"
        case .d1: code = "Day1"
        case .w1: return nil
        }
        let start = CandleTime.windowStart(http.now, step: interval.millis, count: limit) / 1000
        guard let body = await http("https://contract.mexc.com/api/v1/contract/kline/\(id)?interval=\(code)&start=\(start)&end=\(http.now / 1000)"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optBool("success"), let d = root.optObject("data"), let time = d.optArray("time") else { return nil }
        return (0..<time.count).map { i in
            let close = CandleTime.column(d, "close", i)
            let amount = CandleTime.column(d, "amount", i)
            return CandleTime.candle(JSONValue.int64(CandleParse.num(time, i)) &* 1000, o: CandleTime.column(d, "open", i),
                                     h: CandleTime.column(d, "high", i), l: CandleTime.column(d, "low", i), c: close,
                                     v: CandleTime.baseFromQuote(amount, close: close), qv: CandleParse.quoteVolume(amount))
        }
    }
}

/// HTX Spot: `api.huobi.pro/market/history/kline?symbol&period={60min|1day}&size` (höchstens 2000), je Kerze
/// {id (s), open, close, low, high, amount (Menge), vol (Umsatz)}; 4 h aus Stundenkerzen.
struct HtxCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Htx"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair)?.lowercased() else { return nil }
        let period: String
        switch interval {
        case .h1, .h4: period = "60min"
        case .d1: period = "1day"
        case .w1: return nil
        }
        let n = min(CandleTime.rawCount(interval, limit), 2000)
        guard let body = await http("https://api.huobi.pro/market/history/kline?symbol=\(id)&period=\(period)&size=\(n)"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(interval == .h4 ? CandleTime.fourHours(raw) : raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optString("status") == "ok", let data = root.optArray("data") else { return nil }
        return data.objects.map { o in
            CandleTime.candle(JSONValue.int64(CandleParse.num(o.raw["id"])) &* 1000, o: CandleParse.num(o.raw["open"]),
                              h: CandleParse.num(o.raw["high"]), l: CandleParse.num(o.raw["low"]), c: CandleParse.num(o.raw["close"]),
                              v: CandleParse.num(o.raw["amount"]), qv: CandleParse.quoteVolume(CandleParse.num(o.raw["vol"])))
        }
    }
}

/// Gemini: `/v2/candles/{symbol}/{1hr|1day}` — [ts (ms), open, high, low, close, Menge]. Die Dokumentation
/// nennt auch «1h»/«1d»; antwortet die erste Schreibweise nicht, die zweite. 4 h aus Stundenkerzen.
struct GeminiCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Gemini"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair)?.lowercased() else { return nil }
        let frames: [String]
        switch interval {
        case .h1, .h4: frames = ["1hr", "1h"]
        case .d1: frames = ["1day", "1d"]
        case .w1: return nil
        }
        for frame in frames {
            guard let body = await http("https://api.gemini.com/v2/candles/\(id)/\(frame)"),
                  let raw = try? Self.parse(body) else { continue }
            return CandleParse.finish(interval == .h4 ? CandleTime.fourHours(raw) : raw, limit: limit)
        }
        return nil
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        try JArray(string: text).arrays.map { k in
            CandleTime.candle(JSONValue.int64(CandleParse.num(k, 0)), o: CandleParse.num(k, 1), h: CandleParse.num(k, 2),
                              l: CandleParse.num(k, 3), c: CandleParse.num(k, 4), v: CandleParse.num(k, 5))
        }
    }
}

/// Crypto.com Exchange: `/exchange/v1/public/get-candlestick?instrument_name&timeframe={1h|4h|1D}&count&start_ts&end_ts`
/// (ms) — result.data [{o, h, l, c, v, t}].
struct CryptoComCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["CryptoCom"]

    func supports(_ pair: CandlePair) -> Bool { CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let tf: String
        switch interval {
        case .h1: tf = "1h"
        case .h4: tf = "4h"
        case .d1: tf = "1D"
        case .w1: return nil
        }
        let start = CandleTime.windowStart(http.now, step: interval.millis, count: limit)
        let url = "https://api.crypto.com/exchange/v1/public/get-candlestick?instrument_name=\(id)&timeframe=\(tf)&count=\(limit)"
            + "&start_ts=\(start)&end_ts=\(http.now)"
        guard let body = await http(url), let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optLong("code", -1) == 0, let data = root.optObject("result")?.optArray("data") else { return nil }
        return data.objects.map { o in
            CandleTime.candle(JSONValue.int64(CandleParse.num(o.raw["t"])), o: CandleParse.num(o.raw["o"]),
                              h: CandleParse.num(o.raw["h"]), l: CandleParse.num(o.raw["l"]), c: CandleParse.num(o.raw["c"]),
                              v: CandleParse.num(o.raw["v"]))
        }
    }
}

/// Upbit: `/v1/candles/minutes/{60|240}` bzw. `/v1/candles/days` `?market&count` (höchstens 200, ältere Seite
/// mit `to`), NEUESTE zuerst — {candle_date_time_utc, opening_price, high_price, low_price, trade_price,
/// candle_acc_trade_volume, candle_acc_trade_price}.
struct UpbitCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Upbit"]
    private static let page = 200

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let path: String
        switch interval {
        case .h1: path = "minutes/60"
        case .h4: path = "minutes/240"
        case .d1: path = "days"
        case .w1: return nil
        }
        let url = "https://api.upbit.com/v1/candles/\(path)?market=\(id)"
        guard let body = await http("\(url)&count=\(min(limit, Self.page))"), let first = try? Self.parse(body) else { return nil }
        var all = first
        if limit > Self.page, first.count >= Self.page, let oldest = first.map(\.openTime).min() {
            let to = String(CandleTime.formatIso(oldest).prefix(19)) + "Z"
            let rest = min(max(limit - first.count, 1), Self.page)
            if let olderBody = await http("\(url)&count=\(rest)&to=\(to)"), let older = try? Self.parse(olderBody) {
                all = older + first
            }
        }
        return CandleParse.finish(all, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        try JArray(string: text).objects.compactMap { o -> MarketCandle? in
            guard let time = CandleTime.parseIso(o.optString("candle_date_time_utc")) else { return nil }
            return CandleTime.candle(time, o: CandleParse.num(o.raw["opening_price"]), h: CandleParse.num(o.raw["high_price"]),
                                     l: CandleParse.num(o.raw["low_price"]), c: CandleParse.num(o.raw["trade_price"]),
                                     v: CandleParse.num(o.raw["candle_acc_trade_volume"]),
                                     qv: CandleParse.quoteVolume(CandleParse.num(o.raw["candle_acc_trade_price"])))
        }
    }
}

/// WOO X: `/v3/public/kline?symbol&type={1h|4h|1d}&limit` (höchstens 1000) — data.rows [{open, close, low,
/// high, volume (Menge), amount (Umsatz), startTimestamp}]. Spot («SPOT_…») und Perpetuals («PERP_…»).
struct WooCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Woo", "WooFutures"]

    private static func id(_ pair: CandlePair) -> String? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        if pair.marketKey == "WooFutures" && pair.contractType == .perpetual && id.hasPrefix("PERP_") { return id }
        if pair.marketKey == "Woo" && pair.contractType == .none && id.hasPrefix("SPOT_") { return id }
        return nil
    }

    func supports(_ pair: CandlePair) -> Bool { Self.id(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = Self.id(pair), let code = CandleParse.binanceInterval(interval),
              let body = await http("https://api.woox.io/v3/public/kline?symbol=\(id)&type=\(code)&limit=\(min(limit, 1000))"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optBool("success"), let rows = root.optObject("data")?.optArray("rows") else { return nil }
        return rows.objects.map { o in
            CandleTime.candle(JSONValue.int64(CandleParse.num(o.raw["startTimestamp"])), o: CandleParse.num(o.raw["open"]),
                              h: CandleParse.num(o.raw["high"]), l: CandleParse.num(o.raw["low"]), c: CandleParse.num(o.raw["close"]),
                              v: CandleParse.num(o.raw["volume"]), qv: CandleParse.quoteVolume(CandleParse.num(o.raw["amount"])))
        }
    }
}

/// Poloniex: `/markets/{symbol}/candles?interval={HOUR_1|HOUR_4|DAY_1}&limit` (höchstens 500) — [low, high,
/// open, close, amount (Umsatz), quantity (Menge), …, startTime (Index 12), closeTime].
struct PoloniexCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Poloniex"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let code: String
        switch interval {
        case .h1: code = "HOUR_1"
        case .h4: code = "HOUR_4"
        case .d1: code = "DAY_1"
        case .w1: return nil
        }
        guard let body = await http("https://api.poloniex.com/markets/\(id)/candles?interval=\(code)&limit=\(min(limit, 500))"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        try JArray(string: text).arrays.map { k in
            CandleTime.candle(JSONValue.int64(CandleParse.num(k, 12)), o: CandleParse.num(k, 2), h: CandleParse.num(k, 1),
                              l: CandleParse.num(k, 0), c: CandleParse.num(k, 3), v: CandleParse.num(k, 5),
                              qv: CandleParse.quoteVolume(CandleParse.num(k, 4)))
        }
    }
}

/// Deribit: `/api/v2/public/get_tradingview_chart_data?instrument_name&start_timestamp&end_timestamp&resolution={60|1D}`
/// (ms) — result {ticks, open, high, low, close, volume (Basis), cost (Quote), status}; 4 h aus 60 Minuten.
struct DeribitCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Deribit"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType.isPerpetual && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let resolution: String
        let step: Int64
        switch interval {
        case .h1, .h4: resolution = "60"; step = CandleTime.hour
        case .d1: resolution = "1D"; step = CandleInterval.d1.millis
        case .w1: return nil
        }
        let start = CandleTime.windowStart(http.now, step: step, count: CandleTime.rawCount(interval, limit))
        let url = "https://www.deribit.com/api/v2/public/get_tradingview_chart_data?instrument_name=\(id)"
            + "&start_timestamp=\(start)&end_timestamp=\(http.now)&resolution=\(resolution)"
        guard let body = await http(url), let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(interval == .h4 ? CandleTime.fourHours(raw) : raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        guard let r = try JObject(string: text).optObject("result"), r.optString("status") == "ok",
              let ticks = r.optArray("ticks") else { return nil }
        return (0..<ticks.count).map { i in
            CandleTime.candle(JSONValue.int64(CandleParse.num(ticks, i)), o: CandleTime.column(r, "open", i),
                              h: CandleTime.column(r, "high", i), l: CandleTime.column(r, "low", i), c: CandleTime.column(r, "close", i),
                              v: CandleTime.column(r, "volume", i), qv: CandleParse.quoteVolume(CandleTime.column(r, "cost", i)))
        }
    }
}

/// Hyperliquid: POST `/info` {"type":"candleSnapshot","req":{"coin","interval":"1h|4h|1d","startTime","endTime"}}
/// (ms, höchstens die jüngsten 5000) — [{t, T, s, i, o, c, h, l, v, n}].
struct HyperliquidCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Hyperliquid"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType.isPerpetual && CandleTime.pairId(pair, extra: ":") != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let coin = CandleTime.pairId(pair, extra: ":"), let code = CandleParse.binanceInterval(interval) else { return nil }
        let start = CandleTime.windowStart(http.now, step: interval.millis, count: limit)
        let json = "{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"\(coin)\",\"interval\":\"\(code)\",\"startTime\":\(start),\"endTime\":\(http.now)}}"
        guard let body = await http.post("https://api.hyperliquid.xyz/info", json), let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        try JArray(string: text).objects.map { o in
            CandleTime.candle(JSONValue.int64(CandleParse.num(o.raw["t"])), o: CandleParse.num(o.raw["o"]),
                              h: CandleParse.num(o.raw["h"]), l: CandleParse.num(o.raw["l"]), c: CandleParse.num(o.raw["c"]),
                              v: CandleParse.num(o.raw["v"]))
        }
    }
}

/// One Trading: `/fast/v1/candlesticks/{instrument}?unit={HOURS|DAYS}&period={1|4}&from&to&countBack` (ISO 8601,
/// NEUESTE zuerst) — {open, high, low, close, volume, start_time}. `volume` ist laut Beispiel der Umsatz in der
/// Quote (Menge daraus genähert).
struct OneTradingCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["OneTrading"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleTime.pairId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleTime.pairId(pair) else { return nil }
        let unit: String
        let period: Int
        switch interval {
        case .h1: unit = "HOURS"; period = 1
        case .h4: unit = "HOURS"; period = 4
        case .d1: unit = "DAYS"; period = 1
        case .w1: return nil
        }
        let from = CandleTime.formatIso(CandleTime.windowStart(http.now, step: interval.millis, count: limit))
            .replacingOccurrences(of: ":", with: "%3A")
        let to = CandleTime.formatIso(http.now).replacingOccurrences(of: ":", with: "%3A")
        let url = "https://api.onetrading.com/fast/v1/candlesticks/\(id)?unit=\(unit)&period=\(period)&from=\(from)&to=\(to)&countBack=\(limit)"
        guard let body = await http(url), let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard let list = root.optArray("candlesticks") else { return root.optString("status") == "no_data" ? [] : nil }
        return list.objects.compactMap { o -> MarketCandle? in
            guard let time = CandleTime.parseIso(o.optString("start_time")) else { return nil }
            let close = CandleParse.num(o.raw["close"])
            let quote = CandleParse.num(o.raw["volume"])
            return CandleTime.candle(time, o: CandleParse.num(o.raw["open"]), h: CandleParse.num(o.raw["high"]),
                                     l: CandleParse.num(o.raw["low"]), c: close,
                                     v: CandleTime.baseFromQuote(quote, close: close), qv: CandleParse.quoteVolume(quote))
        }
    }
}
