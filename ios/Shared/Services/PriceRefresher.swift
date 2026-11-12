import Foundation

/// Holt die Kurse aller beobachteten Paare und löst aus, was daran hängt:
/// Alarme, Kurs-Mitteilungen und Ansagen — wie `PriceRefresher.kt`.
///
/// Arbeitet auf einer Kopie der Daten und liefert nur die Änderungen zurück
/// (`Outcome.apply(to:)`). So gehen Änderungen, die der Nutzer während der
/// Abfrage macht, nicht verloren — egal ob App, Hintergrund oder Widget fragt.
///
/// Ergebnis-Typen (`Outcome`, `PendingAlarm` …) in `PriceRefresher+Outcome.swift`, die Schritte von `refresh`
/// (Netz, Auswertung je Paar, Abschluss) in `PriceRefresher+Steps.swift`.
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
        let started = EpochTime.nowMillis
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
        if liveQuotes == nil { adoptGroupPauses(&backoff, watches: all, started: started) }
        // Stillgelegte Börse (`ExchangeRetirement`) nie als pausiert: ihr Abruf fragt nichts, setzt nur den Grund
        let paused = liveQuotes != nil ? [] : all.filter {
            ExchangeBackoff.isPaused(backoff[$0.exchangeKey], now: started) && !ExchangeRetirement.isRetired($0.exchangeKey, now: started)
        }
        outcome.pausedIds = Set(paused.map(\.id))
        // Paare mit frischem Live-Kurs (WebSocket): diesmal ohne REST-Abfrage
        let watches = all.filter { !outcome.pausedIds.contains($0.id) && !liveIds.contains($0.id) }
        guard !watches.isEmpty else {
            outcome.durationMillis = EpochTime.nowMillis - started
            // Alle Börsen pausiert: der Bericht sagt bis wann
            if onlyWatchId == nil && !paused.isEmpty {
                // Übernommene gemeinsame Binance-Pause mit speichern (wie Android)
                SharedStorage.exchangeBackoff = ExchangeBackoff.merge(ours: backoff, initial: initialBackoff,
                                                                      stored: SharedStorage.exchangeBackoff,
                                                                      now: EpochTime.nowMillis)
                outcome.report = RefreshReport(at: EpochTime.nowMillis, totalMillis: outcome.durationMillis,
                                               pairs: all.count, networkMillis: 0,
                                               exchanges: pausedExchanges(paused, backoff: backoff))
            }
            return outcome
        }

        // %-Basis: rollend (Ticker, Kerzen nur als Ausweich-Weg) oder seit Tagesbeginn (immer Kerzen)
        let basis = settings.changeBasis
        let changeStamp = ChangeBasisMath.stamp(basis, now: started)
        let dayStart: Int64? = basis.isDay ? changeStamp.dayStart : nil

        // Mit Endzeit (Hintergrund, Widget): Kerzen-Nachlader am Ende abbrechen — sie liefen sonst über die Endzeit
        // hinaus weiter (eigene Aufgaben, kein Nachtragen ohne App im Vordergrund). Ohne Endzeit laden sie fertig.
        var candleLoads: [Task<Void, Never>] = []
        defer { if deadline != nil { candleLoads.forEach { $0.cancel() } } }
        let loaded: Loaded
        if let liveQuotes {
            // Live-Kurse: schon da — kein Netz, keine Kerzen
            loaded = Loaded(fetched: liveFetched(watches, quotes: liveQuotes), dayLoads: Task {})
        } else {
            loaded = await loadFromNetwork(
                watches, paused: paused, snapshot: snapshot, settings: settings, basis: basis, dayStart: dayStart,
                onlyWatchId: onlyWatchId, lowMemory: lowMemory, networkBudgetMillis: networkBudgetMillis,
                deadline: deadline, backoff: backoff, initialBackoff: initialBackoff)
        }
        candleLoads.append(contentsOf: loaded.candleLoads)
        let fetched = loaded.fetched
        let networkMillis = EpochTime.nowMillis - started

        // 2) Auswerten
        let now = EpochTime.nowMillis
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
        let leased = await leasedAlarms(prefetchAlarms: prefetchAlarms, deferExtra: deferExtra, deadline: deadline)
        if let lease = leased.lease { outcome.alarmLease = lease }
        let alarmsByWatch = leased.byWatch
        // Alarm-Protokoll («Kein Kurs», Sperrgründe): auch die erst später geprüften Alarme mit Zusatzdaten — nur
        // festgehalten, entschieden wird damit nichts
        let logAlarmsByWatch = deferExtra
            ? alarmsForLog(checked: alarmsByWatch, deferred: outcome.deferredAlarms.prefetchByWatch)
            : alarmsByWatch
        let run = Evaluation(now: now, settings: settings, speechAllowed: speechAllowed, live: liveQuotes != nil,
                             basis: basis, dayStart: dayStart, dayReferences: loaded.dayReferences,
                             deferExtra: deferExtra, alarmsByWatch: alarmsByWatch,
                             logAlarmsByWatch: logAlarmsByWatch, inputs: inputs)

        // Kurs-Mitteilungen entfernen: gesammelt in EINEM Aufruf statt einmal je Paar
        var cancelIds: [Int64] = []
        var pricePosts: [Watch] = []
        for watch in watches {
            evaluate(watch, result: fetched[watch.id], run: run, outcome: &outcome, cancelIds: &cancelIds,
                     pricePosts: &pricePosts)
        }

        Notifier.cancelPrices(cancelIds)
        await Notifier.showPrices(pricePosts, cancelled: cancelIds)

        outcome.durationMillis = EpochTime.nowMillis - started
        if onlyWatchId == nil && liveQuotes == nil {
            finishFullRun(&outcome, watches: watches, pairs: all.count, changeStamp: changeStamp, dayStart: dayStart,
                          dayLoads: loaded.dayLoads, networkMillis: networkMillis, exchanges: loaded.exchanges)
        }
        return outcome
    }
}
