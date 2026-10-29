import BackgroundTasks
import Foundation
import os

// Bedarfs-Entscheid nach dem Vormerken (Teil von `BackgroundRefresh`): Die Prüfung
// wartet auf WidgetKit und die Platte; mehrere Planungen kurz hintereinander können ihre Entscheide darum in anderer
// Reihenfolge liefern. Nur der Entscheid der neuesten Planung darf absagen oder nachholen.

/// Generationszähler der Planung (thread-sicher): Jede Planung zieht eine Nummer; ein Entscheid gilt nur, solange seine
/// Nummer die neueste ist. Vom Compiler geprüft `Sendable`: `lock` (NSLock) hält Ziehen sowie Prüfen-und-Handeln
/// zusammen (`action` ist nicht `Sendable` und läuft darunter); der Zähler liegt in `latest` und wird nur unter `lock`
/// gelesen oder geändert.
final class BackgroundDemandGate: Sendable {
    private let lock = NSLock()
    private let latest = OSAllocatedUnfairLock(initialState: 0)

    /// Neue Planung: ihre Nummer; ältere Entscheide gelten danach nicht mehr.
    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return latest.withLock { number in
            number += 1
            return number
        }
    }

    /// Führt `action` nur aus, wenn `generation` noch die neueste ist — unter der Sperre, damit keine neue Planung
    /// zwischen Prüfen und Handeln ihre Nummer zieht. - Returns: true = ausgeführt.
    @discardableResult
    func ifCurrent(_ generation: Int, _ action: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard generation == latest.withLock({ $0 }) else { return false }
        action()
        return true
    }
}

/// Wege des Bedarfs-Entscheids (Tests: Fälschungen).
struct BackgroundDemandActions: Sendable {
    /// Einstellungen frisch beim Entscheid (nicht die des Aufrufers, die inzwischen veraltet sein können).
    var loadSettings: @Sendable () -> AppSettings = { SharedStorage.loadSettings() }
    var workNeeded: @Sendable (AppSettings, @escaping @Sendable (Bool) -> Void) -> Void = {
        BackgroundRefresh.workNeeded(settings: $0, completion: $1)
    }
    /// Ist eine Anfrage vorgemerkt?
    var hasPending: @Sendable (@escaping @Sendable (Bool) -> Void) -> Void = { done in
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
            done(requests.contains { $0.identifier == BackgroundRefresh.refreshId })
        }
    }
    var cancel: @Sendable () -> Void = {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: BackgroundRefresh.refreshId)
    }
    /// Vormerken nachholen (mit den frischen Einstellungen); liefert den Fehler, falls es scheiterte.
    var submit: @Sendable (AppSettings) -> Error?
    /// Gescheitertes Vormerken festhalten — ausserhalb der Sperre (schreibt «Letzte Prüfungen»).
    var submitFailed: @Sendable (Error) -> Void = { BackgroundRefresh.logSubmitError($0) }
}

extension BackgroundRefresh {
    static let demandGate = BackgroundDemandGate()

    /// Bedarf prüfen und danach — nur wenn `generation` dann noch die neueste Planung ist — ohne Bedarf absagen bzw.
    /// mit Bedarf, aber ohne vorgemerkte Anfrage (z. B. von einem älteren Entscheid abgesagt), neu vormerken.
    static func followDemand(generation: Int, gate: BackgroundDemandGate = demandGate,
                             actions: BackgroundDemandActions) {
        actions.workNeeded(actions.loadSettings()) { needed in
            guard needed else {
                if gate.ifCurrent(generation, actions.cancel) {
                    AppLog.background.info("Hintergrund-Aktualisierung abgesagt: nichts zu prüfen")
                }
                return
            }
            actions.hasPending { pending in
                guard !pending else { return }
                let fresh = actions.loadSettings()
                var failure: Error?
                let submitted = gate.ifCurrent(generation) { failure = actions.submit(fresh) }
                guard submitted else { return }
                if let failure { return actions.submitFailed(failure) }
                AppLog.background.notice("Hintergrund-Aktualisierung fehlte trotz Bedarf – neu vorgemerkt")
            }
        }
    }

    /// Erstes Vormerken einer nicht ersetzenden Planung — es folgt erst nach der
    /// asynchronen Abfrage der vorgemerkten Anfrage, darum nur, solange `generation` die neueste Planung ist; sonst
    /// ersetzte es die Anfrage einer neueren (z. B. neues Intervall). Protokoll ausserhalb der Sperre (wie `followDemand`).
    /// - Returns: true = vorgemerkt bzw. versucht; false = von einer neueren Planung überholt.
    @discardableResult
    static func submitIfCurrent(generation: Int, gate: BackgroundDemandGate = demandGate,
                                submit: () -> Error?,
                                submitFailed: (Error) -> Void = { BackgroundRefresh.logSubmitError($0) }) -> Bool {
        var failure: Error?
        guard gate.ifCurrent(generation, { failure = submit() }) else { return false }
        if let failure { submitFailed(failure) }
        return true
    }
}
