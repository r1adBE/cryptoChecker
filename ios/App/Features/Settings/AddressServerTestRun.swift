import SwiftUI

/// «Testen» des eigenen Servers (`SettingsAddressServerCard`): Zustand und laufender Abruf (`load`). Jeder Start bekommt
/// eine Kennung; ein spätes Ergebnis eines verworfenen Tests (Eingabe geändert, gespeichert) überschreibt nie einen
/// neueren — auch wenn der Abruf den Abbruch nicht bemerkt. Wie Android `AddressServerTestRun`.
@MainActor
final class AddressServerTestRun: ObservableObject {
    typealias State = SettingsAddressServerCard.TestState

    @Published private(set) var state: State = .idle
    private var task: Task<Void, Never>?
    private var generation = 0
    private let load: @Sendable (String) async -> Int64?

    init(load: @escaping @Sendable (String) async -> Int64? = { await AddressServerTestRun.liveLoad($0) }) {
        self.load = load
    }

    /// Blockhöhe abfragen (nennt keine Adresse); Erfolg hebt Sperre/Pause des Hosts auf.
    nonisolated static func liveLoad(_ url: String) async -> Int64? {
        let height = await WatchedAddressSource.testBtcServer(url)
        if height != nil { await WatchedAddressSource.shared.clearServerPauses(url) }
        return height
    }

    /// Startet einen Test, wenn keiner läuft; die Aufgabe nur für Tests.
    @discardableResult
    func start(_ url: String) -> Task<Void, Never>? {
        guard !state.isRunning else { return nil }
        generation += 1
        let mine = generation
        state = .running(url)
        let load = self.load
        let task = Task { [weak self] in
            let height = await load(url)
            self?.finish(url, height: height, generation: mine)
        }
        self.task = task
        return task
    }

    /// Eingabe geändert oder gespeichert: Ergebnis und laufenden Test verwerfen.
    func reset() {
        generation += 1
        task?.cancel()
        task = nil
        state = .idle
    }

    /// Ergebnis nur, wenn es zum letzten Start gehört und der noch läuft; dann für VoiceOver angesagt.
    private func finish(_ url: String, height: Int64?, generation mine: Int) {
        // Inzwischen geänderte Eingabe, Speichern oder ein neuerer Test: Ergebnis verwerfen
        guard mine == generation, state == .running(url) else { return }
        let result: State = height.map { .ok(url, $0) } ?? .failed(url)
        state = result
        AccessibilityNotification.Announcement(SettingsAddressServerCard.resultText(result)).post()
    }
}
