import Foundation
import Observation

/// Zustand der Uhr-App: Wahl, letzte Kurse je Coin, Laden/Fehler. Lädt beim Öffnen und nach jeder Änderung; ein neuer
/// Abruf bricht den laufenden ab, und ein Ergebnis zählt nur, solange es zur gezeigten Liste gehört (`WatchFetchGate`).
/// Die Wahl wird nur hier geändert und gespeichert, auf dem MainActor: eigene Änderungen (`change`) und der Stand vom
/// iPhone (`takeFromPhone`) laufen so nacheinander, keiner überschreibt den anderen mit einem älteren Stand. Wie Android
/// `WatchStore.updateSettings` (dort unter einer Sperre).
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

    /// Stand des iPhones (`WatchLink`): Wahl und eigene Liste bei höherer oder gleicher Änderungsnummer, die
    /// iPhone-Liste immer; gegen den aktuellen Stand zusammengeführt, gespeichert und neu geladen.
    func takeFromPhone(_ json: String?) {
        guard let remote = WatchSettings.fromJson(json) else { return }
        let merged = WatchSettings.merge(settings, remote, remoteIsPhone: true)
        guard merged != settings else { return }
        settings = merged
        WatchStore.settings = merged
        WatchComplications.reload()
        refresh()
    }

    /// Abruf für die gezeigte Liste; ein laufender wird abgebrochen.
    func refresh() {
        let coins = settings.shown
        let run = gate.start()
        let startedAt = WatchStore.now()
        fetchTask?.cancel()
        loading = true
        fetchTask = Task {
            let fresh = await WatchPriceSource.fetch(coins)
            guard !Task.isCancelled, gate.accepts(run, asked: coins, shown: settings.shown) else { return }
            if !fresh.isEmpty {
                // Je Coin zusammengeführt: fehlende Coins behalten ihren letzten Kurs samt Zeit, ebenso Coins, die
                // ein später gestarteter Abruf (Komplikation) schon geschrieben hat
                if let merged = WatchStore.mergeQuotes(fresh, startedAt: startedAt) { quotes = merged }
                WatchComplications.reload()
            }
            failed = fresh.isEmpty && !coins.isEmpty
            loading = false
        }
    }

    /// Änderung auf der Uhr: mit nächster Änderungsnummer speichern, den aktuellen Stand ans iPhone geben, neu laden.
    func change(_ next: WatchSettings) {
        settings = next.nextRevision()
        WatchStore.settings = settings
        WatchLink.shared.publish(settings)
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
