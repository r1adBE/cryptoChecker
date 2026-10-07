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
        var report = ""

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
                    }
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
    }

    static let notTradedError = NotTraded.marker

    /// So lange wartet ein Durchlauf auf die Lease der Alarm-Auswertung; sonst diesmal ohne Alarme.
    static let alarmLeaseWaitSeconds: Double = 5

    /// Alle Paare (oder nur `onlyWatchId`) abfragen und auswerten.
    ///
    /// - Parameter evaluateAlarms: false = nur Kurse (Widget): keine Alarme auswerten oder melden.
    ///
    /// Alarme: Nach den Kursen wird die prozessübergreifende `AlarmLease` genommen und der
    /// Alarm-Zustand frisch von der Platte gelesen. Mitteilungen kommen NICHT von hier, sondern
    /// liegen in `Outcome.pendingAlarms`: Der Aufrufer wendet das Ergebnis an, speichert und
    /// ruft dann `Outcome.deliverAlarms()` (zeigt sie und gibt die Lease frei).
    static func refresh(snapshot: SharedStorage.Snapshot, settings: AppSettings, onlyWatchId: Int64? = nil,
                        evaluateAlarms: Bool = true) async -> Outcome {
        let started = TimeUtils.nowMillis
        let watches = snapshot.watches.filter { onlyWatchId == nil || $0.id == onlyWatchId }
        var outcome = Outcome()
        guard !watches.isEmpty else {
            outcome.durationMillis = TimeUtils.nowMillis - started
            return outcome
        }

        // 24-h-Bezüge (Kerzen) nur noch als Ausweich-Weg: parallel zu den Kursen nur für Paare,
        // deren Ticker beim letzten Mal keinen 24-h-Wert hatte; der Rest nach den Kursen.
        let remembered = candleNeeds.ids
        let earlyKeys = dayReferenceKeys(watches.filter { remembered.contains($0.id) })
        let earlyLoads = startDayReferenceLoads(earlyKeys)

        // 1) Netz: alle Börsen gleichzeitig, je Börse erst die Sammelabfrage.
        let groups = Dictionary(grouping: watches, by: \.marketKey)
        var fetched: [Int64: Fetched] = [:]
        var lines: [String] = []

        await withTaskGroup(of: ([Int64: Fetched], String).self) { group in
            for (_, list) in groups {
                group.addTask {
                    if onlyWatchId != nil, let w = list.first {
                        return ([w.id: await fetchSingle(w)], "")
                    }
                    return await fetchGroup(list, includeRollingFutures: settings.includeRollingFutures)
                }
            }
            for await (results, line) in group {
                fetched.merge(results) { a, _ in a }
                if !line.isEmpty { lines.append(line) }
            }
        }
        let needingCandles = watchesNeedingCandles(watches, fetched: fetched, remember: onlyWatchId == nil)
        let startedKeys = Set(earlyKeys.map { "\($0.base)|\($0.quote)" })
        let lateKeys = dayReferenceKeys(needingCandles).filter { !startedKeys.contains("\($0.base)|\($0.quote)") }
        let lateLoads = startDayReferenceLoads(lateKeys)
        let dayLoads = Task {
            await earlyLoads.value
            await lateLoads.value
        }
        await waitAtMost(nanos: dayReferenceWaitNanos, for: dayLoads)
        let dayReferences = await cachedDayReferences(needingCandles)
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
            fetched[w.id]?.ticker != nil && (prefetchByWatch[w.id] ?? []).contains { $0.condition == .VOLUME_SPIKE }
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
            let dayChange = change24h(watch, price: price, ticker: ticker, references: dayReferences)
            outcome.prices[watch.id] = PriceUpdate(price: price, time: time, error: nil, change24h: dayChange)

            var updated = watch
            updated.previousPrice = watch.lastPrice
            updated.lastPrice = price
            updated.lastUpdate = time
            updated.lastError = nil
            updated.change24h = dayChange

            // Alarme
            var spokeAlarm = false
            for var alarm in alarmsByWatch[watch.id] ?? [] {
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
            if !spokeAlarm && speechAllowed && !settings.ttsAlarmsOnly && watch.ttsEnabled {
                outcome.priceSpeech.append((watch.id, SpokenText.price(updated, price)))
            }
        }

        Notifier.cancelPrices(cancelIds)

        outcome.durationMillis = TimeUtils.nowMillis - started
        if onlyWatchId == nil {
            let head = [L("refresh_report_total", count: watches.count, secs(outcome.durationMillis), watches.count),
                        L("refresh_report_network", secs(networkMillis))]
            let tail = [L("ios_refresh_report_alarms", outcome.alarmsTriggered, outcome.notified.count)]
            outcome.report = (head + lines.sorted().map { "  • \($0)" } + tail).joined(separator: "\n")
        }
        return outcome
    }

    private static func secs(_ ms: Int64) -> String { String(format: "%.1f s", Double(ms) / 1000) }

    // MARK: 24-h-Veränderung

    /// So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge.
    static let dayReferenceWaitNanos: UInt64 = 5_000_000_000

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

    /// Paare mit Kurs, deren Ticker keinen brauchbaren 24-h-Wert hat (`DayChange.needsCandles`).
    /// `remember`: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen).
    private static func watchesNeedingCandles(_ watches: [Watch], fetched: [Int64: Fetched], remember: Bool) -> [Watch] {
        let needing = watches.filter { watch in
            guard let ticker = fetched[watch.id]?.ticker else { return false }
            return DayChange.needsCandles(ticker.change24hPercent)
        }
        if remember { candleNeeds.ids = Set(needing.map(\.id)) }
        return needing
    }

    /// Lädt die 24-h-Bezüge in einer eigenen Aufgabe: Was nach dem Warten noch fehlt,
    /// wird trotzdem fertig geladen und liegt beim nächsten Durchlauf bereit.
    private static func startDayReferenceLoads(_ keys: [(base: String, quote: String)]) -> Task<Void, Never> {
        Task {
            await withTaskGroup(of: Void.self) { group in
                for key in keys {
                    group.addTask { _ = await DayReferenceStore.shared.dayReference(base: key.base, quote: key.quote) }
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

    /// Gemerkte 24-h-Bezüge («BASE|QUOTE»), auch etwas ältere; fehlende bleiben weg.
    private static func cachedDayReferences(_ watches: [Watch]) async -> [String: DayReference] {
        var result: [String: DayReference] = [:]
        for key in dayReferenceKeys(watches) {
            if let ref = await DayReferenceStore.shared.cachedReference(base: key.base, quote: key.quote) {
                result["\(key.base)|\(key.quote)"] = ref
            }
        }
        return result
    }

    /// Veränderung über 24 Stunden zum neuen Kurs: zuerst der rollende 24-h-Wert aus dem Ticker
    /// (gilt für das Paar selbst, also schon in seiner Quote — auch bei Fiat-Quotes), sonst aus
    /// Kerzen (`DayChange.select`, mit Kursabstand-Prüfung); nil ohne beides — nie die
    /// Veränderung seit der letzten Abfrage.
    private static func change24h(_ watch: Watch, price: Double, ticker: Ticker,
                                  references: [String: DayReference]) -> Double? {
        DayChange.choose(tickerChange: ticker.change24hPercent) {
            let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
            let quote = DayChange.candleQuote(watch.quoteAsset)
            return DayChange.select(price: price,
                                    pairReference: references["\(base)|\(quote)"],
                                    usdtReference: references["\(base)|\(DayChange.usdtQuote)"],
                                    quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
        }
    }

    // MARK: Netz

    private static func fetchGroup(_ group: [Watch], includeRollingFutures: Bool) async -> ([Int64: Fetched], String) {
        let started = TimeUtils.nowMillis
        guard let sample = group.first else { return ([:], "") }

        var bulk = BulkTickers()
        let bulkTried = group.count >= minWatchesForBulk
        if bulkTried, let market = MarketsConfig.market(sample.marketKey), market.bulkTickersNumOfRequests > 0 {
            let ids = Array(Set(group.compactMap(\.pairId)))
            bulk = await MarketService.fetchBulkTickers(market: market, pairIds: ids)
        }
        let bulkMillis = TimeUtils.nowMillis - started

        var results: [Int64: Fetched] = [:]
        var singles: [Watch] = []
        for watch in group {
            if let id = watch.pairId, let t = bulk.tickers[id] {
                results[watch.id] = Fetched(ticker: t, error: nil)
            } else if bulk.complete && (!includeRollingFutures || !watch.contractType.isRolling) {
                results[watch.id] = Fetched(ticker: nil, error: notTradedError, notTraded: true)
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
        let errors = results.values.filter { ($0.ticker == nil || $0.error != nil) && !$0.notTraded }.count
        // Bericht in der App-Sprache — gleiche Schlüssel wie Android (`refresh_report_*`)
        var line = L("refresh_report_market", count: group.count, sample.marketName, group.count, secs(TimeUtils.nowMillis - started))
        if bulkTried {
            let bulkText = bulk.tickers.isEmpty
                ? L("refresh_report_bulk_failed", secs(bulkMillis))
                : L("refresh_report_bulk_ok", count: bulk.tickers.count, secs(bulkMillis), bulk.tickers.count)
            line += " · " + bulkText
        }
        if !singles.isEmpty { line += " · " + L("refresh_report_singles", singles.count) }
        if notTraded > 0 { line += " · " + L("refresh_report_not_traded", count: notTraded) }
        if errors > 0 { line += " · " + L("refresh_report_errors", count: errors) }
        return (results, line)
    }

    private static func fetchSingle(_ watch: Watch) async -> Fetched {
        let started = TimeUtils.nowMillis
        guard let market = MarketsConfig.market(watch.marketKey) else {
            return Fetched(ticker: nil, error: L("market_unavailable_error"), fromSingle: true)
        }
        do {
            let t = try await MarketService.fetchTicker(market: market, info: watch.pairInfo)
            return Fetched(ticker: t, error: nil, fromSingle: true, millis: TimeUtils.nowMillis - started)
        } catch {
            return Fetched(ticker: nil, error: ConnectionErrors.describe(error), fromSingle: true, millis: TimeUtils.nowMillis - started)
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
