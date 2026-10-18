import Foundation

/// «1 h später» aus der Alarm-Mitteilung — wie `AlarmSnooze.kt`: Der Alarm bleibt scharf, nur seine nächste Meldung
/// wartet bis zum Ende der Pause, wie die Nachtruhe im Melde-Schritt (`AlarmDelivery`), nie in der Prüfung selbst
/// (`AlarmEvaluator` bleibt unverändert). Gemeinsamer Testfall `alarm_snooze.json`.
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
