import Foundation

/// Holt die Kurse aller beobachteten Paare und löst aus, was daran hängt:
/// Alarme, Kurs-Mitteilungen und Ansagen — wie `PriceRefresher.kt`.
///
/// Arbeitet auf einer Kopie der Daten und liefert nur die Änderungen zurück
/// (`Outcome.apply(to:)`). So gehen Änderungen, die der Nutzer während der
/// Abfrage macht, nicht verloren — egal ob App, Hintergrund oder Widget fragt.
enum PriceRefresher {

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

        let inputs = await loadAlarmInputs(watches: watches, fetched: fetched, prefetchByWatch: prefetchByWatch,
                                           live: liveQuotes != nil)

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
            let spokeAlarm = checkAlarms(alarmsByWatch[watch.id] ?? [], watch: watch, updated: updated, price: price,
                                         now: now, settings: settings, speechAllowed: speechAllowed, inputs: inputs,
                                         outcome: &outcome)

            priceEffects(watch: watch, updated: updated, price: price, now: now, settings: settings,
                         speechAllowed: speechAllowed, spokeAlarm: spokeAlarm, live: liveQuotes != nil,
                         outcome: &outcome, cancelIds: &cancelIds)
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
}
