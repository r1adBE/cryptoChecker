import Foundation

/// Reine Regeln für die Stundenkerzen der eigenen Börse (Mini-Chart und 24-h-Bezug), wie
/// `DayReferenceStore` für die USDT-Reihe. Wie `OwnHourly` (Android, OwnHourlyCandles.kt).
enum OwnHourly {
    /// Schlusskurse (die jüngsten 24), 24-h-Bezug, Eröffnung je Stunde, Anbieter, letzter Schluss.
    struct Series: Sendable, Equatable {
        let closes: [Double]
        let reference: DayReference?
        let opens: [Int64: Double]
        let provider: String?
        /// Letzter Schluss (auch ohne 24-h-Bezug, für den Tages-Bezug).
        let lastClose: Double
    }

    /// Gültigkeit des Mini-Charts (wie die USDT-Reihe).
    static let ttlMillis: Int64 = 15 * 60_000
    static let failureTtlMillis: Int64 = 5 * 60_000
    /// So lange nach Beginn einer Stunde darf ihre Kerze bei der Quelle noch fehlen.
    static let newCandleGraceMillis: Int64 = 10 * 60_000

    /// Reihe aus aufsteigenden Stundenkerzen, abgerufen um `now`; nil mit weniger als zwei gültigen.
    /// Mini-Chart aus den jüngsten 24 Kerzen, 24-h-Bezug nach `rollingReference`.
    static func series(_ candles: [MarketCandle]?, provider: String?, now: Int64) -> Series? {
        let valid = (candles ?? []).filter { $0.close.isFinite && $0.close > 0 }
        let recent = Array(valid.suffix(ChangeBasisMath.rollingCandles))
        guard recent.count >= 2, let last = valid.last else { return nil }
        var opens: [Int64: Double] = [:]
        for candle in valid { opens[candle.openTime] = candle.open }
        return Series(closes: recent.map(\.close),
                      reference: rollingReference(valid, now: now),
                      opens: opens, provider: provider, lastClose: last.close)
    }

    /// Rollender 24-h-Bezug aus gültigen, aufsteigenden Stundenkerzen zum Zeitpunkt `now`. Manche
    /// Börsen (Upbit u. a.) liefern nur Stunden mit Handel — die jüngsten 24 Kerzen können dann
    /// Tage umfassen. Darum zählt die Zeit, nicht die Anzahl: Fenster = Kerzen ab `now` − 24 h.
    /// Eröffnung = Schluss der letzten Kerze VOR dem Fenster (letzter Kurs vor 24 h); fehlt sie,
    /// die Eröffnung der ersten Kerze im Fenster, nur wenn sie höchstens eine Stunde nach
    /// Fensterbeginn startet. Sonst nil (dann der übliche Ausweichweg). Wie Android.
    static func rollingReference(_ valid: [MarketCandle], now: Int64) -> DayReference? {
        guard let first = valid.first, let last = valid.last else { return nil }
        let windowStart = now - ChangeBasisMath.dayMillis
        if let before = valid.last(where: { $0.openTime < windowStart }) {
            return DayReference.of(open: before.close, lastClose: last.close)
        }
        if first.openTime >= windowStart + ChangeBasisMath.hourMillis { return nil }
        return DayReference.of(open: first.open, lastClose: last.close)
    }

    /// Gilt ein Eintrag (Abruf `time`, `series` oder Fehlschlag) noch? Erfolg `ttl`, Fehlschlag
    /// `failureTtlMillis`. `needsHour`: Kerze ab dieser Startzeit muss enthalten sein — ein Abruf
    /// von vor (oder kurz nach) Beginn dieser Stunde ohne sie gilt nicht als frisch.
    static func isFresh(time: Int64, series: Series?, ttl: Int64, now: Int64, needsHour: Int64? = nil) -> Bool {
        let age = now - time
        let limit = series == nil ? failureTtlMillis : ttl
        if let needsHour, let series, series.opens[needsHour] == nil, time < needsHour + newCandleGraceMillis { return false }
        return age >= 0 && age < limit
    }
}

/// Stundenkerzen der EIGENEN Börse je Paar (`CandlePair.cacheKey`) für den Mini-Chart der Merkliste
/// und den 24-h- bzw. Tages-Bezug der %-Änderung — ein Abruf für beides (26 × 1 h). Nur im
/// Speicher; gleichzeitige Anfragen für dasselbe Paar teilen sich einen Abruf, der auch zu Ende
/// läuft, wenn der Aufrufer nicht mehr wartet. Wie `OwnHourlyCandles` (Android).
actor OwnHourlyStore {
    static let shared = OwnHourlyStore()

    private var cache: [String: (time: Int64, series: OwnHourly.Series?)] = [:]
    private var inFlight: [String: Task<OwnHourly.Series?, Never>] = [:]

    static func supports(_ pair: CandlePair) -> Bool {
        OwnCandleDataSource.supports(pair, interval: .h1)
    }

    /// Geladene Schlusskurse (auch etwas ältere) — für den ersten Frame; nil = nichts geladen.
    func cachedCloses(_ pair: CandlePair) -> [Double]? { cache[pair.cacheKey]?.series?.closes }

    /// Frische Schlusskurse (die jüngsten 24) oder nil.
    func closes(_ pair: CandlePair) async -> [Double]? {
        await series(pair, ttl: OwnHourly.ttlMillis)?.closes
    }

    /// 24-h-Bezug; ein höchstens 60 Minuten alter Abruf gilt ohne Netz.
    func dayReference(_ pair: CandlePair) async -> DayReference? {
        await series(pair, ttl: DayReferenceCache.freshMillis)?.reference
    }

    /// Bezug seit Tagesbeginn `dayStart` (Eröffnung der Kerze, in der er liegt, und letzter Schluss).
    func dayStartReference(_ pair: CandlePair, dayStart: Int64) async -> DayReference? {
        guard let loaded = await series(pair, ttl: DayReferenceCache.freshMillis, needsHour: ChangeBasisMath.hourOf(dayStart))
        else { return nil }
        return ChangeBasisMath.reference(loaded.opens, lastClose: loaded.lastClose, dayStart: dayStart)
    }

    /// Gemerkter 24-h-Bezug, Abruf höchstens `DayReferenceCache.maxAgeMillis` alt; sonst nil.
    func cachedDayReference(_ pair: CandlePair) -> DayReference? { usable(pair)?.reference }

    /// Gemerkter Bezug seit `dayStart`, Abruf höchstens `DayReferenceCache.maxAgeMillis` alt.
    func cachedDayStartReference(_ pair: CandlePair, dayStart: Int64) -> DayReference? {
        guard let s = usable(pair) else { return nil }
        return ChangeBasisMath.reference(s.opens, lastClose: s.lastClose, dayStart: dayStart)
    }

    private func usable(_ pair: CandlePair) -> OwnHourly.Series? {
        guard let entry = cache[pair.cacheKey], let series = entry.series,
              DayReferenceCache.usable(time: entry.time, now: TimeUtils.nowMillis) else { return nil }
        return series
    }

    private func series(_ pair: CandlePair, ttl: Int64, needsHour: Int64? = nil) async -> OwnHourly.Series? {
        guard Self.supports(pair) else { return nil }
        let key = pair.cacheKey
        if let entry = cache[key],
           OwnHourly.isFresh(time: entry.time, series: entry.series, ttl: ttl, now: TimeUtils.nowMillis, needsHour: needsHour) {
            return entry.series
        }
        if let pending = inFlight[key] { return await pending.value }
        // Eigene Aufgabe: Bricht der Aufrufer ab, wird trotzdem fertig geladen und gemerkt.
        let task = Task { await self.loadAndStore(key, pair: pair) }
        inFlight[key] = task
        return await task.value
    }

    private func loadAndStore(_ key: String, pair: CandlePair) async -> OwnHourly.Series? {
        let sourced = await OwnCandleDataSource.shared.candles(pair, interval: .h1, limit: ChangeBasisMath.candles)
        let now = TimeUtils.nowMillis
        let series = OwnHourly.series(sourced?.value, provider: sourced?.provider, now: now)
        inFlight[key] = nil
        // Fehlschlag: ein noch benutzbarer Erfolg bleibt stehen (Bezug bis maxAgeMillis)
        if let previous = cache[key], previous.series != nil, series == nil,
           DayReferenceCache.usable(time: previous.time, now: now) {
            return nil
        }
        cache[key] = (now, series)
        return series
    }
}
