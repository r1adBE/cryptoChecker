import Foundation
import os
import WidgetKit

// Kerzen für den Chart des Einzel-Widgets. Eigene Datei neben `WidgetData.swift`.

// MARK: Mini-Chart

/// Kerzen (OHLC mit Startzeit) für den Chart des Einzel-Widgets: zuerst von der EIGENEN Börse des
/// Paars (`OwnCandleDataSource`), sonst gleiches Paar über die Ausweich-Kette (Binance, Binance.US,
/// Coinbase), siehe `CandleDataSource`. 10 Minuten zwischengespeichert.
enum WidgetSparkline {
    private static let cacheMillis: Int64 = 10 * 60_000
    /// Schlägt das Netz fehl, darf ein älterer Chart noch so lange gezeigt werden.
    private static let staleFallbackMillis: Int64 = 60 * 60_000
    /// Werte je Kerze im Zwischenspeicher: Startzeit, Open, High, Low, Close.
    private static let fields = 5

    static func symbol(base: String, quote: String) -> String? {
        var q = quote.uppercased()
        if q == "USD" { q = "USDT" }
        let s = base.uppercased() + q
        guard !s.isEmpty, s.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
        return s
    }

    /// Kerzen für den Chart eines Paars: zuerst von seiner eigenen Börse, sonst über die Ausweich-Kette
    /// (Futures-Paare zuerst USDⓈ-M) — je nur bei gleichem Token (`WidgetChartGeometry.pairCandles`).
    /// TradFi-Paare und DEX-Pools nie aus der Kette. Wie `SingleWidgetRenderer.chartCandles` (Android).
    /// `provider`: Börse der Kerzen (eigene Börse oder die der Ausweich-Kette; nil = unbekannt).
    static func candles(for watch: Watch, range: WidgetChartRangeOption) async -> Sourced<[MarketCandle]>? {
        let pair = CandlePair.of(watch)
        let price = watch.lastPrice
        if OwnCandleDataSource.supports(pair, interval: range.candleInterval) {
            let key = "widget_spark_own_" + pair.cacheKey + "|" + range.rawValue
            let now = EpochTime.nowMillis
            let cached = load(key)
            if now - cached.at < cacheMillis, let hit = WidgetChartGeometry.pairCandles(cached.candles, price: price) {
                return Sourced(value: hit, provider: watch.exchangeName)
            }
            let own = await OwnCandleDataSource.shared.candles(pair, interval: range.candleInterval, limit: range.limit)
            if let accepted = WidgetChartGeometry.pairCandles(own?.value, price: price) {
                save(key, accepted)
                return Sourced(value: accepted, provider: watch.exchangeName)
            }
            if now - cached.at < staleFallbackMillis, let old = WidgetChartGeometry.pairCandles(cached.candles, price: price) {
                return Sourced(value: old, provider: watch.exchangeName)
            }
        }
        guard OwnCandlePolicy.allowsFallback(pair) else { return nil }
        let fallback = await sourcedCandles(base: watch.baseAsset, quote: watch.quoteAsset, range: range,
                                            preferFutures: pair.isFutures, price: price)
        guard let accepted = WidgetChartGeometry.pairCandles(fallback?.value, price: price) else { return nil }
        // Börse der Ausweich-Reihe (für «Chart: Binance»); klar anders benannter Token: kein Chart
        let provider: String? = fallback?.provider
        guard TokenIdentityStore.shared.fallbackSeriesAllowed(exchangeKey: watch.exchangeKey, base: watch.baseAsset,
                                                              provider: provider) else { return nil }
        return Sourced(value: accepted, provider: provider)
    }

    /// Kerzen im gewählten Zeitraum (aufsteigend); Zwischenspeicher je Paar und Zeitraum.
    /// Einträge nur mit Schlusskursen (unter «v») werden nicht gelesen und beim
    /// nächsten Laden ersetzt. `price`: Kurs des Paars — passt die Reihe einer Quelle nicht
    /// (anderer Token), ist die nächste der Kette dran.
    static func candles(base: String, quote: String, range: WidgetChartRangeOption = .day,
                        preferFutures: Bool = false, price: Double? = nil) async -> [MarketCandle]? {
        await sourcedCandles(base: base, quote: quote, range: range, preferFutures: preferFutures, price: price)?.value
    }

    /// Wie `candles(base:…)`, dazu die Börse der Reihe (im Zwischenspeicher unter «p»; nil = Börse unbekannt).
    static func sourcedCandles(base: String, quote: String, range: WidgetChartRangeOption = .day,
                               preferFutures: Bool = false, price: Double? = nil) async -> Sourced<[MarketCandle]>? {
        guard let symbol = symbol(base: base, quote: quote) else { return nil }
        let key = "widget_spark_" + symbol + "|" + range.rawValue + (preferFutures ? "|F" : "")
        let now = EpochTime.nowMillis
        let cached = load(key)
        if cached.candles.count >= 2, now - cached.at < cacheMillis {
            return Sourced(value: cached.candles, provider: cached.provider)
        }

        // Fremde Reihe gleichen Kürzels: nächste Quelle der Kette
        var accept: (@Sendable ([MarketCandle]) async -> Bool)? = nil
        if let p = price { accept = { raw in WidgetChartGeometry.pairCandles(raw, price: p) != nil } }
        let sourced = await CandleDataSource.candlesSourced(base: base, quote: quote, interval: range.candleInterval,
                                                            limit: range.limit, preferFutures: preferFutures,
                                                            accept: accept)
        if let sourced, sourced.value.count >= 2 {
            save(key, sourced.value, provider: sourced.provider)
            return sourced
        }
        if cached.candles.count >= 2, now - cached.at < staleFallbackMillis {
            return Sourced(value: cached.candles, provider: cached.provider)
        }
        return nil
    }

    /// Gespeicherte Kerzen mit Zeitpunkt und Börse (leer/0/nil, wenn nichts da ist).
    private static func load(_ key: String) -> (at: Int64, candles: [MarketCandle], provider: String?) {
        let cached = SharedStorage.defaults.dictionary(forKey: key)
        let at = Int64((cached?["t"] as? Double) ?? 0)
        let candles = decode((cached?["c"] as? [Double]) ?? [])
        let provider = cached?["p"] as? String
        return candles.count >= 2 ? (at, candles, provider) : (0, [], nil)
    }

    private static func save(_ key: String, _ candles: [MarketCandle], provider: String? = nil) {
        // Einmal am Tag alte Einträge (entfernte Paare, andere Zeiträume) wegräumen
        WidgetSparkCache.pruneIfDue()
        var entry: [String: Any] = ["t": Double(EpochTime.nowMillis), "c": encode(candles)]
        if let provider { entry["p"] = provider }
        SharedStorage.defaults.set(entry, forKey: key)
    }

    private static func encode(_ candles: [MarketCandle]) -> [Double] {
        var out: [Double] = []
        out.reserveCapacity(candles.count * fields)
        for c in candles {
            out.append(Double(c.openTime))
            out.append(c.open)
            out.append(c.high)
            out.append(c.low)
            out.append(c.close)
        }
        return out
    }

    private static func decode(_ flat: [Double]) -> [MarketCandle] {
        guard flat.count >= fields, flat.count % fields == 0 else { return [] }
        // In Schritten mit festen Typen: der lange Ausdruck in einer Closure war für den Compiler zu teuer
        var out: [MarketCandle] = []
        out.reserveCapacity(flat.count / fields)
        var i = 0
        while i + fields <= flat.count {
            let openTime = Int64(flat[i])
            let open: Double = flat[i + 1]
            let high: Double = flat[i + 2]
            let low: Double = flat[i + 3]
            let close: Double = flat[i + 4]
            out.append(MarketCandle(openTime: openTime, open: open, high: high, low: low, close: close, volume: 0))
            i += fields
        }
        return out
    }
}
