import Foundation

/// Holt die Kurse aller beobachteten Paare und löst aus, was daran hängt:
/// Alarme, Kurs-Mitteilungen und Ansagen — wie `PriceRefresher.kt`.
///
/// Arbeitet auf einer Kopie der Daten und liefert nur die Änderungen zurück
/// (`Outcome.apply(to:)`). So gehen Änderungen, die der Nutzer während der
/// Abfrage macht, nicht verloren — egal ob App, Hintergrund oder Widget fragt.
enum PriceRefresher {

    /// Unter so wenigen Paaren spart die Sammelabfrage nichts.
    static let minWatchesForBulk = 3
    /// Gleichzeitige Einzelabfragen je Börse.
    static let maxParallelPerMarket = 4

    struct PriceUpdate: Sendable {
        var price: Double?
        var time: Int64
        var error: String?
        /// Veränderung über 24 Stunden zum neuen Kurs (siehe `DayChange`); nil = kein Bezug.
        var change24h: Double? = nil
    }

    struct AlarmUpdate: Sendable {
        var enabled: Bool
        var referencePrice: Double?
        var referenceAt: Int64
        var lastTriggeredAt: Int64
        var lastTriggeredPrice: Double?
    }

    /// Ausgelöster Alarm, dessen Mitteilung erst NACH dem Speichern gezeigt wird
    /// (`Outcome.deliverAlarms()`): Beendet iOS den Prozess vorher, ist der Zustand
    /// schon gespeichert oder gar nichts passiert — nie eine doppelte Meldung.
    struct PendingAlarm: Sendable {
        var watch: Watch
        var alarm: Alarm
        var price: Double
        var volumeRatio: Double? = nil
        var text: String? = nil
    }

    struct Outcome: Sendable {
        var prices: [Int64: PriceUpdate] = [:]
        var alarms: [Int64: AlarmUpdate] = [:]
        /// Mitteilungen der ausgelösten Alarme — erst nach dem Speichern zeigen.
        var pendingAlarms: [PendingAlarm] = []
        /// Lease der Alarm-Auswertung (nil = keine Alarme ausgewertet); freigegeben
        /// von `deliverAlarms()` / `releaseAlarmLease()`, spätestens beim Aufräumen.
        var alarmLease: AlarmLease? = nil
        /// Watch-Id → (gemeldeter Kurs, Zeitpunkt).
        var notified: [Int64: (Double, Int64)] = [:]
        /// Texte zum Vorlesen; `flush` = laufende Ansage abbrechen (Alarme).
        var speech: [(text: String, flush: Bool)] = []
        /// Kursansagen je Paar (Watch-Id, Text) — getrennt von den Alarmen in `speech`,
        /// damit der Sprecher je Paar nur die neueste wartende Ansage behält.
        var priceSpeech: [(watchId: Int64, text: String)] = []
        var checked = 0
        var failed = 0
        var alarmsTriggered = 0
        var durationMillis: Int64 = 0
        /// Bericht «Letzte Aktualisierung»; nil bei einer Einzelabfrage (`onlyWatchId`).
        var report: RefreshReport? = nil
        /// Paare pausierter Börsen (`ExchangeBackoff`): nicht angefragt, Kurs und Zustand bleiben.
        var pausedIds: Set<Int64> = []
        /// %-Basis und Tagesbeginn, mit denen dieser volle Durchlauf gerechnet hat (nil bei einer
        /// Einzelabfrage) — der Aufrufer speichert ihn mit (`SharedStorage.changeStamp`).
        var changeStamp: ChangeStamp? = nil
        /// Neue Basis oder neuer Tag gegenüber dem gespeicherten Stempel: Paare ohne neuen Kurs
        /// verlieren ihre alte Veränderung (sie gehört nicht mehr dazu).
        var changeStampChanged = false
        /// Tages-Basen: Paare mit neuem Kurs, aber noch ohne Bezug (Watch-Id → Kurs, Zeit) —
        /// `lateDayChanges` trägt sie nach, sobald die Kerzen da sind.
        var missingDayChange: [Int64: (price: Double, time: Int64)] = [:]
        /// Wartet (höchstens 90 s) auf die noch laufenden Bezüge und liefert die Veränderungen der
        /// `missingDayChange`-Paare; nil ohne solche Paare oder bei rollender Basis.
        var lateDayChanges: (@Sendable () async -> [Int64: Double])? = nil

        /// NACH `apply(to:)` und dem Speichern aufrufen: zeigt die Alarm-Mitteilungen und
        /// gibt die Lease frei, damit der nächste Prozess den gespeicherten Stand sieht.
        func deliverAlarms() {
            for item in pendingAlarms {
                Notifier.showAlarm(item.watch, item.alarm, price: item.price, volumeRatio: item.volumeRatio, text: item.text)
            }
            alarmLease?.release()
        }

        /// Ergebnis verworfen (z. B. von iOS abgebrochen, nicht gespeichert): nichts melden,
        /// Lease freigeben — die Alarme sind dann weiterhin scharf und melden beim nächsten Mal.
        func releaseAlarmLease() {
            alarmLease?.release()
        }

        /// Überträgt die Ergebnisse auf den aktuellen Stand.
        func apply(to snapshot: inout SharedStorage.Snapshot) {
            for i in snapshot.watches.indices {
                let id = snapshot.watches[i].id
                if let u = prices[id] {
                    if let price = u.price {
                        snapshot.watches[i].previousPrice = snapshot.watches[i].lastPrice
                        snapshot.watches[i].lastPrice = price
                        snapshot.watches[i].lastUpdate = u.time
                        snapshot.watches[i].lastError = nil
                        snapshot.watches[i].change24h = u.change24h
                    } else {
                        snapshot.watches[i].lastError = u.error ?? L("something_went_wrong")
                        if changeStampChanged { snapshot.watches[i].change24h = nil }
                    }
                } else if changeStampChanged && pausedIds.contains(id) {
                    // Pausierte Börse: Veränderung gehört zur alten Basis
                    snapshot.watches[i].change24h = nil
                }
                if let (price, at) = notified[id] {
                    snapshot.watches[i].notifiedPrice = price
                    snapshot.watches[i].notifiedAt = at
                }
            }
            for i in snapshot.alarms.indices {
                if let u = alarms[snapshot.alarms[i].id] {
                    snapshot.alarms[i].enabled = u.enabled
                    snapshot.alarms[i].referencePrice = u.referencePrice
                    snapshot.alarms[i].referenceAt = u.referenceAt
                    snapshot.alarms[i].lastTriggeredAt = u.lastTriggeredAt
                    snapshot.alarms[i].lastTriggeredPrice = u.lastTriggeredPrice
                }
            }
        }
    }

    private struct Fetched: Sendable {
        var ticker: Ticker?
        var error: String?
        var fromSingle = false
        var millis: Int64 = 0
        var notTraded = false
        /// Ursache für den Bericht (nur bei Fehlern).
        var failure: RefreshFailure? = nil
    }

    static let notTradedError = NotTraded.marker

    /// So lange wartet ein Durchlauf auf die Lease der Alarm-Auswertung; sonst diesmal ohne Alarme.
    static let alarmLeaseWaitSeconds: Double = 5

    /// Alle Paare (oder nur `onlyWatchId`) abfragen und auswerten.
    ///
    /// - Parameter evaluateAlarms: false = nur Kurse (Widget): keine Alarme auswerten oder melden.
    /// - Parameter liveIds: Paare mit frischem Live-Kurs (WebSocket, Merkliste offen, siehe
    ///   `LiveRules.skipRest`) — diesmal ohne REST-Abfrage.
    /// - Parameter liveQuotes: Live-Kurse gesammelt speichern (`AppData.applyLive`): nur diese Paare,
    ///   ohne Netz und Kerzen; die Veränderung steht schon im Ticker (`LiveRules.chooseChange`).
    ///   Gleicher Weg für Alarme und Kurs-Mitteilungen, aber keine Kursansagen, keine Volumen-Alarme
    ///   und kein Bericht.
    ///
    /// Alarme: Nach den Kursen wird die prozessübergreifende `AlarmLease` genommen und der
    /// Alarm-Zustand frisch von der Platte gelesen. Mitteilungen kommen NICHT von hier, sondern
    /// liegen in `Outcome.pendingAlarms`: Der Aufrufer wendet das Ergebnis an, speichert und
    /// ruft dann `Outcome.deliverAlarms()` (zeigt sie und gibt die Lease frei).
    static func refresh(snapshot: SharedStorage.Snapshot, settings: AppSettings, onlyWatchId: Int64? = nil,
                        evaluateAlarms: Bool = true, liveIds: Set<Int64> = [],
                        liveQuotes: [Int64: LiveQuote]? = nil) async -> Outcome {
        let started = TimeUtils.nowMillis
        var outcome = Outcome()
        // Kein Netz: gar nicht erst versuchen — Kurse und Zustände bleiben, die Merkliste zeigt
        // «Offline · Stand …». Kommt das Netz zurück, aktualisiert `AppData` einmal.
        guard NetworkStatus.shared.isOnline else { return outcome }
        // Pausierte Börsen (zu viele Anfragen, wiederholte Zeitüberschreitungen): diesmal aussen vor
        var backoff = SharedStorage.exchangeBackoff
        // Live-Kurse: nur diese Paare (nicht mehr gehandelte bleiben unberührt), keine Pause je Börse
        let all = snapshot.watches.filter { w in
            if let liveQuotes { return liveQuotes[w.id] != nil && !w.isNotTraded }
            return onlyWatchId == nil || w.id == onlyWatchId
        }
        let paused = liveQuotes != nil ? [] : all.filter { ExchangeBackoff.isPaused(backoff[$0.marketKey], now: started) }
        outcome.pausedIds = Set(paused.map(\.id))
        // Paare mit frischem Live-Kurs (WebSocket): diesmal ohne REST-Abfrage
        let watches = all.filter { !outcome.pausedIds.contains($0.id) && !liveIds.contains($0.id) }
        guard !watches.isEmpty else {
            outcome.durationMillis = TimeUtils.nowMillis - started
            // Alle Börsen pausiert: der Bericht sagt bis wann
            if onlyWatchId == nil && !paused.isEmpty {
                outcome.report = RefreshReport(at: TimeUtils.nowMillis, totalMillis: outcome.durationMillis,
                                               pairs: all.count, networkMillis: 0,
                                               markets: pausedMarkets(paused, backoff: backoff))
            }
            return outcome
        }

        // %-Basis: rollend (Ticker, Kerzen nur als Ausweich-Weg) oder seit Tagesbeginn (immer Kerzen)
        let basis = settings.changeBasis
        let changeStamp = ChangeBasisMath.stamp(basis, now: started)
        let dayStart: Int64? = basis.isDay ? changeStamp.dayStart : nil

        var fetched: [Int64: Fetched] = [:]
        var markets: [MarketRefresh] = []
        let dayLoads: Task<Void, Never>
        let dayReferences: [String: DayReference]
        if let liveQuotes {
            // Live-Kurse: schon da — kein Netz, keine Kerzen
            for w in watches {
                guard let q = liveQuotes[w.id] else { continue }
                fetched[w.id] = Fetched(ticker: Ticker(last: q.price, timestamp: q.time, change24hPercent: q.change24h),
                                        error: nil)
            }
            dayLoads = Task {}
            dayReferences = [:]
        } else {
            // Bezüge (Kerzen) parallel zu den Kursen für Paare, die beim letzten Mal Kerzen brauchten
            // (rollend: Ticker ohne 24-h-Wert; Tages-Basen: alle); der Rest nach den Kursen.
            let remembered = candleNeeds.ids
            let earlyKeys = dayReferenceKeys(dayStart != nil ? watches : watches.filter { remembered.contains($0.id) })
            let earlyLoads = startDayReferenceLoads(earlyKeys, dayStart: dayStart)

            // 1) Netz: alle Börsen gleichzeitig, je Börse erst die Sammelabfrage.
            let groups = Dictionary(grouping: watches, by: \.marketKey)
            var signals: [GroupSignals] = []

            await withTaskGroup(of: ([Int64: Fetched], GroupSignals?).self) { group in
                for (_, list) in groups {
                    group.addTask {
                        if onlyWatchId != nil, let w = list.first {
                            let single = await fetchSingle(w)
                            return ([w.id: single], nil)
                        }
                        return await fetchGroup(list, includeRollingFutures: settings.includeRollingFutures)
                    }
                }
                for await (results, signal) in group {
                    fetched.merge(results) { a, _ in a }
                    if let signal { signals.append(signal) }
                }
            }
            // Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben
            if onlyWatchId == nil {
                let finished = TimeUtils.nowMillis
                for signal in signals {
                    var market = signal.report
                    let state = ExchangeBackoff.next(
                        backoff[signal.marketKey],
                        outcome: ExchangeBackoff.outcome(failures: signal.failures, updated: market.updated),
                        now: finished, retryAfterMillis: signal.retryAfterMillis)
                    backoff[signal.marketKey] = state
                    if let state, ExchangeBackoff.isPaused(state, now: finished) {
                        market.pausedUntil = state.pausedUntil
                        market.pauseReason = state.reason
                    }
                    markets.append(market)
                }
                markets.append(contentsOf: pausedMarkets(paused, backoff: backoff))
                SharedStorage.exchangeBackoff = backoff
            }
            let needingCandles = watchesNeedingCandles(watches, fetched: fetched, basis: basis, remember: onlyWatchId == nil)
            let startedKeys = Set(earlyKeys.map { "\($0.base)|\($0.quote)" })
            let lateKeys = dayReferenceKeys(needingCandles).filter { !startedKeys.contains("\($0.base)|\($0.quote)") }
            let lateLoads = startDayReferenceLoads(lateKeys, dayStart: dayStart)
            dayLoads = Task {
                await earlyLoads.value
                await lateLoads.value
            }
            await waitAtMost(nanos: dayReferenceWaitNanos, for: dayLoads)
            dayReferences = await cachedDayReferences(needingCandles, dayStart: dayStart)
        }
        let networkMillis = TimeUtils.nowMillis - started

        // 2) Auswerten
        let now = TimeUtils.nowMillis
        // Nachtruhe: Alarme wie gewohnt auswerten und melden (lautlos, siehe Notifier),
        // aber nichts vorlesen — weder Alarme noch Kurse.
        let speechAllowed = settings.ttsEnabled && !QuietHours.isQuietNow(settings)
        // Für das Vorladen (Volumen, Hoch/Tief, Umrechnung) genügt der mitgegebene Stand;
        // ausgewertet wird weiter unten der frisch gelesene.
        let prefetchAlarms = evaluateAlarms ? snapshot.alarms.filter(\.enabled) : []
        let prefetchByWatch = Dictionary(grouping: prefetchAlarms, by: \.watchId)

        // Volumendaten nur für Paare mit Volumen-Alarm (und erfolgreich geholtem Kurs) —
        // je Paar einmal, alle gleichzeitig statt nacheinander.
        let volumeWatches = watches.filter { w in
            liveQuotes == nil && fetched[w.id]?.ticker != nil && (prefetchByWatch[w.id] ?? []).contains { $0.condition == .VOLUME_SPIKE }
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
            liveQuotes == nil && (fetched[w.id]?.ticker?.last ?? 0) > 0
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

        // Alarme: nur einer zur Zeit (App, Hintergrund) und immer auf dem gespeicherten Stand —
        // sonst meldet ein zweiter Prozess, was der erste gerade gemeldet hat.
        var alarmsByWatch: [Int64: [Alarm]] = [:]
        if evaluateAlarms && !prefetchAlarms.isEmpty {
            if let lease = await AlarmLease.acquire(timeoutSeconds: alarmLeaseWaitSeconds) {
                outcome.alarmLease = lease
                let fresh = SharedStorage.loadSnapshot().alarms.filter(\.enabled)
                alarmsByWatch = Dictionary(grouping: fresh, by: \.watchId)
            }
        }

        // Kurs-Mitteilungen entfernen: gesammelt in EINEM Aufruf statt einmal je Paar
        var cancelIds: [Int64] = []
        for watch in watches {
            outcome.checked += 1
            let result = fetched[watch.id]
            guard let ticker = result?.ticker, result?.error == nil, ticker.last > 0 else {
                outcome.failed += 1
                outcome.prices[watch.id] = PriceUpdate(price: nil, time: now, error: result?.error)
                cancelIds.append(watch.id)
                continue
            }
            let price = ticker.last
            let time = ticker.timestamp > 0 ? ticker.timestamp : now
            let dayChange = liveQuotes != nil ? ticker.change24hPercent
                : change24h(watch, price: price, ticker: ticker, basis: basis, references: dayReferences)
            outcome.prices[watch.id] = PriceUpdate(price: price, time: time, error: nil, change24h: dayChange)
            if dayChange == nil && dayStart != nil && liveQuotes == nil { outcome.missingDayChange[watch.id] = (price, time) }

            var updated = watch
            updated.previousPrice = watch.lastPrice
            updated.lastPrice = price
            updated.lastUpdate = time
            updated.lastError = nil
            updated.change24h = dayChange

            // Alarme
            var spokeAlarm = false
            for var alarm in alarmsByWatch[watch.id] ?? [] {
                // Funding/Open Interest: wie Kursmarken (scharf/gemeldet in `referenceAt`), Wert aus dem Vorladen
                if alarm.condition.isDerivatives {
                    guard let values = derivatives[watch.id] else { continue }
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
                    guard let range = nearRanges[watch.id]?[NearExtreme.windowDays(alarm.windowHours)] else { continue }
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
                    guard let spike = volumes[watch.id],
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
                    guard let rate = convertRates["\(watch.id)|\(currency)"] else { continue }
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
            if liveQuotes == nil && !spokeAlarm && speechAllowed && !settings.ttsAlarmsOnly && watch.ttsEnabled {
                outcome.priceSpeech.append((watch.id, SpokenText.price(updated, price)))
            }
        }

        Notifier.cancelPrices(cancelIds)

        outcome.durationMillis = TimeUtils.nowMillis - started
        if onlyWatchId == nil && liveQuotes == nil {
            outcome.changeStamp = changeStamp
            // Ohne Stempel: Werte von vorher, also rollend
            outcome.changeStampChanged = (SharedStorage.changeStamp ?? ChangeStamp(basis: .ROLLING_24H, dayStart: 0)) != changeStamp
            // Tages-Basen: Bezüge, die nach dem Warten noch kommen, später nachtragen statt «—»
            if let dayStart, !outcome.missingDayChange.isEmpty {
                let missing = watches.filter { outcome.missingDayChange[$0.id] != nil }
                let prices = outcome.missingDayChange.mapValues { $0.price }
                outcome.lateDayChanges = {
                    await PriceRefresher.waitAtMost(nanos: PriceRefresher.lateFillWaitNanos, for: dayLoads)
                    let references = await PriceRefresher.cachedDayReferences(missing, dayStart: dayStart)
                    var result: [Int64: Double] = [:]
                    for watch in missing {
                        guard let price = prices[watch.id],
                              let change = PriceRefresher.candleChange(watch, price: price, references: references),
                              change.isFinite else { continue }
                        result[watch.id] = change
                    }
                    return result
                }
            }
            // Wie Android; Datenbank und Widgets misst hier niemand (speichert der Aufrufer),
            // «Alarme» = alles nach dem Netz (Auswerten, Mitteilungen, Ansagen).
            outcome.report = RefreshReport(
                at: TimeUtils.nowMillis,
                totalMillis: outcome.durationMillis,
                pairs: all.count,
                networkMillis: networkMillis,
                markets: markets,
                effectsMillis: max(0, outcome.durationMillis - networkMillis),
                alarms: outcome.alarmsTriggered,
                notifications: outcome.notified.count
            )
        }
        return outcome
    }

    // MARK: 24-h-Veränderung

    /// So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge.
    static let dayReferenceWaitNanos: UInt64 = 5_000_000_000
    /// So lange werden späte Tages-Bezüge im Hintergrund noch nachgetragen.
    static let lateFillWaitNanos: UInt64 = 90_000_000_000

    /// Benötigte Kerzenreihen: je Basis-Asset die Reihe in der Quote des Paars (USD-artige
    /// teilen sich die USDT-Reihe des Mini-Charts), bei Fiat-Quotes zusätzlich die USDT-Reihe.
    private static func dayReferenceKeys(_ watches: [Watch]) -> [(base: String, quote: String)] {
        var seen = Set<String>()
        var keys: [(base: String, quote: String)] = []
        for watch in watches {
            let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
            let quote = DayChange.candleQuote(watch.quoteAsset)
            var quotes = [quote]
            if quote != DayChange.usdtQuote && DayChange.isFiat(watch.quoteAsset) { quotes.append(DayChange.usdtQuote) }
            for q in quotes {
                let (inserted, _) = seen.insert("\(base)|\(q)")
                if inserted { keys.append((base: base, quote: q)) }
            }
        }
        return keys
    }

    /// Paare, die beim letzten vollen Durchlauf Kerzen brauchten (Ticker ohne 24-h-Wert).
    private static let candleNeeds = CandleNeeds()

    /// Paare mit Kurs, die Kerzen brauchen (`ChangeBasisMath.needsCandles`): rollend nur ohne
    /// brauchbaren 24-h-Wert im Ticker, Tages-Basen alle.
    /// `remember`: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen).
    private static func watchesNeedingCandles(_ watches: [Watch], fetched: [Int64: Fetched], basis: ChangeBasis,
                                              remember: Bool) -> [Watch] {
        let needing = watches.filter { watch in
            guard let ticker = fetched[watch.id]?.ticker else { return false }
            return ChangeBasisMath.needsCandles(basis, tickerChange: ticker.change24hPercent)
        }
        if remember { candleNeeds.ids = Set(needing.map(\.id)) }
        return needing
    }

    /// Lädt die Bezüge in einer eigenen Aufgabe: Was nach dem Warten noch fehlt, wird trotzdem
    /// fertig geladen und liegt beim nächsten Durchlauf bereit. `dayStart`: Tages-Basis —
    /// Bezug seit diesem Tagesbeginn statt rollend.
    private static func startDayReferenceLoads(_ keys: [(base: String, quote: String)], dayStart: Int64?) -> Task<Void, Never> {
        Task {
            await withTaskGroup(of: Void.self) { group in
                for key in keys {
                    group.addTask {
                        if let dayStart {
                            _ = await DayReferenceStore.shared.dayStartReference(base: key.base, quote: key.quote, dayStart: dayStart)
                        } else {
                            _ = await DayReferenceStore.shared.dayReference(base: key.base, quote: key.quote)
                        }
                    }
                }
            }
        }
    }

    /// Wartet auf `task`, aber höchstens `nanos`; die Aufgabe selbst läuft weiter.
    private static func waitAtMost(nanos: UInt64, for task: Task<Void, Never>) async {
        let gate = ResumeOnce()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            gate.set(continuation)
            Task {
                await task.value
                gate.resume()
            }
            Task {
                try? await Task.sleep(nanoseconds: nanos)
                gate.resume()
            }
        }
    }

    /// Gemerkte Bezüge («BASE|QUOTE»), auch etwas ältere; fehlende bleiben weg.
    /// `dayStart`: seit diesem Tagesbeginn statt rollend.
    private static func cachedDayReferences(_ watches: [Watch], dayStart: Int64?) async -> [String: DayReference] {
        var result: [String: DayReference] = [:]
        for key in dayReferenceKeys(watches) {
            let ref: DayReference?
            if let dayStart {
                ref = await DayReferenceStore.shared.cachedDayStartReference(base: key.base, quote: key.quote, dayStart: dayStart)
            } else {
                ref = await DayReferenceStore.shared.cachedReference(base: key.base, quote: key.quote)
            }
            if let ref { result["\(key.base)|\(key.quote)"] = ref }
        }
        return result
    }

    /// Veränderung zum neuen Kurs gemäss %-Basis (`ChangeBasisMath.choose`). Rollend: zuerst der
    /// 24-h-Wert aus dem Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei
    /// Fiat-Quotes), sonst aus Kerzen. Tages-Basen: nur aus Kerzen seit Tagesbeginn (`references`
    /// sind dann solche). Kerzen mit `DayChange.select` (Kursabstand-Prüfung); nil ohne Bezug —
    /// nie die Veränderung seit der letzten Abfrage.
    private static func change24h(_ watch: Watch, price: Double, ticker: Ticker, basis: ChangeBasis,
                                  references: [String: DayReference]) -> Double? {
        ChangeBasisMath.choose(basis, tickerChange: ticker.change24hPercent) {
            candleChange(watch, price: price, references: references)
        }
    }

    /// Veränderung aus den Bezügen der Kerzen (Paar, bei Fiat-Quotes ersatzweise die USDT-Reihe).
    private static func candleChange(_ watch: Watch, price: Double, references: [String: DayReference]) -> Double? {
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let quote = DayChange.candleQuote(watch.quoteAsset)
        return DayChange.select(price: price,
                                pairReference: references["\(base)|\(quote)"],
                                usdtReference: references["\(base)|\(DayChange.usdtQuote)"],
                                quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
    }

    // MARK: Netz

    /// Bericht-Einträge der übersprungenen (pausierten) Börsen.
    private static func pausedMarkets(_ paused: [Watch], backoff: [String: ExchangeBackoff.State]) -> [MarketRefresh] {
        Dictionary(grouping: paused, by: \.marketKey).compactMap { key, list in
            guard let first = list.first else { return nil }
            return MarketRefresh(name: first.marketName, millis: 0, pairs: list.count, updated: 0,
                                 pausedUntil: backoff[key]?.pausedUntil, pauseReason: backoff[key]?.reason)
        }
    }

    /// Bericht einer Börse plus was die Pause je Börse (`ExchangeBackoff`) braucht.
    private struct GroupSignals: Sendable {
        var marketKey: String
        var report: MarketRefresh
        /// Ursachen aller Fehler (auch einer gescheiterten Sammelabfrage).
        var failures: [RefreshFailure]
        var retryAfterMillis: Int64?
    }

    private static func fetchGroup(_ group: [Watch], includeRollingFutures: Bool) async -> ([Int64: Fetched], GroupSignals?) {
        let started = TimeUtils.nowMillis
        guard let sample = group.first else { return ([:], nil) }

        var bulk = BulkTickers()
        let bulkTried = group.count >= minWatchesForBulk
        if bulkTried, let market = MarketsConfig.market(sample.marketKey), market.bulkTickersNumOfRequests > 0 {
            let ids = Array(Set(group.compactMap(\.pairId)))
            bulk = await MarketService.fetchBulkTickers(market: market, pairIds: ids)
        }
        let bulkMillis = TimeUtils.nowMillis - started

        // Sammelabfrage mit «zu vielen Anfragen» abgelehnt: keine Einzelabfragen hinterher —
        // das verschlimmerte es nur; die Börse wird pausiert (`ExchangeBackoff`).
        let bulkRateLimited = bulk.error.map { RefreshReportLogic.classify($0) == .RATE_LIMIT } ?? false

        var results: [Int64: Fetched] = [:]
        var singles: [Watch] = []
        for watch in group {
            if let id = watch.pairId, let t = bulk.tickers[id] {
                results[watch.id] = Fetched(ticker: t, error: nil)
            } else if bulk.complete && (!includeRollingFutures || !watch.contractType.isRolling) {
                results[watch.id] = Fetched(ticker: nil, error: notTradedError, notTraded: true)
            } else if bulkRateLimited {
                results[watch.id] = Fetched(ticker: nil, error: bulk.error, failure: .RATE_LIMIT)
            } else {
                singles.append(watch)
            }
        }

        // Einzelabfragen, höchstens vier gleichzeitig je Börse — gleitend: sobald eine fertig
        // ist, startet die nächste (bisher wartete jeder Viererblock auf seine langsamste).
        if !singles.isEmpty {
            await withTaskGroup(of: (Int64, Fetched).self) { tg in
                var next = 0
                while next < min(maxParallelPerMarket, singles.count) {
                    let w = singles[next]
                    tg.addTask { (w.id, await fetchSingle(w)) }
                    next += 1
                }
                while let item = await tg.next() {
                    results[item.0] = item.1
                    if next < singles.count {
                        let w = singles[next]
                        tg.addTask { (w.id, await fetchSingle(w)) }
                        next += 1
                    }
                }
            }
        }

        let notTraded = results.values.filter(\.notTraded).count
        let errors = results.values.filter { ($0.ticker == nil || $0.error != nil) && !$0.notTraded }
        // Ursache: die beim Abfragen erkannte (z. B. Zeitüberschreitung), sonst aus dem Fehlertext
        let failures = errors.map { $0.failure ?? RefreshReportLogic.classify($0.error) }
        let reason = RefreshReportLogic.mostFrequent(failures)
        let entry = MarketRefresh(
            name: sample.marketName,
            millis: TimeUtils.nowMillis - started,
            pairs: group.count,
            updated: group.count - notTraded - errors.count,
            notTraded: notTraded,
            failed: errors.count,
            bulkTried: bulkTried,
            bulkMillis: bulkMillis,
            bulkPrices: bulk.tickers.count,
            singles: singles.count,
            reason: reason
        )
        let errorTexts = errors.map(\.error) + [bulk.error]
        let signals = GroupSignals(
            marketKey: sample.marketKey,
            report: entry,
            failures: failures + (bulk.error.map { [RefreshReportLogic.classify($0)] } ?? []),
            retryAfterMillis: ExchangeBackoff.retryAfterMillis(errorTexts)
        )
        return (results, signals)
    }

    private static func fetchSingle(_ watch: Watch) async -> Fetched {
        let started = TimeUtils.nowMillis
        guard let market = MarketsConfig.market(watch.marketKey) else {
            return Fetched(ticker: nil, error: L("market_unavailable_error"), fromSingle: true, failure: .UNAVAILABLE)
        }
        do {
            let t = try await MarketService.fetchTicker(market: market, info: watch.pairInfo)
            return Fetched(ticker: t, error: nil, fromSingle: true, millis: TimeUtils.nowMillis - started)
        } catch {
            // Zeitüberschreitung eigens erkennen: `describe` macht daraus «kein Netz»
            let timedOut = (error as? URLError)?.code == .timedOut
            let described = ConnectionErrors.describe(error)
            return Fetched(ticker: nil, error: described, fromSingle: true, millis: TimeUtils.nowMillis - started,
                           failure: timedOut ? .TIMEOUT : RefreshReportLogic.classify(described))
        }
    }
}

/// Gemerkte Watch-Ids, deren Ticker beim letzten vollen Durchlauf keinen 24-h-Wert hatte
/// (threadsicher; App und Hintergrund können gleichzeitig aktualisieren).
private final class CandleNeeds: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = Set<Int64>()

    var ids: Set<Int64> {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); stored = newValue; lock.unlock() }
    }
}

/// Setzt eine Fortsetzung genau einmal fort (wer zuerst kommt: Ende der Aufgabe oder Zeitgrenze).
private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var done = false

    func set(_ continuation: CheckedContinuation<Void, Never>) {
        lock.lock()
        defer { lock.unlock() }
        if done {
            continuation.resume()
        } else {
            self.continuation = continuation
        }
    }

    func resume() {
        lock.lock()
        let pending = continuation
        continuation = nil
        let first = !done
        done = true
        lock.unlock()
        if first { pending?.resume() }
    }
}
