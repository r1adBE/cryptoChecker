import Foundation

/// Volumen der letzten abgeschlossenen Stunde im Vergleich zum Schnitt der 24 Stunden davor.
struct VolumeSpike: Equatable, Sendable {
    /// z. B. 4.2 = «4,2-mal so viel wie üblich»
    let ratio: Double
    /// Startzeit der bewerteten Stundenkerze (ms, UTC)
    let candleOpenTime: Int64
}

/// Stundenkerzen für den Volumen-Spike-Alarm, «Ungewöhnliche Aktivität» und «Warum?». Für ein Paar
/// der Merkliste (`pairCandles`) wie der Chart: zuerst die EIGENE Börse (`OwnCandleDataSource`), sonst
/// die Ausweich-Kette (`CandleDataSource`: Binance, Binance.US, Coinbase) nur mit Prüfung auf gleichen
/// Token (`OwnCandlePolicy.acceptsFallback`; dazu wie der Chart der Name des Coins an der Börse des Paars —
/// Binance bei klar anderem Namen nie, `TokenIdentityStore`); ohne Kurs, für TradFi, DEX und Laufzeit-Kontrakte keine
/// Ausweich-Reihe. Markt-Referenzen (BTC/USDT …) direkt aus der Kette (`hourlyCandlesSourced`).
/// Wie `VolumeDataSource.kt`.
enum VolumeDataSource {
    /// Genug für 24 Vergleichsrenditen + letzte abgeschlossene + laufende Stunde, mit Reserve.
    static let limit = 30
    /// 24 Vergleichsstunden + letzte abgeschlossene + laufende Stunde.
    private static let minSpikeCandles = 26
    private static let cacheMillis: Int64 = 5 * 60_000

    // Kerzen je Symbol (und Anzahl), kurz zwischengespeichert (auch «nicht gefunden»), mit Anbieter
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: (time: Int64, candles: Sourced<[MarketCandle]>?)] = [:]

    /// Markt-Referenz (z. B. BTC/USDT für den Puls) direkt aus der Ausweich-Kette.
    static func hourlySpike(base: String, quote: String) async -> VolumeSpike? {
        guard let candles = await hourlyCandles(base: base, quote: quote) else { return nil }
        return spikeOf(candles)
    }

    /// Volumen-Spike eines Paars der Merkliste (Volumen-Alarm, Schnell-Alarme); `price` für die
    /// Prüfung auf gleichen Token.
    static func hourlySpike(watch: Watch, price: Double?) async -> VolumeSpike? {
        guard let candles = await pairCandles(CandlePair.of(watch), price: price, interval: .h1, limit: limit)?.value
        else { return nil }
        return spikeOf(candles)
    }

    /// Kerzen eines Paars der Merkliste (`pair`, Kurs `price`) wie der Chart, siehe `pairCandlesOf`;
    /// kurz zwischengespeichert (auch «nicht gefunden»).
    static func pairCandles(_ pair: CandlePair, price: Double?, interval: CandleInterval,
                            limit count: Int) async -> Sourced<[MarketCandle]>? {
        let key = "P|\(pair.cacheKey)|\(interval.rawValue)|\(count)"
        if let hit = cached(key, now: TimeUtils.nowMillis) { return hit.candles }
        // Name des Coins an der Börse des Paars (gleicher Token, K-8) — wie der Chart
        let identityName = TokenIdentityStore.shared.pairName(marketKey: pair.marketKey, base: pair.base)
        let result = await pairCandlesOf(
            pair, price: price, identityName: identityName,
            ownSupported: OwnCandleDataSource.supports(pair, interval: interval),
            own: { await OwnCandleDataSource.shared.candles(pair, interval: interval, limit: count) },
            chain: { name, accept in
                await CandleDataSource.candlesSourced(base: pair.base, quote: pair.quote, interval: interval,
                                                      limit: count, preferFutures: pair.isFutures,
                                                      identityName: name, accept: accept)
            }
        )
        // Abgebrochen (Zeitbudget): «nicht gefunden» nicht merken
        if Task.isCancelled && result == nil { return nil }
        store(key, result)
        return result
    }

    /// Kerzen eines Paars wie der Chart: 1. eigene Börse (`ownSupported`, `own`); 2. nur wenn die
    /// Ausweich-Kette erlaubt ist (`OwnCandlePolicy.allowsFallback`: nicht TradFi, DEX, Laufzeit-Kontrakt)
    /// und der Kurs `price` bekannt ist, die Kette `chain` — jede Reihe nur bei gleichem Token
    /// (`OwnCandlePolicy.acceptsFallback`), sonst die nächste Quelle der Kette. `identityName` (Name des Coins an
    /// der Börse des Paars) geht wie beim Chart an die Kette: Binance-Quellen nur ohne klaren Namens-Widerspruch
    /// (`TokenIdentity.compatible`, K-8). Wie Android.
    static func pairCandlesOf(
        _ pair: CandlePair, price: Double?, identityName: String?, ownSupported: Bool,
        own: () async -> Sourced<[MarketCandle]>?,
        chain: (String?, @escaping @Sendable ([MarketCandle]) async -> Bool) async -> Sourced<[MarketCandle]>?
    ) async -> Sourced<[MarketCandle]>? {
        if ownSupported, let hit = await own() { return hit }
        guard OwnCandlePolicy.allowsFallback(pair), let p = price, p.isFinite, p > 0 else { return nil }
        return await chain(identityName) { OwnCandlePolicy.acceptsFallback($0, price: p) }
    }

    /// Die letzten `limit` Stundenkerzen, aufsteigend; die letzte läuft noch.
    /// nil, wenn keine Quelle der Ausweich-Kette das Paar führt.
    /// `futures` = Futures-Paar: Kerzen zuerst vom USDⓈ-M-Markt (fapi), nicht vom Spot.
    static func hourlyCandles(base: String, quote: String, futures: Bool = false) async -> [MarketCandle]? {
        await hourlyCandlesSourced(base: base, quote: quote, futures: futures)?.value
    }

    /// Wie `hourlyCandles`, mit wählbarer Anzahl `count` (z. B. 100 für «Warum?») und dem Anbieter,
    /// der geliefert hat («Binance», «Binance.US», «Coinbase», siehe `CandleDataSource.candlesSourced`).
    static func hourlyCandlesSourced(base: String, quote: String, futures: Bool = false,
                                     count: Int = limit) async -> Sourced<[MarketCandle]>? {
        guard let symbol = symbol(base: base, quote: quote) else { return nil }
        let key = (futures ? symbol + "|F" : symbol) + "|\(count)"
        let now = TimeUtils.nowMillis
        if let hit = cached(key, now: now) { return hit.candles }

        let result = await CandleDataSource.candlesSourced(base: base, quote: quote, interval: .h1, limit: count,
                                                           preferFutures: futures)
        // Abgebrochen (Zeitbudget): «nicht gefunden» nicht merken
        if Task.isCancelled && result == nil { return nil }
        store(key, result)
        return result
    }

    private static func cached(_ key: String, now: Int64) -> (time: Int64, candles: Sourced<[MarketCandle]>?)? {
        lock.lock(); defer { lock.unlock() }
        guard let entry = cache[key] else { return nil }
        let age = now - entry.time
        return age >= 0 && age < cacheMillis ? entry : nil
    }

    private static func store(_ key: String, _ candles: Sourced<[MarketCandle]>?) {
        lock.lock(); defer { lock.unlock() }
        cache[key] = (time: TimeUtils.nowMillis, candles: candles)
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
