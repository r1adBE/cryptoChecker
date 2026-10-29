import BackgroundTasks
import Foundation
import os

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
/// `expirationHandler` (iOS ruft ihn auf einem beliebigen Thread). Thread-sicher und vom Compiler geprüft `Sendable`:
/// die Aufgabe (`BGTask`, nicht `Sendable`) liegt in einer Sperre und wird nur unter ihr angefasst. Wer `gate` gewinnt,
/// meldet — nur er nimmt die Sperre, sie wartet also nie auf einen anderen.
final class BackgroundTaskCompletion: Sendable {
    private let gate = OnceGate()
    private let task: OSAllocatedUnfairLock<any BackgroundRefreshTask>

    init(_ task: any BackgroundRefreshTask) {
        self.task = OSAllocatedUnfairLock(uncheckedState: task)
    }

    func complete(success: Bool) {
        guard gate.claim() else { return }
        task.withLock { $0.setTaskCompleted(success: success) }
    }
}

/// Hält den Arbeits-Task einer Hintergrund-Aufgabe für den `expirationHandler`, der schon VOR dem Task gesetzt
/// wird (thread-sicher; iOS ruft den Handler auf einem beliebigen Thread). Kommt der Abbruch, bevor der Task
/// eingetragen ist, wird er beim Eintragen sofort abgebrochen.
final class TaskCancelBox: Sendable {
    private struct State: Sendable {
        var task: Task<Void, Never>?
        var cancelled = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    func set(_ newTask: Task<Void, Never>) {
        let cancelNow = state.withLock { s -> Bool in
            if !s.cancelled { s.task = newTask }
            return s.cancelled
        }
        if cancelNow { newTask.cancel() }
    }

    func cancel() {
        let current = state.withLock { s -> Task<Void, Never>? in
            s.cancelled = true
            defer { s.task = nil }
            return s.task
        }
        current?.cancel()
    }
}

/// Zwischenstand für «Letzte Prüfungen» (thread-sicher): `.failed`, bis die Kurse gespeichert sind.
final class CheckResultBox: Sendable {
    private let value = OSAllocatedUnfairLock(initialState: CheckResult.failed)

    func set(_ result: CheckResult) {
        value.withLock { $0 = result }
    }

    func get() -> CheckResult {
        value.withLock { $0 }
    }
}

/// Lässt genau einen Aufruf durch (thread-sicher).
final class OnceGate: Sendable {
    private let claimed = OSAllocatedUnfairLock(initialState: false)

    /// true nur beim ersten Aufruf.
    func claim() -> Bool {
        claimed.withLock { taken in
            if taken { return false }
            taken = true
            return true
        }
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
