import Foundation

/// Was nach einem neuen Kurs passiert: Alarm-Daten vorladen, Alarme prüfen (mit Ansage),
/// Kurs-Mitteilung und Kursansage. Wie `RefreshEffects.kt`; Teil von `PriceRefresher`.
extension PriceRefresher {
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
        // DEX-Pool mit zu wenig oder unbekannter Liquidität: Alarme bleiben, lösen aber nicht aus (`DexLiquidity`)
        if DexLiquidityStore.blocksAlarms(marketKey: watch.marketKey, pairId: watch.pairId) {
            // Alarm-Protokoll: nur festhalten, warum nicht
            outcome.alarmLog += blockedAlarmLog(alarms, watch: watch, updated: updated, lowLiquidity: true,
                                                markFallback: false, now: now,
                                                cooldownMinutes: settings.alarmCooldownMinutes)
            return false
        }
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
        /// `logPrice`: Alarm-Protokoll — Kurs in der Währung der Schwelle; nil = dieser Alarm wird nicht protokolliert.
        func report(_ alarm: Alarm, text: String? = nil, volumeRatio: Double? = nil, candleOpenTime: Int64? = nil,
                    nearLevel: Double? = nil, shownPrice: Double? = nil, shownCurrency: String? = nil,
                    logPrice: Double? = nil) {
            // Nachtruhe oder keine Mitteilungs-Erlaubnis (auch bei wiederholenden): nicht auslösen,
            // Alarm bleibt unverändert scharf (`QuietHours.mayFire`)
            if !QuietHours.mayFire(holdAlarms: holdAlarms, deliverable: inputs.alarmsDeliverable) {
                if let logPrice,
                   let trace = AlarmLog.deliveryTrace(holdAlarms: holdAlarms, canShow: inputs.alarmsDeliverable,
                                                      shown: nil, saved: false),
                   let entry = alarmLogEntry(alarm, watch: updated, trace: trace, now: now, comparePrice: logPrice) {
                    outcome.alarmLog.append(entry)
                }
                return
            }
            let after = AlarmEvaluator.triggered(alarm, price: price, time: now, candleOpenTime: candleOpenTime,
                                                 nearLevel: nearLevel)
            let speak = speechAllowed && alarm.speak
            outcome.pendingAlarms.append(PendingAlarm(
                watch: updated, alarm: alarm, price: price, volumeRatio: volumeRatio, text: text,
                update: AlarmUpdate(evaluated: alarm, result: after, setsEnabled: true, pair: updated.identity),
                // Ansage wie die Mitteilung: Kursmarke mit Alarmwährung nennt den umgerechneten Kurs mit Währung
                speech: speak ? SpokenText.alarm(updated, alarm.condition, shownPrice ?? price, currency: shownCurrency) : nil,
                shownPrice: shownPrice, shownCurrency: shownCurrency,
                // Ergebnis steht erst nach dem Melden fest (`deliverAlarms`): vorläufig «fällig»
                logEntry: logPrice.flatMap {
                    alarmLogEntry(alarm, watch: updated, trace: AlarmLogTrace(due: true), now: now, comparePrice: $0)
                }))
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
                // Nur als geprüft vermerken, wenn der benötigte Wert da ist (Funding bzw. Open Interest); sonst
                // «nicht geprüft» statt stiller Lücke — wie Android
                let raw = alarm.condition.isFunding ? values.fundingPercent : values.oiUnits
                guard raw != nil else {
                    checkStatus.markUnchecked(alarm.id, reason: .noData, now: now)
                    continue
                }
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
                // Dieselbe Kerze nicht nochmals melden (referenceAt); Kerzen einer anderen Börse: im Text genannt
                let text = spike.source.map {
                    AlarmTexts.withNote(L("notification_volume_spike_text", AlarmTexts.factor(spike.ratio)), candleSource: $0)
                }
                report(alarm, text: text, volumeRatio: spike.ratio, candleOpenTime: spike.candleOpenTime)
                continue
            }
            // Bewegungs- bzw. Prozentalarm ohne Bezug: aktuellen Kurs als Bezug setzen, ohne auszulösen
            if AlarmEvaluator.needsReference(alarm: alarm, now: now) {
                var after = alarm
                after.referencePrice = price
                after.referenceAt = now
                outcome.alarms[alarm.id] = AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
                if let entry = alarmLogEntry(alarm, watch: updated, trace: AlarmLogTrace(), now: now,
                                             comparePrice: price) {
                    outcome.alarmLog.append(entry)
                }
                continue
            }
            // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen
            var comparePrice = price
            if let currency = alarm.convertCurrency {
                guard let rate = inputs.convertRates["\(watch.id)|\(currency)"] else {
                    if inputs.skippedStages.contains(.convert) { continue }
                    checkStatus.markUnchecked(alarm.id, reason: .noRate, now: now)
                    if let entry = alarmLogEntry(alarm, watch: updated, trace: AlarmLogTrace(noRate: true), now: now) {
                        outcome.alarmLog.append(entry)
                    }
                    continue
                }
                comparePrice = price * rate
                // Devisenkurs zu alt bzw. USDT/USD nur unsicher über USDC: diesmal nicht prüfen, Alarm bleibt scharf
                if let skip = inputs.convertSkips["\(watch.id)|\(currency)"] {
                    checkStatus.markUnchecked(alarm.id, reason: .noRate, now: now)
                    let due = AlarmEvaluator.shouldTrigger(alarm: alarm, price: comparePrice,
                                                           previousPrice: watch.lastPrice.map { $0 * rate }, now: now,
                                                           cooldownMinutes: settings.alarmCooldownMinutes,
                                                           moveHistory: moveHistory)
                    let trace = AlarmLogTrace(fxStale: skip == .FX_STALE, fxUnsure: skip == .UNSURE, due: due)
                    if let entry = alarmLogEntry(alarm, watch: updated, trace: trace, now: now, comparePrice: comparePrice) {
                        outcome.alarmLog.append(entry)
                    }
                    continue
                }
            }
            checkStatus.markChecked(alarm.id)
            // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
            if AlarmEvaluator.shouldRearmLevel(alarm: alarm, price: comparePrice, now: now) {
                rearm(alarm)
                if let entry = alarmLogEntry(alarm, watch: updated, trace: AlarmLogTrace(), now: now,
                                             comparePrice: comparePrice) {
                    outcome.alarmLog.append(entry)
                }
                continue
            }
            guard AlarmEvaluator.shouldTrigger(alarm: alarm, price: comparePrice, previousPrice: watch.lastPrice,
                                               now: now, cooldownMinutes: settings.alarmCooldownMinutes,
                                               moveHistory: moveHistory) else {
                // Protokoll: erfüllt, aber in der Ruhezeit? (dieselbe reine Regel ohne Ruhezeit nachgefragt)
                if AlarmLog.covers(alarm.condition) {
                    let metWithoutCooldown = settings.alarmCooldownMinutes > 0
                        && AlarmEvaluator.shouldTrigger(alarm: alarm, price: comparePrice, previousPrice: watch.lastPrice,
                                                        now: now, cooldownMinutes: 0, moveHistory: moveHistory)
                    let waiting = alarm.condition.isPriceThreshold && !AlarmEvaluator.isLevelArmed(alarm)
                    if let entry = alarmLogEntry(alarm, watch: updated,
                                                 trace: AlarmLogTrace(coolingDown: metWithoutCooldown,
                                                                      waitingRearm: waiting),
                                                 now: now, comparePrice: comparePrice) {
                        outcome.alarmLog.append(entry)
                    }
                }
                continue
            }
            // Umgerechnet: «jetzt» in der Währung der Schwelle (sonst stünde ein USDT-Kurs neben einer EUR-Marke)
            if let currency = alarm.convertCurrency {
                // Umgerechnet mit USDT = 1 (USDT/USD nicht gemessen): im Text vermerkt
                let text = inputs.convertUsdtAssumed.contains("\(watch.id)|\(currency)")
                    ? AlarmTexts.withNote(L("notification_alarm_text", AlarmTexts.describe(alarm),
                                            PriceFormat.priceWithCurrency(comparePrice, currency)), usdtAssumed: true)
                    : nil
                report(alarm, text: text, shownPrice: comparePrice, shownCurrency: currency, logPrice: comparePrice)
            } else {
                report(alarm, logPrice: comparePrice)
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
