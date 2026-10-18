import Foundation

/// Einträge des Alarm-Protokolls aus der Prüfung (`AlarmLog`): nur beobachten — keine Entscheidung hängt hieran.
/// Gesammelt in `Outcome.alarmLog` bzw. `PendingAlarm.logEntry`, geschrieben nach dem Melden (`Outcome.deliverAlarms`).
/// Wie `AlarmLogCollector.kt`.
extension PriceRefresher {

    /// Alarme, für die «Kein Kurs» bzw. Sperrgründe festgehalten werden: die jetzt geprüften und die erst später
    /// geprüften mit Zusatzdaten (z. B. Schwelle in anderer Währung) — wie Android, das dafür alle eingeschalteten
    /// Alarme liest. Ein Alarm steht nie doppelt.
    static func alarmsForLog(checked: [Int64: [Alarm]], deferred: [Int64: [Alarm]]) -> [Int64: [Alarm]] {
        var all = checked
        for (watchId, extra) in deferred {
            let known = Set(all[watchId, default: []].map(\.id))
            all[watchId, default: []] += extra.filter { !known.contains($0.id) }
        }
        return all
    }

    /// Eintrag für `alarm` auf `watch` (Paar mit neuem Kurs bzw. ohne Kurs); nil für nicht erfasste Alarme
    /// (`AlarmLog.covers`). `comparePrice`: Kurs in der Währung der Schwelle (nur mit Umrechnung gespeichert).
    static func alarmLogEntry(_ alarm: Alarm, watch: Watch, trace: AlarmLogTrace, now: Int64,
                              comparePrice: Double? = nil) -> AlarmLogEntry? {
        guard AlarmLog.covers(alarm.condition) else { return nil }
        let outcome = AlarmLog.outcome(trace)
        let currency = alarm.convertCurrency
        return AlarmLogEntry(
            alarmId: alarm.id, at: now, pair: watch.displayName, exchange: watch.marketName, quote: watch.quoteAsset,
            price: trace.noPrice ? nil : watch.lastPrice, condition: alarm.condition, threshold: alarm.threshold,
            windowHours: alarm.windowHours, currency: currency, shownPrice: currency != nil ? comparePrice : nil,
            outcome: outcome, exchangeTime: watch.exchangeTime, notable: AlarmLog.notable(outcome, trace))
    }

    /// Paar mit neuem Kurs, dessen Alarme gesperrt sind (DEX-Liquidität, Mark-Preis, Kurs laut Börse zu alt). Ob ein
    /// Alarm sonst fällig gewesen wäre, sagt eine Vorschau mit denselben reinen Regeln (`AlarmEvaluator.shouldTrigger`) —
    /// nur für Alarme ohne Umrechnung und ohne Kursverlauf (Kursmarke, Steigt/Fällt um); sonst nur «zuletzt geprüft».
    /// `watch`: Stand vor dem Kurs (vorheriger Kurs), `updated`: mit dem neuen Kurs.
    static func blockedAlarmLog(_ alarms: [Alarm], watch: Watch, updated: Watch, lowLiquidity: Bool, markFallback: Bool,
                                now: Int64, cooldownMinutes: Int) -> [AlarmLogEntry] {
        guard let price = updated.lastPrice else { return [] }
        let stale = AlarmFreshness.exchangeTimeTooOld(updated.exchangeTime, now: now)
        return alarms.compactMap { alarm in
            let due = alarm.convertCurrency == nil && alarm.condition != .MOVE_PERCENT_WINDOW
                && !AlarmEvaluator.needsReference(alarm: alarm, now: now)
                && AlarmEvaluator.shouldTrigger(alarm: alarm, price: price, previousPrice: watch.lastPrice, now: now,
                                                cooldownMinutes: cooldownMinutes)
            return alarmLogEntry(alarm, watch: updated,
                                 trace: AlarmLogTrace(lowLiquidity: lowLiquidity, markPrice: markFallback, stale: stale,
                                                      due: due),
                                 now: now)
        }
    }
}
