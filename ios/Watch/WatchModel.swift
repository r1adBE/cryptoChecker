import Foundation
import Observation

/// Zustand der Uhr-App: Wahl, letzte Kurse je Coin, Portfolio-Wert vom iPhone, Laden/Fehler. Kurse vom iPhone übernimmt
/// sie (`takePhoneQuotes`); selbst abgefragt wird nur, was nach `WatchPhoneQuotes.selfFetch` fällig ist — beim Öffnen,
/// nach jeder Änderung, auf «Aktualisieren» und offen im Intervall des iPhones. Ein neuer Abruf bricht den laufenden ab,
/// und ein Ergebnis zählt nur, solange es zur gezeigten Liste gehört (`WatchFetchGate`).
/// Die Wahl wird nur hier geändert und gespeichert, auf dem MainActor: eigene Änderungen (`change`) und der Stand vom
/// iPhone (`takeFromPhone`) laufen so nacheinander, keiner überschreibt den anderen mit einem älteren Stand. Wie Android
/// `WatchStore.updateSettings` (dort unter einer Sperre).
@MainActor
@Observable
final class WatchModel {
    var settings = WatchStore.settings
    var quotes: [String: WatchCached] = WatchStore.quotes()
    var portfolio: WatchPortfolio? = WatchStore.portfolio()
    var loading = false
    var failed = false
    /// Ein Abruf an der Quelle läuft (Kreisel im Knopf «Aktualisieren»); nach dem Knopf mindestens `tapAckNanos` lang.
    /// Nichts zu holen (nur Paare vom iPhone, frische Kurse): kein Kreisel. Wie Wear `WatchFetcher.fetching`.
    var fetching = false
    @ObservationIgnored private static let tapAckNanos: UInt64 = 1_000_000_000
    @ObservationIgnored private var gate = WatchFetchGate()
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    /// Eine Instanz je App (die Verbindung zum iPhone meldet neue Wahl hierher, `WatchLink`).
    static let shared = WatchModel()

    private init() {}

    /// Anwendungskontext des iPhones (`WatchLink`): erst die Wahl (sonst fielen Kurse neuer Coins weg), dann Kurse und
    /// Portfolio-Wert, zuletzt — bei anderer Wahl — neu laden (nur, was die Kurse vom iPhone nicht abdecken).
    func takeFromPhone(_ json: String?, quotes: String?, portfolio: String?) {
        let changed = takeSettings(json)
        takePhoneQuotes(quotes)
        takePortfolio(portfolio)
        if changed { refreshDue() }
    }

    /// Wahl und eigene Liste bei höherer oder gleicher Änderungsnummer, iPhone-Liste, Kursquelle und Intervall immer;
    /// gegen den aktuellen Stand zusammengeführt und gespeichert. true = geändert.
    private func takeSettings(_ json: String?) -> Bool {
        guard let remote = WatchSettings.fromJson(json) else { return false }
        let merged = WatchSettings.merge(settings, remote, remoteIsPhone: true)
        guard merged != settings else { return false }
        settings = merged
        WatchStore.settings = merged
        WatchComplications.reload()
        return true
    }

    /// Kurse vom iPhone (`WatchLink`): je Coin übernehmen, falls nicht älter als der gespeicherte Stand.
    private func takePhoneQuotes(_ json: String?) {
        guard let list = WatchPhoneQuotes.fromJson(json), let merged = WatchStore.takePhoneQuotes(list) else { return }
        quotes = merged
        WatchComplications.reload()
    }

    /// Portfolio-Wert vom iPhone (`WatchLink`): speichern bzw. entfernen.
    private func takePortfolio(_ json: String?) {
        guard let message = WatchPortfolio.fromJson(json) else { return }
        WatchStore.takePortfolio(message)
        var next: WatchPortfolio?
        if case .value(let p) = message { next = p }
        guard next != portfolio else { return }
        portfolio = next
        WatchComplications.reload()
    }

    /// iPhone-App nicht mehr installiert (`WatchLink`): Wahl wie ohne iPhone (`WatchSettings.forgetPhone`), Kurse nicht
    /// mehr vom iPhone (`WatchStore.forgetPhoneQuotes`), Portfolio-Wert weg — wie Wear OS bei gelöschten Einträgen.
    func forgetPhone() {
        let next = WatchSettings.forgetPhone(settings)
        let changed = next != settings
        let hadPhone = !WatchStore.phoneCoins.isEmpty || WatchStore.phoneTime > 0 || portfolio != nil
        guard changed || hadPhone else { return }
        if changed {
            settings = next
            WatchStore.settings = next
        }
        WatchStore.forgetPhoneQuotes()
        WatchStore.takePortfolio(.clear)
        portfolio = nil
        WatchComplications.reload()
        if changed { refreshDue() }
    }

    /// Öffnen, neue Liste, andere Kursquelle: nur, was fällig ist — mit Kursen vom iPhone kein Abruf bei jedem Blick
    /// auf die Uhr; mit «Immer selbst abrufen» alles. Wie Wear `WatchApp` (`refresh(force = source == SELF)`).
    func refreshDue() {
        refresh(force: settings.source == .alwaysSelf)
    }

    /// Abruf für die gezeigte Liste (`force` = «Aktualisieren»: alles, auch Coins vom iPhone — sonst nur, was fällig
    /// ist); ein laufender wird abgebrochen. Ohne Fälliges kein Abruf, kein Kreisel und kein Fehler.
    func refresh(force: Bool) {
        let coins = settings.shown
        let run = gate.start()
        let startedAt = WatchStore.now()
        fetchTask?.cancel()
        let need = WatchPhoneQuotes.selfFetch(coins, times: quotes.mapValues(\.time), phoneCoins: WatchStore.phoneCoins,
                                              usePhone: WatchPhoneQuotes.usePhone(
                                                source: settings.source, connected: WatchLink.shared.phoneReachable,
                                                lastDelivery: WatchStore.phoneTime, now: startedAt,
                                                intervalMinutes: settings.interval),
                                              intervalMinutes: settings.interval, now: startedAt, force: force)
        guard !need.isEmpty else {
            failed = false
            loading = false
            fetching = false
            return
        }
        loading = true
        fetching = true
        fetchTask = Task {
            let fresh = await WatchPriceSource.fetch(need)
            guard !Task.isCancelled, gate.accepts(run, asked: coins, shown: settings.shown) else { return }
            // Knopf: Kreisel mindestens kurz, damit der Tipp sichtbar ankommt
            if force {
                let elapsed = UInt64(max(0, WatchStore.now() - startedAt)) * 1_000_000
                if elapsed < Self.tapAckNanos { try? await Task.sleep(nanoseconds: Self.tapAckNanos - elapsed) }
                guard !Task.isCancelled else { return }
            }
            if !fresh.isEmpty {
                // Je Coin zusammengeführt: fehlende Coins behalten ihren letzten Kurs samt Zeit, ebenso Coins, die
                // ein später gestarteter Abruf (Komplikation) schon geschrieben hat
                if let merged = WatchStore.mergeQuotes(fresh, startedAt: startedAt) { quotes = merged }
                WatchComplications.reload()
            }
            failed = fresh.isEmpty
            loading = false
            fetching = false
        }
    }

    /// Änderung auf der Uhr: nach dem zuletzt empfangenen Stand des iPhones mit max(Uhr, iPhone) + 1 speichern
    /// (`WatchSettings.change`), den aktuellen Stand ans iPhone geben, neu laden.
    func change(_ edit: (inout WatchSettings) -> Void) {
        settings = WatchSettings.change(settings, remote: WatchLink.shared.phoneSettings, isPhone: false, edit)
        WatchStore.settings = settings
        WatchLink.shared.publish(settings)
        WatchComplications.reload()
        refreshDue()
    }

    func toggleMode() {
        // Ziel nach der gezeigten Wahl, nicht nach einem neueren Stand des iPhones
        let target: WatchSettings.Mode = settings.mode == .phone ? .own : .phone
        change { $0.mode = target }
    }

    func toggle(_ coin: String) {
        change { $0.own = WatchCoins.toggle($0.own, coin) }
    }
}
