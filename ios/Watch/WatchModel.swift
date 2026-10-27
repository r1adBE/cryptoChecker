import Foundation
import Observation

/// Zustand der Uhr-App (Build 155): Wahl, letzte Kurse, Laden/Fehler. Lädt beim Öffnen und nach jeder Änderung.
@MainActor
@Observable
final class WatchModel {
    var settings = WatchStore.settings
    var quotes: [String: WatchQuote] = WatchStore.quotes().quotes
    var time: Int64 = WatchStore.quotes().time
    var loading = false
    var failed = false
    /// Eine Instanz je App (die Verbindung zum iPhone meldet neue Wahl hierher, `WatchLink`).
    static let shared = WatchModel()

    private init() {}

    /// Neue Wahl vom iPhone übernehmen.
    func reloadFromStore() {
        let fresh = WatchStore.settings
        guard fresh != settings else { return }
        settings = fresh
        Task { await refresh() }
    }

    func refresh() async {
        let coins = settings.shown
        loading = true
        let fresh = await WatchPriceSource.fetch(coins)
        if !fresh.isEmpty {
            let now = WatchStore.now()
            WatchStore.saveQuotes(fresh, time: now)
            quotes = fresh
            time = now
            failed = false
            WatchComplications.reload()
        } else {
            failed = !coins.isEmpty
        }
        loading = false
    }

    /// Änderung auf der Uhr: speichern, ans iPhone geben, neu laden.
    func change(_ next: WatchSettings) {
        var stamped = next
        stamped.time = WatchStore.now()
        settings = stamped
        WatchStore.settings = stamped
        WatchLink.shared.publish(stamped)
        WatchComplications.reload()
        Task { await refresh() }
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
