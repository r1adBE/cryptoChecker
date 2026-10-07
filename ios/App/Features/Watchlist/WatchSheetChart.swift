import SwiftUI

// Kurs-Chart im Aktionsblatt — reine Regeln und Zwischenspeicher, wie `SheetChart.kt` und
// `SheetChartRepository.kt` (Android). Zeichnen: `PriceChartRenderer` (Shared, wie das Einzel-Widget).

extension PriceChartRange {
    /// Gültigkeit im Zwischenspeicher: 5 Minuten (24 h), 30 Minuten (7 und 30 Tage).
    var sheetCacheMillis: Int64 {
        switch self {
        case .day: 5 * 60_000
        case .week, .month: 30 * 60_000
        }
    }

    /// Vorlage für Datum/Uhrzeit beim Ziehen (`setLocalizedDateFormatFromTemplate`):
    /// 24 h «Di 14:00», 7 Tage «Di 14. Okt., 16:00», 30 Tage «Di 14. Okt.».
    var sheetTimeTemplate: String {
        switch self {
        case .day: "EEEjm"
        case .week: "EEEdMMMjm"
        case .month: "EEEdMMM"
        }
    }
}

/// Ergebnis für das Blatt: gar nicht zeigen, keine Kerzen oder fertige Kerzen.
enum SheetChartResult: Equatable, Sendable {
    /// DEX-Paar oder Kürzel, die die Kerzenquelle nie liefern kann: Chart-Block ausblenden.
    case unsupported
    /// Keine Quelle lieferte passende Kerzen: «Keine Kursdaten für dieses Paar».
    case noData
    /// Kerzen in der Quote des Paars; `converted` = aus der USDT-Reihe umgerechnet.
    case ready([MarketCandle], converted: Bool)
}

/// Eine Kerzenabfrage: Paar oder Ausweich-Reihe gegen USDT (`convert` = umrechnen).
struct SheetCandleRequest: Equatable, Sendable {
    let base: String
    let quote: String
    let convert: Bool
}

/// Reine Regeln des Charts im Aktionsblatt — gleiche Regeln wie Android (`SheetChart`).
enum SheetChart {
    /// Markt-Schlüssel von DexScreener (wie im Hinzufügen-Tab): keine Börsen-Kerzen.
    static let dexMarketKey = "DexScreener"

    /// Kurzes Ticken nur beim Wechsel auf eine andere Kerze.
    static func isNewCandle(previous: Int?, current: Int?) -> Bool {
        guard let current else { return false }
        return previous != current
    }

    /// Gleiche Regel wie die Kerzenquelle: nur Buchstaben und Ziffern.
    private static func isAsset(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespaces)
        return !t.isEmpty && t.allSatisfy { $0.isLetter || $0.isNumber }
    }

    /// Kann die Kerzenquelle das Paar überhaupt liefern? Sonst Block ganz ausblenden.
    static func isSupported(marketKey: String, base: String, quote: String) -> Bool {
        marketKey.caseInsensitiveCompare(dexMarketKey) != .orderedSame && isAsset(base) && isAsset(quote)
    }

    /// Abfragen in fester Reihenfolge — wie der 24-h-Bezug (`DayChange.select`):
    ///  1. das Paar selbst (USD-artige Quotes auf die USDT-Reihe, `DayChange.candleQuote`),
    ///  2. bei Fiat-Quote ohne eigene Kerzen die USDT-Reihe des Basis-Assets, umgerechnet.
    static func requests(base: String, quote: String, quoteIsFiat: Bool) -> [SheetCandleRequest] {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = DayChange.candleQuote(quote)
        let pair = SheetCandleRequest(base: b, quote: q, convert: false)
        guard quoteIsFiat, q != DayChange.usdtQuote else { return [pair] }
        return [pair, SheetCandleRequest(base: b, quote: DayChange.usdtQuote, convert: true)]
    }

    /// Prüft und wandelt Kerzen einer Abfrage; nil = nicht brauchbar (nächste Abfrage).
    ///  - Paar: Weicht der Kurs mehr als `DayChange.maxPriceGap` vom letzten Schluss ab,
    ///    gehören Kerzen und Kurs wohl nicht zum selben Coin.
    ///  - Umrechnung: USDT-Reihe so skaliert, dass der letzte Schluss dem Kurs des Paars
    ///    entspricht (Verlauf in Prozent wie `DayChange.fromSeries`); ohne Kurs nicht möglich.
    static func accept(_ candles: [MarketCandle]?, request: SheetCandleRequest, price: Double?) -> [MarketCandle]? {
        let clean = WidgetChartGeometry.clean(candles ?? [])
        guard clean.count >= 2, let last = clean.last?.close else { return nil }
        let p = price.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        if !request.convert {
            if let p, abs(p / last - 1) > DayChange.maxPriceGap { return nil }
            return clean
        }
        guard let p else { return nil }
        let factor = p / last
        return clean.map {
            MarketCandle(openTime: $0.openTime, open: $0.open * factor, high: $0.high * factor,
                         low: $0.low * factor, close: $0.close * factor, volume: $0.volume)
        }
    }

    /// Kerze unter dem Finger: Kerze i belegt [plotLeft + i·slot, plotLeft + (i+1)·slot).
    static func scrubIndex(x: CGFloat, plotLeft: CGFloat, slot: CGFloat, count: Int) -> Int? {
        guard count > 0, slot > 0, !x.isNaN else { return nil }
        let raw = ((x - plotLeft) / slot).rounded(.down)
        let clamped = Swift.min(Swift.max(raw, 0), CGFloat(count - 1))
        return Int(clamped)
    }

    /// Veränderung in Prozent gegen `start`; nil ohne gültigen Start.
    static func changePercent(start: Double, value: Double) -> Double? {
        guard start.isFinite, value.isFinite, start > 0 else { return nil }
        return (value - start) / start * 100
    }

    /// Veränderung über den Zeitraum — gleiche Kennzahlen wie der VoiceOver-Satz.
    static func rangeChange(_ candles: [MarketCandle], type: PriceChartType) -> Double? {
        guard let s = WidgetChartGeometry.summary(candles, type: type) else { return nil }
        return changePercent(start: s.start, value: s.end)
    }

    /// Schluss der Kerze `index` gegen den Beginn des Zeitraums (wie `rangeChange`).
    static func scrubChange(_ candles: [MarketCandle], type: PriceChartType, index: Int) -> Double? {
        guard let s = WidgetChartGeometry.summary(candles, type: type),
              candles.indices.contains(index) else { return nil }
        return changePercent(start: s.start, value: candles[index].close)
    }

    /// Linker Rand des Etiketts beim Ziehen: mittig über der Markierung, aber ganz innerhalb
    /// von `minX`…`maxX`; breiter als die Fläche → am linken Rand.
    static func labelLeft(centerX: CGFloat, width: CGFloat, minX: CGFloat, maxX: CGFloat) -> CGFloat {
        let maxLeft = Swift.max(minX, maxX - width)
        return Swift.min(Swift.max(centerX - width / 2, minX), maxLeft)
    }
}

/// Kerzen des Blatts: gleiche Quelle und Intervalle wie das Einzel-Widget (`CandleDataSource`,
/// `PriceChartRange`); im Speicher je Paar und Zeitraum 5 bzw. 30 Minuten (nur Erfolge).
/// Netz und Umrechnung laufen abseits des Main-Threads.
final class SheetChartStore: @unchecked Sendable {
    static let shared = SheetChartStore()

    private let lock = NSLock()
    private var entries: [String: (time: Int64, candles: [MarketCandle], converted: Bool)] = [:]

    private static func key(_ base: String, _ quote: String, _ range: PriceChartRange) -> String {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        return "\(b)|\(q)|\(range.rawValue)"
    }

    /// Ohne Netz: ausgeblendet (DEX), gültiger Zwischenspeicher oder nil (laden).
    func cached(_ watch: Watch, range: PriceChartRange, now: Int64 = TimeUtils.nowMillis) -> SheetChartResult? {
        guard SheetChart.isSupported(marketKey: watch.marketKey, base: watch.baseAsset, quote: watch.quoteAsset) else {
            return .unsupported
        }
        let k = Self.key(watch.baseAsset, watch.quoteAsset, range)
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[k] else { return nil }
        let age = now - entry.time
        guard age >= 0, age < range.sheetCacheMillis else {
            entries[k] = nil
            return nil
        }
        return .ready(entry.candles, converted: entry.converted)
    }

    private func store(_ watch: Watch, range: PriceChartRange, candles: [MarketCandle], converted: Bool) {
        let k = Self.key(watch.baseAsset, watch.quoteAsset, range)
        lock.lock()
        defer { lock.unlock() }
        entries[k] = (time: TimeUtils.nowMillis, candles: candles, converted: converted)
    }

    func load(_ watch: Watch, range: PriceChartRange) async -> SheetChartResult {
        if let hit = cached(watch, range: range) { return hit }
        let requests = SheetChart.requests(base: watch.baseAsset, quote: watch.quoteAsset,
                                           quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
        let price = watch.lastPrice
        // Eigene Aufgabe abseits des Main-Threads (Ausweich-Kette, Umrechnung)
        let found = await Task.detached(priority: .userInitiated) { () -> (candles: [MarketCandle], converted: Bool)? in
            for request in requests {
                if Task.isCancelled { return nil }
                let raw = await CandleDataSource.candles(base: request.base, quote: request.quote,
                                                         interval: range.candleInterval, limit: range.limit)
                if let candles = SheetChart.accept(raw, request: request, price: price) {
                    return (candles: candles, converted: request.convert)
                }
            }
            return nil
        }.value
        guard let found else { return .noData }
        store(watch, range: range, candles: found.candles, converted: found.converted)
        return .ready(found.candles, converted: found.converted)
    }
}
