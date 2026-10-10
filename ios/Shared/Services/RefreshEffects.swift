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
        /// Mitteilungen erlaubt? Ohne bleiben einmalige Alarme scharf, statt still verbraucht zu werden.
        var alarmsDeliverable = true
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
        // Mitteilungs-Erlaubnis einmal je Durchlauf (nur wenn Alarme ausgewertet werden)
        var deliverable = true
        if !prefetchByWatch.isEmpty { deliverable = await Notifier.isAuthorized() }
        return AlarmInputs(volumes: volumes, derivatives: derivatives, nearRanges: nearRanges,
                           convertRates: convertRates, alarmsDeliverable: deliverable)
    }

    /// Prüft die Alarme eines Paars mit neuem Kurs; Änderungen, Mitteilungen und Ansagen landen
    /// in `outcome`. `updated`: das Paar mit dem neuen Kurs. Rückgabe: ein Alarm wurde angesagt.
    ///
    /// Änderungen tragen den ausgewerteten Stand mit (`AlarmUpdate.matches`): Hat der Nutzer den
    /// Alarm inzwischen bearbeitet oder abgeschaltet, verwirft `Outcome.apply(to:)` sie und die
    /// Mitteilung entfällt. Einmalige Alarme lösen ohne Mitteilungs-Erlaubnis nicht aus
    /// (`AlarmInputs.alarmsDeliverable`): Sie bleiben scharf und melden, sobald es wieder geht.
    static func checkAlarms(_ alarms: [Alarm], watch: Watch, updated: Watch, price: Double, now: Int64,
                            settings: AppSettings, speechAllowed: Bool, inputs: AlarmInputs,
                            outcome: inout Outcome) -> Bool {
        var spokeAlarm = false
        // Bewegungs-Alarm: Kursverlauf des Paars (nur wenn es so einen Alarm hat)
        let hasMove = alarms.contains { $0.condition == .MOVE_PERCENT_WINDOW }
        let moveHistory = hasMove ? MoveHistoryStore.history(watch.id) : []

        /// Ausgelösten Alarm melden: Zustand nach dem Auslösen (`AlarmEvaluator.triggered`),
        /// Mitteilung (erst nach dem Speichern, siehe `PendingAlarm`), Ansage. Einmalige Alarme
        /// ohne zustellbare Mitteilung bleiben unverändert scharf.
        func report(_ alarm: Alarm, _ pending: PendingAlarm, candleOpenTime: Int64? = nil, nearLevel: Double? = nil) {
            if !alarm.repeating && !inputs.alarmsDeliverable { return }
            let after = AlarmEvaluator.triggered(alarm, price: price, time: now, candleOpenTime: candleOpenTime,
                                                 nearLevel: nearLevel)
            outcome.pendingAlarms.append(pending)
            outcome.alarmsTriggered += 1
            outcome.alarms[alarm.id] = AlarmUpdate(evaluated: alarm, result: after, setsEnabled: true)
            if speechAllowed && alarm.speak {
                outcome.speech.append((SpokenText.alarm(updated, alarm.condition, price), true))
                spokeAlarm = true
            }
        }

        /// Wieder scharf stellen (`referenceAt` = 0); die gemeldete Marke von «Nahe am Hoch/Tief»
        /// bleibt (`NearExtreme.reportedMark`), `enabled` bleibt unberührt.
        func rearm(_ alarm: Alarm) {
            var after = alarm
            after.referenceAt = 0
            outcome.alarms[alarm.id] = AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
        }

        for alarm in alarms {
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
                    rearm(alarm)
                    continue
                case let .fire(measured):
                    // «Funding über 0,05 % — jetzt 0,061 %»
                    let text = L("notification_alarm_text", AlarmTexts.describe(alarm),
                                 AlarmTexts.derivativesValue(alarm.condition, measured))
                    report(alarm, PendingAlarm(watch: updated, alarm: alarm, price: price, text: text))
                    continue
                }
            }
            if alarm.condition.isNearExtreme {
                guard let range = inputs.nearRanges[watch.id]?[NearExtreme.windowDays(alarm.windowHours)] else { continue }
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
                    report(alarm, PendingAlarm(watch: updated, alarm: alarm, price: price, text: text), nearLevel: level)
                    continue
                }
            }
            if alarm.condition == .VOLUME_SPIKE {
                guard let spike = inputs.volumes[watch.id],
                      AlarmEvaluator.shouldTriggerVolumeSpike(alarm: alarm, ratio: spike.ratio,
                                                              candleOpenTime: spike.candleOpenTime, now: now,
                                                              cooldownMinutes: settings.alarmCooldownMinutes)
                else { continue }
                // Dieselbe Kerze nicht nochmals melden (referenceAt)
                report(alarm, PendingAlarm(watch: updated, alarm: alarm, price: price, volumeRatio: spike.ratio),
                       candleOpenTime: spike.candleOpenTime)
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
                guard let rate = inputs.convertRates["\(watch.id)|\(currency)"] else { continue }
                comparePrice = price * rate
            }
            // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
            if AlarmEvaluator.shouldRearmLevel(alarm: alarm, price: comparePrice) {
                rearm(alarm)
                continue
            }
            guard AlarmEvaluator.shouldTrigger(alarm: alarm, price: comparePrice, previousPrice: watch.lastPrice,
                                               now: now, cooldownMinutes: settings.alarmCooldownMinutes,
                                               moveHistory: moveHistory) else { continue }
            report(alarm, PendingAlarm(watch: updated, alarm: alarm, price: price))
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
