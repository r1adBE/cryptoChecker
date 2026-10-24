import Combine
import Foundation

/// Alarme, die bei der Prüfung ausgelassen werden mussten (kein Devisenkurs für die Alarmwährung,
/// keine Kerzen für «Nahe am Hoch/Tief» bzw. den Volumen-Spike): «nicht geprüft seit …» je Alarm-Id —
/// wie `AlarmCheckStatus.kt`. Bewusst nur im Speicher dieses Prozesses (nur dieses Gerät, nicht in der
/// Sicherung, nicht im Snapshot); prüft das Widget, sieht die App das nicht. Nach einem Neustart leer.
final class AlarmCheckStatus: ObservableObject, @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = AlarmCheckStatus()

    enum Reason: Equatable, Sendable {
        /// Kein Faktor Quote → Alarmwährung (bzw. Kerzenwährung bei «Nahe am Hoch/Tief»).
        case noRate
        /// Keine Tages- bzw. Stundenkerzen.
        case noCandles
        /// Keine Funding- bzw. Open-Interest-Werte der Quelle (Fehlschlag, gesperrt, nicht geliefert).
        case noData
    }

    /// `since` = erste ausgelassene Prüfung in Folge; `reason` = Grund der letzten.
    struct Unchecked: Equatable, Sendable {
        var reason: Reason
        var since: Int64
    }

    private let lock = NSLock()
    private var map: [Int64: Unchecked] = [:]

    /// Nicht geprüft? nil = zuletzt geprüft (oder noch nie ausgelassen).
    func unchecked(_ alarmId: Int64) -> Unchecked? {
        lock.lock(); defer { lock.unlock() }
        return map[alarmId]
    }

    /// Benachrichtigung der Ansicht schon unterwegs (unter `lock`): viele Änderungen in einem Durchlauf ergeben eine.
    private var notifyPending = false

    func markUnchecked(_ alarmId: Int64, reason: Reason, now: Int64) {
        lock.lock()
        let old = map[alarmId]
        let next = Unchecked(reason: reason, since: old?.since ?? now)
        let changed = old != next
        if changed { map[alarmId] = next }
        lock.unlock()
        if changed { notify() }
    }

    func markChecked(_ alarmId: Int64) {
        lock.lock()
        let changed = map.removeValue(forKey: alarmId) != nil
        lock.unlock()
        if changed { notify() }
    }

    /// Nur einzelne Einträge ändern (keine Kopie und kein Vergleich der ganzen Karte je Alarm – bei 2000 Paaren
    /// sonst Alarme × Einträge je Prüfung); die Ansicht (Alarmliste) auf dem Main-Thread einmal benachrichtigen.
    private func notify() {
        lock.lock()
        let schedule = !notifyPending
        notifyPending = true
        lock.unlock()
        guard schedule else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.notifyPending = false
            self.lock.unlock()
            self.objectWillChange.send()
        }
    }

    /// Ausgelassen: «seit» bleibt die erste ausgelassene Prüfung, der Grund wird der neueste.
    static func unchecked(_ map: [Int64: Unchecked], alarmId: Int64, reason: Reason, now: Int64) -> [Int64: Unchecked] {
        var copy = map
        copy[alarmId] = Unchecked(reason: reason, since: map[alarmId]?.since ?? now)
        return copy
    }

    /// Geprüft: Marke entfernen.
    static func checked(_ map: [Int64: Unchecked], alarmId: Int64) -> [Int64: Unchecked] {
        var copy = map
        copy[alarmId] = nil
        return copy
    }
}
