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
        /// Letzte echte Kursänderung (`PriceFreshness.nextPriceChangedAt`); 0 = nicht gesetzt.
        var priceChangedAt: Int64 = 0
        /// Zeit laut Börse, nil = keine (`Ticker.exchangeTime`).
        var exchangeTime: Int64? = nil
    }

    /// Änderung eines Alarms durch die Auswertung. Übernommen wird sie nur, wenn der Alarm noch so
    /// ist wie ausgewertet (`matches`) — sonst bleibt die Bearbeitung des Nutzers.
    struct AlarmUpdate: Sendable {
        /// nil = nicht anfassen (Wiederscharfstellen, neuer Bezug).
        var enabled: Bool?
        var referencePrice: Double?
        var referenceAt: Int64
        var lastTriggeredAt: Int64
        var lastTriggeredPrice: Double?
        /// Ausgewerteter Stand: Bedingung, Schwelle, Fenster, Wiederholung.
        var condition: AlarmCondition
        var threshold: Double
        var windowHours: Int
        var repeating: Bool

        /// `evaluated` = Alarm wie ausgewertet, `result` = Zustand danach; `setsEnabled` nur beim Auslösen.
        init(evaluated: Alarm, result: Alarm, setsEnabled: Bool) {
            enabled = setsEnabled ? result.enabled : nil
            referencePrice = result.referencePrice
            referenceAt = result.referenceAt
            lastTriggeredAt = result.lastTriggeredAt
            lastTriggeredPrice = result.lastTriggeredPrice
            condition = evaluated.condition
            threshold = evaluated.threshold
            windowHours = evaluated.windowHours
            repeating = evaluated.repeating
        }

        /// Alarm unverändert (eingeschaltet, gleiche Bedingung, Schwelle, Fenster; beim Auslösen
        /// auch gleiche Wiederholung)? Wie die bedingten UPDATEs in Android (`WatchDao`).
        func matches(_ alarm: Alarm) -> Bool {
            alarm.enabled && alarm.condition == condition && alarm.threshold == threshold
                && alarm.windowHours == windowHours && (enabled == nil || alarm.repeating == repeating)
        }

        /// Überträgt die Änderung auf den Alarm `id` in `snapshot`, wenn er noch so ist wie ausgewertet.
        /// - Returns: true = übertragen; false = inzwischen bearbeitet, abgeschaltet oder gelöscht.
        @discardableResult
        func apply(toAlarm id: Int64, in snapshot: inout SharedStorage.Snapshot) -> Bool {
            guard let i = snapshot.alarms.firstIndex(where: { $0.id == id }), matches(snapshot.alarms[i]) else {
                return false
            }
            if let enabled { snapshot.alarms[i].enabled = enabled }
            snapshot.alarms[i].referencePrice = referencePrice
            snapshot.alarms[i].referenceAt = referenceAt
            snapshot.alarms[i].lastTriggeredAt = lastTriggeredAt
            snapshot.alarms[i].lastTriggeredPrice = lastTriggeredPrice
            return true
        }
    }

    /// Ausgelöster Alarm: erst melden, dann speichern (`Outcome.deliverAlarms`, wie Android
    /// `AlarmDelivery`). Kommt die Mitteilung nicht an, bleibt der Alarm scharf; beendet iOS den Prozess
    /// zwischen Melden und Speichern, meldet er sich frühestens nach der Abklingzeit noch einmal.
    struct PendingAlarm: Sendable {
        var watch: Watch
        var alarm: Alarm
        var price: Double
        var volumeRatio: Double? = nil
        var text: String? = nil
        /// Zustand nach dem Auslösen — gespeichert erst nach bestätigter Mitteilung.
        var update: AlarmUpdate
        /// Ansage, sobald gemeldet und gespeichert; nil = keine.
        var speech: String? = nil
    }

    /// Mitteilungs-Weg der Paar-Alarme für `Outcome.deliverAlarms`; Tests setzen einen Fake ein.
    struct AlarmNotifier: Sendable {
        /// Kann eine Mitteilung gerade sichtbar ankommen?
        var canShow: @Sendable () async -> Bool
        /// Meldet; true erst, wenn die Mitteilung bestätigt ist.
        var show: @Sendable (PendingAlarm) async -> Bool

        static let system = AlarmNotifier(
            canShow: { await Notifier.isAuthorized() },
            show: { item in
                await Notifier.showAlarm(item.watch, item.alarm, price: item.price, volumeRatio: item.volumeRatio,
                                         text: item.text)
            })
    }

    /// Zurückgestellte Alarm-Prüfung (siehe `Outcome.deferredAlarms`): je Paar mit neuem Kurs der
    /// Stand davor/danach; `prefetchByWatch` = aktive Alarme mit Zusatzdaten aus dem mitgegebenen Stand.
    struct DeferredAlarms: Sendable {
        struct Item: Sendable {
            var watch: Watch
            var updated: Watch
            var price: Double
            var fetched: Fetched
        }
        var items: [Item] = []
        var prefetchByWatch: [Int64: [Alarm]] = [:]
        var isEmpty: Bool { items.isEmpty }
    }

    struct Outcome: Sendable {
        var prices: [Int64: PriceUpdate] = [:]
        /// Änderungen ohne Auslösen (wieder scharf, neuer Bezug) — mit den Kursen gespeichert.
        var alarms: [Int64: AlarmUpdate] = [:]
        /// Ausgelöste Alarme — nach dem Speichern der Kurse melden und erst dann speichern (`deliverAlarms`).
        var pendingAlarms: [PendingAlarm] = []
        /// Lease der Alarm-Auswertung (nil = keine Alarme ausgewertet); freigegeben
        /// von `deliverAlarms` / `releaseAlarmLease()`, spätestens beim Aufräumen.
        var alarmLease: AlarmLease? = nil
        /// Watch-Id → (gemeldeter Kurs, Zeitpunkt).
        var notified: [Int64: (Double, Int64)] = [:]
        /// Kursansagen je Paar (Watch-Id, Text) — Alarm-Ansagen stehen in `PendingAlarm.speech`;
        /// der Sprecher behält je Paar nur die neueste wartende Ansage.
        var priceSpeech: [(watchId: Int64, text: String)] = []
        var checked = 0
        var failed = 0
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
        /// Nur mit Endzeit (Hintergrund): Alarme, die Zusatzdaten brauchen — erst nach dem Speichern
        /// der Kurse prüfen (`checkDeferredAlarms`), damit Kurse und Kursalarme nie auf sie warten.
        var deferredAlarms = DeferredAlarms()

        /// NACH `apply(to:)` und dem Speichern der Kurse aufrufen (die Lease ist noch gehalten): je
        /// ausgelöstem Alarm zuerst melden, prüfen, dass die Mitteilung angekommen ist, und erst dann den
        /// Zustand nach dem Auslösen speichern (`AlarmDelivery`, wie Android). Danach die Lease freigeben —
        /// der nächste Prozess sieht so nur gespeicherte, also wirklich gemeldete Auslösungen.
        /// - Parameter current: Alarme des gespeicherten Stands: inzwischen bearbeitet, abgeschaltet oder
        ///   gelöscht → keine Mitteilung.
        /// - Parameter save: überträgt den Zustand eines Alarms auf den gespeicherten Stand (App: Stand im
        ///   Speicher und Datei; Hintergrund und Widget: `saveAlarmOnDisk`); true = gespeichert.
        /// - Parameter reloadStored: liest die Alarme vor jeder Meldung frisch von der Platte; nil = nicht
        ///   lesbar (dann gilt allein `current`, Fail-open). Tests: Fake.
        /// - Parameter notifier, delivery, now: Mitteilungs-Weg, Speicher der unbestätigten Versuche und Uhr (Tests: Fakes).
        /// - Returns: gemeldete und gespeicherte Alarme (für die Ansagen).
        func deliverAlarms(settings: AppSettings, current: [Alarm],
                           save: @escaping @Sendable (Int64, AlarmUpdate) async -> Bool,
                           reloadStored: @Sendable () async -> [Alarm]? = { SharedStorage.readSnapshot().snapshot?.alarms },
                           notifier: AlarmNotifier = .system, delivery: AlarmDelivery = .pairs,
                           now: @Sendable () -> Int64 = { TimeUtils.nowMillis }) async -> [PendingAlarm] {
            defer { alarmLease?.release() }
            guard !pendingAlarms.isEmpty else { return [] }
            let byId = Dictionary(current.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            // Erlaubnis und Nachtruhe einmal für alle; die Auswertung hat beides schon geprüft
            // (`QuietHours.mayFire`), seither kann sich aber etwas geändert haben
            let deliverable = await notifier.canShow()
            let holdAlarms = QuietHours.holdsAlarmsNow(settings)
            var delivered: [PendingAlarm] = []
            for item in pendingAlarms {
                guard let stored = byId[item.alarm.id], item.update.matches(stored) else { continue }
                // Lease vor jeder Meldung auffrischen: Langes Melden (viele Alarme) liesse sie sonst nach
                // `AlarmLease.staleAfterSeconds` verwaisen, ein zweiter Prozess übernähme und meldete doppelt
                alarmLease?.refresh()
                // Frisch von der Platte: Hat ein anderer Prozess den Alarm seit der Auswertung schon
                // ausgelöst (z. B. nach übernommener Lease), bearbeitet oder gelöscht → nicht noch einmal melden
                if let onDisk = await reloadStored() {
                    guard let fresh = onDisk.first(where: { $0.id == item.alarm.id }), item.update.matches(fresh),
                          fresh.lastTriggeredAt <= item.alarm.lastTriggeredAt else { continue }
                }
                let saved = await delivery.deliver(
                    alarmId: item.alarm.id, now: now(), cooldownMinutes: settings.alarmCooldownMinutes,
                    holdAlarms: holdAlarms, canShow: { deliverable },
                    show: { await notifier.show(item) },
                    mark: { await save(item.alarm.id, item.update) })
                if saved { delivered.append(item) }
            }
            return delivered
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
                        snapshot.watches[i].priceChangedAt = u.priceChangedAt
                        snapshot.watches[i].exchangeTime = u.exchangeTime
                    } else {
                        // Nur kurze Codes speichern, keine rohen Fehlertexte (Hosts, URLs) — `StoredError`
                        snapshot.watches[i].lastError = StoredError.sanitize(u.error) ?? L("something_went_wrong")
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
            // Nur auf unveränderte Alarme: eine Bearbeitung während der Abfrage bleibt
            for (id, update) in alarms { update.apply(toAlarm: id, in: &snapshot) }
        }
    }

    /// `save` für `Outcome.deliverAlarms` ohne Stand im Speicher (Hintergrund-Aufgabe, Widget): überträgt
    /// den Zustand eines gemeldeten Alarms abgestimmt auf die Datei. true = übertragen und geschrieben.
    static func saveAlarmOnDisk(_ id: Int64, _ update: AlarmUpdate) -> Bool {
        var applied = false
        let saved = SharedStorage.updateSnapshot { applied = update.apply(toAlarm: id, in: &$0) }
        return saved != nil && applied
    }

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
        // Kein Netz: gar nicht erst versuchen — Kurse und Zustände bleiben, die Merkliste zeigt
        // «Offline · Stand …». Kommt das Netz zurück, aktualisiert `AppData` einmal.
        guard NetworkStatus.shared.isOnline else { return outcome }
        // Pausierte Börsen (zu viele Anfragen, wiederholte Zeitüberschreitungen): diesmal aussen vor
        var backoff = SharedStorage.exchangeBackoff
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
                SharedStorage.exchangeBackoff = backoff
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
                // Ein neuer Kurs aus dem Strom ist selbst der Beleg: seine Zeit gilt als Börsenzeit
                fetched[w.id] = Fetched(ticker: Ticker(last: q.price, timestamp: q.time, change24hPercent: q.change24h,
                                                       exchangeTime: q.time),
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
                                                                 lowMemory: lowMemory)
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
                outcome.prices[watch.id] = PriceUpdate(price: nil, time: now, error: result?.error)
                cancelIds.append(watch.id)
                continue
            }
            let price = ticker.last
            let time = ticker.timestamp > 0 ? ticker.timestamp : now
            let dayChange: Double?
            if liveQuotes != nil {
                // Live-Wert aus dem Ticker: kein Kerzen-Hinweis
                candlePills.set(watch.id, series: nil)
                dayChange = ticker.change24hPercent
            } else {
                dayChange = change24h(watch, price: price, ticker: ticker, basis: basis, references: dayReferences)
            }
            // Frische (`PriceFreshness`): letzte echte Kursänderung und Zeit laut Börse (nil = keine)
            let changedAt = PriceFreshness.nextPriceChangedAt(previousPrice: watch.lastPrice,
                                                              previousChangedAt: watch.priceChangedAt,
                                                              price: price, time: time)
            outcome.prices[watch.id] = PriceUpdate(price: price, time: time, error: nil, change24h: dayChange,
                                                   priceChangedAt: changedAt, exchangeTime: ticker.exchangeTime)
            if dayChange == nil && dayStart != nil && liveQuotes == nil { outcome.missingDayChange[watch.id] = (price, time) }

            var updated = watch
            updated.previousPrice = watch.lastPrice
            updated.lastPrice = price
            updated.lastUpdate = time
            updated.lastError = nil
            updated.change24h = dayChange
            updated.priceChangedAt = changedAt
            updated.exchangeTime = ticker.exchangeTime

            // Alarme mit Zusatzdaten: nach dem Speichern prüfen
            if deferExtra, outcome.deferredAlarms.prefetchByWatch[watch.id] != nil, let result {
                outcome.deferredAlarms.items.append(DeferredAlarms.Item(watch: watch, updated: updated, price: price,
                                                                        fetched: result))
            }

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
                // Fällige Alarme; gemeldet und gespeichert werden sie erst danach (`deliverAlarms`)
                alarms: outcome.pendingAlarms.count,
                notifications: outcome.notified.count
            )
        }
        return outcome
    }

    /// Wartezeit auf die Lease: höchstens `alarmLeaseWaitSeconds`, mit Endzeit nicht darüber hinaus.
    static func leaseWaitSeconds(_ deadline: RefreshDeadline?, now: Int64 = TimeUtils.nowMillis) -> Double {
        let wanted = Int64(alarmLeaseWaitSeconds * 1000)
        return Double(deadline?.allowance(wanted: wanted, now: now) ?? wanted) / 1000
    }

    /// Zweiter Schritt im Hintergrund (nach dem Speichern der Kurse): Alarme mit Zusatzdaten
    /// (`Outcome.deferredAlarms`) prüfen — Daten höchstens bis `deadline` laden; was nicht rechtzeitig
    /// kommt, wird diese Runde ausgelassen (Alarm bleibt unverändert scharf). Gleicher Ablauf wie
    /// `refresh`: Lease, frisch gelesene Alarme; der Aufrufer wendet an, speichert und ruft
    /// `deliverAlarms` (bzw. `releaseAlarmLease()`).
    static func checkDeferredAlarms(_ deferred: DeferredAlarms, settings: AppSettings,
                                    deadline: RefreshDeadline?) async -> Outcome {
        var outcome = Outcome()
        guard !deferred.isEmpty else { return outcome }
        let watches = deferred.items.map(\.watch)
        var fetched: [Int64: Fetched] = [:]
        for item in deferred.items { fetched[item.watch.id] = item.fetched }
        let inputs = await loadAlarmInputs(watches: watches, fetched: fetched, prefetchByWatch: deferred.prefetchByWatch,
                                           live: false, deadline: deadline)
        guard !Task.isCancelled,
              let lease = await AlarmLease.acquire(timeoutSeconds: leaseWaitSeconds(deadline)) else { return outcome }
        outcome.alarmLease = lease
        let fresh = SharedStorage.loadSnapshot().alarms.filter { $0.enabled && AlarmDataStage.needsExtraData($0) }
        let alarmsByWatch = Dictionary(grouping: fresh, by: \.watchId)
        let now = TimeUtils.nowMillis
        let speechAllowed = settings.ttsEnabled && !QuietHours.isQuietNow(settings)
        for item in deferred.items {
            _ = checkAlarms(alarmsByWatch[item.watch.id] ?? [], watch: item.watch, updated: item.updated,
                            price: item.price, now: now, settings: settings, speechAllowed: speechAllowed,
                            inputs: inputs, outcome: &outcome)
        }
        return outcome
    }
}
