import Foundation

// Alarm-Daten eines Durchlaufs vorladen (Teil von `PriceRefresher`, gehört zu `RefreshEffects.swift`): Volumen,
// Hebel-Daten, Hoch/Tief-Fenster und Umrechnungskurse je Paar einmal, alle gleichzeitig — wie `RefreshEffects.kt`
// (Android).

extension PriceRefresher {
    /// Vorgeladene Werte für die Alarme eines Durchlaufs (je Paar einmal, alle gleichzeitig geholt).
    struct AlarmInputs {
        var volumes: [Int64: VolumeSpike] = [:]
        var derivatives: [Int64: DerivativesAlarmData.Values] = [:]
        var nearRanges: [Int64: [Int: NearExtreme.Range]] = [:]
        /// Paare ohne Hoch/Tief: warum (keine Kerzen bzw. kein Faktor in die Quote) — für «nicht geprüft».
        var nearMissing: [Int64: AlarmCheckStatus.Reason] = [:]
        /// Paare, deren Stundenvolumen abgefragt wurde (nicht bei Live-Kursen): fehlt es, gab es keine Kerzen.
        var volumeRequested: Set<Int64> = []
        /// «Watch-Id|Währung» → Faktor Quote → Währung.
        var convertRates: [String: Double] = [:]
        /// «Watch-Id|Währung» → diesmal nicht prüfen (Devisenkurs zu alt, USDT/USD unsicher; `FiatAlarmGuard.skip`).
        var convertSkips: [String: FiatAlarmGuard.Skip] = [:]
        /// «Watch-Id|Währung», deren Faktor mit USDT = 1 rechnet (keine Messung): Vermerk im Alarmtext.
        var convertUsdtAssumed: Set<String> = []
        /// Mitteilungen erlaubt? Ohne bleiben Alarme (einmalige wie wiederholende) scharf, statt still verbraucht zu werden.
        var alarmsDeliverable = true
        /// Stufen, die aus Zeitmangel (Endzeit) nicht gelaufen oder nicht fertig geworden sind: Ihre Alarme
        /// werden diese Runde ohne Hinweis «nicht geprüft» ausgelassen (es fehlte Zeit, nicht die Daten).
        var skippedStages: Set<AlarmDataStage> = []
    }

    /// Lädt, was die Alarme brauchen: Volumen, Funding/Open Interest, Hoch/Tief und Umrechnungen.
    /// `prefetchByWatch`: aktive Alarme aus dem mitgegebenen Stand; `live`: Live-Kurse (WebSocket).
    /// Die Stufen sind unabhängig und laufen gleichzeitig. `deadline`: Stufen, die nicht mehr anfangen
    /// können, fallen weg; die anderen werden an der Endzeit abgeschnitten (`AlarmInputs.skippedStages`).
    static func loadAlarmInputs(watches: [Watch], fetched: [Int64: Fetched], prefetchByWatch: [Int64: [Alarm]],
                                live: Bool, deadline: RefreshDeadline? = nil) async -> AlarmInputs {
        // Mitteilungs-Erlaubnis einmal je Durchlauf (nur wenn Alarme ausgewertet werden) — zuerst, geht schnell
        var deliverable = true
        if !prefetchByWatch.isEmpty { deliverable = await Notifier.isAuthorized() }

        // Volumendaten nur für Paare mit Volumen-Alarm (und erfolgreich geholtem Kurs)
        let volumeWatches = watches.filter { w in
            !live && fetched[w.id]?.ticker != nil && (prefetchByWatch[w.id] ?? []).contains { $0.condition == .VOLUME_SPIKE }
        }
        // Funding/Open Interest nur für Perpetuals mit solchen Alarmen (nicht bei Live-Kursen),
        // je Paar höchstens alle 5 Min. abgefragt (`DerivativesAlarmData`)
        let derivativesWatches = watches.filter { w in
            !live && (fetched[w.id]?.ticker?.last ?? 0) > 0
                && (prefetchByWatch[w.id] ?? []).contains { $0.condition.isDerivatives }
        }
        // «Nahe am Hoch/Tief»: nur für Paare mit so einem Alarm
        let nearWatches = watches.filter { w in
            fetched[w.id]?.ticker != nil && (prefetchByWatch[w.id] ?? []).contains { $0.condition.isNearExtreme }
        }
        // Kursalarme in einer anderen Währung: Faktor je Paar und Währung einmal pro Durchlauf
        var convertNeeds: [(watchId: Int64, quote: String, currency: String)] = []
        for w in watches where fetched[w.id]?.ticker != nil {
            var seen = Set<String>()
            for a in prefetchByWatch[w.id] ?? [] {
                if let currency = a.convertCurrency, seen.insert(currency).inserted {
                    // Aster «U» zählt als USD (`CurrencyConversion.conversionQuote`)
                    convertNeeds.append((watchId: w.id,
                                         quote: CurrencyConversion.conversionQuote(marketKey: w.marketKey, quote: w.quoteAsset),
                                         currency: currency))
                }
            }
        }

        var needed = Set<AlarmDataStage>()
        if !volumeWatches.isEmpty { needed.insert(.volume) }
        if !derivativesWatches.isEmpty { needed.insert(.derivatives) }
        if !nearWatches.isEmpty { needed.insert(.nearExtreme) }
        if !convertNeeds.isEmpty { needed.insert(.convert) }
        // Gemeinsame Zeitgrenze aller Stufen (nil = ohne Grenze)
        let allowance = deadline?.allowance(wanted: Int64.max)
        let planned = AlarmDataStage.plan(needed, allowanceMillis: allowance)

        /// Eine Stufe: nicht geplant → nil; mit Endzeit höchstens bis dahin (sonst nil).
        @Sendable func stage<T: Sendable>(_ s: AlarmDataStage, _ work: @escaping @Sendable () async -> T) async -> T? {
            guard planned.contains(s) else { return nil }
            guard let allowance else { return await work() }
            return await RefreshDeadline.run(within: allowance, work)
        }

        let derivativesPrices = derivativesWatches.map { (watch: $0, price: fetched[$0.id]?.ticker?.last ?? 0) }
        let convertList = convertNeeds
        // Kurs des Durchlaufs für die Prüfung auf gleichen Token (Ausweich-Kerzen)
        let volumePrices: [(watch: Watch, price: Double?)] = volumeWatches.map { w in
            let last = fetched[w.id]?.ticker?.last ?? 0
            return (watch: w, price: last > 0 ? last : w.lastPrice)
        }
        async let volumesResult = stage(.volume) { await PriceRefresher.loadVolumes(volumePrices) }
        async let derivativesResult = stage(.derivatives) { await PriceRefresher.loadDerivatives(derivativesPrices) }
        // Kurs des Durchlaufs für die Kursband-Prüfung der Ausweich-Reihe (wie beim Mini-Chart)
        let nearPrices: [(watch: Watch, price: Double?)] = nearWatches.map { w in
            let last = fetched[w.id]?.ticker?.last ?? 0
            return (watch: w, price: last > 0 ? last : w.lastPrice)
        }
        async let nearResult = stage(.nearExtreme) { await PriceRefresher.loadNearRanges(nearPrices) }
        async let convertResult = stage(.convert) { await PriceRefresher.loadConvertRates(convertList) }
        let volumes = await volumesResult
        let derivatives = await derivativesResult
        let near = await nearResult
        let convert = await convertResult

        var skipped = Set<AlarmDataStage>()
        if needed.contains(.volume) && volumes == nil { skipped.insert(.volume) }
        if needed.contains(.derivatives) && derivatives == nil { skipped.insert(.derivatives) }
        if needed.contains(.nearExtreme) && near == nil { skipped.insert(.nearExtreme) }
        if needed.contains(.convert) && convert == nil { skipped.insert(.convert) }
        return AlarmInputs(volumes: volumes ?? [:], derivatives: derivatives ?? [:], nearRanges: near?.ranges ?? [:],
                           nearMissing: near?.missing ?? [:], volumeRequested: Set(volumeWatches.map(\.id)),
                           convertRates: convert?.rates ?? [:], convertSkips: convert?.skips ?? [:],
                           convertUsdtAssumed: convert?.usdtAssumed ?? [], alarmsDeliverable: deliverable,
                           skippedStages: skipped)
    }

    /// Stundenvolumen je Paar, alle gleichzeitig.
    private static func loadVolumes(_ list: [(watch: Watch, price: Double?)]) async -> [Int64: VolumeSpike] {
        var volumes: [Int64: VolumeSpike] = [:]
        await withTaskGroup(of: (Int64, VolumeSpike?).self) { group in
            for (w, price) in list {
                group.addTask { (w.id, await VolumeDataSource.hourlySpike(watch: w, price: price)) }
            }
            for await (id, spike) in group {
                if let spike { volumes[id] = spike }
            }
        }
        return volumes
    }

    /// Funding/Open Interest je Perpetual, alle gleichzeitig.
    private static func loadDerivatives(_ list: [(watch: Watch, price: Double)]) async -> [Int64: DerivativesAlarmData.Values] {
        var derivatives: [Int64: DerivativesAlarmData.Values] = [:]
        let fetchedAt = TimeUtils.nowMillis
        await withTaskGroup(of: (Int64, DerivativesAlarmData.Values?).self) { group in
            for item in list {
                group.addTask { (item.watch.id, await DerivativesAlarmData.values(item.watch, price: item.price, now: fetchedAt)) }
            }
            for await (id, values) in group {
                if let values { derivatives[id] = values }
            }
        }
        return derivatives
    }

    /// Ergebnis der Stufe «Nahe am Hoch/Tief».
    struct NearLoad: Sendable {
        var ranges: [Int64: [Int: NearExtreme.Range]]
        var missing: [Int64: AlarmCheckStatus.Reason]
    }

    /// Hoch/Tief der Zeiträume je Paar (6 h zwischengespeichert), alle gleichzeitig. Gab es nur USDT-Kerzen,
    /// wird in die Quote des Paars umgerechnet; ohne Faktor fällt das Paar diesmal weg. Die Reihe der Ausweich-Kette
    /// gilt nur bei gleichem Namen und Kurs ±25 % (`NearExtremeDataSource.seriesAllowed`, wie der Mini-Chart) — sonst
    /// kein Hoch/Tief-Alarm statt der Werte eines anderen Tokens. Wie Android `RefreshEffects`.
    private static func loadNearRanges(_ list: [(watch: Watch, price: Double?)]) async -> NearLoad {
        var nearRanges: [Int64: [Int: NearExtreme.Range]] = [:]
        var nearMissing: [Int64: AlarmCheckStatus.Reason] = [:]
        await withTaskGroup(of: (Int64, [Int: NearExtreme.Range]?, AlarmCheckStatus.Reason?).self) { group in
            for item in list {
                let w = item.watch
                let price = item.price
                group.addTask {
                    guard let loaded = await NearExtremeDataSource.ranges(base: w.baseAsset, quote: w.quoteAsset)
                    else { return (w.id, nil, .noCandles) }
                    let sameCurrency = loaded.currency.caseInsensitiveCompare(w.quoteAsset) == .orderedSame
                    // Faktor Quote → Kerzenwährung; Hoch/Tief also dadurch teilen
                    var rate = 1.0
                    if !sameCurrency {
                        guard let r = await CurrencyConverter.rate(quote: w.quoteAsset, target: loaded.currency),
                              r > 0 else { return (w.id, nil, .noRate) }
                        rate = r
                    }
                    guard NearExtremeDataSource.seriesAllowed(loaded, marketKey: w.marketKey, base: w.baseAsset,
                                                              price: price, quoteToSeries: rate)
                    else { return (w.id, nil, .noCandles) }
                    if sameCurrency { return (w.id, loaded.ranges, nil) }
                    let factor = rate
                    return (w.id, loaded.ranges.mapValues { $0.scaled(1 / factor) }, nil)
                }
            }
            for await (id, ranges, missing) in group {
                if let ranges { nearRanges[id] = ranges }
                if let missing { nearMissing[id] = missing }
            }
        }
        return NearLoad(ranges: nearRanges, missing: nearMissing)
    }

    /// Faktoren und Schranken der Alarme in einer anderen Währung (`FiatAlarmGuard`).
    struct ConvertLoad: Sendable {
        var rates: [String: Double] = [:]
        var skips: [String: FiatAlarmGuard.Skip] = [:]
        var usdtAssumed: Set<String> = []
    }

    /// Faktoren «Watch-Id|Währung» für Alarme in einer anderen Währung, alle gleichzeitig; danach je Faktor das Alter
    /// des Devisenkurses und der Stand von USDT/USD (Auslassen bzw. Vermerk «USDT = 1 angenommen») — wie Android.
    private static func loadConvertRates(_ needs: [(watchId: Int64, quote: String, currency: String)]) async -> ConvertLoad {
        let rates = await loadRates(needs)
        var load = ConvertLoad(rates: rates)
        let now = TimeUtils.nowMillis
        for need in needs {
            let key = "\(need.watchId)|\(need.currency)"
            let usdt = CurrencyConverter.usdtState(quote: need.quote, target: need.currency, now: now)
            if let skip = FiatAlarmGuard.skip(fxRateTime: CurrencyConverter.fxRateTime(quote: need.quote, target: need.currency),
                                              usdt: usdt, now: now) {
                load.skips[key] = skip
            }
            if usdt == .ASSUMED { load.usdtAssumed.insert(key) }
        }
        return load
    }

    private static func loadRates(_ needs: [(watchId: Int64, quote: String, currency: String)]) async -> [String: Double] {
        var convertRates: [String: Double] = [:]
        await withTaskGroup(of: (String, Double?).self) { group in
            for need in needs {
                group.addTask {
                    let rate = await CurrencyConverter.rate(quote: need.quote, target: need.currency)
                    return ("\(need.watchId)|\(need.currency)", rate)
                }
            }
            for await (key, rate) in group {
                if let rate { convertRates[key] = rate }
            }
        }
        return convertRates
    }
}
