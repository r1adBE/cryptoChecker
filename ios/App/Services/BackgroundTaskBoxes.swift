import BackgroundTasks
import Foundation

// Thread-sichere Hilfen der Hintergrund-Aufgabe (`BackgroundRefresh`): Ende genau einmal melden, Task abbrechen,
// Zwischenstand merken.

/// Meldet das Ende einer Hintergrund-Aufgabe genau einmal — vom Ablauf selbst oder aus dem
/// `expirationHandler` (iOS ruft ihn auf einem beliebigen Thread). Thread-sicher.
final class BackgroundTaskCompletion: @unchecked Sendable { // @unchecked: BGTask nur einmal über `gate` (OnceGate, NSLock) abgeschlossen
    private let gate = OnceGate()
    private let task: BGTask

    init(_ task: BGTask) {
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
