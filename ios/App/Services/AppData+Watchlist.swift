import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// Merkliste in AppData: Erst-Hinzufügen (Moment und Meldung), Startpaare, Paar einfügen/ändern, Löschen mit
// Rückgängig (einzeln, nicht gehandelte, Auswahl), alles löschen.
// Eigene Datei, weil die Merkliste ein eigener Bereich ist; der Zustand bleibt in `AppData.swift`.

extension AppData {
    // MARK: Erst-Hinzufügen

    /// Wäre das nächste Paar das allererste? (Merker nicht gesetzt und Merkliste leer.)
    var isFirstPairAdd: Bool { !settings.firstPairAdded && snapshot.watches.isEmpty }

    func markFirstPairAdded() {
        if !settings.firstPairAdded { settings.firstPairAdded = true }
    }

    /// Erstes Paar über den Hinzufügen-Tab: Moment für die Merkliste vormerken
    /// (Banner, Haptik und Ansage macht der Tab). `announceInWatchlist`: Banner und Ansage macht
    /// die Merkliste (Vorschau aus «Heute auffällig» im Markt-Tab, wie Android `afterExplorerAdd`).
    /// - Returns: Text der Rückmeldung, z. B. «BTC/USDT wird jetzt überwacht».
    @discardableResult
    func celebrateFirstAdd(_ watchId: Int64, announceInWatchlist: Bool = false) -> String {
        let message = Self.watchingMessage(ids: [watchId], in: snapshot.watches)
        addCelebration = AddCelebration(watchIds: [watchId], message: message, announceInWatchlist: announceInWatchlist)
        return message
    }

    /// Holt den ausstehenden Moment ab (einmalig).
    func takeAddCelebration() -> AddCelebration? {
        let c = addCelebration
        addCelebration = nil
        return c
    }

    /// Ein Paar: «BTC/USDT»; mehrere: die Coins, z. B. «BTC, ETH, XRP, BNB, SOL».
    static func watchingMessage(ids: [Int64], in watches: [Watch]) -> String {
        let byId = Dictionary(uniqueKeysWithValues: watches.map { ($0.id, $0) })
        let added = ids.compactMap { byId[$0] }
        let names = added.count == 1 ? added[0].displayPair : added.map(\.baseAsset).joined(separator: ", ")
        // Mehrere Paare: eigene Mehrzahl-Fassung («… sind jetzt in deiner Merkliste»)
        return L(added.count > 1 ? "pairs_added_watching" : "pair_added_watching", names)
    }

    // MARK: Startpaare

    /// Startpaar für die leere Merkliste: Binance-Spot `<COIN>/USDT`, in der
    /// Geräte-Region USA Binance.US `<COIN>/USDT` (siehe `StarterPairs.pair`).
    static func starterPair(_ coin: String, locale: Locale = .current) -> (marketKey: String, pair: MarketPair) {
        StarterPairs.pair(for: coin, region: StarterPairs.region(locale))
    }

    /// Fügt die Startpaare hinzu wie der Hinzufügen-Tab (vorhandene Paare werden
    /// übersprungen), mit Kurs-Mitteilung wie jedes neue Paar (Entscheid 16. Oktober 2026). Nach der Erlaubnis
    /// fragt die Startauswahl (`WatchlistStarter`) gleich danach. Holt gleich die Kurse.
    /// - Returns: Anzahl neu hinzugefügter Paare.
    @discardableResult
    func addStarterCoins(_ coins: [String]) -> Int {
        var ids: [Int64] = []
        mutate { s in
            for coin in coins {
                let starter = Self.starterPair(coin)
                guard let market = MarketsConfig.market(starter.marketKey),
                      let id = Self.insert(&s, market: market, pair: starter.pair) else { continue }
                ids.append(id)
            }
        }
        guard !ids.isEmpty else { return 0 }
        markFirstPairAdded()
        // Start-Tipp: immer mit dem «Erst-Hinzufügen»-Moment (ein Paar oder alle fünf)
        addCelebration = AddCelebration(watchIds: ids, message: Self.watchingMessage(ids: ids, in: snapshot.watches),
                                        announceInWatchlist: true)
        Task { [weak self, ids] in
            guard let self else { return }
            // Läuft schon eine Aktualisierung, die neuen Paare einzeln holen
            if self.refreshing {
                for id in ids { await self.refreshOne(id) }
            } else {
                await self.refreshAll()
            }
        }
        return ids.count
    }

    func contains(marketKey: String, pair: MarketPair) -> Bool {
        let type = FuturesContractType.normalized(marketKey: marketKey, pairId: pair.pairId, pair.contractType)
        let wanted = WatchIdentity.key(marketKey: marketKey, base: pair.base, quote: pair.quote,
                                       contract: String(type.rawValue), pairId: pair.pairId)
        return snapshot.watches.contains { $0.identity == wanted }
    }

    static func insert(_ s: inout SharedStorage.Snapshot, market: Market, pair rawPair: MarketPair,
                               group: String? = nil) -> Int64? {
        // Paar aus einer älteren, noch nicht neu geladenen Paarliste: inverse Perpetuals wie heute gespeichert
        var pair = rawPair
        pair.contractType = FuturesContractType.normalized(marketKey: market.key, pairId: pair.pairId, pair.contractType)
        let wanted = WatchIdentity.key(marketKey: market.key, base: pair.base, quote: pair.quote,
                                       contract: String(pair.contractType.rawValue), pairId: pair.pairId)
        if s.watches.contains(where: { $0.identity == wanted }) { return nil }
        let id = max(s.nextWatchId, (s.watches.map(\.id).max() ?? 0) + 1)
        s.nextWatchId = id + 1
        let nextOrder = (s.watches.map(\.sortOrder).max() ?? -1) + 1
        var watch = Watch(id: id, marketKey: market.key, marketName: market.name,
                          baseAsset: pair.base, quoteAsset: pair.quote,
                          contractType: pair.contractType, pairId: pair.pairId, sortOrder: nextOrder)
        // Ohne Wahl: TradFi-Futures nach «TradFi», Laufzeit-Futures nach «QTLY»
        watch.groupName = AutoGroup.resolve(group, pair: pair)
        // Neue Paare ohne Kurs-Mitteilung (einschalten im Paar-Menü)
        watch.notificationEnabled = Watch.newPairNotificationEnabled
        s.watches.append(watch)
        return id
    }

    /// «Paar bearbeiten»: Börse, Paar und Kontrakt eines Eintrags ändern — wie
    /// `WatchRepository.changePair`. Derselbe Eintrag (Id, Gruppe, Favorit, Notiz, Platz,
    /// Mitteilung, Vorlesen, Alarme bleiben); Kurs, 24-h-Wert, Fehler/«nicht gehandelt» und
    /// Meldekurs des alten Paars werden verworfen, ebenso Alarm-Bezüge am alten Kurs,
    /// ⚡-Signale und Mitteilungen. Danach gleich den neuen Kurs holen.
    /// - Returns: `.duplicate`, wenn das neue Paar schon als anderer Eintrag in der Liste steht.
    @discardableResult
    func changePair(_ watchId: Int64, market: Market, pair: MarketPair) -> WatchEdit.Outcome? {
        guard let watch = self.watch(watchId) else { return nil }
        let target = WatchEdit.Key(marketKey: market.key, pair: pair)
        let existing = snapshot.watches.first { WatchEdit.Key($0) == target }?.id
        let outcome = WatchEdit.decide(selfId: watchId, current: WatchEdit.Key(watch), target: target, existingId: existing)
        guard outcome == .changed else { return outcome }
        Notifier.cancelPrice(watchId)
        Notifier.cancelActivity(watchId)
        activityReports[watchId] = nil
        ActivityRepository.forget(watchId)
        mutate { s in
            guard let i = s.watches.firstIndex(where: { $0.id == watchId }) else { return }
            s.watches[i].marketKey = market.key
            s.watches[i].marketName = market.name
            s.watches[i].baseAsset = pair.base
            s.watches[i].quoteAsset = pair.quote
            s.watches[i].contractType = pair.contractType
            s.watches[i].pairId = pair.pairId
            s.watches[i].lastPrice = nil
            s.watches[i].previousPrice = nil
            s.watches[i].lastUpdate = 0
            s.watches[i].notifiedPrice = nil
            s.watches[i].notifiedAt = 0
            s.watches[i].lastError = nil
            s.watches[i].change24h = nil
            s.watches[i].priceChangedAt = 0
            s.watches[i].exchangeTime = nil
            for j in s.alarms.indices where s.alarms[j].watchId == watchId
                && WatchEdit.priceReferenceConditions.contains(s.alarms[j].condition) {
                s.alarms[j].referencePrice = nil
                s.alarms[j].referenceAt = 0
            }
        }
        noteTradFi(market: market, pair: pair)
        Task { [weak self] in
            guard let self else { return }
            await self.refreshOne(watchId)
            // Prozentalarme messen ab dem ersten Kurs des neuen Paars
            guard let price = self.watch(watchId)?.lastPrice, price > 0 else { return }
            self.mutate({ s in
                for j in s.alarms.indices where s.alarms[j].watchId == watchId && s.alarms[j].referencePrice == nil
                    && WatchEdit.percentConditions.contains(s.alarms[j].condition) {
                    s.alarms[j].referencePrice = price
                }
            }, reloadWidgets: false)
        }
        return outcome
    }

    func delete(_ watch: Watch) {
        Notifier.cancelPrice(watch.id)
        Notifier.cancelActivity(watch.id)
        // Live-Aktivität des Paars endet mit ihm
        let watchId = watch.id
        Task { await LiveActivityController.stop(watchId: watchId) }
        activityReports[watch.id] = nil
        mutate { s in
            s.watches.removeAll { $0.id == watch.id }
            s.alarms.removeAll { $0.watchId == watch.id }
        }
    }

    /// Löschen mit «Rückgängig»: löscht sofort wie `delete` (samt Alarmen) und liefert
    /// alles für `restore`. Ids werden nie wieder vergeben, darum ist das Wiedereinfügen
    /// mit den alten Ids sicher. nil, wenn es das Paar nicht mehr gibt.
    func deleteForUndo(_ watch: Watch) -> DeletedWatch? {
        // Gespeicherter Stand (ohne Live-Kurs darüber): wird beim «Rückgängig» so wieder eingefügt
        guard let current = snapshot.watches.first(where: { $0.id == watch.id }) else { return nil }
        let deleted = DeletedWatch(
            watch: current,
            alarms: snapshot.alarms.filter { $0.watchId == current.id },
            activity: activityReports[current.id],
            selectedGroup: settings.watchlistGroup,
            liveActivity: LiveActivityController.isRunning(watchId: current.id)
        )
        delete(current)
        return deleted
    }

    /// «Rückgängig»: Paar und Alarme unverändert zurück. Wurde dasselbe Paar inzwischen
    /// neu hinzugefügt, passiert nichts (false).
    @discardableResult
    func restore(_ deleted: DeletedWatch) -> Bool {
        let w = deleted.watch
        guard !snapshot.watches.contains(where: { $0.id == w.id || $0.samePair(as: w) }) else { return false }
        mutate { s in
            s.watches.append(w)
            let taken = Set(s.alarms.map(\.id))
            s.alarms.append(contentsOf: deleted.alarms.filter { !taken.contains($0.id) })
            s.nextWatchId = max(s.nextWatchId, w.id + 1)
            if let maxAlarm = deleted.alarms.map(\.id).max() { s.nextAlarmId = max(s.nextAlarmId, maxAlarm + 1) }
        }
        if let activity = deleted.activity { activityReports[w.id] = activity }
        // Live-Aktivität endete mit dem Löschen: wieder starten
        if deleted.liveActivity {
            Task { _ = await LiveActivityController.start(w) }
        }
        // Gruppe (oder «Token») war mit dem letzten Paar verschwunden: Ansicht wieder auf sie stellen
        if let group = deleted.selectedGroup, settings.watchlistGroup == nil,
           group == w.groupName || (WatchFilter.isTokens(group) && WatchFilter.showsTokens(snapshot.watches))
            || (WatchFilter.isAlpha(group) && WatchFilter.showsAlpha(snapshot.watches)) {
            settings.watchlistGroup = group
        }
        return true
    }

    /// Paare der Merkliste, die ihre Börse nicht mehr führt (`NotTraded`).
    var notTradedCount: Int { snapshot.watches.filter(\.isNotTraded).count }

    /// «Nicht gehandelte Paare entfernen»: alle nicht mehr gehandelten Paare samt Alarmen in
    /// EINEM Vorgang löschen (wie `deleteForUndo` festgehalten); `restore(_:)` mit der Liste holt
    /// sie zurück. Leer, wenn es keine gibt.
    func deleteNotTradedForUndo() -> [DeletedWatch] {
        deleteForUndo(ids: Set(snapshot.watches.filter(\.isNotTraded).map(\.id)))
    }

    /// Mehrfachauswahl › «Löschen»: die Paare `ids` samt Alarmen in EINEM Vorgang löschen, wie
    /// `deleteForUndo` festgehalten; `restore(_:)` mit der Liste holt sie zurück — wie Android
    /// `deleteSelectedWithUndo`. Leer, wenn keines davon (mehr) da ist.
    func deleteForUndo(ids wanted: Set<Int64>) -> [DeletedWatch] {
        let targets = snapshot.watches.filter { wanted.contains($0.id) }
        guard !targets.isEmpty else { return [] }
        let ids = Set(targets.map(\.id))
        let group = settings.watchlistGroup
        let deleted = targets.map { w in
            DeletedWatch(
                watch: w,
                alarms: snapshot.alarms.filter { $0.watchId == w.id },
                activity: activityReports[w.id],
                selectedGroup: group,
                liveActivity: LiveActivityController.isRunning(watchId: w.id)
            )
        }
        for w in targets {
            Notifier.cancelPrice(w.id)
            Notifier.cancelActivity(w.id)
            activityReports[w.id] = nil
        }
        // Live-Aktivitäten der Paare enden mit ihnen
        let live = deleted.filter(\.liveActivity).map(\.watch.id)
        if !live.isEmpty {
            Task { for id in live { await LiveActivityController.stop(watchId: id) } }
        }
        mutate { s in
            s.watches.removeAll { ids.contains($0.id) }
            s.alarms.removeAll { ids.contains($0.watchId) }
        }
        return deleted
    }

    /// «Rückgängig» für mehrere Paare in einem Vorgang (siehe `restore(_:)` für eines).
    /// Paare, die inzwischen neu hinzugefügt wurden, bleiben aus. Liefert die Zahl der zurückgeholten.
    @discardableResult
    func restore(_ deleted: [DeletedWatch]) -> Int {
        var restored: [DeletedWatch] = []
        mutate { s in
            for d in deleted {
                let w = d.watch
                guard !s.watches.contains(where: { $0.id == w.id || $0.samePair(as: w) }) else { continue }
                s.watches.append(w)
                let taken = Set(s.alarms.map(\.id))
                s.alarms.append(contentsOf: d.alarms.filter { !taken.contains($0.id) })
                s.nextWatchId = max(s.nextWatchId, w.id + 1)
                if let maxAlarm = d.alarms.map(\.id).max() { s.nextAlarmId = max(s.nextAlarmId, maxAlarm + 1) }
                restored.append(d)
            }
        }
        for d in restored {
            if let activity = d.activity { activityReports[d.watch.id] = activity }
            if d.liveActivity {
                let w = d.watch
                Task { _ = await LiveActivityController.start(w) }
            }
        }
        // Gruppe war mit den Paaren verschwunden: Ansicht wieder auf sie stellen
        if let group = restored.first?.selectedGroup, settings.watchlistGroup == nil,
           restored.contains(where: { $0.watch.groupName == group })
            || (WatchFilter.isTokens(group) && WatchFilter.showsTokens(snapshot.watches))
            || (WatchFilter.isAlpha(group) && WatchFilter.showsAlpha(snapshot.watches)) {
            settings.watchlistGroup = group
        }
        return restored.count
    }

    func deleteAll() {
        snapshot.watches.forEach {
            Notifier.cancelPrice($0.id)
            Notifier.cancelActivity($0.id)
        }
        activityReports = [:]
        Task { await LiveActivityController.endAll() }
        mutate { s in
            s.watches.removeAll()
            s.alarms.removeAll()
        }
    }
}
