import Foundation

/// «1 h später» aus der Alarm-Mitteilung — wie `AlarmSnooze.kt`: Nur die nächste Meldung wartet bis zum Ende der
/// Pause, wie die Nachtruhe im Melde-Schritt (`AlarmDelivery`), nie in der Prüfung selbst (`AlarmEvaluator` bleibt
/// unverändert). Angeboten nur, wo das wirkt (`offeredFor`): Prozent- und Bewegungsalarme bleiben nach dem Auslösen
/// scharf; ist die Bedingung nach der Pause noch erfüllt, melden sie sich beim nächsten Durchlauf.
/// Gemeinsamer Testfall `alarm_snooze.json`.
///
/// Die laufenden Pausen liegen im App-Group-Speicher (Alarm-Id → Ende), wie die unbestätigten Versuche von
/// `AlarmDelivery`: App, Hintergrund-Aufgabe und Widget melden Alarme und müssen die Pause alle sehen.
enum AlarmSnooze {
    /// Dauer von «1 h später».
    static let snoozeMillis: Int64 = 60 * 60_000

    static let storeKey = "alarm_snooze_until"

    /// Ende der Pause, wenn jetzt (`now`) «1 h später» getippt wird.
    static func snoozeUntil(now: Int64) -> Int64 { now + snoozeMillis }

    /// Hält die Pause die Meldung jetzt zurück? Nur zwischen jetzt und `until`; liegt `until` weiter als `snoozeMillis`
    /// in der Zukunft (Uhr zurückgestellt), gilt die Pause als vorbei — ein Alarm schweigt nie länger als versprochen.
    static func isSnoozed(until: Int64?, now: Int64) -> Bool {
        guard let until, until > 0 else { return false }
        let rest = until - now
        return rest > 0 && rest <= snoozeMillis
    }

    /// Knopf «1 h später» zeigen? Nur bei wiederholenden Alarmen (ein einmaliger ist nach dem Melden aus) und nur bei
    /// Prozent- und Bewegungsalarmen (`isPercent`): Sie bleiben nach dem Auslösen scharf (neuer Bezug bzw. neues
    /// Fenster, `AlarmEvaluator.triggered`), die Pause hält also wirklich eine Meldung zurück. Kursmarken, Funding und
    /// Open Interest werden erst wieder scharf, wenn der Wert auf die andere Seite zurückkehrt; «Nahe am Hoch/Tief»
    /// erst ausserhalb der Zone oder frühestens 1 h später bei einem weiteren Hoch/Tief; der Volumen-Spike meldet
    /// ohnehin höchstens einmal je Stundenkerze — dort hielte die Pause nichts zurück. Wie Android.
    static func offeredFor(_ condition: AlarmCondition, repeating: Bool) -> Bool {
        repeating && condition.isPercent
    }

    /// Nur noch laufende Pausen (abgelaufene fallen beim Speichern weg).
    static func prune(_ all: [Int64: Int64], now: Int64) -> [Int64: Int64] {
        all.filter { isSnoozed(until: $0.value, now: now) }
    }

    // MARK: App-Group-Speicher

    /// Ende der Pause von `alarmId`; nil = keine.
    static func until(_ alarmId: Int64, store: UserDefaults = SharedStorage.defaults) -> Int64? {
        guard let value = (store.dictionary(forKey: storeKey) as? [String: Double])?[String(alarmId)] else { return nil }
        return Int64(value)
    }

    /// «1 h später» für `alarmId` ab `now`; räumt abgelaufene Pausen mit weg. Kommt nur aus der App (Knopf der
    /// Mitteilung, MainActor) — gleichzeitiges Schreiben aus zwei Prozessen gibt es darum nicht.
    static func snooze(_ alarmId: Int64, now: Int64, store: UserDefaults = SharedStorage.defaults) {
        let stored = (store.dictionary(forKey: storeKey) as? [String: Double]) ?? [:]
        var all: [Int64: Int64] = [:]
        for (key, value) in stored { if let id = Int64(key) { all[id] = Int64(value) } }
        all = prune(all, now: now)
        all[alarmId] = snoozeUntil(now: now)
        store.set(Dictionary(uniqueKeysWithValues: all.map { (String($0.key), Double($0.value)) }), forKey: storeKey)
    }
}
