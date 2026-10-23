import Foundation

/// Single-Flight der Adress-Aktualisierung (wie Android `singleFlightRefresh`): eine Runde (Lesen, Laden, Speichern)
/// zur Zeit. Vordergrund wartet auf eine laufende Runde und startet danach eine eigene, die frisch liest (eben
/// geladene Adressen sind nicht mehr fällig, nichts doppelt); Hintergrund kehrt sofort zurück, wenn schon eine läuft.
@MainActor
final class AddressRefreshGate {
    private var current: (id: Int, task: Task<Void, Never>)?
    private var nextId = 0

    /// Läuft gerade eine Runde?
    var isRunning: Bool { current != nil }

    /// - Parameter background: Hintergrund/Widget: nicht warten.
    /// - Returns: false, wenn der Hintergrund wegen einer laufenden Runde nichts tat.
    @discardableResult
    func run(background: Bool, _ body: @escaping @MainActor @Sendable () async -> Void) async -> Bool {
        while let running = current {
            if background { return false }
            await running.task.value
        }
        nextId += 1
        let id = nextId
        // Die Runde räumt sich selbst ab, bevor sie endet: Wartende sehen danach keine erledigte Runde mehr
        let task = Task { @MainActor in
            await body()
            self.finish(id)
        }
        current = (id: id, task: task)
        // Abbruch des Startenden (z. B. Ablauf der Hintergrundzeit) bricht die Runde ab
        await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
        return true
    }

    private func finish(_ id: Int) {
        if current?.id == id { current = nil }
    }
}
