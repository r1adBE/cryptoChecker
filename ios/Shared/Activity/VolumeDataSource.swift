import Foundation

/// Volumen der letzten abgeschlossenen Stunde im Vergleich zum Schnitt der 24 Stunden davor.
struct VolumeSpike: Equatable, Sendable {
    /// z. B. 4.2 = «4,2-mal so viel wie üblich»
    let ratio: Double
    /// Startzeit der bewerteten Stundenkerze (ms, UTC)
    let candleOpenTime: Int64
}

/// Stundenkerzen für den Volumen-Spike-Alarm und «Ungewöhnliche Aktivität» —
/// über die Ausweich-Kette von `CandleDataSource` (Binance, Binance.US, Coinbase).
/// Gleiches Paar, auch wenn es an einer anderen Börse hängt; Paare, die keine
/// Quelle führt, liefern nil. Wie `VolumeDataSource.kt`.
enum VolumeDataSource {
    /// Genug für 24 Vergleichsrenditen + letzte abgeschlossene + laufende Stunde, mit Reserve.
    static let limit = 30
    /// 24 Vergleichsstunden + letzte abgeschlossene + laufende Stunde.
    private static let minSpikeCandles = 26
    private static let cacheMillis: Int64 = 5 * 60_000

    // Kerzen je Symbol, kurz zwischengespeichert (auch «nicht gefunden»)
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: (time: Int64, candles: [MarketCandle]?)] = [:]

    static func hourlySpike(base: String, quote: String) async -> VolumeSpike? {
        guard let candles = await hourlyCandles(base: base, quote: quote) else { return nil }
        return spikeOf(candles)
    }

    /// Die letzten `limit` Stundenkerzen, aufsteigend; die letzte läuft noch.
    /// nil, wenn keine Quelle der Ausweich-Kette das Paar führt.
    /// `futures` = Futures-Paar: Kerzen zuerst vom USDⓈ-M-Markt (fapi), nicht vom Spot.
    static func hourlyCandles(base: String, quote: String, futures: Bool = false) async -> [MarketCandle]? {
        guard let symbol = symbol(base: base, quote: quote) else { return nil }
        let key = futures ? symbol + "|F" : symbol
        let now = TimeUtils.nowMillis
        if let hit = cached(key, now: now) { return hit.candles }

        let result = await CandleDataSource.candles(base: base, quote: quote, interval: .h1, limit: limit,
                                                    preferFutures: futures)
        // Abgebrochen (Zeitbudget): «nicht gefunden» nicht merken
        if Task.isCancelled && result == nil { return nil }
        store(key, result)
        return result
    }

    private static func cached(_ symbol: String, now: Int64) -> (time: Int64, candles: [MarketCandle]?)? {
        lock.lock(); defer { lock.unlock() }
        guard let entry = cache[symbol] else { return nil }
        let age = now - entry.time
        return age >= 0 && age < cacheMillis ? entry : nil
    }

    private static func store(_ symbol: String, _ candles: [MarketCandle]?) {
        lock.lock(); defer { lock.unlock() }
        cache[symbol] = (time: TimeUtils.nowMillis, candles: candles)
    }

    /// Binance-Symbol, z. B. BTC + USD → BTCUSDT.
    static func symbol(base: String, quote: String) -> String? {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        var q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        if q == "USD" { q = "USDT" }
        let s = b + q
        guard !b.isEmpty, !q.isEmpty, s.allSatisfy({ $0.isLetter || $0.isNumber }) else { return nil }
        return s
    }

    /// Die letzte Kerze läuft noch; bewertet wird die vorletzte gegen die 24 davor.
    static func spikeOf(_ candles: [MarketCandle]) -> VolumeSpike? {
        let n = candles.count
        guard n >= minSpikeCandles else { return nil }
        let lastIndex = n - 2
        let average = (lastIndex - 24..<lastIndex).reduce(0.0) { $0 + candles[$1].volume } / 24
        guard average > 0 else { return nil }
        return VolumeSpike(ratio: candles[lastIndex].volume / average, candleOpenTime: candles[lastIndex].openTime)
    }
}
