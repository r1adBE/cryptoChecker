import Foundation

/// Kurs eines Coins gegen USDT auf der Uhr: letzter Kurs, Änderung über 24 h in Prozent (nil = unbekannt), Börse.
struct WatchQuote: Codable, Equatable, Sendable {
    let symbol: String
    let price: Double
    let change24h: Double?
    let source: String
}

/// Abfragen und Antworten der Börsen für die Uhr (wie Android `WatchQuotes`): Binance `ticker/24hr?symbols=[…]` (alle
/// Coins in einer Abfrage), Bybit `v5/market/tickers?category=spot&symbol=` und OKX `v5/market/ticker?instId=` (je Coin
/// eine kleine Abfrage statt der ganzen Spot-Liste von mehreren 100 KB). Nur gewünschte Coins, nur Kurse > 0.
/// In WatchCore, damit die Unit-Tests der App es erreichen (`Parity/watch_quotes.json`).
enum WatchQuoteParser {
    static let binanceName = "Binance"
    static let bybitName = "Bybit"
    static let okxName = "OKX"

    /// Binance: nur die gewünschten Coins (`symbols=["BTCUSDT",…]`).
    static func binanceURL(_ coins: [String]) -> URL? {
        var parts = URLComponents(string: "https://api.binance.com/api/v3/ticker/24hr")
        let symbols = "[" + coins.map { "\"\($0)USDT\"" }.joined(separator: ",") + "]"
        parts?.queryItems = [URLQueryItem(name: "symbols", value: symbols)]
        return parts?.url
    }

    /// Bybit: ein Spot-Paar (Kürzel nur `[A-Z0-9]`, siehe `WatchCoins`).
    static func bybitURL(_ coin: String) -> URL? {
        URL(string: "https://api.bybit.com/v5/market/tickers?category=spot&symbol=\(coin)USDT")
    }

    /// OKX: ein Spot-Paar.
    static func okxURL(_ coin: String) -> URL? {
        URL(string: "https://www.okx.com/api/v5/market/ticker?instId=\(coin)-USDT")
    }

    /// Binance: Array von `{symbol:"BTCUSDT", lastPrice, priceChangePercent}`.
    static func binance(_ data: Data, wanted: [String]) -> [WatchQuote] {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        return array.compactMap { o in
            guard let coin = coin(o["symbol"] as? String, wanted), let price = positive(o["lastPrice"]) else { return nil }
            return WatchQuote(symbol: coin, price: price, change24h: finite(o["priceChangePercent"]), source: binanceName)
        }
    }

    /// Bybit: `result.list` mit `{symbol:"BTCUSDT", lastPrice, price24hPcnt}` (Anteil, 0.0123 = 1.23 %); unbekanntes
    /// Paar: `retCode` ≠ 0 ohne `list` → leer.
    static func bybit(_ data: Data, wanted: [String]) -> [WatchQuote] {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = (root["result"] as? [String: Any])?["list"] as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let coin = coin(o["symbol"] as? String, wanted), let price = positive(o["lastPrice"]) else { return nil }
            return WatchQuote(symbol: coin, price: price, change24h: finite(o["price24hPcnt"]).map { $0 * 100 },
                              source: bybitName)
        }
    }

    /// OKX: `data` mit `{instId:"BTC-USDT", last, open24h}`; Änderung = last gegen open24h; unbekannt: `data` leer.
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

/// Ablauf der Kursabfrage der Uhr (wie Android `WatchPriceSource.fetch`): zuerst Binance (alle Coins in einer
/// Abfrage), fehlende bzw. bei Fehler oder Sperre Bybit, dann OKX — dort je fehlendem Coin eine kleine Abfrage
/// (gleichzeitig), nie die ganze Spot-Liste. Höchstens `WatchCoins.max` Coins. Die Abfrage selbst (`get`, nil =
/// HTTP-Fehler, Sperre oder kein Netz) kommt von aussen: `WatchPriceSource` auf der Uhr, eine Attrappe in den Tests.
enum WatchQuoteFetch {
    private enum Fallback: Sendable { case bybit, okx }

    /// Obergrenze eines ganzen Abrufs der Komplikation (alle Börsen zusammen) — wie Android
    /// `PriceComplicationService.FETCH_TIMEOUT_MS`: WidgetKit wartet nicht beliebig auf die Zeitleiste.
    static let complicationTimeoutSeconds: Double = 20

    /// `fetch` mit Gesamt-Zeitgrenze: Ist `run` nach `seconds` nicht fertig, wird er abgebrochen und das Ergebnis ist
    /// leer (der gespeicherte Kurs bleibt). Wie Android `withTimeoutOrNull`.
    static func within(seconds: Double,
                       _ run: @escaping @Sendable () async -> [String: WatchQuote]) async -> [String: WatchQuote] {
        await withTaskGroup(of: [String: WatchQuote]?.self) { group in
            group.addTask { await run() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? [:]
        }
    }

    static func fetch(_ coins: [String], get: @escaping @Sendable (URL) async -> Data?) async -> [String: WatchQuote] {
        var seen = Set<String>()
        let wanted = Array(coins.filter { seen.insert($0).inserted }.prefix(WatchCoins.max))
        guard !wanted.isEmpty else { return [:] }
        var binance: [WatchQuote] = []
        if let url = WatchQuoteParser.binanceURL(wanted), let data = await get(url) {
            binance = WatchQuoteParser.binance(data, wanted: wanted)
        }
        var merged = WatchQuoteParser.merge(binance)
        for fallback in [Fallback.bybit, .okx] where merged.count < wanted.count {
            let found = await perCoin(wanted.filter { merged[$0] == nil }, fallback, get)
            merged = WatchQuoteParser.merge(Array(merged.values), found)
        }
        return merged
    }

    /// Je Coin eine Abfrage (gleichzeitig); ein Fehler betrifft nur diesen Coin.
    private static func perCoin(_ coins: [String], _ fallback: Fallback,
                                _ get: @escaping @Sendable (URL) async -> Data?) async -> [WatchQuote] {
        await withTaskGroup(of: [WatchQuote].self) { group in
            for coin in coins {
                group.addTask {
                    switch fallback {
                    case .bybit:
                        guard let url = WatchQuoteParser.bybitURL(coin), let data = await get(url) else { return [] }
                        return WatchQuoteParser.bybit(data, wanted: [coin])
                    case .okx:
                        guard let url = WatchQuoteParser.okxURL(coin), let data = await get(url) else { return [] }
                        return WatchQuoteParser.okx(data, wanted: [coin])
                    }
                }
            }
            var out: [WatchQuote] = []
            for await found in group { out += found }
            return out
        }
    }
}
