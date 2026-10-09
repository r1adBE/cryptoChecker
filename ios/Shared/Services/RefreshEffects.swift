import Foundation

/// Was nach einem neuen Kurs passiert: Alarm-Daten vorladen, Alarme prüfen (mit Ansage),
/// Kurs-Mitteilung und Kursansage. Wie `RefreshEffects.kt`; Teil von `PriceRefresher`.
extension PriceRefresher {

    /// Vorgeladene Werte für die Alarme eines Durchlaufs (je Paar einmal, alle gleichzeitig geholt).
    struct AlarmInputs {
        var volumes: [Int64: VolumeSpike] = [:]
        var derivatives: [Int64: DerivativesAlarmData.Values] = [:]
        var nearRanges: [Int64: [Int: NearExtreme.Range]] = [:]
        /// «Watch-Id|Währung» → Faktor Quote → Währung.
        var convertRates: [String: Double] = [:]
    }

    /// Lädt, was die Alarme brauchen: Volumen, Funding/Open Interest, Hoch/Tief und Umrechnungen.
    /// `prefetchByWatch`: aktive Alarme aus dem mitgegebenen Stand; `live`: Live-Kurse (WebSocket).
    static func loadAlarmInputs(watches: [Watch], fetched: [Int64: Fetched], prefetchByWatch: [Int64: [Alarm]],
                                live: Bool) async -> AlarmInputs {
        // Volumendaten nur für Paare mit Volumen-Alarm (und erfolgreich geholtem Kurs) —
        // je Paar einmal, alle gleichzeitig statt nacheinander.
        let volumeWatches = watches.filter { w in
            !live && fetched[w.id]?.ticker != nil && (prefetchByWatch[w.id] ?? []).contains { $0.condition == .VOLUME_SPIKE }
        }
        var volumes: [Int64: VolumeSpike] = [:]
        if !volumeWatches.isEmpty {
            await withTaskGroup(of: (Int64, VolumeSpike?).self) { group in
                for w in volumeWatches {
                    group.addTask { (w.id, await VolumeDataSource.hourlySpike(base: w.baseAsset, quote: w.quoteAsset)) }
                }
                for await (id, spike) in group {
                    if let spike { volumes[id] = spike }
                }
            }
        }

        // Funding/Open Interest nur für Perpetuals mit solchen Alarmen (nicht bei Live-Kursen),
        // je Paar höchstens alle 5 Min. abgefragt (`DerivativesAlarmData`), alle gleichzeitig.
        let derivativesWatches = watches.filter { w in
            !live && (fetched[w.id]?.ticker?.last ?? 0) > 0
                && (prefetchByWatch[w.id] ?? []).contains { $0.condition.isDerivatives }
        }
        var derivatives: [Int64: DerivativesAlarmData.Values] = [:]
        if !derivativesWatches.isEmpty {
            let fetchedAt = TimeUtils.nowMillis
            await withTaskGroup(of: (Int64, DerivativesAlarmData.Values?).self) { group in
                for w in derivativesWatches {
                    let price = fetched[w.id]?.ticker?.last ?? 0
                    group.addTask { (w.id, await DerivativesAlarmData.values(w, price: price, now: fetchedAt)) }
                }
                for await (id, values) in group {
                    if let values { derivatives[id] = values }
                }
            }
        }

        // «Nahe am Hoch/Tief»: Hoch/Tief der Zeiträume nur für Paare mit so einem Alarm,
        // je Paar einmal (6 h zwischengespeichert), alle gleichzeitig. Gab es nur USDT-Kerzen,
        // wird in die Quote des Paars umgerechnet; ohne Faktor fällt das Paar diesmal weg.
        let nearWatches = watches.filter { w in
            fetched[w.id]?.ticker != nil && (prefetchByWatch[w.id] ?? []).contains { $0.condition.isNearExtreme }
        }
        var nearRanges: [Int64: [Int: NearExtreme.Range]] = [:]
        if !nearWatches.isEmpty {
            await withTaskGroup(of: (Int64, [Int: NearExtreme.Range]?).self) { group in
                for w in nearWatches {
                    group.addTask {
                        guard let loaded = await NearExtremeDataSource.ranges(base: w.baseAsset, quote: w.quoteAsset)
                        else { return (w.id, nil) }
                        if loaded.currency.caseInsensitiveCompare(w.quoteAsset) == .orderedSame {
                            return (w.id, loaded.ranges)
                        }
                        // Faktor Quote → Kerzenwährung; Hoch/Tief also dadurch teilen
                        guard let rate = await CurrencyConverter.rate(quote: w.quoteAsset, target: loaded.currency),
                              rate > 0 else { return (w.id, nil) }
                        return (w.id, loaded.ranges.mapValues { $0.scaled(1 / rate) })
                    }
                }
                for await (id, ranges) in group {
                    if let ranges { nearRanges[id] = ranges }
                }
            }
        }

        // Kursalarme in einer anderen Währung («≈ Umrechnung»): Faktor je Paar und
        // Währung einmal pro Durchlauf, alle gleichzeitig. Ohne Faktor wird der Alarm diesmal ausgelassen.
        var convertNeeds: [(watchId: Int64, quote: String, currency: String)] = []
        for w in watches where fetched[w.id]?.ticker != nil {
            var seen = Set<String>()
            for a in prefetchByWatch[w.id] ?? [] {
                if let currency = a.convertCurrency, seen.insert(currency).inserted {
                    convertNeeds.append((watchId: w.id, quote: w.quoteAsset, currency: currency))
                }
            }
        }
        var convertRates: [String: Double] = [:]
        if !convertNeeds.isEmpty {
            await withTaskGroup(of: (String, Double?).self) { group in
                for need in convertNeeds {
                    group.addTask {
                        let rate = await CurrencyConverter.rate(quote: need.quote, target: need.currency)
                        return ("\(need.watchId)|\(need.currency)", rate)
                    }
                }
                for await (key, rate) in group {
                    if let rate { convertRates[key] = rate }
                }
            }
        }
        return AlarmInputs(volumes: volumes, derivatives: derivatives, nearRanges: nearRanges,
                           convertRates: convertRates)
    }

    /// Prüft die Alarme eines Paars mit neuem Kurs; Änderungen, Mitteilungen und Ansagen landen
    /// in `outcome`. `updated`: das Paar mit dem neuen Kurs. Rückgabe: ein Alarm wurde angesagt.
    static func checkAlarms(_ alarms: [Alarm], watch: Watch, updated: Watch, price: Double, now: Int64,
                            settings: AppSettings, speechAllowed: Bool, inputs: AlarmInputs,
                            outcome: inout Outcome) -> Bool {
        var spokeAlarm = false
        for var alarm in alarms {
            // Funding/Open Interest: wie Kursmarken (scharf/gemeldet in `referenceAt`), Wert aus dem Vorladen
            if alarm.condition.isDerivatives {
                guard let values = inputs.derivatives[watch.id] else { continue }
                let value = DerivativesAlarmData.value(for: alarm, watchId: watch.id, values: values, now: now)
                let decision = DerivativesAlarm.decide(
                    condition: alarm.condition, threshold: alarm.threshold, value: value,
                    armed: alarm.referenceAt <= 0, enabled: alarm.enabled, lastTriggeredAt: alarm.lastTriggeredAt,
                    now: now, cooldownMinutes: settings.alarmCooldownMinutes)
                switch decision {
                case .idle:
                    continue
                case .rearm:
                    outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: alarm.referencePrice,
                                                           referenceAt: 0, lastTriggeredAt: alarm.lastTriggeredAt,
                                                           lastTriggeredPrice: alarm.lastTriggeredPrice)
                    continue
                case let .fire(measured):
                    // «Funding über 0,05 % — jetzt 0,061 %»
                    let text = L("notification_alarm_text", AlarmTexts.describe(alarm),
                                 AlarmTexts.derivativesValue(alarm.condition, measured))
                    outcome.pendingAlarms.append(PendingAlarm(watch: updated, alarm: alarm, price: price, text: text))
                    outcome.alarmsTriggered += 1
                    // Wie markAlarmTriggered: gemeldet (referenceAt > 0), einmalige abschalten.
                    alarm.enabled = alarm.repeating
                    outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: alarm.referencePrice,
                                                           referenceAt: now, lastTriggeredAt: now, lastTriggeredPrice: price)
                    if speechAllowed && alarm.speak {
                        outcome.speech.append((SpokenText.alarm(updated, alarm.condition, price), true))
                        spokeAlarm = true
                    }
                    continue
                }
            }
            if alarm.condition.isNearExtreme {
                guard let range = inputs.nearRanges[watch.id]?[NearExtreme.windowDays(alarm.windowHours)] else { continue }
                let decision = NearExtreme.decide(
                    side: alarm.condition == .NEAR_HIGH ? .high : .low, price: price, range: range,
                    thresholdPercent: alarm.threshold, armed: alarm.referenceAt <= 0, lastLevel: alarm.referencePrice,
                    inCooldown: NearExtreme.inCooldown(lastTriggeredAt: alarm.lastTriggeredAt, now: now,
                                                       cooldownMinutes: settings.alarmCooldownMinutes),
                    lastTriggeredAt: alarm.lastTriggeredAt, now: now)
                switch decision {
                case .idle:
                    continue
                case .rearm:
                    outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: nil, referenceAt: 0,
                                                           lastTriggeredAt: alarm.lastTriggeredAt,
                                                           lastTriggeredPrice: alarm.lastTriggeredPrice)
                    continue
                case let .fire(newExtreme, distancePercent, extreme, level):
                    let text = AlarmTexts.nearExtremeText(alarm, symbol: watch.baseAsset, currency: watch.quoteAsset,
                                                          price: price, newExtreme: newExtreme,
                                                          distancePercent: distancePercent, extreme: extreme)
                    outcome.pendingAlarms.append(PendingAlarm(watch: updated, alarm: alarm, price: price, text: text))
                    outcome.alarmsTriggered += 1
                    // Wie markAlarmTriggered: Marke merken, gemeldet (referenceAt > 0), einmalige abschalten.
                    alarm.enabled = alarm.repeating
                    outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: level, referenceAt: now,
                                                           lastTriggeredAt: now, lastTriggeredPrice: price)
                    if speechAllowed && alarm.speak {
                        outcome.speech.append((SpokenText.alarm(updated, alarm.condition, price), true))
                        spokeAlarm = true
                    }
                    continue
                }
            }
            if alarm.condition == .VOLUME_SPIKE {
                guard let spike = inputs.volumes[watch.id],
                      AlarmEvaluator.shouldTriggerVolumeSpike(alarm: alarm, ratio: spike.ratio,
                                                              candleOpenTime: spike.candleOpenTime, now: now,
                                                              cooldownMinutes: settings.alarmCooldownMinutes)
                else { continue }
                outcome.pendingAlarms.append(PendingAlarm(watch: updated, alarm: alarm, price: price,
                                                          volumeRatio: spike.ratio))
                outcome.alarmsTriggered += 1
                // Wie markAlarmTriggered: dieselbe Kerze nicht nochmals melden (referenceAt),
                // einmalige Alarme abschalten.
                alarm.enabled = alarm.repeating
                outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: alarm.referencePrice,
                                                       referenceAt: spike.candleOpenTime, lastTriggeredAt: now,
                                                       lastTriggeredPrice: price)
                if speechAllowed && alarm.speak {
                    outcome.speech.append((SpokenText.alarm(updated, alarm.condition, price), true))
                    spokeAlarm = true
                }
                continue
            }
            if AlarmEvaluator.needsWindowReset(alarm: alarm, now: now) {
                outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: price, referenceAt: now,
                                                       lastTriggeredAt: alarm.lastTriggeredAt, lastTriggeredPrice: alarm.lastTriggeredPrice)
                continue
            }
            // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen
            var comparePrice = price
            if let currency = alarm.convertCurrency {
                guard let rate = inputs.convertRates["\(watch.id)|\(currency)"] else { continue }
                comparePrice = price * rate
            }
            // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
            if AlarmEvaluator.shouldRearmLevel(alarm: alarm, price: comparePrice) {
                outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: alarm.referencePrice,
                                                       referenceAt: 0, lastTriggeredAt: alarm.lastTriggeredAt,
                                                       lastTriggeredPrice: alarm.lastTriggeredPrice)
                continue
            }
            guard AlarmEvaluator.shouldTrigger(alarm: alarm, price: comparePrice, previousPrice: watch.lastPrice,
                                               now: now, cooldownMinutes: settings.alarmCooldownMinutes) else { continue }
            outcome.pendingAlarms.append(PendingAlarm(watch: updated, alarm: alarm, price: price))
            outcome.alarmsTriggered += 1
            // Wie markAlarmTriggered: Bezug neu setzen, Kursmarke als gemeldet merken
            // (referenceAt > 0 bis zur Rückkehr), einmalige Alarme abschalten.
            alarm.lastTriggeredAt = now
            alarm.lastTriggeredPrice = price
            if alarm.condition.isPercent { alarm.referencePrice = price }
            if alarm.condition == .MOVE_PERCENT_WINDOW || alarm.condition.isPriceThreshold { alarm.referenceAt = now }
            alarm.enabled = alarm.repeating
            outcome.alarms[alarm.id] = AlarmUpdate(enabled: alarm.enabled, referencePrice: alarm.referencePrice,
                                                   referenceAt: alarm.referenceAt, lastTriggeredAt: now, lastTriggeredPrice: price)
            if speechAllowed && alarm.speak {
                outcome.speech.append((SpokenText.alarm(updated, alarm.condition, price), true))
                spokeAlarm = true
            }
        }
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
