import Combine
import Foundation
import os

/// Alarme, die bei der Prüfung ausgelassen werden mussten (kein Devisenkurs für die Alarmwährung,
/// keine Kerzen für «Nahe am Hoch/Tief» bzw. den Volumen-Spike): «nicht geprüft seit …» je Alarm-Id —
/// wie `AlarmCheckStatus.kt`. Bewusst nur im Speicher dieses Prozesses (nur dieses Gerät, nicht in der
/// Sicherung, nicht im Snapshot); prüft das Widget, sieht die App das nicht. Nach einem Neustart leer.
final class AlarmCheckStatus: ObservableObject, Sendable {
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

    private struct State: Sendable {
        var map: [Int64: Unchecked] = [:]
        /// Benachrichtigung der Ansicht schon unterwegs: viele Änderungen in einem Durchlauf ergeben eine.
        var notifyPending = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    /// Nicht geprüft? nil = zuletzt geprüft (oder noch nie ausgelassen).
    func unchecked(_ alarmId: Int64) -> Unchecked? {
        state.withLock { $0.map[alarmId] }
    }

    func markUnchecked(_ alarmId: Int64, reason: Reason, now: Int64) {
        let changed = state.withLock { s -> Bool in
            let old = s.map[alarmId]
            let next = Unchecked(reason: reason, since: old?.since ?? now)
            let changed = old != next
            if changed { s.map[alarmId] = next }
            return changed
        }
        if changed { notify() }
    }

    func markChecked(_ alarmId: Int64) {
        let changed = state.withLock { $0.map.removeValue(forKey: alarmId) != nil }
        if changed { notify() }
    }

    /// Nur einzelne Einträge ändern (keine Kopie und kein Vergleich der ganzen Karte je Alarm – bei 2000 Paaren
    /// sonst Alarme × Einträge je Prüfung); die Ansicht (Alarmliste) auf dem Main-Thread einmal benachrichtigen.
    private func notify() {
        let schedule = state.withLock { s -> Bool in
            defer { s.notifyPending = true }
            return !s.notifyPending
        }
        guard schedule else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.state.withLock { $0.notifyPending = false }
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
