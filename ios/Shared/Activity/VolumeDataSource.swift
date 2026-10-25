import Foundation
import os

/// Volumen der letzten abgeschlossenen Stunde im Vergleich zum Schnitt der 24 Stunden davor.
struct VolumeSpike: Equatable, Sendable {
    /// z. B. 4.2 = «4,2-mal so viel wie üblich»
    let ratio: Double
    /// Startzeit der bewerteten Stundenkerze (ms, UTC)
    let candleOpenTime: Int64
    /// Börse der Kerzen, wenn nicht die des Paars (Ausweich-Kette, z. B. «Binance»): im Alarmtext genannt; nil = eigene.
    var source: String? = nil

    /// Mit der Börse der Kerzen `provider`, wenn sie nicht die des Paars ist (`DayChange.foreignCandleSource`) — wie Android.
    func withSource(_ watch: Watch, provider: String?) -> VolumeSpike {
        var copy = self
        copy.source = DayChange.foreignCandleSource(markets: [watch.marketKey, watch.marketName], provider: provider)
        return copy
    }
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
    private static let cacheMillis: Int64 = 5 * 60_000

    // Kerzen je Symbol (und Anzahl), kurz zwischengespeichert (auch «nicht gefunden»), mit Anbieter
    /// Begrenzt (`BoundedCache`), hinter einer Sperre mit Zustand.
    private static let cache = OSAllocatedUnfairLock(
        initialState: BoundedCache<String, (time: Int64, candles: Sourced<[MarketCandle]>?)>())

    /// Markt-Referenz (z. B. BTC/USDT für den Puls) direkt aus der Ausweich-Kette.
    static func hourlySpike(base: String, quote: String) async -> VolumeSpike? {
        guard let candles = await hourlyCandles(base: base, quote: quote) else { return nil }
        // Uhrzeit NACH dem Laden: die Reihe wird gegen die aktuelle Stunde geprüft
        return spikeOf(candles, now: TimeUtils.nowMillis)
    }

    /// Volumen-Spike eines Paars der Merkliste (Volumen-Alarm, Schnell-Alarme); `price` für die
    /// Prüfung auf gleichen Token.
    static func hourlySpike(watch: Watch, price: Double?) async -> VolumeSpike? {
        guard let sourced = await pairCandles(CandlePair.of(watch), price: price, interval: .h1, limit: limit)
        else { return nil }
        // Kerzen einer anderen Börse: im Alarmtext genannt; Uhrzeit nach dem Laden (laufende Stunde)
        return spikeOf(sourced.value, now: TimeUtils.nowMillis)?.withSource(watch, provider: sourced.provider)
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
        guard let entry = cache.withLock({ $0.get(key) }) else { return nil }
        let age = now - entry.time
        return age >= 0 && age < cacheMillis ? entry : nil
    }

    private static func store(_ key: String, _ candles: Sourced<[MarketCandle]>?) {
        let entry = (time: TimeUtils.nowMillis, candles: candles)
        cache.withLock { $0.set(key, entry) }
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

    /// Bewertet wird die letzte ABGESCHLOSSENE Stunde gegen die 24 davor — nach Uhrzeit, nicht nach
    /// Position: Kerzen werden je Stunde (`openTime / 1 h`, abgerundet) zusammengefasst, fehlende Stunden
    /// (Börsen lassen Stunden ohne Handel aus) zählen mit Volumen 0. Vorher war bei Lücken «die vorletzte
    /// Kerze» womöglich Stunden alt und der Schnitt über mehr als 24 Stunden gerechnet (jetzt wie Android).
    ///
    /// Laufende Stunde = die spätere aus Uhr (`now`) und neuester Kerze (Uhr des Geräts etwas nach).
    /// nil, wenn die Reihe nicht mehr läuft (`CandleSeries.isLive`) oder nicht alle 24 Vergleichsstunden
    /// abdeckt (sonst zählten unbekannte Stunden als 0 und blähten den Spike auf). Wie `VolumeDataSource.spikeOf` (Kotlin).
    static func spikeOf(_ candles: [MarketCandle], now: Int64) -> VolumeSpike? {
        guard candles.count >= 2, CandleSeries.isLive(candles, intervalMillis: hourMillis, now: now) else { return nil }
        var volumes: [Int64: Double] = [:]
        for candle in candles where candle.volume.isFinite && candle.volume >= 0 {
            let hour = floorDiv(candle.openTime, hourMillis)
            volumes[hour, default: 0] += candle.volume
        }
        guard let newest = volumes.keys.max(), let oldest = volumes.keys.min() else { return nil }
        let running = max(floorDiv(now, hourMillis), newest)
        let evaluated = running - 1
        // Die Reihe muss bis an den Anfang der 24 Vergleichsstunden zurückreichen
        guard oldest <= evaluated - compareHours else { return nil }
        var sum = 0.0
        for hour in (evaluated - compareHours)..<evaluated { sum += volumes[hour] ?? 0 }
        let average = sum / Double(compareHours)
        guard average > 0 else { return nil }
        return VolumeSpike(ratio: (volumes[evaluated] ?? 0) / average, candleOpenTime: evaluated * hourMillis)
    }

    private static let hourMillis: Int64 = 3_600_000

    /// Vergleichsstunden vor der bewerteten.
    private static let compareHours: Int64 = 24

    /// Ganzzahlige Division, zur kleineren Zahl gerundet (wie `Math.floorDiv`; Swift `/` rundet gegen 0).
    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }
}
