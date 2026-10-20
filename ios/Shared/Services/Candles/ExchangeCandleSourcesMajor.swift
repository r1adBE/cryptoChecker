import Foundation

// Eigene Kerzen je Börse: öffentliche Endpunkte ohne Schlüssel (Stand Oktober 2026).
// Kennung des Paars wie beim Kursabruf (`CandlePair.pairId`); fehlt sie, gebildet wie der Adapter.
// Wie ExchangeCandleSourcesMajor.kt.

/// Binance Spot: `/api/v3/klines` — zuerst der öffentliche Spiegel, dann api.binance.com.
struct BinanceCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Binance"]
    private static let endpoints = ["https://data-api.binance.vision/api/v3/klines", "https://api.binance.com/api/v3/klines"]

    static func symbol(_ pair: CandlePair) -> String? {
        CandleParse.id(pair.pairId) { CandleParse.assets(pair).map { $0.base + $0.quote } }
    }

    // Alpha-Paare («ALPHA_175USDT») haben eigene Kerzen (`BinanceAlphaCandles`)
    func supports(_ pair: CandlePair) -> Bool {
        pair.contractType == .none && !BinanceAlpha.isPairId(pair.pairId) && Self.symbol(pair) != nil
    }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let symbol = Self.symbol(pair), let code = CandleParse.binanceInterval(interval) else { return nil }
        for endpoint in Self.endpoints {
            guard let body = await http("\(endpoint)?symbol=\(symbol)&interval=\(code)&limit=\(min(limit, 1000))"),
                  let raw = try? CandleParse.binance(body, coinM: false) else { continue }
            let candles = CandleParse.finish(raw, limit: limit)
            if candles.count >= 2 { return candles }
        }
        return nil
    }
}

/// Binance Alpha (`BinanceAlpha`): `www.binance.com/bapi/defi/v1/public/alpha-trade/klines` für Paare mit
/// Kennung «ALPHA_<id>USDT» (unter der Börse Binance). Antwort `{"code":"000000","data":[[…Binance-Kline…]]}`.
struct BinanceAlphaCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = [BinanceAlpha.marketKey]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && BinanceAlpha.isPairId(pair.pairId) }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = pair.pairId, BinanceAlpha.isPairId(id), let code = CandleParse.binanceInterval(interval),
              let body = await http(BinanceAlpha.klinesURL(pairId: id, interval: code, limit: limit)),
              let array = try? BinanceAlpha.klines(body) else { return nil }
        return CandleParse.finish(CandleParse.binance(array, coinM: false), limit: limit)
    }
}

/// Binance.US: `api.binance.us/api/v3/klines`.
struct BinanceUsCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["BinanceUs"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && BinanceCandles.symbol(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let symbol = BinanceCandles.symbol(pair), let code = CandleParse.binanceInterval(interval),
              let body = await http("https://api.binance.us/api/v3/klines?symbol=\(symbol)&interval=\(code)&limit=\(min(limit, 1000))"),
              let raw = try? CandleParse.binance(body, coinM: false) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }
}

/// Binance Futures: USDⓈ-M `fapi.binance.com/fapi/v1/klines`, COIN-M (Kennung «2:BTCUSD_PERP»)
/// `dapi.binance.com/dapi/v1/klines`. Perpetuals (auch TradFi, gleiche Endpunkte) und Laufzeit-Kontrakte
/// (Quartal): deren Symbol aus der Paarliste wie die Kursabfrage (`BTCUSDT_251226`, COIN-M `BTCUSD_251226`)
/// — nie die Kerzen des Perpetuals.
struct BinanceFuturesCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["BinanceFutures"]
    private static let coinMPrefix = "2:"

    /// Verfallstag (UTC, «yyMMdd») des Laufzeit-Kontrakts wie die Kursabfrage; nil = Perpetual/Spot.
    static func deliveryCode(_ type: FuturesContractType) -> String? {
        guard let date = type.deliveryDate else { return nil }
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyMMdd"
        return f.string(from: date)
    }

    static func id(_ pair: CandlePair) -> (symbol: String, coinM: Bool)? {
        let raw = pair.pairId?.trimmingCharacters(in: .whitespaces)
        let coinM = raw?.hasPrefix(coinMPrefix) ?? false
        if pair.isDated {
            // Symbol der Börse aus der Paarliste wie die Kursabfrage («BTCUSDT_251226»), nie aus dem
            // Kalender (nach der Abrechnung Freitag 08:00 UTC träfe der den abgelaufenen Kontrakt)
            if let raw {
                let stripped = coinM ? String(raw.dropFirst(coinMPrefix.count)) : raw
                if FuturesContractType.deliveryCode(of: stripped) != nil {
                    return CandleParse.safeId(stripped).map { (symbol: $0, coinM: coinM) }
                }
            }
            // Ältere Kennung ohne Verfallstag: Kalender als Notbehelf
            guard let code = deliveryCode(pair.contractType), let assets = CandleParse.assets(pair) else { return nil }
            return (symbol: "\(assets.base)\(assets.quote)_\(code)", coinM: coinM)
        }
        if let raw, coinM {
            return CandleParse.safeId(String(raw.dropFirst(coinMPrefix.count))).map { (symbol: $0, coinM: true) }
        }
        return BinanceCandles.symbol(pair).map { (symbol: $0, coinM: false) }
    }

    func supports(_ pair: CandlePair) -> Bool {
        (pair.contractType.isPerpetual || pair.isDated) && Self.id(pair) != nil
    }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = Self.id(pair), let code = CandleParse.binanceInterval(interval) else { return nil }
        let endpoint = id.coinM ? "https://dapi.binance.com/dapi/v1/klines" : "https://fapi.binance.com/fapi/v1/klines"
        guard let body = await http("\(endpoint)?symbol=\(id.symbol)&interval=\(code)&limit=\(min(limit, 1500))"),
              let raw = try? CandleParse.binance(body, coinM: id.coinM) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }
}

/// Coinbase Exchange: `/products/{id}/candles` (höchstens 300 je Anfrage, NEUESTE zuerst; nur 1 h und
/// 1 Tag — 4 h aus Stundenkerzen zusammengesetzt; für 1 Jahr eine ältere Seite dazu).
struct CoinbaseCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Coinbase"]
    private static let hourMillis: Int64 = 60 * 60_000

    static func product(_ pair: CandlePair) -> String? {
        CandleParse.id(pair.pairId) { CandleParse.assets(pair).map { "\($0.base)-\($0.quote)" } }
    }

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && Self.product(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let product = Self.product(pair) else { return nil }
        let granularity: Int
        switch interval {
        case .h1, .h4: granularity = 3600
        case .d1: granularity = 86400
        case .w1: return nil
        }
        let url = "https://api.exchange.coinbase.com/products/\(product)/candles?granularity=\(granularity)"
        guard let body = await http(url), var raw = try? CandleDataSource.parseCoinbase(body) else { return nil }
        let needed = limit * (interval == .h4 ? 4 : 1)
        if let first = raw.first,
           let older = CandleSeries.coinbaseOlderWindow(firstOpen: first.openTime, fetched: raw.count, needed: needed,
                                                        granularityMillis: Int64(granularity) * 1000) {
            let olderUrl = url + "&start=" + CandleParse.iso(older.lowerBound) + "&end=" + CandleParse.iso(older.upperBound)
            if let olderBody = await http(olderUrl), let olderRaw = try? CandleDataSource.parseCoinbase(olderBody) {
                raw = CandleSeries.mergeAscending(older: olderRaw, newer: raw)
            }
        }
        let candles = interval == .h4
            ? CandleDataSource.aggregate(raw, bucketMillis: 4 * Self.hourMillis, offsetMillis: 0, fullCount: 4)
            : raw
        return CandleParse.finish(candles, limit: limit)
    }
}

/// Kraken: `/0/public/OHLC?pair={id}&interval={Minuten}` — höchstens die jüngsten 720 Kerzen.
/// Je Kerze [Zeit (s), open, high, low, close, vwap, Menge, Anzahl]; Quote-Umsatz = vwap × Menge.
/// Kennung = Schlüssel der Paarliste (z. B. «XXBTZUSD»); ohne Kennung keine eigenen Kerzen.
struct KrakenCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Kraken"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CandleParse.safeId(pair.pairId) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = CandleParse.safeId(pair.pairId) else { return nil }
        let minutes: Int
        switch interval {
        case .h1: minutes = 60
        case .h4: minutes = 240
        case .d1: minutes = 1440
        case .w1: return nil
        }
        guard let body = await http("https://api.kraken.com/0/public/OHLC?pair=\(id)&interval=\(minutes)"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        if let errors = root.optArray("error"), !errors.isEmpty { return nil }
        guard let result = root.optObject("result") else { return nil }
        // Erster Schlüssel ausser «last» (Name je nach Paar, z. B. «XXBTZUSD»); sortiert, damit eindeutig
        guard let key = result.keys.sorted().first(where: { $0 != "last" && result.optArray($0) != nil }),
              let rows = result.optArray(key) else { return nil }
        return rows.arrays.map { k in
            let vwap = CandleParse.num(k, 5)
            let volume = CandleParse.num(k, 6)
            return MarketCandle(openTime: JSONValue.int64(CandleParse.num(k, 0)) &* 1000,
                                open: CandleParse.num(k, 1), high: CandleParse.num(k, 2),
                                low: CandleParse.num(k, 3), close: CandleParse.num(k, 4),
                                volume: volume, quoteVolume: CandleParse.quoteVolume(vwap * volume))
        }
    }

    func isRateLimited(_ body: String) -> Bool { RateLimitSignal.inOkBody(body) }
}

/// Bitstamp: `/api/v2/ohlc/{url_symbol}/?step={s}&limit={n}` (höchstens 1000), älteste zuerst;
/// je Kerze ein Objekt mit Texten (Zeit in Sekunden).
struct BitstampCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Bitstamp"]

    private static func symbol(_ pair: CandlePair) -> String? {
        CandleParse.id(pair.pairId) { CandleParse.assets(pair).map { $0.base + $0.quote } }?.lowercased()
    }

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && Self.symbol(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let symbol = Self.symbol(pair) else { return nil }
        let step: Int
        switch interval {
        case .h1: step = 3600
        case .h4: step = 14400
        case .d1: step = 86400
        case .w1: return nil
        }
        guard let body = await http("https://www.bitstamp.net/api/v2/ohlc/\(symbol)/?step=\(step)&limit=\(min(limit, 1000))"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        guard let rows = try JObject(string: text).optObject("data")?.optArray("ohlc") else { return nil }
        return rows.objects.map { o in
            MarketCandle(openTime: JSONValue.int64(CandleParse.num(o.raw["timestamp"])) &* 1000,
                         open: CandleParse.num(o.raw["open"]), high: CandleParse.num(o.raw["high"]),
                         low: CandleParse.num(o.raw["low"]), close: CandleParse.num(o.raw["close"]),
                         volume: CandleParse.num(o.raw["volume"]))
        }
    }
}

/// Bitvavo: `/v2/{market}/candles?interval={1h|4h|1d}&limit={n}` (höchstens 1440), NEUESTE zuerst;
/// je Kerze [Zeit (ms), open, high, low, close, Menge] (Texte).
struct BitvavoCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Bitvavo"]

    func supports(_ pair: CandlePair) -> Bool { pair.contractType == .none && CoinbaseCandles.product(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let market = CoinbaseCandles.product(pair), let code = CandleParse.binanceInterval(interval),
              let body = await http("https://api.bitvavo.com/v2/\(market)/candles?interval=\(code)&limit=\(min(limit, 1440))"),
              let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle] {
        let array = try JArray(string: text)
        return (0..<array.count).compactMap { i -> MarketCandle? in
            if let k = array.optArray(i) {
                return MarketCandle(openTime: JSONValue.int64(CandleParse.num(k, 0)),
                                    open: CandleParse.num(k, 1), high: CandleParse.num(k, 2),
                                    low: CandleParse.num(k, 3), close: CandleParse.num(k, 4),
                                    volume: CandleParse.num(k, 5))
            }
            // Laut Dokumentation auch als Objekt mit Namen möglich
            guard let o = array.optObject(i) else { return nil }
            return MarketCandle(openTime: JSONValue.int64(CandleParse.num(o.raw["timestamp"])),
                                open: CandleParse.num(o.raw["open"]), high: CandleParse.num(o.raw["high"]),
                                low: CandleParse.num(o.raw["low"]), close: CandleParse.num(o.raw["close"]),
                                volume: CandleParse.num(o.raw["volume"]))
        }
    }
}

/// Bybit v5: `/v5/market/kline?category={spot|linear}&symbol={id}&interval={60|240|D}&limit={n}`
/// (höchstens 1000), NEUESTE zuerst; je Kerze [Start (ms), open, high, low, close, Menge, Umsatz].
/// Spot für «Bybit», USDT/USDC-Perpetuals (linear, auch TradFi) für «BybitFutures».
struct BybitCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Bybit", "BybitFutures"]

    private static func category(_ pair: CandlePair) -> String? {
        if pair.marketKey == "BybitFutures" && pair.contractType == .perpetual { return "linear" }
        if pair.marketKey == "Bybit" && pair.contractType == .none { return "spot" }
        return nil
    }

    func supports(_ pair: CandlePair) -> Bool { Self.category(pair) != nil && BinanceCandles.symbol(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let category = Self.category(pair), let symbol = BinanceCandles.symbol(pair) else { return nil }
        let code: String
        switch interval {
        case .h1: code = "60"
        case .h4: code = "240"
        case .d1: code = "D"
        case .w1: return nil
        }
        let url = "https://api.bybit.com/v5/market/kline?category=\(category)&symbol=\(symbol)&interval=\(code)&limit=\(min(limit, 1000))"
        guard let body = await http(url), let raw = try? Self.parse(body) else { return nil }
        return CandleParse.finish(raw, limit: limit)
    }

    static func parse(_ text: String) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optLong("retCode", -1) == 0, let list = root.optObject("result")?.optArray("list") else { return nil }
        return list.arrays.map { k in
            MarketCandle(openTime: JSONValue.int64(CandleParse.num(k, 0)),
                         open: CandleParse.num(k, 1), high: CandleParse.num(k, 2),
                         low: CandleParse.num(k, 3), close: CandleParse.num(k, 4),
                         volume: CandleParse.num(k, 5), quoteVolume: CandleParse.quoteVolume(CandleParse.num(k, 6)))
        }
    }

    /// retCode 10006 = zu viele Anfragen.
    func isRateLimited(_ body: String) -> Bool { RateLimitSignal.inOkBody(body) }
}

/// OKX: `/api/v5/market/candles?instId={id}&bar={1H|4H|1Dutc}&limit={n}` (höchstens 300 je Seite,
/// ältere Seite mit `after`), NEUESTE zuerst; je Kerze [ts, o, h, l, c, vol, volCcy, volCcyQuote,
/// confirm]. Menge: Spot `vol`, Swap `volCcy` (vol zählt dort Kontrakte); Umsatz `volCcyQuote`.
struct OkxCandles: ExchangeCandleSource {
    let marketKeys: Set<String> = ["Okex", "OkexFutures"]
    private static let page = 300

    private static func instId(_ pair: CandlePair) -> String? {
        let swap = pair.marketKey == "OkexFutures"
        if swap && !pair.contractType.isPerpetual { return nil }
        if !swap && pair.contractType != .none { return nil }
        let derived = CandleParse.id(pair.pairId) {
            CandleParse.assets(pair).map { swap ? "\($0.base)-\($0.quote)-SWAP" : "\($0.base)-\($0.quote)" }
        }
        guard let id = derived, id.hasSuffix("-SWAP") == swap else { return nil }
        return id
    }

    func supports(_ pair: CandlePair) -> Bool { Self.instId(pair) != nil }

    func fetch(_ pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHTTP) async -> [MarketCandle]? {
        guard let id = Self.instId(pair) else { return nil }
        let swap = pair.marketKey == "OkexFutures"
        let bar: String
        switch interval {
        case .h1: bar = "1H"
        case .h4: bar = "4H"
        case .d1: bar = "1Dutc"
        case .w1: return nil
        }
        let url = "https://www.okx.com/api/v5/market/candles?instId=\(id)&bar=\(bar)"
        guard let body = await http("\(url)&limit=\(min(limit, Self.page))"),
              let firstPage = try? Self.parse(body, swap: swap) else { return nil }
        var all = firstPage
        // Mehr als eine Seite (1 Jahr Tageskerzen): ältere Kerzen vor der ältesten bisherigen
        if limit > Self.page, firstPage.count >= Self.page, let oldest = firstPage.map(\.openTime).min() {
            let rest = min(max(limit - firstPage.count, 1), Self.page)
            if let olderBody = await http("\(url)&after=\(oldest)&limit=\(rest)"),
               let older = try? Self.parse(olderBody, swap: swap) {
                all = older + firstPage
            }
        }
        return CandleParse.finish(all, limit: limit)
    }

    static func parse(_ text: String, swap: Bool) throws -> [MarketCandle]? {
        let root = try JObject(string: text)
        guard root.optString("code") == "0", let data = root.optArray("data") else { return nil }
        return data.arrays.map { k in
            MarketCandle(openTime: JSONValue.int64(CandleParse.num(k, 0)),
                         open: CandleParse.num(k, 1), high: CandleParse.num(k, 2),
                         low: CandleParse.num(k, 3), close: CandleParse.num(k, 4),
                         volume: swap ? CandleParse.num(k, 6) : CandleParse.num(k, 5),
                         quoteVolume: CandleParse.quoteVolume(CandleParse.num(k, 7)))
        }
    }

    /// code 50011 = zu viele Anfragen.
    func isRateLimited(_ body: String) -> Bool { RateLimitSignal.inOkBody(body) }
}
