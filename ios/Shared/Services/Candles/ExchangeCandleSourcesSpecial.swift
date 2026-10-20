import Foundation

// Eigene Kerzen, zweite Runde (Fortsetzung von `ExchangeCandleSourcesGlobal.swift`): Upbit, Woo, Poloniex, Deribit,
// Hyperliquid und One Trading — öffentliche Endpunkte ohne Schlüssel (Stand Oktober 2026). Wie
// ExchangeCandleSourcesGlobal.kt.

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
