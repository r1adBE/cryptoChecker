import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// App vorne/im Hintergrund und Live-Kurse per WebSocket in AppData (Strom starten/stoppen, Kurse übernehmen).
// Eigene Datei, weil der Live-Strom ein eigener Ablauf ist.

extension AppData {
    // MARK: App vorne oder im Hintergrund

    func setAppActive(_ active: Bool) {
        appActive = active
        // In den Hintergrund: zusammengefasste Live-Kurse sofort schreiben (vor dem Abwarten der Schreib-Warteschlange)
        if !active { flushPriceSave() }
        // Lesen im Hintergrund: Der Haupt-Thread wartet nicht auf Entschlüsseln oder das Widget (`NSFileCoordinator`)
        if active { reloadFromDiskInBackground() }
        updateLiveStream()
    }

    // MARK: Live-Kurse per WebSocket (solange die Merkliste offen ist)

    /// App aktiv, Einstellung und Netz an den Strom weitergeben.
    func updateLiveStream() {
        let active = appActive
        let enabled = settings.liveWebSocket
        let isOnline = online
        Task { await LivePriceStream.shared.setEnvironment(appActive: active, enabled: enabled, online: isOnline) }
    }

    /// Vom Strom: neuer Anzeige-Stand (nil = Kurse unverändert), Börsen mit Daten, Zeit je Paar.
    func setLive(quotes: [Int64: LiveQuote]?, exchanges: [String], liveIds: Set<Int64>, tickAt: [Int64: Int64]) {
        if let quotes { LivePrices.shared.apply(quotes) }
        if exchanges != refreshState.liveExchanges { refreshState.liveExchanges = exchanges }
        if liveIds != refreshState.liveWatchIds { refreshState.liveWatchIds = liveIds }
        liveTickAt = tickAt
    }

    /// Strom beendet: wieder die gespeicherten Kurse zeigen.
    func clearLive() {
        LivePrices.shared.clear()
        if !refreshState.liveExchanges.isEmpty { refreshState.liveExchanges = [] }
        if !refreshState.liveWatchIds.isEmpty { refreshState.liveWatchIds = [] }
        liveTickAt = [:]
    }

    /// Live-Kurse gesammelt speichern und auswerten — derselbe Weg wie eine Aktualisierung
    /// (`PriceRefresher` mit `liveQuotes`): Alarme ohne doppelte Meldungen, Kurs-Mitteilungen.
    /// Läuft gerade eine Aktualisierung oder hält ein anderer Schreiber das Gate: false, der Strom versucht es beim
    /// nächsten Mal (wie Android `tryWithLock`).
    func applyLive(_ quotes: [Int64: LiveQuote], reloadWidgets: Bool) async -> Bool {
        // Läuft eine Aktualisierung (alle oder ein Paar) oder übernimmt gerade jemand Kurse: nicht anstellen — der
        // Puffer behält je Paar nur den neuesten Kurs, der Strom versucht es beim nächsten Mal (zusammengeführt)
        guard !refreshState.refreshing, refreshState.refreshingWatchIds.isEmpty, priceWriteGate.tryAcquire() else {
            return false
        }
        defer { priceWriteGate.release() }
        let now = EpochTime.nowMillis
        let rolling = !settings.changeBasis.isDay
        let stampCurrent = changeStamp == ChangeBasisMath.stamp(settings.changeBasis, now: now)
        var byId: [Int64: Watch] = [:]
        for w in snapshot.watches { byId[w.id] = w }
        var chosen: [Int64: LiveQuote] = [:]
        for (id, quote) in quotes {
            guard let watch = byId[id] else { continue }
            // Kurs von vor «Paar bearbeiten»: gehört zum alten Paar
            if let changedAt = pairChangedAt[id], quote.time < changedAt { continue }
            let change = LiveRules.chooseChange(
                rollingBasis: rolling, stampCurrent: stampCurrent,
                exchangeRolling: LiveExchange.from(marketKey: watch.marketKey)?.rollingChange == true,
                live: quote.change24h, existing: watch.change24h)
            chosen[id] = LiveQuote(price: quote.price, change24h: change, time: quote.time, tradeTime: quote.tradeTime)
        }
        guard !chosen.isEmpty else { return true }
        let resetBefore = resetGeneration
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, liveQuotes: chosen)
        // Während der Abfrage «App zurücksetzen»: Ergebnis gehört zum gelöschten Stand
        guard resetGeneration == resetBefore else { return true }
        // Zeit zuerst (Widget-Kopfzeile), die Dauer des letzten Durchlaufs bleibt
        // Live-Takte setzen nur die Uhrzeit der Anzeige — weder `lastAlarmCheckAt` (keine volle Prüfung aller Alarme)
        // noch `lastFullRefreshAt` (sonst sperrte jeder Takt «Aktualisieren» von Hand, `RefreshDebounce`)
        if reloadWidgets && outcome.checked > outcome.failed {
            SharedStorage.lastRefreshAt = EpochTime.nowMillis
        }
        apply(outcome, full: false, reloadWidgets: reloadWidgets, live: true)
        return true
    }

    /// Speichert der Strom bzw. ein anderer Schreiber gerade (höchstens ein paar hundert ms): der Reihe nach abwarten
    /// (`PriceWriteGate`, ohne Abfragen in Schleife), damit nicht zwei Durchläufe denselben Stand auswerten
    /// (Alarme doppelt) — wie der Mutex in Android.
    /// - Returns: false = abgebrochen, bevor das Gate an der Reihe war (der Aufrufer lässt den Durchlauf aus).
    @discardableResult
    func waitForLiveApply() async -> Bool {
        await priceWriteGate.waitUntilFree()
    }

    /// Paare mit frischem Live-Kurs, die die REST-Abfrage diesmal auslässt (`LiveRules.skipRest`).
    func liveCoveredIds() -> Set<Int64> {
        guard !liveTickAt.isEmpty else { return [] }
        let now = EpochTime.nowMillis
        let rolling = !settings.changeBasis.isDay
        let stampUnchanged = changeStamp == ChangeBasisMath.stamp(settings.changeBasis, now: now)
        // Volumen-, Funding- und Open-Interest-Alarme hängen nicht am Live-Kurs (`applyLive` prüft sie
        // nicht): solche Paare weiter per REST abfragen, sonst bliebe der Alarm bei offener Merkliste stumm
        let volumeIds = Set(snapshot.alarms.filter {
            $0.enabled && ($0.condition == .VOLUME_SPIKE || $0.condition.isDerivatives)
        }.map(\.watchId))
        var ids = Set<Int64>()
        for w in snapshot.watches where !w.disabled && !volumeIds.contains(w.id) && LiveRules.skipRest(
            lastTickAt: liveTickAt[w.id], now: now, rollingBasis: rolling, stampUnchanged: stampUnchanged,
            exchangeRolling: LiveExchange.from(marketKey: w.marketKey)?.rollingChange == true) {
            ids.insert(w.id)
        }
        return ids
    }
}
