import Foundation

/// Kurs eines Coins gegen USDT auf der Uhr: letzter Kurs, Änderung über 24 h in Prozent (nil = unbekannt), Börse.
struct WatchQuote: Codable, Equatable, Sendable {
    let symbol: String
    let price: Double
    let change24h: Double?
    let source: String
}

/// Antworten der Börsen für die Uhr lesen (Build 155, wie Android `WatchQuotes`): Binance `ticker/24hr`, Bybit
/// `v5/market/tickers` (Spot), OKX `v5/market/tickers` (Spot). Nur gewünschte Coins, nur Kurse > 0.
enum WatchQuoteParser {
    static let binanceName = "Binance"
    static let bybitName = "Bybit"
    static let okxName = "OKX"

    /// Binance: Array von `{symbol:"BTCUSDT", lastPrice, priceChangePercent}`.
    static func binance(_ data: Data, wanted: [String]) -> [WatchQuote] {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        return array.compactMap { o in
            guard let coin = coin(o["symbol"] as? String, wanted), let price = positive(o["lastPrice"]) else { return nil }
            return WatchQuote(symbol: coin, price: price, change24h: finite(o["priceChangePercent"]), source: binanceName)
        }
    }

    /// Bybit: `result.list` mit `{symbol:"BTCUSDT", lastPrice, price24hPcnt}` (Anteil, 0.0123 = 1.23 %).
    static func bybit(_ data: Data, wanted: [String]) -> [WatchQuote] {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = (root["result"] as? [String: Any])?["list"] as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let coin = coin(o["symbol"] as? String, wanted), let price = positive(o["lastPrice"]) else { return nil }
            return WatchQuote(symbol: coin, price: price, change24h: finite(o["price24hPcnt"]).map { $0 * 100 },
                              source: bybitName)
        }
    }

    /// OKX: `data` mit `{instId:"BTC-USDT", last, open24h}`; Änderung = last gegen open24h.
    static func okx(_ data: Data, wanted: [String]) -> [WatchQuote] {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = root["data"] as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            let symbol = (o["instId"] as? String)?.replacingOccurrences(of: "-", with: "")
            guard let coin = coin(symbol, wanted), let price = positive(o["last"]) else { return nil }
            let change = positive(o["open24h"]).map { (price / $0 - 1) * 100 }
            return WatchQuote(symbol: coin, price: price, change24h: change, source: okxName)
        }
    }

    /// Je Coin die erste Börse, die ihn liefert (Reihenfolge = Vorrang).
    static func merge(_ sources: [WatchQuote]...) -> [String: WatchQuote] {
        var out: [String: WatchQuote] = [:]
        for list in sources {
            for q in list where out[q.symbol] == nil { out[q.symbol] = q }
        }
        return out
    }

    private static func coin(_ symbol: String?, _ wanted: [String]) -> String? {
        guard let symbol, symbol.hasSuffix("USDT") else { return nil }
        let coin = String(symbol.dropLast(4))
        return wanted.contains(coin) ? coin : nil
    }

    private static func finite(_ raw: Any?) -> Double? {
        guard let text = raw as? String, let v = Double(text), v.isFinite else { return nil }
        return v
    }

    private static func positive(_ raw: Any?) -> Double? {
        guard let v = finite(raw), v > 0 else { return nil }
        return v
    }
}

/// Kurse direkt bei den Börsen (kein Server): zuerst Binance (nur die gewünschten Coins), fehlende bzw. bei Fehler oder
/// Sperre Bybit, dann OKX. Antworten höchstens `maxBytes`, je Abfrage `timeout`.
enum WatchPriceSource {
    private static let timeout: TimeInterval = 10
    private static let maxBytes = 4 * 1024 * 1024

    static func fetch(_ coins: [String]) async -> [String: WatchQuote] {
        guard !coins.isEmpty else { return [:] }
        let symbols = "[" + coins.map { "\"\($0)USDT\"" }.joined(separator: ",") + "]"
        var parts = URLComponents(string: "https://api.binance.com/api/v3/ticker/24hr")
        parts?.queryItems = [URLQueryItem(name: "symbols", value: symbols)]
        let binance = await get(parts?.url).map { WatchQuoteParser.binance($0, wanted: coins) } ?? []
        var merged = WatchQuoteParser.merge(binance)
        if merged.count < coins.count {
            let missing = coins.filter { merged[$0] == nil }
            let bybit = await get(URL(string: "https://api.bybit.com/v5/market/tickers?category=spot"))
                .map { WatchQuoteParser.bybit($0, wanted: missing) } ?? []
            merged = WatchQuoteParser.merge(binance, bybit)
        }
        if merged.count < coins.count {
            let missing = coins.filter { merged[$0] == nil }
            let okx = await get(URL(string: "https://www.okx.com/api/v5/market/tickers?instType=SPOT"))
                .map { WatchQuoteParser.okx($0, wanted: missing) } ?? []
            merged = WatchQuoteParser.merge(Array(merged.values), okx)
        }
        return merged
    }

    private static func get(_ url: URL?) async -> Data? {
        guard let url else { return nil }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout * 2
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        guard let result = try? await session.data(for: request),
              let http = result.1 as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              result.0.count <= maxBytes else { return nil }
        return result.0
    }
}
