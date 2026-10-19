import Foundation

/// Holt die Kurse aller beobachteten Paare und löst aus, was daran hängt:
/// Alarme, Kurs-Mitteilungen und Ansagen — wie `PriceRefresher.kt`.
///
/// Arbeitet auf einer Kopie der Daten und liefert nur die Änderungen zurück
/// (`Outcome.apply(to:)`). So gehen Änderungen, die der Nutzer während der
/// Abfrage macht, nicht verloren — egal ob App, Hintergrund oder Widget fragt.
///
/// Ergebnis-Typen (`Outcome`, `PendingAlarm` …) in `PriceRefresher+Outcome.swift`.
enum PriceRefresher {

    /// So lange wartet ein Durchlauf auf die Lease der Alarm-Auswertung; sonst diesmal ohne Alarme.
    static let alarmLeaseWaitSeconds: Double = 5

    /// Alle Paare (oder nur `onlyWatchId`) abfragen und auswerten.
    ///
    /// - Parameter liveIds: Paare mit frischem Live-Kurs (WebSocket, Merkliste offen, siehe
    ///   `LiveRules.skipRest`) — diesmal ohne REST-Abfrage.
    /// - Parameter liveQuotes: Live-Kurse gesammelt speichern (`AppData.applyLive`): nur diese Paare,
    ///   ohne Netz und Kerzen; die Veränderung steht schon im Ticker (`LiveRules.chooseChange`).
    ///   Gleicher Weg für Alarme und Kurs-Mitteilungen, aber keine Kursansagen, keine Volumen-Alarme
    ///   und kein Bericht.
    ///
    /// Alarme: Nach den Kursen wird die prozessübergreifende `AlarmLease` genommen und der
    /// Alarm-Zustand frisch von der Platte gelesen. Mitteilungen kommen NICHT von hier, sondern
    /// liegen in `Outcome.pendingAlarms`: Der Aufrufer wendet das Ergebnis an, speichert die Kurse und
    /// ruft dann `Outcome.deliverAlarms` (meldet, speichert die bestätigten Alarme, gibt die Lease frei).
    /// - Parameter networkBudgetMillis: höchstens so lange auf die Börsen warten (Hintergrund: iOS gibt ~30 s);
    ///   Börsen, die bis dahin nicht geantwortet haben, fehlen diesmal — die anderen werden gespeichert und geprüft.
    /// - Parameter deadline: Endzeit (Hintergrund, Widget): begrenzt Kurse, Tages-Bezüge und Lease; Alarme mit
    ///   Zusatzdaten werden NICHT hier geprüft, sondern in `Outcome.deferredAlarms` zurückgestellt.
    /// - Parameter lowMemory: Widget-Erweiterung: keine grossen ungefilterten Sammellisten (`LowMemoryBulk`);
    ///   nicht abgefragte Paare behalten ihren gespeicherten Kurs (zählen weder als geprüft noch als Fehler).
    static func refresh(snapshot: SharedStorage.Snapshot, settings: AppSettings, onlyWatchId: Int64? = nil,
                        liveIds: Set<Int64> = [],
                        liveQuotes: [Int64: LiveQuote]? = nil, networkBudgetMillis: Int64? = nil,
                        deadline: RefreshDeadline? = nil, lowMemory: Bool = false) async -> Outcome {
        let started = TimeUtils.nowMillis
        var outcome = Outcome()
        outcome.alarmLogLive = liveQuotes != nil
        // Kein Netz: gar nicht erst versuchen — Kurse und Zustände bleiben, die Merkliste zeigt
        // «Offline · Stand …». Kommt das Netz zurück, aktualisiert `AppData` einmal. Mit Endzeit (Hintergrund,
        // Widget) nie nach dem gemerkten Stand aussetzen: der kann nach einer Pause des Prozesses veraltet sein —
        // dann einfach versuchen; sonst ein gemerktes «offline» frisch nachprüfen.
        guard deadline != nil || NetworkStatus.shared.isOnlineFresh else { return outcome }
        // Pausierte Börsen (zu viele Anfragen, wiederholte Zeitüberschreitungen): diesmal aussen vor.
        // Beim Speichern mit dem dann gespeicherten Stand zusammenführen (`ExchangeBackoff.merge`): ein anderer
        // Prozess (Widget, Hintergrund) kann inzwischen eine Pause gemerkt haben
        let initialBackoff = SharedStorage.exchangeBackoff
        var backoff = initialBackoff
        // Live-Kurse: nur diese Paare (nicht mehr gehandelte bleiben unberührt), keine Pause je Börse.
        // Deaktivierte Paare nie: keine Abfrage, keine Alarme, keine Mitteilungen (wie Android)
        let all = snapshot.watches.filter { w in
            if w.disabled { return false }
            if let liveQuotes { return liveQuotes[w.id] != nil && !w.isNotTraded }
            return onlyWatchId == nil || w.id == onlyWatchId
        }
        // Gemeinsame Binance-Pause (429/418 bei Kerzen, Futures …): auch die Kurse pausieren,
        // sonst verlängerte die Kursabfrage die IP-Sperre (siehe `BlockedSources`) — wie Android
        if liveQuotes == nil {
            for key in Set(all.map(\.marketKey)) {
                guard let until = BlockedSources.groupPausedUntil(key) else { continue }
                let state = backoff[key]
                if (state?.pausedUntil ?? Int64.min) < until {
                    var merged = state ?? ExchangeBackoff.State(strikes: 1)
                    merged.pausedUntil = until
                    merged.reason = .RATE_LIMIT
                    merged.timeoutRuns = 0
                    backoff[key] = merged
                }
            }
        }
        let paused = liveQuotes != nil ? [] : all.filter { ExchangeBackoff.isPaused(backoff[$0.marketKey], now: started) }
        outcome.pausedIds = Set(paused.map(\.id))
        // Paare mit frischem Live-Kurs (WebSocket): diesmal ohne REST-Abfrage
        let watches = all.filter { !outcome.pausedIds.contains($0.id) && !liveIds.contains($0.id) }
        guard !watches.isEmpty else {
            outcome.durationMillis = TimeUtils.nowMillis - started
            // Alle Börsen pausiert: der Bericht sagt bis wann
            if onlyWatchId == nil && !paused.isEmpty {
                // Übernommene gemeinsame Binance-Pause mit speichern (wie Android)
                SharedStorage.exchangeBackoff = ExchangeBackoff.merge(ours: backoff, initial: initialBackoff,
                                                                      stored: SharedStorage.exchangeBackoff)
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
                // Börsenzeit nur als im Strom belegte Handelszeit (`LiveRules.exchangeTime`), nie die Takt-Zeit
                let exchangeTime = LiveRules.exchangeTime(q, storedPrice: w.lastPrice, storedExchangeTime: w.exchangeTime)
                fetched[w.id] = Fetched(ticker: Ticker(last: q.price, timestamp: q.time, change24hPercent: q.change24h,
                                                       exchangeTime: exchangeTime),
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
            // Je Börse Paare mit aktivem Alarm zuerst abfragen (`FetchOrder`)
            let alarmedIds = Set(snapshot.alarms.filter(\.enabled).map(\.watchId))
            var signals: [GroupSignals] = []
            // Mit Endzeit: höchstens bis dahin (Speichern bleibt übrig)
            let networkBudget = deadline.map { $0.allowance(wanted: networkBudgetMillis ?? Int64.max) } ?? networkBudgetMillis

            await withTaskGroup(of: ([Int64: Fetched], GroupSignals?, Bool).self) { group in
                for (_, list) in groups {
                    group.addTask {
                        if onlyWatchId != nil, let w = list.first {
                            let single = await fetchSingle(w)
                            return ([w.id: single], nil, false)
                        }
                        let (results, signal) = await fetchGroup(list, includeRollingFutures: settings.includeRollingFutures,
                                                                 lowMemory: lowMemory, alarmed: alarmedIds)
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
            // Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben
            if onlyWatchId == nil {
                let finished = TimeUtils.nowMillis
                for signal in signals {
                    var market = signal.report
                    let state = ExchangeBackoff.next(
                        backoff[signal.marketKey],
                        outcome: ExchangeBackoff.outcome(failures: signal.failures, updated: market.updated),
                        now: finished, retryAfterMillis: signal.retryAfterMillis,
                        // 418 und «Retry-After» wie bei Kerzen (gemeinsame Binance-Pause)
                        banned: signal.banned, shared: ExchangeBackoff.sharedGroup(signal.marketKey) != nil)
                    backoff[signal.marketKey] = state
                    // 429/418 beim Ticker: Kerzen, Futures und Aktivität an dieselben Hosts pausieren mit
                    if let state, state.reason == .RATE_LIMIT, ExchangeBackoff.isPaused(state, now: finished) {
                        BlockedSources.pauseGroup(signal.marketKey, until: state.pausedUntil)
                    }
                    if let state, ExchangeBackoff.isPaused(state, now: finished) {
                        market.pausedUntil = state.pausedUntil
                        market.pauseReason = state.reason
                    }
                    markets.append(market)
                }
                markets.append(contentsOf: pausedMarkets(paused, backoff: backoff))
                // 429 bei Spot pausiert auch Futures auf demselben Host (und umgekehrt) — wie Android
                SharedStorage.exchangeBackoff = ExchangeBackoff.merge(
                    ours: ExchangeBackoff.withHostGroupPauses(backoff, now: finished), initial: initialBackoff,
                    stored: SharedStorage.exchangeBackoff)
            }
            let needingCandles = watchesNeedingCandles(watches, fetched: fetched, basis: basis, remember: onlyWatchId == nil)
            let startedKeys = Set(earlyKeys.map { "\($0.base)|\($0.quote)" })
            let lateKeys = dayReferenceKeys(needingCandles).filter { !startedKeys.contains("\($0.base)|\($0.quote)") }
            let lateLoads = startDayReferenceLoads(lateKeys, dayStart: dayStart)
            dayLoads = Task {
                await earlyLoads.value
                await lateLoads.value
            }
            await waitAtMost(nanos: RefreshDeadline.nanos(wanted: Int64(dayReferenceWaitNanos / 1_000_000), deadline: deadline),
                             for: dayLoads)
            dayReferences = await cachedDayReferences(needingCandles, dayStart: dayStart)
        }
        let networkMillis = TimeUtils.nowMillis - started

        // 2) Auswerten
        let now = TimeUtils.nowMillis
        // Nachtruhe: Alarme warten bis zum Ende (`QuietHours.holdsAlarms`, in checkAlarms),
        // und nichts vorlesen — weder Alarme noch Kurse.
        let speechAllowed = settings.ttsEnabled && !QuietHours.isQuietNow(settings)
        // Für das Vorladen (Volumen, Hoch/Tief, Umrechnung) genügt der mitgegebene Stand;
        // ausgewertet wird weiter unten der frisch gelesene.
        let enabledAlarms = snapshot.alarms.filter(\.enabled)
        // Mit Endzeit: hier nur Alarme, die allein den Kurs brauchen; die anderen später (`deferredAlarms`)
        let deferExtra = deadline != nil && liveQuotes == nil
        let prefetchAlarms = deferExtra ? enabledAlarms.filter { !AlarmDataStage.needsExtraData($0) } : enabledAlarms
        let prefetchByWatch = Dictionary(grouping: prefetchAlarms, by: \.watchId)
        if deferExtra {
            outcome.deferredAlarms.prefetchByWatch = Dictionary(
                grouping: enabledAlarms.filter(AlarmDataStage.needsExtraData), by: \.watchId)
        }

        let inputs = await loadAlarmInputs(watches: watches, fetched: fetched, prefetchByWatch: prefetchByWatch,
                                           live: liveQuotes != nil, deadline: deadline)

        // Alarme: nur einer zur Zeit (App, Hintergrund) und immer auf dem gespeicherten Stand —
        // sonst meldet ein zweiter Prozess, was der erste gerade gemeldet hat.
        var alarmsByWatch: [Int64: [Alarm]] = [:]
        if !prefetchAlarms.isEmpty {
            if let lease = await AlarmLease.acquire(timeoutSeconds: leaseWaitSeconds(deadline)) {
                outcome.alarmLease = lease
                let fresh = SharedStorage.loadSnapshot().alarms.filter {
                    $0.enabled && !(deferExtra && AlarmDataStage.needsExtraData($0))
                }
                alarmsByWatch = Dictionary(grouping: fresh, by: \.watchId)
            }
        }
        // Alarm-Protokoll («Kein Kurs», Sperrgründe): auch die erst später geprüften Alarme mit Zusatzdaten — nur
        // festgehalten, entschieden wird damit nichts
        let logAlarmsByWatch = deferExtra
            ? alarmsForLog(checked: alarmsByWatch, deferred: outcome.deferredAlarms.prefetchByWatch)
            : alarmsByWatch

        // Kurs-Mitteilungen entfernen: gesammelt in EINEM Aufruf statt einmal je Paar
        var cancelIds: [Int64] = []
        for watch in watches {
            let result = fetched[watch.id]
            // Widget: bewusst nicht abgefragt — gespeicherter Kurs und Zustand bleiben; wie eine pausierte Börse
            // (neue %-Basis: alte Veränderung weg, `apply`)
            if result?.skipped == true {
                outcome.pausedIds.insert(watch.id)
                continue
            }
            outcome.checked += 1
            // NaN/∞ nie als Kurs übernehmen — wie PriceRefresher.kt
            guard let ticker = result?.ticker, result?.error == nil, ticker.last.isFinite, ticker.last > 0 else {
                outcome.failed += 1
                outcome.prices[watch.id] = PriceUpdate(price: nil, time: now, error: result?.error, pair: watch.identity)
                cancelIds.append(watch.id)
                // Alarm-Protokoll: «Kein Kurs» als letzte Prüfung der Alarme dieses Paars
                if liveQuotes == nil {
                    outcome.alarmLog += (logAlarmsByWatch[watch.id] ?? []).compactMap {
                        alarmLogEntry($0, watch: watch, trace: AlarmLogTrace(noPrice: true), now: now)
                    }
                }
                continue
            }
            let price = ticker.last
            let time = ticker.timestamp > 0 ? ticker.timestamp : now
            // DEX-Pool: Liquidität jeder Abfrage merken (Alarm-Sperre in `checkAlarms`, Hinweis in Suche und Alarmen)
            if liveQuotes == nil && DexLiquidity.isDex(watch.marketKey) {
                DexLiquidityStore.put(watch.pairId, ticker.liquidityUsd)
            }
            // Kurs = Mark-Preis mangels Mittelkurs (Hyperliquid): «Mark» in Zeile und Blatt; Live-Kurse kommen nie als
            // Mark-Ersatz, der Merker gilt bis zur nächsten Abfrage
            if liveQuotes == nil { MarkFallbackStore.put(watch.id, ticker.markFallback) }
            let dayChange: Double?
            if liveQuotes != nil {
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
            if dayChange == nil && dayStart != nil && liveQuotes == nil { outcome.missingDayChange[watch.id] = (price, time) }

            var updated = watch
            updated.previousPrice = watch.lastPrice
            updated.lastPrice = price
            updated.lastUpdate = time
            updated.lastError = nil
            updated.change24h = dayChange
            updated.priceChangedAt = changedAt
            updated.exchangeTime = ticker.exchangeTime

            // Kurs laut Börse zu alt (`AlarmFreshness`, Handel ruht) bzw. nur Mark-Preis: Alarme bleiben scharf, lösen
            // aber nicht aus
            let alarmsAllowed = !AlarmFreshness.blocksAlarms(updated.exchangeTime, markFallback: ticker.markFallback,
                                                             now: now)
            if !alarmsAllowed, let blocked = logAlarmsByWatch[watch.id] {
                // Alarm-Protokoll: nur festhalten, warum nicht (dieselbe Reihenfolge der Gründe wie Android)
                outcome.alarmLog += blockedAlarmLog(
                    blocked, watch: watch, updated: updated,
                    lowLiquidity: DexLiquidityStore.blocksAlarms(marketKey: watch.marketKey, pairId: watch.pairId),
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
                // Fällige Alarme; gemeldet und gespeichert werden sie erst danach (`deliverAlarms`)
                alarms: outcome.pendingAlarms.count,
                notifications: outcome.notified.count
            )
        }
        return outcome
    }
}
