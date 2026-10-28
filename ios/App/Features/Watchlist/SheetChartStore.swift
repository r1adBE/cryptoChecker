import SwiftUI

// Zwischenspeicher der Kerzen im Aktionsblatt — wie `SheetChartRepository.kt` (Android). Regeln in
// WatchSheetChart.swift.

/// Kerzen des Blatts: gleiche Intervalle wie das Einzel-Widget (`PriceChartRange`). Zuerst die Kerzen
/// der EIGENEN Börse des Paars (`OwnCandleDataSource`, z. B. Kraken-Paar → Kraken); sonst die
/// Ausweich-Kette (`CandleDataSource`, Futures-Paare zuerst USDⓈ-M); bei Fiat-Quotes ersatzweise die
/// USDT-Reihe, je Kerze mit dem Devisenkurs ihres Tags umgerechnet (dieselben EZB-Tageskurse wie der
/// Wertverlauf, `HistoricPriceSource.usdToSeries`). Jede Reihe gilt nur bei gleichem Token
/// (`SheetChart.accept`); passt die Reihe einer Quelle nicht, ist die nächste der Kette dran.
/// TradFi-Paare und DEX-Pools nie aus der Kette (`OwnCandlePolicy.allowsFallback`).
/// Im Speicher je Paar (mit Börse) und Zeitraum 5, 30 bzw. 60 Minuten (nur Erfolge); verworfen beim
/// Aktualisieren von Hand (`invalidate`, `invalidateAll`) oder bei einem Kurssprung über 3 %.
/// Netz und Umrechnung laufen abseits des Main-Threads. Wie `SheetChartRepository.kt`.
final class SheetChartStore: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = SheetChartStore()

    private struct Entry {
        let time: Int64
        let candles: [MarketCandle]
        let converted: Bool
        let info: SheetChartInfo
        /// Kurs beim Speichern (Bezug für den Sprung); nil = letzter Schluss.
        let price: Double?
    }

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]

    /// Längster Zeitraum des Blatts (1 Jahr): alle Zeiträume aus EINER Devisen-Abfrage.
    private static let fxDays = 366

    /// Börse (eigene Kerzen je Börse) und Kürzel des Paars.
    private static func prefix(_ watch: Watch) -> String {
        let b = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let q = watch.quoteAsset.trimmingCharacters(in: .whitespaces).uppercased()
        return "\(CandlePair(watch: watch, tradFi: false).cacheKey)#\(b)|\(q)|"
    }

    private static func key(_ watch: Watch, _ range: PriceChartRange) -> String {
        prefix(watch) + range.rawValue
    }

    /// Ohne Netz: ausgeblendet (DEX), gültiger Zwischenspeicher oder nil (laden).
    func cached(_ watch: Watch, range: PriceChartRange, now: Int64 = EpochTime.nowMillis) -> SheetChartResult? {
        guard SheetChart.isSupported(exchangeKey: watch.exchangeKey, base: watch.baseAsset, quote: watch.quoteAsset) else {
            return .unsupported
        }
        let k = Self.key(watch, range)
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[k] else { return nil }
        let age = now - entry.time
        // Bezug: Kurs beim Speichern (Kerzen und Kurs dürfen je nach Börse leicht auseinanderliegen)
        let reference = entry.price ?? entry.candles.last?.close
        guard age >= 0, age < range.sheetCacheMillis,
              !SheetChart.priceJumped(reference: reference, price: watch.lastPrice) else {
            entries[k] = nil
            return nil
        }
        return .ready(entry.candles, converted: entry.converted, info: entry.info)
    }

    /// Aktualisieren im Blatt: Kerzen dieses Paars (alle Zeiträume) neu laden.
    func invalidate(_ watch: Watch) {
        let p = Self.prefix(watch)
        lock.withLock { entries = entries.filter { !$0.key.hasPrefix(p) } }
        let pair = CandlePair(watch: watch, tradFi: false)
        Task { await OwnCandleDataSource.shared.invalidate(pair) }
    }

    /// Nach unten ziehen in der Merkliste: alle Kerzen neu laden.
    func invalidateAll() {
        lock.withLock { entries.removeAll() }
        Task { await OwnCandleDataSource.shared.invalidateAll() }
    }

    private func store(_ watch: Watch, range: PriceChartRange, candles: [MarketCandle], converted: Bool,
                       info: SheetChartInfo) {
        let k = Self.key(watch, range)
        let price = watch.lastPrice.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        lock.withLock {
            entries[k] = Entry(time: EpochTime.nowMillis, candles: candles, converted: converted, info: info, price: price)
        }
    }

    /// Prüfung einer Reihe der Ausweich-Kette (gleicher Token, Umrechnung); Tageskurse einmal je Abfrage.
    private actor Attempt {
        private(set) var accepted: [MarketCandle]?
        private(set) var rates: [Int: Double]?
        private var ratesLoaded = false

        func check(_ raw: [MarketCandle], request: SheetCandleRequest, quote: String, price: Double?) async -> Bool {
            // Tageskurse nur für die Ausweich-Reihe
            if request.convert && !ratesLoaded {
                let today = LocalDay(epochMillisUTC: EpochTime.nowMillis)
                let span = PortfolioHistoryFx.requestRange(days: SheetChartStore.fxDays, today: today)
                rates = await HistoricPriceSource.usdToSeries(quote, from: span.from, to: span.to)
                ratesLoaded = true
            }
            // Ohne Tageskurse: aktueller Devisenkurs für die Prüfung auf gleichen Token
            var current: Double? = nil
            if request.convert, rates?.isEmpty ?? true { current = await FxRateSource.usdTo(quote) }
            accepted = SheetChart.accept(raw, request: request, price: price, dailyRates: rates, currentRate: current)
            return accepted != nil
        }
    }

    func load(_ watch: Watch, range: PriceChartRange) async -> SheetChartResult {
        if let hit = cached(watch, range: range) { return hit }
        let quote = watch.quoteAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let requests = SheetChart.requests(base: watch.baseAsset, quote: watch.quoteAsset,
                                           quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
        let price = watch.lastPrice
        let pair = CandlePair.of(watch)
        // Name des Coins an der Börse des Paars; gelesen erst in der Aufgabe (Datei, nicht auf dem Main-Thread)
        let exchangeKey = watch.exchangeKey
        let rawBase = watch.baseAsset
        // Eigene Aufgabe abseits des Main-Threads (eigene Börse, Ausweich-Kette, Tageskurse, Umrechnung)
        let found = await Task.detached(priority: .userInitiated) { () -> (candles: [MarketCandle], converted: Bool, info: SheetChartInfo)? in
            // 1. Eigene Börse des Paars (kein Hinweis auf eine fremde Quelle nötig)
            if OwnCandleDataSource.supports(pair, interval: range.candleInterval) {
                let own = await OwnCandleDataSource.shared.candles(pair, interval: range.candleInterval, limit: range.limit)
                let request = SheetCandleRequest(base: base, quote: quote, convert: false)
                if let own, let candles = SheetChart.accept(own.value, request: request, price: price) {
                    let info = SheetChartInfo(provider: own.provider, sourcePair: "\(base)/\(quote)", fx: .none,
                                              fetchedAt: EpochTime.nowMillis)
                    return (candles: candles, converted: false, info: info)
                }
            }
            // TradFi und DEX: nie Kerzen eines gleichnamigen Krypto-Tokens
            guard OwnCandlePolicy.allowsFallback(pair) else { return nil }
            // 2. Ausweich-Kette (Binance … Coinbase), Futures-Paare zuerst USDⓈ-M; Binance nur, wenn der Name
            // des Coins an der Börse des Paars dort nicht klar anders lautet (gleicher Token)
            let identityName = TokenIdentityStore.shared.pairName(exchangeKey: exchangeKey, base: rawBase)
            for request in requests {
                if Task.isCancelled { return nil }
                let attempt = Attempt()
                let sourced = await CandleDataSource.candlesSourced(
                    base: request.base, quote: request.quote, interval: range.candleInterval, limit: range.limit,
                    preferFutures: pair.isFutures, identityName: identityName
                ) { raw in
                    // Fremde Reihe gleichen Kürzels: nächste Quelle der Kette
                    await attempt.check(raw, request: request, quote: quote, price: price)
                }
                let accepted = await attempt.accepted
                let rates = await attempt.rates
                if let sourced, let candles = accepted {
                    let info = SheetChartInfo(provider: sourced.provider, sourcePair: "\(request.base)/\(request.quote)",
                                              fx: SheetChart.fxMode(request, dailyRates: rates),
                                              fetchedAt: EpochTime.nowMillis)
                    return (candles: candles, converted: request.convert, info: info)
                }
            }
            return nil
        }.value
        guard let found else { return .noData }
        store(watch, range: range, candles: found.candles, converted: found.converted, info: found.info)
        return .ready(found.candles, converted: found.converted, info: found.info)
    }
}
