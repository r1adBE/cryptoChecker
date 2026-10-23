import BackgroundTasks
import Foundation

// Thread-sichere Hilfen der Hintergrund-Aufgabe (`BackgroundRefresh`): Ende genau einmal melden, Task abbrechen,
// Zwischenstand merken.

/// Was `BackgroundRefresh.handleRefresh` von der Hintergrund-Aufgabe braucht: Ablauf-Handler setzen und
/// das Ende melden. In der App ist es die `BGTask`, in Tests eine Fälschung, die beides festhält.
protocol BackgroundRefreshTask: AnyObject {
    /// Setzt den `expirationHandler` (eigener Name: kein Zusammenstoss mit dem Setter der Eigenschaft).
    func onExpiration(_ handler: @escaping () -> Void)
    func setTaskCompleted(success: Bool)
}

extension BGTask: BackgroundRefreshTask {
    func onExpiration(_ handler: @escaping () -> Void) { expirationHandler = handler }
}

/// Meldet das Ende einer Hintergrund-Aufgabe genau einmal — vom Ablauf selbst oder aus dem
/// `expirationHandler` (iOS ruft ihn auf einem beliebigen Thread). Thread-sicher.
final class BackgroundTaskCompletion: @unchecked Sendable { // @unchecked: BGTask nur einmal über `gate` (OnceGate, NSLock) abgeschlossen
    private let gate = OnceGate()
    private let task: any BackgroundRefreshTask

    init(_ task: any BackgroundRefreshTask) {
        self.task = task
    }

    func complete(success: Bool) {
        if gate.claim() { task.setTaskCompleted(success: success) }
    }
}

/// Hält den Arbeits-Task einer Hintergrund-Aufgabe für den `expirationHandler`, der schon VOR dem Task gesetzt
/// wird (thread-sicher; iOS ruft den Handler auf einem beliebigen Thread). Kommt der Abbruch, bevor der Task
/// eingetragen ist, wird er beim Eintragen sofort abgebrochen.
final class TaskCancelBox: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var cancelled = false

    func set(_ newTask: Task<Void, Never>) {
        lock.lock()
        let cancelNow = cancelled
        if !cancelNow { task = newTask }
        lock.unlock()
        if cancelNow { newTask.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let current = task
        task = nil
        lock.unlock()
        current?.cancel()
    }
}

/// Zwischenstand für «Letzte Prüfungen» (thread-sicher): `.failed`, bis die Kurse gespeichert sind.
final class CheckResultBox: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var value: CheckResult = .failed

    func set(_ result: CheckResult) {
        lock.lock()
        defer { lock.unlock() }
        value = result
    }

    func get() -> CheckResult {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// Lässt genau einen Aufruf durch (thread-sicher).
final class OnceGate: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var claimed = false

    /// true nur beim ersten Aufruf.
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if claimed { return false }
        claimed = true
        return true
    }
}

/// Ablauf eines `expirationHandler`: zuerst die Arbeit abbrechen und iOS das Ende melden — so wartet
/// `setTaskCompleted` nie auf Datei-Abstimmung (z. B. «Letzte Prüfungen», das Widget hält die Datei). Protokoll und neues
/// Vormerken laufen danach abseits des Aufrufers (`afterwards`); hält iOS die App vorher an, holt der Task sie beim
/// nächsten Aufwachen nach.
struct BackgroundExpiry {
    let cancel: () -> Void
    let complete: () -> Void
    let afterwards: @Sendable () -> Void
    /// Startet `afterwards` abseits des Aufrufers (Tests: Fälschung, die nur festhält).
    var detach: (@escaping @Sendable () -> Void) -> Void = { work in
        _ = Task.detached(priority: .utility) { work() }
    }

    func run() {
        cancel()
        complete()
        detach(afterwards)
    }
}
