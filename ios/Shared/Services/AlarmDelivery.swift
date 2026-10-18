import Foundation

/// Ablauf «ausgelösten Alarm melden» — wie `AlarmDelivery.kt`: Nachtruhe oder keine zustellbare
/// Mitteilung → nichts tun, Alarm bleibt scharf. Sonst ZUERST melden (`show`) und erst speichern
/// (`mark`), wenn die Mitteilung bestätigt ist; unbestätigte Meldungen werden frühestens nach der
/// Abklingzeit wiederholt (`retryBlocked`). Der Versuch wird schon VOR dem Melden als unbestätigt vermerkt und
/// erst nach dem Speichern gelöscht: Beendet iOS den Prozess zwischen Melden und Speichern, greift beim nächsten
/// Lauf die Abklingzeit statt einer sofortigen zweiten Meldung (Android hält den Prozess dafür mit `NonCancellable`).
///
/// Anders als Android liegt der unbestätigte Versuch im App-Group-Speicher statt nur im Speicher des
/// Prozesses: App, Hintergrund-Aufgabe und Widget melden Alarme, und ein Neuversuch im anderen Prozess
/// gleich danach wäre eine zweite Meldung. Gelesen und geschrieben wird nur unter der `AlarmLease`
/// (Paar-Alarme) bzw. auf dem MainActor der App (Portfolio-Alarme) — darum genügt hier ein Lock.
final class AlarmDelivery: @unchecked Sendable {
    /// Paar-Alarme (App, Hintergrund, Widget).
    static let pairs = AlarmDelivery(storeKey: "alarm_unconfirmed_at")
    /// Portfolio-Alarme: eigene Ids, darum eigener Eintrag.
    static let portfolio = AlarmDelivery(storeKey: "portfolio_alarm_unconfirmed_at")

    /// Nicht bestätigter Alarm: frühestens so spät erneut melden (wie `NotificationBudget.MIN_RETRY_MINUTES`).
    static let minRetryMinutes = 5

    private let store: UserDefaults
    private let storeKey: String
    private let lock = NSLock()

    init(storeKey: String, store: UserDefaults = SharedStorage.defaults) {
        self.storeKey = storeKey
        self.store = store
    }

    /// Alarm wurde gemeldet, die Mitteilung aber nicht bestätigt: Er bleibt scharf, meldet aber erst nach
    /// der Abklingzeit (mindestens `minRetryMinutes`) erneut — kein Dauerfeuer, falls die Prüfung irrt.
    /// Wie `NotificationBudget.retryBlocked` (Android).
    static func retryBlocked(unconfirmedAt: Int64?, now: Int64, cooldownMinutes: Int) -> Bool {
        guard let unconfirmedAt, unconfirmedAt > 0 else { return false }
        let wait = Int64(max(cooldownMinutes, minRetryMinutes)) * 60_000
        let age = now - unconfirmedAt
        return age >= 0 && age < wait
    }

    /// - Parameter canShow: kann eine Mitteilung gerade sichtbar ankommen (`Notifier.isAuthorized`)?
    /// - Parameter show: meldet; true erst, wenn die Mitteilung bestätigt ist.
    /// - Parameter mark: speichert den Zustand nach dem Auslösen; false = nicht gespeichert (z. B. inzwischen bearbeitet).
    /// - Parameter retract: entfernt die schon gezeigte Mitteilung, wenn `mark` nichts speichern konnte — sie gehörte
    ///   zu einem Alarm, den es so nicht mehr gibt. Wie Android.
    /// - Returns: true = gemeldet und gespeichert (zählt als ausgelöst).
    func deliver(alarmId: Int64, now: Int64, cooldownMinutes: Int, holdAlarms: Bool,
                 canShow: () async -> Bool,
                 show: @escaping @Sendable () async -> Bool,
                 mark: @escaping @Sendable () async -> Bool,
                 retract: @escaping @Sendable () async -> Void = {}) async -> Bool {
        if holdAlarms { return false }
        let deliverable = await canShow()
        if !QuietHours.mayFire(holdAlarms: holdAlarms, deliverable: deliverable) { return false }
        if Self.retryBlocked(unconfirmedAt: unconfirmedAt(alarmId), now: now, cooldownMinutes: cooldownMinutes) {
            return false
        }
        // Melden, Prüfen und Speichern nicht abbrechbar (wie `NonCancellable`): ein eigener Task erbt den
        // Abbruch des Aufrufers nicht — sonst Mitteilung ohne gespeicherten Alarm
        return await Task {
            // «Wird gemeldet» vor dem Melden vermerken (wie ein unbestätigter Versuch)
            self.setUnconfirmed(alarmId, at: now)
            // Nicht bestätigt: Vermerk bleibt — frühestens nach der Abklingzeit erneut
            guard await show() else { return false }
            let saved = await mark()
            self.setUnconfirmed(alarmId, at: nil)
            if !saved { await retract() }
            return saved
        }.value
    }

    // MARK: Unbestätigte Versuche (App-Group-Speicher)

    func unconfirmedAt(_ alarmId: Int64) -> Int64? {
        lock.lock(); defer { lock.unlock() }
        guard let value = (store.dictionary(forKey: storeKey) as? [String: Double])?[String(alarmId)] else { return nil }
        return Int64(value)
    }

    private func setUnconfirmed(_ alarmId: Int64, at time: Int64?) {
        lock.lock(); defer { lock.unlock() }
        var all = (store.dictionary(forKey: storeKey) as? [String: Double]) ?? [:]
        let key = String(alarmId)
        guard all[key] != nil || time != nil else { return }
        all[key] = time.map { Double($0) }
        if all.isEmpty {
            store.removeObject(forKey: storeKey)
        } else {
            store.set(all, forKey: storeKey)
        }
    }
}
