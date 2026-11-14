import Foundation

// Schritte von `PriceRefresher.refresh` (Teil von `PriceRefresher`), in der Reihenfolge des Durchlaufs: gemeinsame
// Pausen übernehmen, Kurse holen (Live-Kurse oder Netz samt Tages-Bezügen), Alarme unter der Lease lesen, je Paar
// auswerten (Kurs festhalten, dann Alarme und Kurs-Mitteilung) und den Bericht eines vollen Durchlaufs abschliessen.
// Jeder Schritt tut dasselbe wie zuvor an seiner Stelle in `refresh`; die Reihenfolge der Wirkungen bleibt.

extension PriceRefresher {
    /// Kurse eines Durchlaufs und was beim Holen anfiel.
    struct Loaded {
        var fetched: [Int64: Fetched] = [:]
        /// Bericht je Börse (nur volle Durchläufe übers Netz).
        var exchanges: [ExchangeRefresh] = []
        /// Alle Kerzen-Nachlader zusammen (Live-Kurse: sofort fertig).
        var dayLoads: Task<Void, Never>
        var dayReferences: [String: DayReference] = [:]
        /// Die einzelnen Nachlader: mit Endzeit bricht `refresh` sie am Ende ab.
        var candleLoads: [Task<Void, Never>] = []
    }

    /// Was die Auswertung jedes Paars braucht — je Durchlauf einmal bestimmt.
    struct Evaluation {
        let now: Int64
        let settings: AppSettings
        let speechAllowed: Bool
        /// Live-Kurse (WebSocket): keine Merker je Abfrage, kein «Kein Kurs» im Protokoll.
        let live: Bool
        let basis: ChangeBasis
        let dayStart: Int64?
        let dayReferences: [String: DayReference]
        /// Mit Endzeit: Alarme mit Zusatzdaten erst nach dem Speichern (`Outcome.deferredAlarms`).
        let deferExtra: Bool
        let alarmsByWatch: [Int64: [Alarm]]
        let logAlarmsByWatch: [Int64: [Alarm]]
        let inputs: AlarmInputs
    }

    /// Gemeinsame Pause einer Börsengruppe (`BlockedSources`) in die Pausen je Börse übernehmen.
    static func adoptGroupPauses(_ backoff: inout [String: ExchangeBackoff.State], watches all: [Watch], started: Int64) {
        for key in Set(all.map(\.exchangeKey)) {
            guard let until = BlockedSources.groupPausedUntil(key) else { continue }
            let state = backoff[key]
            // Eine unmögliche eigene Pause (`ExchangeBackoff.isPaused`) wird ersetzt — wie Android
            if (state?.pausedUntil ?? Int64.min) < until || !ExchangeBackoff.isPaused(state, now: started) {
                var merged = state ?? ExchangeBackoff.State(strikes: 1)
                merged.pausedUntil = until
                merged.reason = .RATE_LIMIT
                merged.timeoutRuns = 0
                backoff[key] = merged
            }
        }
    }

    /// Live-Kurse als Abfrage-Ergebnis: schon da — kein Netz, keine Kerzen.
    static func liveFetched(_ watches: [Watch], quotes liveQuotes: [Int64: LiveQuote]) -> [Int64: Fetched] {
        var fetched: [Int64: Fetched] = [:]
        for w in watches {
            guard let q = liveQuotes[w.id] else { continue }
            // Börsenzeit nur als im Strom belegte Handelszeit (`LiveRules.exchangeTime`), nie die Takt-Zeit
            let exchangeTime = LiveRules.exchangeTime(q, storedPrice: w.lastPrice, storedExchangeTime: w.exchangeTime)
            // Wie die Anzeige (`LiveRules.updateTime`): ein Takt ohne Handel setzt die Zeit nicht zurück
            let time = LiveRules.updateTime(q, storedPrice: w.lastPrice, storedLastUpdate: w.lastUpdate)
            fetched[w.id] = Fetched(ticker: Ticker(last: q.price, timestamp: time, change24hPercent: q.change24h,
                                                   exchangeTime: exchangeTime),
                                    error: nil)
        }
        return fetched
    }

    /// Kurse übers Netz samt Tages-Bezügen: Kerzen parallel zu den Kursen, Pausen je Börse nachführen (nur volle
    /// Durchläufe), fehlende Bezüge nachladen und höchstens bis zur Frist darauf warten.
    static func loadFromNetwork(_ watches: [Watch], paused: [Watch], snapshot: SharedStorage.Snapshot,
                                settings: AppSettings, basis: ChangeBasis, dayStart: Int64?, onlyWatchId: Int64?,
                                lowMemory: Bool, networkBudgetMillis: Int64?, deadline: RefreshDeadline?,
                                backoff: [String: ExchangeBackoff.State],
                                initialBackoff: [String: ExchangeBackoff.State]) async -> Loaded {
        // Bezüge (Kerzen) parallel zu den Kursen für Paare, die beim letzten Mal Kerzen brauchten
        // (rollend: Ticker ohne 24-h-Wert; Tages-Basen: alle); der Rest nach den Kursen.
        let remembered = candleNeeds.ids
        let earlyKeys = dayReferenceKeys(dayStart != nil ? watches : watches.filter { remembered.contains($0.id) })
        let earlyLoads = startDayReferenceLoads(earlyKeys, dayStart: dayStart)

        // 1) Netz: alle Börsen gleichzeitig, je Börse erst die Sammelabfrage.
        let groups = Dictionary(grouping: watches, by: \.exchangeKey)
        // Je Börse Paare mit aktivem Alarm zuerst abfragen (`FetchOrder`)
        let alarmedIds = Set(snapshot.alarms.filter(\.enabled).map(\.watchId))
        // Mit Endzeit: höchstens bis dahin (Speichern bleibt übrig)
        let networkBudget = deadline.map { $0.allowance(wanted: networkBudgetMillis ?? Int64.max) } ?? networkBudgetMillis
        let (fetched, signals) = await fetchGroups(groups, settings: settings, onlyWatchId: onlyWatchId,
                                                   lowMemory: lowMemory, alarmedIds: alarmedIds,
                                                   networkBudget: networkBudget)
        // Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben
        var exchanges: [ExchangeRefresh] = []
        if onlyWatchId == nil {
            exchanges = noteGroupResults(signals, paused: paused, backoff: backoff, initialBackoff: initialBackoff)
        }
        let needingCandles = watchesNeedingCandles(watches, fetched: fetched, basis: basis, remember: onlyWatchId == nil)
        let startedKeys = Set(earlyKeys.map { "\($0.base)|\($0.quote)" })
        let lateKeys = dayReferenceKeys(needingCandles).filter { !startedKeys.contains("\($0.base)|\($0.quote)") }
        let lateLoads = startDayReferenceLoads(lateKeys, dayStart: dayStart)
        let dayLoads: Task<Void, Never> = Task {
            await earlyLoads.value
            await lateLoads.value
        }
        await waitAtMost(nanos: RefreshDeadline.nanos(wanted: Int64(dayReferenceWaitNanos / 1_000_000), deadline: deadline),
                         for: dayLoads)
        let dayReferences = await cachedDayReferences(needingCandles, dayStart: dayStart)
        return Loaded(fetched: fetched, exchanges: exchanges, dayLoads: dayLoads, dayReferences: dayReferences,
                      candleLoads: [earlyLoads, lateLoads])
    }

    /// Alle Börsen gleichzeitig abfragen; mit `networkBudget` nach dieser Zeit die übrigen abbrechen (Teilergebnis).
    static func fetchGroups(_ groups: [String: [Watch]], settings: AppSettings, onlyWatchId: Int64?, lowMemory: Bool,
                            alarmedIds: Set<Int64>, networkBudget: Int64?) async -> ([Int64: Fetched], [GroupSignals]) {
        var fetched: [Int64: Fetched] = [:]
        var signals: [GroupSignals] = []
        // Mit Budget: fertige Einzelabfragen laufender Börsen behalten, je Lauf anderer Beginn der Reihenfolge
        let partial = FetchedBox()
        let rotation = networkBudget == nil ? 0 : Int(EpochTime.nowMillis / 1_000 % Int64(Int32.max))
        await withTaskGroup(of: ([Int64: Fetched], GroupSignals?, Bool).self) { group in
            for (_, list) in groups {
                group.addTask {
                    if onlyWatchId != nil, let w = list.first {
                        let single = await fetchSingle(w)
                        return ([w.id: single], nil, false)
                    }
                    let (results, signal) = await fetchGroup(list, includeRollingFutures: settings.includeRollingFutures,
                                                             lowMemory: lowMemory, alarmed: alarmedIds, rotation: rotation,
                                                             partial: partial)
                    return (results, signal, false)
                }
            }
            // Zeitbudget: danach die übrigen Börsen abbrechen (Teilergebnis statt gar keines)
            if let networkBudget {
                group.addTask {
                    try? await Task.sleep(nanoseconds: UInt64(max(0, networkBudget)) * 1_000_000)
                    return ([:], nil, true)
                }
            }
            var pendingGroups = groups.count
            for await (results, signal, timedOut) in group {
                if timedOut {
                    group.cancelAll()
                    // Budget um: schon geholte Kurse der laufenden Börsen bleiben; die übrigen Paare «Zeitüberschreitung»
                    // (am Budget, nicht an der Börse: kein Backoff, keine volle Prüfung)
                    for (id, value) in partial.all where fetched[id] == nil { fetched[id] = value }
                    for (_, list) in groups {
                        for w in list where fetched[w.id] == nil {
                            fetched[w.id] = Fetched(ticker: nil, error: budgetTimeoutError, failure: .TIMEOUT,
                                                    exchangeFailure: .timeout, budgetCut: true)
                        }
                    }
                    break
                }
                fetched.merge(results) { a, _ in a }
                if let signal { signals.append(signal) }
                pendingGroups -= 1
                if pendingGroups == 0 {
                    // Alle Börsen fertig: den Zeitgeber nicht abwarten
                    group.cancelAll()
                    break
                }
            }
        }
        return (fetched, signals)
    }

    /// Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben; Pausen speichern. Liefert den
    /// Bericht je Börse (abgefragte, dann pausierte).
    static func noteGroupResults(_ signals: [GroupSignals], paused: [Watch], backoff initial: [String: ExchangeBackoff.State],
                                 initialBackoff: [String: ExchangeBackoff.State]) -> [ExchangeRefresh] {
        var backoff = initial
        var exchanges: [ExchangeRefresh] = []
        let finished = EpochTime.nowMillis
        for signal in signals {
            var exchange = signal.report
            let state = ExchangeBackoff.next(
                backoff[signal.exchangeKey],
                outcome: ExchangeBackoff.outcome(failures: signal.failures, updated: exchange.updated),
                now: finished, retryAfterMillis: signal.retryAfterMillis,
                // 418 und «Retry-After» wie bei Kerzen (gemeinsame Binance-Pause)
                banned: signal.banned, shared: ExchangeBackoff.sharedGroup(signal.exchangeKey) != nil)
            backoff[signal.exchangeKey] = state
            // 429/418 beim Ticker: Kerzen, Futures und Aktivität an dieselben Hosts pausieren mit
            if let state, state.reason == .RATE_LIMIT, ExchangeBackoff.isPaused(state, now: finished) {
                BlockedSources.pauseGroup(signal.exchangeKey, until: state.pausedUntil)
            }
            if let state, ExchangeBackoff.isPaused(state, now: finished) {
                exchange.pausedUntil = state.pausedUntil
                exchange.pauseReason = state.reason
            }
            exchanges.append(exchange)
        }
        exchanges.append(contentsOf: pausedExchanges(paused, backoff: backoff))
        // 429 bei Spot pausiert auch Futures auf demselben Host (und umgekehrt) — wie Android
        SharedStorage.exchangeBackoff = ExchangeBackoff.merge(
            ours: ExchangeBackoff.withHostGroupPauses(backoff, now: finished), initial: initialBackoff,
            stored: SharedStorage.exchangeBackoff, now: finished)
        return exchanges
    }

    /// Alarme unter der prozessübergreifenden Lease, frisch von der Platte gelesen; ohne Lease (oder ohne Alarme)
    /// leer — dann diesmal ohne Alarme.
    static func leasedAlarms(prefetchAlarms: [Alarm], deferExtra: Bool,
                             deadline: RefreshDeadline?) async -> (lease: AlarmLease?, byWatch: [Int64: [Alarm]]) {
        guard !prefetchAlarms.isEmpty,
              let lease = await AlarmLease.acquire(timeoutSeconds: leaseWaitSeconds(deadline)) else { return (nil, [:]) }
        let fresh = SharedStorage.loadSnapshot().alarms.filter {
            $0.enabled && !(deferExtra && AlarmDataStage.needsExtraData($0))
        }
        return (lease, Dictionary(grouping: fresh, by: \.watchId))
    }

    /// Ein Paar auswerten: ausgelassen, ohne Kurs oder mit Kurs (festhalten, dann Alarme und Kurs-Mitteilung).
    static func evaluate(_ watch: Watch, result: Fetched?, run: Evaluation, outcome: inout Outcome,
                         cancelIds: inout [Int64], pricePosts: inout [Watch]) {
        // Widget: bewusst nicht abgefragt — gespeicherter Kurs und Zustand bleiben; wie eine pausierte Börse
        // (neue %-Basis: alte Veränderung weg, `apply`)
        if result?.skipped == true {
            outcome.pausedIds.insert(watch.id)
            return
        }
        // Nicht gehandelt bzw. Börse stillgelegt: ein Zustand, kein Abruf — weder «geprüft» noch «fehlgeschlagen»
        // (sonst kippten «Zuletzt geprüft», Widget-Kopf und «Letzte Prüfungen»); wie Android
        let notTraded = result?.notTraded == true
        if notTraded { outcome.notTraded += 1 } else { outcome.checked += 1 }
        // NaN/∞ nie als Kurs übernehmen — wie PriceRefresher.kt
        guard let ticker = result?.ticker, result?.error == nil, ticker.last.isFinite, ticker.last > 0 else {
            if !notTraded { outcome.failed += 1 }
            if result?.budgetCut == true { outcome.cutByBudget += 1 }
            outcome.prices[watch.id] = PriceUpdate(price: nil, time: run.now, error: result?.error, pair: watch.identity)
            // Kurs-Mitteilung nur entfernen, wenn nicht mehr gehandelt bzw. Börse stillgelegt; ein gewöhnlicher Fehler
            // (Zeit, 5xx, Netz) lässt sie stehen — sonst käme sie erst nach der nächsten Bewegung um die Schwelle wieder
            if notTraded { cancelIds.append(watch.id) }
            // Alarm-Protokoll: «Kein Kurs» als letzte Prüfung der Alarme dieses Paars
            if !run.live {
                outcome.alarmLog += (run.logAlarmsByWatch[watch.id] ?? []).compactMap {
                    alarmLogEntry($0, watch: watch, trace: AlarmLogTrace(noPrice: true), now: run.now)
                }
            }
            return
        }
        let updated = recordPrice(watch, ticker: ticker, run: run, outcome: &outcome)
        alarmsAndPriceEffects(watch, updated: updated, ticker: ticker, result: result, run: run, outcome: &outcome,
                              cancelIds: &cancelIds, pricePosts: &pricePosts)
    }

    /// Gültigen Kurs festhalten: Merker je Abfrage, Veränderung, Frische — und das Paar mit dem neuen Stand liefern.
    static func recordPrice(_ watch: Watch, ticker: Ticker, run: Evaluation, outcome: inout Outcome) -> Watch {
        let now = run.now
        let basis = run.basis
        let dayStart = run.dayStart
        let dayReferences = run.dayReferences
        let price = ticker.last
        let time = ticker.timestamp > 0 ? ticker.timestamp : now
        // DEX-Pool: Liquidität jeder Abfrage merken (Alarm-Sperre in `checkAlarms`, Hinweis in Suche und Alarmen)
        if !run.live && DexLiquidity.isDex(watch.exchangeKey) {
            DexLiquidityStore.put(watch.pairId, ticker.liquidityUsd)
        }
        // Kurs = Mark-Preis mangels Mittelkurs (Hyperliquid): «Mark» in Zeile und Blatt; Live-Kurse kommen nie als
        // Mark-Ersatz, der Merker gilt bis zur nächsten Abfrage
        if !run.live { MarkFallbackStore.put(watch.id, ticker.markFallback) }
        // Ebenso der Stundenkurs (WOO X Spot): gilt bis zur nächsten Abfrage
        if !run.live { HourlyCloseStore.put(watch.id, ticker.hourlyClose) }
        let dayChange: Double?
        if run.live {
            // Live-Wert aus dem Ticker: kein Kerzen-Hinweis — aber nur, wenn er die Veränderung ersetzt hat.
            // Bleibt die bisherige (`LiveRules.chooseChange`, z. B. Tages-Basis), bleibt auch ihr Anbieter:
            // sonst stünde ein Wert aus fremden Kerzen ohne Hinweis da, als käme er von der eigenen Börse
            if ticker.change24hPercent != watch.change24h { candlePills.set(watch.id, series: nil) }
            dayChange = ticker.change24hPercent
        } else {
            dayChange = change24h(watch, price: price, ticker: ticker, basis: basis, references: dayReferences)
        }
        // Frische (`PriceFreshness`): letzte echte Kursänderung und Zeit laut Börse (nil = keine)
        let changedAt = PriceFreshness.nextPriceChangedAt(previousPrice: watch.lastPrice,
                                                          previousChangedAt: watch.priceChangedAt,
                                                          price: price, time: time)
        outcome.prices[watch.id] = PriceUpdate(price: price, time: time, error: nil, change24h: dayChange,
                                               priceChangedAt: changedAt, exchangeTime: ticker.exchangeTime,
                                               pair: watch.identity)
        if dayChange == nil && dayStart != nil && !run.live { outcome.missingDayChange[watch.id] = (price, time) }

        var updated = watch
        updated.previousPrice = watch.lastPrice
        updated.lastPrice = price
        updated.lastUpdate = time
        updated.lastError = nil
        updated.change24h = dayChange
        updated.priceChangedAt = changedAt
        updated.exchangeTime = ticker.exchangeTime
        return updated
    }

    /// Alarme des Paars (sofern die Quelle sie erlaubt und der Kurs frisch ist), dann Kurs-Mitteilung und Ansage.
    static func alarmsAndPriceEffects(_ watch: Watch, updated: Watch, ticker: Ticker, result: Fetched?, run: Evaluation,
                                      outcome: inout Outcome, cancelIds: inout [Int64], pricePosts: inout [Watch]) {
        let now = run.now
        let settings = run.settings
        let speechAllowed = run.speechAllowed
        let deferExtra = run.deferExtra
        let alarmsByWatch = run.alarmsByWatch
        let logAlarmsByWatch = run.logAlarmsByWatch
        let inputs = run.inputs
        let price = ticker.last
        // Kurs laut Börse zu alt (`AlarmFreshness`, Handel ruht) bzw. nur Mark-Preis: Alarme bleiben scharf, lösen
        // aber nicht aus
        // Quelle ohne Alarme (EZB-Referenzkurse, `Exchange.allowsAlarms`): nie auswerten, nichts protokollieren
        let sourceAllowsAlarms = ExchangesConfig.exchange(watch.exchangeKey)?.allowsAlarms ?? true
        let alarmsAllowed = sourceAllowsAlarms
            && !AlarmFreshness.blocksAlarms(updated.exchangeTime, markFallback: ticker.markFallback, now: now)
        if sourceAllowsAlarms, !alarmsAllowed, let blocked = logAlarmsByWatch[watch.id] {
            // Alarm-Protokoll: nur festhalten, warum nicht (dieselbe Reihenfolge der Gründe wie Android)
            outcome.alarmLog += blockedAlarmLog(
                blocked, watch: watch, updated: updated,
                lowLiquidity: DexLiquidityStore.blocksAlarms(exchangeKey: watch.exchangeKey, pairId: watch.pairId),
                markFallback: ticker.markFallback, now: now, cooldownMinutes: settings.alarmCooldownMinutes)
        }

        // Alarme mit Zusatzdaten: nach dem Speichern prüfen
        if alarmsAllowed, deferExtra, outcome.deferredAlarms.prefetchByWatch[watch.id] != nil, let result {
            outcome.deferredAlarms.items.append(DeferredAlarms.Item(watch: watch, updated: updated, price: price,
                                                                    fetched: result))
        }

        // Alarme
        var spokeAlarm = false
        if alarmsAllowed {
            spokeAlarm = checkAlarms(alarmsByWatch[watch.id] ?? [], watch: watch, updated: updated, price: price,
                                     now: now, settings: settings, speechAllowed: speechAllowed, inputs: inputs,
                                     outcome: &outcome)
        }

        priceEffects(watch: watch, updated: updated, price: price, now: now, settings: settings,
                     speechAllowed: speechAllowed, spokeAlarm: spokeAlarm, live: run.live,
                     outcome: &outcome, cancelIds: &cancelIds, pricePosts: &pricePosts)
    }

    /// Abschluss eines vollen Durchlaufs übers Netz: %-Basis, späte Tages-Bezüge und Bericht.
    static func finishFullRun(_ outcome: inout Outcome, watches: [Watch], pairs: Int, changeStamp: ChangeStamp,
                              dayStart: Int64?, dayLoads: Task<Void, Never>, networkMillis: Int64,
                              exchanges: [ExchangeRefresh]) {
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
            at: EpochTime.nowMillis,
            totalMillis: outcome.durationMillis,
            pairs: pairs,
            networkMillis: networkMillis,
            exchanges: exchanges,
            effectsMillis: max(0, outcome.durationMillis - networkMillis),
            // Fällige Alarme; gemeldet und gespeichert werden sie erst danach (`deliverAlarms`)
            alarms: outcome.pendingAlarms.count,
            notifications: outcome.notified.count
        )
    }
}

extension PriceRefresher.Outcome {
    /// Ergebnis für «Letzte Prüfungen» (`CheckRunLog.result`): offline nicht versucht = übersprungen; alle Börsen
    /// pausiert bzw. Alarme ohne Lease = pausiert. Wie Android `BackgroundCheck`.
    var checkResult: CheckResult {
        skippedOffline ? .skipped : CheckRunLog.result(checked: checked, failed: failed, paused: pausedIds.count,
                                                       alarmsUnchecked: alarmsUnchecked)
    }

    /// Volle Prüfung mit allen Alarmen (`BackgroundSchedule.isFullCheck`); ohne Alarm-Lease bzw. mit Budget-Lücken nie.
    var isFullCheck: Bool {
        !alarmsUnchecked && BackgroundSchedule.isFullCheck(attempted: checked, failed: failed, notTraded: notTraded,
                                                           cutByBudget: cutByBudget)
    }
}
