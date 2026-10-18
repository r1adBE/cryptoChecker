import Foundation

// Gemeinsame Helfer der eigenen Kerzen je Börse (`ExchangeCandleSources.swift`, `ExchangeCandleSourcesMore.swift`):
// Zahlen lesen, Kennungen prüfen, Reihe abschliessen — wie `CandleParse` in `ExchangeCandleSources.kt` (Android).

/// Gemeinsames für die Quellen: Zahlen lesen, Kennungen prüfen, Reihe abschliessen.
enum CandleParse {
    /// Zahl aus String oder Number; NaN, wenn keine.
    static func num(_ v: Any?) -> Double {
        guard let v else { return .nan }
        return JSONValue.double(v) ?? .nan
    }

    static func num(_ a: JArray, _ i: Int) -> Double { a.optDouble(i, .nan) }

    /// Kennung nur aus Buchstaben, Ziffern, «-» und «_» (wird unverändert in die Adresse gesetzt).
    static func safeId(_ s: String?) -> String? {
        let t = (s ?? "").trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        return t
    }

    /// Kennung des Paars (`pairId`); fehlt sie, gebildet mit `derive` (wie der Adapter). Eine vorhandene,
    /// aber unsichere Kennung gilt nicht (nie aus den Kürzeln geraten).
    static func id(_ pairId: String?, derive: () -> String?) -> String? {
        let raw = (pairId ?? "").trimmingCharacters(in: .whitespaces)
        return raw.isEmpty ? derive() : safeId(raw)
    }

    /// Beide Kürzel (Buchstaben und Ziffern, gross) oder nil.
    static func assets(_ pair: CandlePair) -> (base: String, quote: String)? {
        guard let b = asset(pair.base), let q = asset(pair.quote) else { return nil }
        return (base: b, quote: q)
    }

    /// Kürzel nur aus Buchstaben und Ziffern, gross.
    static func asset(_ s: String) -> String? {
        let t = s.trimmingCharacters(in: .whitespaces).uppercased()
        guard !t.isEmpty, t.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
        return t
    }

    /// Gültige Kerzen (Kurse > 0), je Startzeit eine, aufsteigend, die jüngsten `limit`.
    static func finish(_ candles: [MarketCandle], limit: Int) -> [MarketCandle] {
        var byTime: [Int64: MarketCandle] = [:]
        var order: [Int64] = []
        for c in candles {
            guard c.open.isFinite, c.high.isFinite, c.low.isFinite, c.close.isFinite,
                  c.open > 0, c.high > 0, c.low > 0, c.close > 0, c.openTime > 0 else { continue }
            // Wie Kotlin `distinctBy`: die erste Kerze je Startzeit gilt
            if byTime[c.openTime] != nil { continue }
            let volume = c.volume.isFinite && c.volume >= 0 ? c.volume : 0
            byTime[c.openTime] = MarketCandle(openTime: c.openTime, open: c.open, high: c.high, low: c.low,
                                              close: c.close, volume: volume, quoteVolume: c.quoteVolume)
            order.append(c.openTime)
        }
        let sorted = order.sorted().compactMap { byTime[$0] }
        return Array(sorted.suffix(max(limit, 0)))
    }

    /// Quote-Umsatz nur, wenn gültig (sonst nähert das Blatt Menge × Schluss).
    static func quoteVolume(_ v: Double) -> Double? { v.isFinite && v >= 0 ? v : nil }

    /// Binance-Kline: [openTime, open, high, low, close, volume, closeTime, quoteVolume, …].
    /// COIN-M (`coinM`): Feld 5 zählt Kontrakte, Feld 7 ist die Menge in der Basis — dann
    /// Menge = Feld 7, Quote-Umsatz unbekannt.
    static func binance(_ text: String, coinM: Bool) throws -> [MarketCandle] {
        let array = try JArray(string: text)
        return binance(array, coinM: coinM)
    }

    /// Wie oben, aus dem schon gelesenen Array (Binance Alpha: `data` der Antwort).
    static func binance(_ array: JArray, coinM: Bool) -> [MarketCandle] {
        array.arrays.map { k in
            MarketCandle(openTime: JSONValue.int64(num(k, 0)),
                         open: num(k, 1), high: num(k, 2), low: num(k, 3), close: num(k, 4),
                         volume: coinM ? num(k, 7) : num(k, 5),
                         quoteVolume: coinM ? nil : quoteVolume(num(k, 7)))
        }
    }

    static func binanceInterval(_ interval: CandleInterval) -> String? {
        switch interval {
        case .h1: return "1h"
        case .h4: return "4h"
        case .d1: return "1d"
        case .w1: return nil
        }
    }

    /// Coinbase-Zeit für `start`/`end`: ISO 8601 in UTC, z. B. «2025-10-07T00:00:00Z».
    static func iso(_ millis: Int64) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}
