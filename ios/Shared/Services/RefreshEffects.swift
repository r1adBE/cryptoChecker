import Foundation

/// Was nach einem neuen Kurs passiert: Alarm-Daten vorladen, Alarme prüfen (mit Ansage),
/// Kurs-Mitteilung und Kursansage. Wie `RefreshEffects.kt`; Teil von `PriceRefresher`.
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
                    convertNeeds.append((watchId: w.id, quote: w.quoteAsset, currency: currency))
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
                           convertRates: convert ?? [:], alarmsDeliverable: deliverable, skippedStages: skipped)
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

    /// Faktoren «Watch-Id|Währung» für Alarme in einer anderen Währung, alle gleichzeitig.
    private static func loadConvertRates(_ needs: [(watchId: Int64, quote: String, currency: String)]) async -> [String: Double] {
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

    /// Prüft die Alarme eines Paars mit neuem Kurs; Änderungen und ausgelöste Alarme (mit Ansage) landen
    /// in `outcome`. `updated`: das Paar mit dem neuen Kurs. Rückgabe: ein Alarm soll angesagt werden.
    ///
    /// Änderungen tragen den ausgewerteten Stand mit (`AlarmUpdate.matches`): Hat der Nutzer den
    /// Alarm inzwischen bearbeitet oder abgeschaltet, verwerfen `Outcome.apply(to:)` bzw.
    /// `Outcome.deliverAlarms` sie, und die Mitteilung entfällt. Alarme (auch wiederholende) lösen ohne
    /// Mitteilungs-Erlaubnis nicht aus (`AlarmInputs.alarmsDeliverable`): Sie bleiben scharf und melden,
    /// sobald es wieder geht.
    static func checkAlarms(_ alarms: [Alarm], watch: Watch, updated: Watch, price: Double, now: Int64,
                            settings: AppSettings, speechAllowed: Bool, inputs: AlarmInputs,
                            outcome: inout Outcome) -> Bool {
        var spokeAlarm = false
        // Nachtruhe: Alarme warten bis zum Ende und melden sich dann mit Ton (`QuietHours.holdsAlarms`)
        let holdAlarms = QuietHours.holdsAlarmsNow(settings)
        // Bewegungs-Alarm: Kursverlauf des Paars (nur wenn es so einen Alarm hat)
        let hasMove = alarms.contains { $0.condition == .MOVE_PERCENT_WINDOW }
        let moveHistory = hasMove ? MoveHistoryStore.history(watch.id) : []

        /// Ausgelösten Alarm vormerken: Zustand nach dem Auslösen (`AlarmEvaluator.triggered`) und Ansage —
        /// gemeldet, gespeichert und angesagt erst in `Outcome.deliverAlarms` (zuerst die Mitteilung, wie
        /// Android `AlarmDelivery`). Alarme ohne zustellbare Mitteilung (einmalige wie wiederholende)
        /// bleiben unverändert scharf.
        /// `shownPrice`/`shownCurrency`: Kurs in der Alarmwährung (Schwelle in anderer Währung), damit «jetzt» in
        /// derselben Währung steht wie die Schwelle; nil = Kurs in der Quote-Währung des Paars.
        func report(_ alarm: Alarm, text: String? = nil, volumeRatio: Double? = nil, candleOpenTime: Int64? = nil,
                    nearLevel: Double? = nil, shownPrice: Double? = nil, shownCurrency: String? = nil) {
            // Nachtruhe oder keine Mitteilungs-Erlaubnis (auch bei wiederholenden): nicht auslösen,
            // Alarm bleibt unverändert scharf (`QuietHours.mayFire`)
            if !QuietHours.mayFire(holdAlarms: holdAlarms, deliverable: inputs.alarmsDeliverable) { return }
            let after = AlarmEvaluator.triggered(alarm, price: price, time: now, candleOpenTime: candleOpenTime,
                                                 nearLevel: nearLevel)
            let speak = speechAllowed && alarm.speak
            outcome.pendingAlarms.append(PendingAlarm(
                watch: updated, alarm: alarm, price: price, volumeRatio: volumeRatio, text: text,
                update: AlarmUpdate(evaluated: alarm, result: after, setsEnabled: true),
                speech: speak ? SpokenText.alarm(updated, alarm.condition, price) : nil,
                shownPrice: shownPrice, shownCurrency: shownCurrency))
            if speak { spokeAlarm = true }
        }

        /// Wieder scharf stellen (`referenceAt` = 0); die gemeldete Marke von «Nahe am Hoch/Tief»
        /// bleibt (`NearExtreme.reportedMark`), `enabled` bleibt unberührt.
        func rearm(_ alarm: Alarm) {
            var after = alarm
            after.referenceAt = 0
            outcome.alarms[alarm.id] = AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
        }

        // Ausgelassen (kein Devisenkurs/keine Kerzen) bzw. wieder geprüft: für den Hinweis in der Alarmliste
        let checkStatus = AlarmCheckStatus.shared

        for alarm in alarms {
            // Funding/Open Interest: wie Kursmarken (scharf/gemeldet in `referenceAt`), Wert aus dem Vorladen
            if alarm.condition.isDerivatives {
                guard let values = inputs.derivatives[watch.id] else { continue }
                // Nur als geprüft vermerken, wenn der benötigte Wert da ist (Funding bzw. Open Interest) — wie Android;
                // ohne Wert diesmal auslassen, ohne einen Hinweis «nicht geprüft» zu löschen
                let raw = alarm.condition.isFunding ? values.fundingPercent : values.oiUnits
                guard raw != nil else { continue }
                // Kein Hinweis «nicht geprüft» (z. B. von einer früheren Kursmarke) stehen lassen
                checkStatus.markChecked(alarm.id)
                let value = DerivativesAlarmData.value(for: alarm, watchId: watch.id, values: values, now: now)
                let decision = DerivativesAlarm.decide(
                    condition: alarm.condition, threshold: alarm.threshold, value: value,
                    armed: alarm.referenceAt <= 0, enabled: alarm.enabled, lastTriggeredAt: alarm.lastTriggeredAt,
                    now: now, cooldownMinutes: settings.alarmCooldownMinutes)
                switch decision {
                case .idle:
                    continue
                case .rearm:
                    rearm(alarm)
                    continue
                case let .fire(measured):
                    // «Funding über 0,05 % — jetzt 0,061 %»
                    var text = L("notification_alarm_text", AlarmTexts.describe(alarm),
                                 AlarmTexts.derivativesValue(alarm.condition, measured))
                    // «… · Richtwert Binance Futures», wenn die Werte von einer anderen Börse stammen
                    if let reference = values.referenceSource {
                        text += " · " + L("futures_alarm_reference", reference)
                    }
                    report(alarm, text: text)
                    continue
                }
            }
            if alarm.condition.isNearExtreme {
                guard let range = inputs.nearRanges[watch.id]?[NearExtreme.windowDays(alarm.windowHours)] else {
                    // Aus Zeitmangel nicht geladen: diese Runde still auslassen
                    if inputs.skippedStages.contains(.nearExtreme) { continue }
                    checkStatus.markUnchecked(alarm.id, reason: inputs.nearMissing[watch.id] ?? .noCandles, now: now)
                    continue
                }
                checkStatus.markChecked(alarm.id)
                let decision = NearExtreme.decide(
                    side: alarm.condition == .NEAR_HIGH ? .high : .low, price: price, range: range,
                    thresholdPercent: alarm.threshold, armed: alarm.referenceAt <= 0,
                    lastLevel: NearExtreme.reportedMark(lastLevel: alarm.referencePrice, lastTriggeredAt: alarm.lastTriggeredAt,
                                                        windowDays: alarm.windowHours, now: now),
                    inCooldown: NearExtreme.inCooldown(lastTriggeredAt: alarm.lastTriggeredAt, now: now,
                                                       cooldownMinutes: settings.alarmCooldownMinutes),
                    lastTriggeredAt: alarm.lastTriggeredAt, now: now)
                switch decision {
                case .idle:
                    continue
                case .rearm:
                    rearm(alarm)
                    continue
                case let .fire(newExtreme, distancePercent, extreme, level):
                    let text = AlarmTexts.nearExtremeText(alarm, symbol: watch.baseAsset, currency: watch.quoteAsset,
                                                          price: price, newExtreme: newExtreme,
                                                          distancePercent: distancePercent, extreme: extreme)
                    report(alarm, text: text, nearLevel: level)
                    continue
                }
            }
            if alarm.condition == .VOLUME_SPIKE {
                guard let spike = inputs.volumes[watch.id] else {
                    // Live-Kurse fragen kein Volumen ab, aus Zeitmangel nicht geladen: dann kein Hinweis
                    if inputs.volumeRequested.contains(watch.id) && !inputs.skippedStages.contains(.volume) {
                        checkStatus.markUnchecked(alarm.id, reason: .noCandles, now: now)
                    }
                    continue
                }
                checkStatus.markChecked(alarm.id)
                guard AlarmEvaluator.shouldTriggerVolumeSpike(alarm: alarm, ratio: spike.ratio,
                                                              candleOpenTime: spike.candleOpenTime, now: now,
                                                              cooldownMinutes: settings.alarmCooldownMinutes)
                else { continue }
                // Dieselbe Kerze nicht nochmals melden (referenceAt)
                report(alarm, volumeRatio: spike.ratio, candleOpenTime: spike.candleOpenTime)
                continue
            }
            // Bewegungs- bzw. Prozentalarm ohne Bezug: aktuellen Kurs als Bezug setzen, ohne auszulösen
            if AlarmEvaluator.needsReference(alarm: alarm, now: now) {
                var after = alarm
                after.referencePrice = price
                after.referenceAt = now
                outcome.alarms[alarm.id] = AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
                continue
            }
            // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen
            var comparePrice = price
            if let currency = alarm.convertCurrency {
                guard let rate = inputs.convertRates["\(watch.id)|\(currency)"] else {
                    if inputs.skippedStages.contains(.convert) { continue }
                    checkStatus.markUnchecked(alarm.id, reason: .noRate, now: now)
                    continue
                }
                comparePrice = price * rate
            }
            checkStatus.markChecked(alarm.id)
            // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
            if AlarmEvaluator.shouldRearmLevel(alarm: alarm, price: comparePrice, now: now) {
                rearm(alarm)
                continue
            }
            guard AlarmEvaluator.shouldTrigger(alarm: alarm, price: comparePrice, previousPrice: watch.lastPrice,
                                               now: now, cooldownMinutes: settings.alarmCooldownMinutes,
                                               moveHistory: moveHistory) else { continue }
            // Umgerechnet: «jetzt» in der Währung der Schwelle (sonst stünde ein USDT-Kurs neben einer EUR-Marke)
            if let currency = alarm.convertCurrency {
                report(alarm, shownPrice: comparePrice, shownCurrency: currency)
            } else {
                report(alarm)
            }
        }
        // Erst nach der Prüfung: der aktuelle Kurs ist ohnehin der Vergleichswert
        if hasMove { MoveHistoryStore.append(watch.id, MoveWindow.PricePoint(price: price, time: now), now: now) }
        return spokeAlarm
    }

    /// Kurs-Mitteilung (je nach Einstellung erst ab einer Bewegung) und Kursansage eines Paars.
    /// Paare ohne Mitteilung kommen in `cancelIds` (gesammelt entfernt).
    static func priceEffects(watch: Watch, updated: Watch, price: Double, now: Int64, settings: AppSettings,
                             speechAllowed: Bool, spokeAlarm: Bool, live: Bool,
                             outcome: inout Outcome, cancelIds: inout [Int64]) {
        // Kurs-Mitteilung
        if settings.priceNotifications && watch.notificationEnabled {
            var notify = true
            if settings.notificationChangePercent > 0, let ref = watch.notifiedPrice, ref > 0 {
                notify = abs((price - ref) / ref * 100) >= settings.notificationChangePercent
            }
            if notify {
                Notifier.showPrice(updated)
                outcome.notified[watch.id] = (price, now)
            }
        } else {
            cancelIds.append(watch.id)
        }

        // Ansage
        if !live && !spokeAlarm && speechAllowed && !settings.ttsAlarmsOnly && watch.ttsEnabled {
            outcome.priceSpeech.append((watch.id, SpokenText.price(updated, price)))
        }
    }
}
