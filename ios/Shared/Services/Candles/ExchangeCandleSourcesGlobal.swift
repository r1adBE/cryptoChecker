import Foundation

// Eigene Kerzen, zweite Runde: weitere Börsen mit öffentlichem Endpunkt ohne Schlüssel (Stand Oktober
// 2026, je aus der Dokumentation der Börse). Kennung des Paars immer wie beim Kursabruf (ohne Kennung
// keine eigenen Kerzen). Wie ExchangeCandleSourcesGlobal.kt.

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
