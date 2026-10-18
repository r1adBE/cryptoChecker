import Foundation

// Zweiter Schritt im Hintergrund (Teil von `PriceRefresher`): Wartezeit auf die Lease und zurückgestellte Alarme mit
// Zusatzdaten prüfen — wie `PriceRefresher.kt` (Android).

extension PriceRefresher {
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
