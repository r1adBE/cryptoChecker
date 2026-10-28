import Foundation
import Observation

/// Zustand der Uhr-App: Wahl, letzte Kurse je Coin, Laden/Fehler. Lädt beim Öffnen und nach jeder Änderung; ein neuer
/// Abruf bricht den laufenden ab, und ein Ergebnis zählt nur, solange es zur gezeigten Liste gehört (`WatchFetchGate`).
@MainActor
@Observable
final class WatchModel {
    var settings = WatchStore.settings
    var quotes: [String: WatchCached] = WatchStore.quotes()
    var loading = false
    var failed = false
    @ObservationIgnored private var gate = WatchFetchGate()
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    /// Eine Instanz je App (die Verbindung zum iPhone meldet neue Wahl hierher, `WatchLink`).
    static let shared = WatchModel()

    private init() {}

    /// Neue Wahl vom iPhone übernehmen.
    func reloadFromStore() {
        let fresh = WatchStore.settings
        guard fresh != settings else { return }
        settings = fresh
        refresh()
    }

    /// Abruf für die gezeigte Liste; ein laufender wird abgebrochen.
    func refresh() {
        let coins = settings.shown
        let run = gate.start()
        fetchTask?.cancel()
        loading = true
        fetchTask = Task {
            let fresh = await WatchPriceSource.fetch(coins)
            guard !Task.isCancelled, gate.accepts(run, asked: coins, shown: settings.shown) else { return }
            if !fresh.isEmpty {
                // Je Coin zusammengeführt: fehlende Coins behalten ihren letzten Kurs samt Zeit
                quotes = WatchStore.mergeQuotes(fresh, time: WatchStore.now())
                WatchComplications.reload()
            }
            failed = fresh.isEmpty && !coins.isEmpty
            loading = false
        }
    }

    /// Änderung auf der Uhr: mit nächster Änderungsnummer speichern, ans iPhone geben, neu laden.
    func change(_ next: WatchSettings) {
        let edited = next.nextRevision()
        settings = edited
        WatchStore.settings = edited
        WatchLink.shared.publish(edited)
        WatchComplications.reload()
        refresh()
    }

    func toggleMode() {
        var next = settings
        next.mode = settings.mode == .phone ? .own : .phone
        change(next)
    }

    func toggle(_ coin: String) {
        var next = settings
        next.own = WatchCoins.toggle(settings.own, coin)
        change(next)
    }
}
