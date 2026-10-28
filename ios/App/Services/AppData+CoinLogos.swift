import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// Coin-Logos in AppData: Abgleich beim Start und beim Aktualisieren, Wiederholung nach Netzfehlern und nach Pausen der
// Bild-Server (`CoinLogoRetryState`), TradFi-Paare.
// Eigene Datei, weil der Logo-Abgleich mit den übrigen Daten nichts teilt.

// MARK: Coin-Logos

extension AppData {
    /// Lädt im Hintergrund die Logos aller Coins der Rangliste, die noch fehlen (nie einzeln,
    /// damit CoinGecko keine Merkliste ablesen kann) — sofern Logos irgendwo gebraucht werden.
    /// Kamen neue dazu und sind sie für Widgets an, zeichnen die Widgets neu.
    func startCoinLogoSync() {
        Task { await syncCoinLogos() }
    }

    /// Ein Abgleich (wie Android `CoinLogoRepository.startSync`): läuft nur einmal gleichzeitig; kommt
    /// währenddessen ein weiterer Auftrag (z. B. Schalter eingeschaltet), läuft er danach noch einmal.
    /// Scheiterte er am Netz, holt `retryCoinLogosIfPending` ihn mit wachsendem Abstand nach.
    func syncCoinLogos() async {
        // Ein Abgleich startet jetzt: ein geplantes Nachholen nach der Pause entfällt (der Abgleich plant neu)
        CoinLogoRetryState.pauseTask?.cancel()
        CoinLogoRetryState.pauseTask = nil
        guard CoinLogoUse.needed(settings) || settings.watchlistNames else {
            // Nur «inkl. Alpha-Tokens»: allein die Alpha-Liste (ohne Rangliste und Bilder), sonst nichts
            if CoinLogos.alphaOnly(logos: false, names: false, includeAlpha: settings.includeAlphaTokens) {
                await CoinLogoStore.shared.refreshAlphaOnly()
                // Alpha-Kennzeichen sofort in Merkliste und Auswahl
                objectWillChange.send()
            }
            return
        }
        if CoinLogoRetryState.syncing {
            CoinLogoRetryState.rerun = true
            return
        }
        CoinLogoRetryState.syncing = true
        repeat {
            CoinLogoRetryState.rerun = false
            await runCoinLogoSync()
        } while CoinLogoRetryState.rerun
        let pending = await CoinLogoStore.shared.retryPending
        CoinLogoRetryState.noteResult(failedForNetwork: pending)
        CoinLogoRetryState.syncing = false
        // Bei scheinbar vorhandenem Netz gescheitert (z. B. direkt nach der Installation): nachholen
        if pending { retryCoinLogosIfPending(online: NetworkStatus.shared.isOnline) }
        await schedulePauseRetry()
    }

    /// Bremste ein Bild-Server (`CoinLogoStore.imagesPausedUntil`): am Ende seiner Pause einmal `syncCoinLogos`
    /// (Schalter, Fristen und Pausen gelten dort wie sonst) — ein Task im App-Prozess, ohne Hintergrund-Aufgabe; ein
    /// früher startender Abgleich ersetzt ihn. Nichts gebremst: kein Task. Wie Android `CoinLogoSync.schedulePauseRetry`.
    private func schedulePauseRetry() async {
        let until = await CoinLogoStore.shared.imagesPausedUntil
        CoinLogoRetryState.pauseTask?.cancel()
        CoinLogoRetryState.pauseTask = nil
        guard let wait = CoinLogoRetry.pauseRetryDelayMillis(pausedUntil: until, now: CoinLogoRetryState.nowMillis)
        else { return }
        CoinLogoRetryState.pauseTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
            if Task.isCancelled { return }
            // Vor dem Start austragen: `syncCoinLogos` bricht sonst sich selbst ab
            CoinLogoRetryState.pauseTask = nil
            await syncCoinLogos()
        }
    }

    private func runCoinLogoSync() async {
        let logos = CoinLogoUse.needed(settings)
        guard logos || settings.watchlistNames else { return }
        // 1. Binance- und Alpha-Liste vor allem anderen (vorher kamen Namen und «α» erst nach Paarlisten,
        // Nasdaq-Liste und allen Seiten der Rangliste): Namen und Alpha-Kennzeichen sofort
        if await CoinLogoStore.shared.refreshLists() { objectWillChange.send() }
        let tradFiChanged = await refreshTradFiPairs()
        // Aktiennamen nur mit «Namen anzeigen» (Nasdaq-Symbolliste, höchstens wöchentlich)
        let stockNames = settings.watchlistNames ? await CoinLogoStore.shared.refreshStockNames() : false
        let added = await CoinLogoStore.shared.syncAll(images: logos)
        if (added > 0 || tradFiChanged || stockNames) && settings.widgetCoinLogos {
            WidgetCenter.shared.reloadTimelines(ofKind: SharedStorage.watchlistWidgetKind)
            WidgetCenter.shared.reloadTimelines(ofKind: SharedStorage.singleWidgetKind)
        }
    }

    /// Einen am fehlenden Netz gescheiterten Logo-Abgleich nachholen (z. B. kein Internet direkt nach
    /// der Installation) — wie Android `CoinLogoSync.watchConnectivity`: beim Wechsel auf «online» und
    /// nach einem Fehlschlag bei scheinbar vorhandenem Netz, mit wachsendem Abstand
    /// (`CoinLogoRetry.retryDelayMillis`, 5 s … 15 Min., auch über Netzwechsel hinweg). Offline hält
    /// das Nachholen an; ein erfolgreicher Abgleich beendet es. Das Öffnen der App holt ohnehin nach.
    func retryCoinLogosIfPending(online: Bool) {
        guard online else {
            CoinLogoRetryState.task?.cancel()
            CoinLogoRetryState.task = nil
            return
        }
        guard CoinLogoRetryState.task == nil else { return }
        CoinLogoRetryState.task = Task { await retryCoinLogosUntilDone() }
    }

    private func retryCoinLogosUntilDone() async {
        while !Task.isCancelled {
            // Läuft schon einer (z. B. vom Öffnen der App): sein Ergebnis abwarten
            while CoinLogoRetryState.syncing, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            let wait = CoinLogoRetry.retryDelayMillis(failures: CoinLogoRetryState.failures,
                                                      lastFailureAt: CoinLogoRetryState.lastFailureAt,
                                                      now: CoinLogoRetryState.nowMillis)
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000) }
            if Task.isCancelled { return }
            if CoinLogoRetryState.syncing { continue }
            let pending = await CoinLogoStore.shared.retryPending
            let needed = CoinLogoRetry.syncNeeded(pendingAfterNetworkFailure: pending,
                                                  online: NetworkStatus.shared.isOnline)
            // Schalter für Logos und Namen aus: nichts nachzuholen
            guard needed, CoinLogoUse.needed(settings) || settings.watchlistNames else { break }
            await syncCoinLogos()
        }
        if !Task.isCancelled { CoinLogoRetryState.task = nil }
    }

    /// Hält fest, welche Paare der Merkliste TradFi sind (Kennzeichen der gespeicherten
    /// Paarliste), damit sie nie das Logo eines gleichnamigen Krypto-Tokens bekommen. Fehlt die
    /// Liste einer Börse, wird sie einmal geladen — die ganze öffentliche Liste, nie ein einzelnes
    /// Paar. true, wenn sich etwas geändert hat.
    @discardableResult
    func refreshTradFiPairs() async -> Bool {
        let watches = snapshot.watches.filter { CoinLogos.tradFiExchanges.contains($0.exchangeKey) }
        var found = Set<String>()
        // Alle TradFi-Kürzel dieser Listen (für die Aktien-Logos, nicht nur die beobachteten)
        var bases = Set<String>()
        for (exchangeKey, group) in Dictionary(grouping: watches, by: \.exchangeKey) {
            var known = await PairCache.shared.tradFiPairKeys(for: exchangeKey)
            if known == nil, !Self.tradFiListsTried.contains(exchangeKey) {
                Self.tradFiListsTried.insert(exchangeKey)
                _ = try? await PairCache.shared.sync(exchangeKey)
                known = await PairCache.shared.tradFiPairKeys(for: exchangeKey)
            }
            guard let known else { continue }
            for key in known {
                let parts = key.split(separator: "|", omittingEmptySubsequences: false)
                if parts.count > 1 { bases.insert(String(parts[1])) }
            }
            for watch in group where known.contains(watch.logoPairKey) { found.insert(watch.logoPairKey) }
        }
        CoinLogoStore.setTradFiBases(bases)
        guard CoinLogoStore.setTradFiPairs(found) else { return false }
        CoinLogoRevision.bump()
        return true
    }

    /// Börsen, deren Paarliste in diesem Lauf schon für die TradFi-Erkennung geladen wurde.
    private static var tradFiListsTried = Set<String>()

    /// Neu hinzugefügtes TradFi-Paar gleich vormerken (Logo aus dem TradFi-Namensraum).
    func noteTradFi(exchange: Exchange, pair: ExchangePair) {
        guard pair.isTradFi else { return }
        let key = CoinLogos.pairKey(exchangeKey: exchange.key, base: pair.base, quote: pair.quote,
                                    contractType: String(pair.contractType.rawValue))
        if CoinLogoStore.addTradFiPair(key) { CoinLogoRevision.bump() }
    }

    /// Wie `noteTradFi` für viele Paare (Sammelaufnahme): ein Schreibvorgang, höchstens ein `CoinLogoRevision.bump()`.
    func noteTradFi(exchange: Exchange, pairs: [ExchangePair]) {
        let keys = pairs.filter(\.isTradFi).map {
            CoinLogos.pairKey(exchangeKey: exchange.key, base: $0.base, quote: $0.quote,
                              contractType: String($0.contractType.rawValue))
        }
        if CoinLogoStore.addTradFiPairs(keys) { CoinLogoRevision.bump() }
    }
}

/// Zustand des Logo-Abgleichs und seines Nachholens (nur im Speicher, wie Android
/// `CoinLogoRepository.networkFailures` / `isSyncing`).
@MainActor
enum CoinLogoRetryState {
    static var syncing = false
    static var rerun = false
    static var task: Task<Void, Never>?
    /// Nachholen am Ende einer Bild-Server-Pause (`schedulePauseRetry`); nil = keines geplant.
    static var pauseTask: Task<Void, Never>?
    /// Aufeinanderfolgende, am Netz gescheiterte Abgleiche und Zeitpunkt des letzten (ms).
    static var failures = 0
    static var lastFailureAt: Int64 = 0

    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func noteResult(failedForNetwork: Bool) {
        if failedForNetwork {
            failures += 1
            lastFailureAt = nowMillis
        } else {
            failures = 0
        }
    }
}
