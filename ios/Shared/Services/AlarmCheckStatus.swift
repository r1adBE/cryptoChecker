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

    func markUnchecked(_ alarmId: Int64, reason: Reason, now: Int64) {
        update { Self.unchecked($0, alarmId: alarmId, reason: reason, now: now) }
    }

    func markChecked(_ alarmId: Int64) {
        update { Self.checked($0, alarmId: alarmId) }
    }

    /// Ändern; bei einer Änderung die Ansicht (Alarmliste) auf dem Main-Thread benachrichtigen.
    private func update(_ change: ([Int64: Unchecked]) -> [Int64: Unchecked]) {
        lock.lock()
        let next = change(map)
        let changed = next != map
        map = next
        lock.unlock()
        guard changed else { return }
        DispatchQueue.main.async { [weak self] in self?.objectWillChange.send() }
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
