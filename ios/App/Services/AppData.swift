import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

/// Ergebnis beim Hinzufügen mehrerer Paare.
struct BulkAddResult {
    var added: Int
    var skipped: Int
}

/// Verschieben im Sortiermodus.
enum WatchMove { case top, up, down, bottom }

/// «Erst-Hinzufügen»: neu hinzugefügte Paare, deren kurzer Moment in der Merkliste
/// noch aussteht (Zeile erscheint, Mini-Chart zeichnet sich, Häkchen). Nur im Speicher.
struct AddCelebration: Equatable, Identifiable {
    let id = UUID()
    /// Paare in der Reihenfolge des Hinzufügens (Versatz 90 ms je Zeile).
    let watchIds: [Int64]
    /// «BTC/USDT wird jetzt überwacht» bzw. «BTC, ETH, XRP, BNB, SOL wird jetzt überwacht».
    let message: String
    /// Banner, Haptik und Ansage zeigt die Merkliste (Start-Tipp). Beim Hinzufügen-Tab
    /// übernimmt das der Tab selbst; die Merkliste spielt nur die Zeilen ab.
    let announceInWatchlist: Bool
}

/// Per Wischen gelöschtes Paar — alles, was «Rückgängig» braucht, um es genau so
/// wiederherzustellen (gleiche Id, Platz, Gruppe, Notiz, Mitteilung, Favorit und
/// Alarme mit ihren Ids). Nur im Speicher.
struct DeletedWatch {
    let watch: Watch
    let alarms: [Alarm]
    let activity: ActivityReport?
    /// Gewählte Gruppe der Merkliste vor dem Löschen (verschwindet mit dem letzten Paar).
    let selectedGroup: String?
    /// Lief eine Live-Aktivität für das Paar? Dann startet «Rückgängig» sie neu.
    var liveActivity = false
}

/// Zentrale Datenhaltung der App: Merkliste, Alarme, Einstellungen.
/// Entspricht WatchRepository + SettingsRepository + den ViewModels der Android-Fassung.
@MainActor
final class AppData: ObservableObject {
    static let shared = AppData()

    @Published private(set) var snapshot: SharedStorage.Snapshot
    @Published var settings: AppSettings {
        didSet {
            guard settings != oldValue else { return }
            SharedStorage.saveSettings(settings)
            settingsChanged(from: oldValue)
        }
    }
    @Published private(set) var refreshing = false
    @Published private(set) var lastRefreshMillis: Int64 = SharedStorage.lastRefreshDuration
    @Published private(set) var lastRefreshReport: RefreshReport? = SharedStorage.lastRefreshReport
    /// %-Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden.
    @Published private(set) var changeStamp: ChangeStamp? = SharedStorage.changeStamp
    /// Paare, die gerade einzeln aktualisiert werden.
    @Published private(set) var refreshingWatchIds: Set<Int64> = []
    @Published var favorites: [FavoriteKind: Set<String>] = [:]
    /// «Ungewöhnliche Aktivität» je Paar (Watch-Id); abgelaufene Signale filtert
    /// `ActivityReport.active(now:)`.
    @Published private(set) var activityReports: [Int64: ActivityReport] = [:]
    /// Portfolio-Transaktionen, neueste zuerst (wie `PortfolioDao.observeAll`).
    @Published private(set) var portfolio: [PortfolioTx] = []
    /// Ausstehender «Erst-Hinzufügen»-Moment; die Merkliste holt ihn ab (`takeAddCelebration`).
    @Published private(set) var addCelebration: AddCelebration?
    /// Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …», keine Aktualisierung.
    @Published private(set) var online = NetworkStatus.shared.isOnline
    /// App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht «Ablauf».
    @Published private(set) var appStartMillis: Int64? = SharedStorage.appStartMillis
    // Live-Kurse je Paar: `LivePrices` (eigener, je Zeile beobachteter Speicher) — ein Tick
    // veröffentlicht hier nichts, sonst würden Markt, Portfolio und Einstellungen neu aufgebaut.
    /// Börsen, deren Live-Strom gerade Kurse liefert («LIVE» in der Status-Pille, Bericht).
    @Published private(set) var liveExchanges: [String] = []
    /// Letzter Live-Kurs je Watch-Id (ms) — Paare mit frischem Kurs lässt die REST-Abfrage aus.
    private var liveTickAt: [Int64: Int64] = [:]
    /// Live-Kurse werden gerade gespeichert (`applyLive`): eine volle bzw. einzelne Aktualisierung
    /// wartet so lange; umgekehrt lässt `applyLive` aus, solange eine Aktualisierung läuft.
    private var liveApplying = false

    private var portfolioFile: PortfolioFile
    private var liveTask: Task<Void, Never>?
    private var appActive = false

    private init() {
        snapshot = SharedStorage.loadSnapshot()
        settings = SharedStorage.loadSettings()
        portfolioFile = PortfolioStore.load()
        // didSet läuft im init nicht — Kursfarben hier setzen
        PriceColors.scheme = settings.priceColorScheme
        HighContrast.setting = settings.highContrast
        PriceColors.inverted = settings.priceColorsInverted
        for kind in FavoriteKind.allCases { favorites[kind] = SharedStorage.favorites(kind) }
        activityReports = ActivityRepository.reports()
        portfolio = PortfolioStore.newestFirst(portfolioFile.transactions)
        // Einmalig: alter Bestand aus der Merkliste wird zu Käufen im Portfolio
        migrateHoldingsOnce()
        // Update mit vorhandenen Alarmen: die Erst-Alarm-Bestätigung gilt als gezeigt
        if !settings.firstAlarmShown && !snapshot.alarms.isEmpty {
            settings.firstAlarmShown = true
            SharedStorage.saveSettings(settings)
        }
        // Update mit vorhandenen Paaren: das erste Hinzufügen gilt als erledigt
        if !settings.firstPairAdded && !snapshot.watches.isEmpty {
            settings.firstPairAdded = true
            SharedStorage.saveSettings(settings)
        }
        // Netz weg/zurück (#22/#23): Statuszeile; zurück → genau eine Aktualisierung
        NetworkStatus.shared.observe { online in
            Task { @MainActor in AppData.shared.networkChanged(online) }
        }
    }

    private func networkChanged(_ isOnline: Bool) {
        let previous = online
        online = isOnline
        if OfflineGate.resumeOnChange(previous: previous, online: isOnline) && appActive {
            Task { await refreshAll() }
        }
        updateLiveStream()
    }

    /// Erstes Bild der Merkliste nach dem Start gemessen (nur lokal gespeichert).
    func recordAppStart(_ millis: Int64) {
        SharedStorage.appStartMillis = millis
        appStartMillis = millis
    }

    // MARK: Lesen

    /// Anzeige-Reihenfolge: Favoriten zuerst, dann sortOrder, dann id.
    /// Gespeicherte Kurse (alle 10 s mit den Live-Kursen nachgeführt); den Live-Kurs legt jede
    /// Zeile selbst darüber (`LivePrices`, `WatchlistLiveRow`), damit ein Tick nur sie neu zeichnet.
    var watches: [Watch] {
        snapshot.watches.sorted {
            if $0.favorite != $1.favorite { return $0.favorite }
            if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
            return $0.id < $1.id
        }
    }

    var alarms: [Alarm] { snapshot.alarms.sorted { $0.id < $1.id } }

    // MARK: Gruppen

    /// Vorhandene Gruppen, alphabetisch ohne Gross/Klein — wie `groupsOf` in Android.
    /// Eine Gruppe gibt es, solange mindestens ein Paar sie nutzt.
    var watchGroups: [String] {
        Self.groups(of: snapshot.watches)
    }

    static func groups(of watches: [Watch]) -> [String] {
        Array(Set(watches.compactMap(\.groupName)))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    /// Gewählte Gruppe der Merkliste; nil = «Alle» (auch wenn es die Gruppe nicht mehr gibt).
    var selectedWatchlistGroup: String? {
        guard let group = settings.watchlistGroup, snapshot.watches.contains(where: { $0.groupName == group })
        else { return nil }
        return group
    }

    /// Sichtbare Paare: alle oder nur die der gewählten Gruppe (Anzeige-Reihenfolge).
    var visibleWatches: [Watch] {
        guard let group = selectedWatchlistGroup else { return watches }
        return watches.filter { $0.groupName == group }
    }

    func selectWatchlistGroup(_ group: String?) {
        settings.watchlistGroup = group
    }

    /// «Gruppe bearbeiten» in einem Schritt (eine Speicherung) — wie
    /// `WatchRepository.applyGroupEdit`: Die bisherige Gruppe `oldName`
    /// (nil = neue Gruppe) wird aufgelöst, danach erhalten genau `memberIds`
    /// den Namen `newName`. Deckt Umbenennen, Hinzufügen, Entfernen und
    /// Verschieben aus anderen Gruppen ab; gleicht der Name einer anderen
    /// Gruppe, werden beide zusammengeführt. Eine neue Gruppe ohne Paare
    /// entsteht nicht. Die Auswahl der Merkliste folgt einer Umbenennung.
    func applyGroupEdit(oldName: String?, newName: String, memberIds: Set<Int64>) {
        guard let name = Watch.validGroupName(newName) else { return }
        if oldName == nil && memberIds.isEmpty { return }
        // Vor dem Speichern umstellen — sonst gälte die alte Gruppe als verschwunden («Alle»)
        if let oldName, oldName != name, !memberIds.isEmpty, settings.watchlistGroup == oldName {
            settings.watchlistGroup = name
        }
        mutate { s in
            for i in s.watches.indices {
                if let oldName, s.watches[i].groupName == oldName { s.watches[i].groupName = nil }
                if memberIds.contains(s.watches[i].id) { s.watches[i].groupName = name }
            }
        }
    }

    /// Gruppe löschen: Die Paare bleiben in der Merkliste, nur ohne Gruppe;
    /// die Ansicht springt auf «Alle».
    func deleteGroup(_ name: String) {
        if settings.watchlistGroup == name { settings.watchlistGroup = nil }
        mutate { s in
            for i in s.watches.indices where s.watches[i].groupName == name {
                s.watches[i].groupName = nil
            }
        }
    }

    /// «In Gruppe» beim Hinzufügen: nur für die laufende Sitzung, nicht gespeichert.
    /// nil = «Keine Gruppe».
    @Published var addTargetGroup: String?

    /// Verschwindet die gewählte Gruppe (letztes Paar entfernt oder umgruppiert),
    /// gilt wieder «Alle».
    private func dropMissingWatchlistGroup() {
        guard let group = settings.watchlistGroup,
              !snapshot.watches.contains(where: { $0.groupName == group }) else { return }
        settings.watchlistGroup = nil
    }

    func watch(_ id: Int64) -> Watch? {
        guard let found = snapshot.watches.first(where: { $0.id == id }) else { return nil }
        // Im `body` gelesen beobachtet die Ansicht nur den Live-Kurs dieses einen Paars
        return found.withLive(LivePrices.shared.quote(for: id), rollingBasis: !settings.changeBasis.isDay)
    }

    func alarms(for watchId: Int64) -> [Alarm] { alarms.filter { $0.watchId == watchId } }

    var alarmsWithWatch: [AlarmWithWatch] {
        let byId = Dictionary(uniqueKeysWithValues: snapshot.watches.map { ($0.id, $0) })
        return alarms.compactMap { a in byId[a.watchId].map { AlarmWithWatch(alarm: a, watch: $0) } }
    }

    /// Anzahl aktiver Alarme je Paar.
    var activeAlarmCounts: [Int64: Int] {
        Dictionary(grouping: snapshot.alarms.filter(\.enabled), by: \.watchId).mapValues(\.count)
    }

    /// Ab diesem Alter gilt ein Kurs als veraltet: 2,5 × Intervall, mindestens 5 Minuten.
    var staleAfterMillis: Int64 {
        max(refreshIntervalMillis * 5 / 2, 5 * 60_000)
    }

    /// Ab diesem Alter steht in der Zeile «veraltet»: im Live-Modus (App vorne mit Live-Abfrage)
    /// nach 2 Minuten, sonst 3 × Intervall, mindestens 15 Minuten (`OutdatedRule`).
    var outdatedAfterMillis: Int64 {
        OutdatedRule.afterMillis(settings, live: appActive && settings.liveService)
    }

    /// Eingestelltes Aktualisierungs-Intervall (Live bzw. Hintergrund).
    private var refreshIntervalMillis: Int64 {
        settings.liveService ? Int64(settings.liveIntervalSeconds) * 1000
            : Int64(settings.backgroundIntervalMinutes) * 60_000
    }

    // MARK: Speichern

    /// - Parameter widgetKinds: nur diese Widget-Arten neu laden; nil = alle.
    private func mutate(_ body: (inout SharedStorage.Snapshot) -> Void, reloadWidgets: Bool = true, widgetKinds: [String]? = nil) {
        var s = snapshot
        body(&s)
        snapshot = s
        SharedStorage.saveSnapshot(s)
        dropMissingWatchlistGroup()
        guard reloadWidgets else { return }
        if let widgetKinds {
            widgetKinds.forEach { WidgetCenter.shared.reloadTimelines(ofKind: $0) }
        } else {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// Stand von der Platte neu laden (z. B. nachdem das Widget aktualisiert hat).
    func reloadFromDisk() {
        snapshot = SharedStorage.loadSnapshot()
        lastRefreshMillis = SharedStorage.lastRefreshDuration
        lastRefreshReport = SharedStorage.lastRefreshReport
        changeStamp = SharedStorage.changeStamp
        activityReports = ActivityRepository.reports()
        dropMissingWatchlistGroup()
    }

    /// «Basis der %-Änderung» zum Zeitpunkt `now`: passt der Stempel der gespeicherten Werte nicht
    /// (Basis gewechselt, neuer Tag), zeigen Pillen und Puls «—», bis neu gerechnet ist.
    func changeView(now: Int64 = TimeUtils.nowMillis) -> ChangeView {
        ChangeView.of(stamp: changeStamp, basis: settings.changeBasis, now: now)
    }

    // MARK: Merkliste

    /// nil, wenn das Paar schon beobachtet wird.
    @discardableResult
    /// `group`: Gruppe für das NEUE Paar; ein bereits vorhandenes behält seine.
    func addWatch(market: Market, pair: CurrencyPairInfo, group: String? = nil) -> Int64? {
        var newId: Int64?
        mutate { s in
            newId = Self.insert(&s, market: market, pair: pair, group: group)
        }
        if newId != nil { markFirstPairAdded() }
        return newId
    }

    func addWatches(market: Market, pairs: [CurrencyPairInfo], group: String? = nil) -> BulkAddResult {
        var added = 0, skipped = 0
        mutate { s in
            for p in pairs {
                if Self.insert(&s, market: market, pair: p, group: group) != nil { added += 1 } else { skipped += 1 }
            }
        }
        if added > 0 { markFirstPairAdded() }
        return BulkAddResult(added: added, skipped: skipped)
    }

    // MARK: Erst-Hinzufügen

    /// Wäre das nächste Paar das allererste? (Merker nicht gesetzt und Merkliste leer.)
    var isFirstPairAdd: Bool { !settings.firstPairAdded && snapshot.watches.isEmpty }

    private func markFirstPairAdded() {
        if !settings.firstPairAdded { settings.firstPairAdded = true }
    }

    /// Erstes Paar über den Hinzufügen-Tab: Moment für die Merkliste vormerken
    /// (Banner, Haptik und Ansage macht der Tab).
    /// - Returns: Text der Rückmeldung, z. B. «BTC/USDT wird jetzt überwacht».
    @discardableResult
    func celebrateFirstAdd(_ watchId: Int64) -> String {
        let message = Self.watchingMessage(ids: [watchId], in: snapshot.watches)
        addCelebration = AddCelebration(watchIds: [watchId], message: message, announceInWatchlist: false)
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
        return L("pair_added_watching", names)
    }

    // MARK: Startpaare

    /// Startpaar für die leere Merkliste: Binance-Spot `<COIN>/USDT`, in der
    /// Geräte-Region USA Coinbase `<COIN>/USD` (Schlüssel und Paar-Ids wie in
    /// den Paarlisten der Börsen: «BTCUSDT» bzw. «BTC-USD»).
    static func starterPair(_ coin: String, locale: Locale = .current) -> (marketKey: String, pair: CurrencyPairInfo) {
        let symbol = coin.uppercased()
        if locale.region?.identifier.uppercased() == "US" {
            return ("Coinbase", CurrencyPairInfo(symbol, "USD", "\(symbol)-USD"))
        }
        return ("Binance", CurrencyPairInfo(symbol, "USDT", symbol + "USDT"))
    }

    /// Fügt die Startpaare hinzu wie der Hinzufügen-Tab (vorhandene Paare werden
    /// übersprungen), aber mit ausgeschalteter Kurs-Mitteilung je Paar — keine
    /// Mitteilung bei jeder Aktualisierung und keine Rückfrage nach der Erlaubnis
    /// beim ersten Tippen. Holt gleich die Kurse.
    /// - Returns: Anzahl neu hinzugefügter Paare.
    @discardableResult
    func addStarterCoins(_ coins: [String]) -> Int {
        var ids: [Int64] = []
        mutate { s in
            for coin in coins {
                let starter = Self.starterPair(coin)
                guard let market = MarketsConfig.market(starter.marketKey),
                      let id = Self.insert(&s, market: market, pair: starter.pair),
                      let i = s.watches.firstIndex(where: { $0.id == id }) else { continue }
                s.watches[i].notificationEnabled = false
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

    func contains(marketKey: String, pair: CurrencyPairInfo) -> Bool {
        snapshot.watches.contains {
            $0.marketKey == marketKey && $0.baseAsset == pair.base && $0.quoteAsset == pair.quote && $0.contractType == pair.contractType
        }
    }

    private static func insert(_ s: inout SharedStorage.Snapshot, market: Market, pair: CurrencyPairInfo,
                               group: String? = nil) -> Int64? {
        if s.watches.contains(where: {
            $0.marketKey == market.key && $0.baseAsset == pair.base && $0.quoteAsset == pair.quote && $0.contractType == pair.contractType
        }) { return nil }
        let id = max(s.nextWatchId, (s.watches.map(\.id).max() ?? 0) + 1)
        s.nextWatchId = id + 1
        let nextOrder = (s.watches.map(\.sortOrder).max() ?? -1) + 1
        var watch = Watch(id: id, marketKey: market.key, marketName: market.name,
                          baseAsset: pair.base, quoteAsset: pair.quote,
                          contractType: pair.contractType, pairId: pair.pairId, sortOrder: nextOrder)
        // Ohne Wahl: TradFi-Futures nach «TradFi», Laufzeit-Futures nach «QTLY»
        watch.groupName = AutoGroup.resolve(group, pair: pair)
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
    func changePair(_ watchId: Int64, market: Market, pair: CurrencyPairInfo) -> WatchEdit.Outcome? {
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
            for j in s.alarms.indices where s.alarms[j].watchId == watchId
                && WatchEdit.priceReferenceConditions.contains(s.alarms[j].condition) {
                s.alarms[j].referencePrice = nil
                s.alarms[j].referenceAt = 0
            }
        }
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
        guard !snapshot.watches.contains(where: {
            $0.id == w.id || ($0.marketKey == w.marketKey && $0.baseAsset == w.baseAsset
                && $0.quoteAsset == w.quoteAsset && $0.contractType == w.contractType)
        }) else { return false }
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
        // Gruppe war mit dem letzten Paar verschwunden: Ansicht wieder auf sie stellen
        if let group = deleted.selectedGroup, settings.watchlistGroup == nil, group == w.groupName {
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
        let targets = snapshot.watches.filter(\.isNotTraded)
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
           restored.contains(where: { $0.watch.groupName == group }) {
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

    func setNotificationEnabled(_ watch: Watch, _ enabled: Bool) {
        mutate({ s in
            guard let i = s.watches.firstIndex(where: { $0.id == watch.id }) else { return }
            s.watches[i].notificationEnabled = enabled
            if enabled {
                // Bewusst eingeschaltet: einmal zeigen, Bezug auf den aktuellen Kurs setzen.
                Notifier.showPrice(s.watches[i])
                s.watches[i].notifiedPrice = s.watches[i].lastPrice
                s.watches[i].notifiedAt = TimeUtils.nowMillis
            } else {
                Notifier.cancelPrice(watch.id)
                s.watches[i].notifiedPrice = nil
            }
        }, reloadWidgets: false)
    }

    func setTtsEnabled(_ watch: Watch, _ enabled: Bool) {
        mutate({ s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].ttsEnabled = enabled }
        }, reloadWidgets: false)
    }

    /// Notiz setzen; nil oder leer = keine Notiz. Widgets zeigen keine Notiz.
    func setNote(_ watch: Watch, _ note: String?) {
        let value = Watch.validNote(note)
        mutate({ s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].note = value }
        }, reloadWidgets: false)
    }

    /// Gruppe setzen; nil oder leer = keine Gruppe. Widgets neu zeichnen (Gruppen-Filter).
    func setGroup(_ watch: Watch, _ groupName: String?) {
        let value = Watch.validGroupName(groupName)
        mutate { s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].groupName = value }
        }
    }

    /// Favorit an/aus (Stern, Wischen nach rechts, Aktionen-Menü).
    func toggleFavorite(_ watch: Watch) {
        mutate { s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].favorite.toggle() }
        }
    }

    /// Verschiebt ein Paar innerhalb seiner Abteilung (Favoriten bzw. übrige —
    /// Favoriten bleiben immer oben). Mit `group` nur unter den Paaren dieser
    /// Gruppe (gefilterte Ansicht); ausgeblendete Paare behalten ihren Platz.
    /// Danach wird die ganze Liste fortlaufend neu nummeriert — wie `WatchRepository.move`.
    func move(_ watch: Watch, _ move: WatchMove, group: String? = nil) {
        let all = watches
        guard let current = all.first(where: { $0.id == watch.id }) else { return }
        var section = all.filter { $0.favorite == current.favorite && (group == nil || $0.groupName == group) }
        // Paar gehört nicht zur gefilterten Gruppe: nichts zu verschieben
        guard let from = section.firstIndex(where: { $0.id == watch.id }) else { return }
        let to: Int
        switch move {
        case .top: to = 0
        case .up: to = max(from - 1, 0)
        case .down: to = min(from + 1, section.count - 1)
        case .bottom: to = section.count - 1
        }
        guard from != to else { return }
        section.insert(section.remove(at: from), at: to)
        renumber(Self.placeInSlots(all, section).map(\.id))
    }

    /// Übernimmt eine per Ziehen festgelegte Reihenfolge der sichtbaren Paare.
    /// In einer gefilterten Ansicht enthält `orderedIds` nur die Paare der Gruppe:
    /// Sie tauschen untereinander die Plätze, alle anderen bleiben, wo sie sind.
    /// Favoriten bleiben trotzdem immer vor den übrigen — wie `WatchRepository.reorder`.
    func reorder(_ orderedIds: [Int64]) {
        let all = watches
        let byId = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })
        var seen = Set<Int64>()
        let wanted = orderedIds.filter { seen.insert($0).inserted }.compactMap { byId[$0] }
        renumber(Self.placeInSlots(all, wanted).map(\.id))
    }

    /// Setzt `subset` in neuer Reihenfolge auf die Plätze, die seine Paare in `all`
    /// bisher belegen; die übrigen Paare bleiben unverändert. Favoriten danach oben
    /// (stabile Aufteilung) — wie `placeInSlots` in Android.
    static func placeInSlots(_ all: [Watch], _ subset: [Watch]) -> [Watch] {
        let ids = Set(subset.map(\.id))
        let slots = all.indices.filter { ids.contains(all[$0].id) }
        var result = all
        for (i, slot) in slots.enumerated() where i < subset.count {
            result[slot] = subset[i]
        }
        return result.filter(\.favorite) + result.filter { !$0.favorite }
    }

    private func renumber(_ ids: [Int64]) {
        mutate { s in
            for (index, id) in ids.enumerated() {
                if let i = s.watches.firstIndex(where: { $0.id == id }) { s.watches[i].sortOrder = index }
            }
        }
    }

    // MARK: Alarme

    /// Speichert einen Alarm (id 0 = neu) — Bezugskurs wie in `AlarmsViewModel.save`.
    /// - Returns: true, wenn es der allererste Alarm ist (Bestätigung zeigen);
    ///   `firstAlarmShown` ist dann schon gesetzt.
    @discardableResult
    func saveAlarm(watchId: Int64, id: Int64, condition: AlarmCondition, threshold: Double,
                   repeating: Bool, sound: Bool, vibrate: Bool, speak: Bool, windowHours: Int,
                   currency: String? = nil) -> Bool {
        let existing = snapshot.alarms.first { $0.id == id && id != 0 }
        // Erster Alarm überhaupt? Gibt es schon Alarme, gilt die Bestätigung als gezeigt.
        let firstAlarm = !settings.firstAlarmShown && snapshot.alarms.isEmpty
        if !settings.firstAlarmShown { settings.firstAlarmShown = true }
        // Eigene Währung nur bei Kursalarmen und nur, wenn sie von der Quote abweicht
        let validCode = Alarm.validCurrency(currency)
        let differsFromQuote = !CurrencyConversion.sameCurrency(validCode, watch(watchId)?.quoteAsset)
        let alarmCurrency: String? = condition.isPriceThreshold && differsFromQuote ? validCode : nil
        let keep = existing != nil && existing?.condition == condition && existing?.windowHours == windowHours
        let referencePrice: Double?
        if !condition.isPercent { referencePrice = nil }
        else if keep { referencePrice = existing?.referencePrice }
        else { referencePrice = watch(watchId)?.lastPrice }

        // Bewegungs-Alarm: Fenster beginnt jetzt (oder läuft unverändert weiter).
        // Volumen-Spike: zuletzt gemeldete Kerze behalten, damit sie nicht nochmals meldet.
        let referenceAt: Int64
        if condition == .VOLUME_SPIKE {
            referenceAt = existing?.condition == .VOLUME_SPIKE ? (existing?.referenceAt ?? 0) : 0
        } else if condition != .MOVE_PERCENT_WINDOW { referenceAt = 0 }
        else if keep { referenceAt = existing?.referenceAt ?? 0 }
        else if referencePrice != nil { referenceAt = TimeUtils.nowMillis }
        else { referenceAt = 0 }

        mutate({ s in
            var alarmId = id
            if alarmId == 0 {
                alarmId = max(s.nextAlarmId, (s.alarms.map(\.id).max() ?? 0) + 1)
                s.nextAlarmId = alarmId + 1
            }
            let alarm = Alarm(id: alarmId, watchId: watchId, condition: condition, threshold: threshold,
                              enabled: true, repeating: repeating, sound: sound, vibrate: vibrate, speak: speak,
                              referencePrice: referencePrice, lastTriggeredAt: 0, lastTriggeredPrice: nil,
                              windowHours: windowHours, referenceAt: referenceAt, currency: alarmCurrency)
            if let i = s.alarms.firstIndex(where: { $0.id == alarmId }) { s.alarms[i] = alarm } else { s.alarms.append(alarm) }
        }, reloadWidgets: false)
        Task { _ = await Notifier.requestPermission() }
        return firstAlarm
    }

    /// Schnell-Alarm sofort anlegen (scharf, Ton/Vibration wie neue Alarme) — wie
    /// `AlarmsViewModel.createFromTemplate`. Liefert die Id (für «Rückgängig») und ob es
    /// der allererste Alarm überhaupt ist (Bestätigung zeigen).
    func createAlarm(watchId: Int64, from def: AlarmTemplates.Definition) -> (id: Int64, firstAlarm: Bool) {
        let firstAlarm = !settings.firstAlarmShown && snapshot.alarms.isEmpty
        if !settings.firstAlarmShown { settings.firstAlarmShown = true }
        var newId: Int64 = 0
        mutate({ s in
            let alarmId = max(s.nextAlarmId, (s.alarms.map(\.id).max() ?? 0) + 1)
            s.nextAlarmId = alarmId + 1
            newId = alarmId
            s.alarms.append(Alarm(id: alarmId, watchId: watchId, condition: def.condition, threshold: def.threshold,
                                  enabled: true, repeating: def.repeating, sound: true, vibrate: true, speak: false,
                                  referencePrice: def.referencePrice, lastTriggeredAt: 0, lastTriggeredPrice: nil,
                                  windowHours: def.windowHours, referenceAt: 0, currency: nil))
        }, reloadWidgets: false)
        Task { _ = await Notifier.requestPermission() }
        return (newId, firstAlarm)
    }

    // MARK: Portfolio-Alarme («Portfolio-Wert»)

    /// Alarme «Portfolio-Wert», nach Id.
    var portfolioAlarms: [PortfolioAlarm] { (snapshot.portfolioAlarms ?? []).sorted { $0.id < $1.id } }

    /// Mindestens ein scharfer Portfolio-Alarm (und Portfolio eingeschaltet)?
    var hasActivePortfolioAlarms: Bool {
        settings.portfolioEnabled && (snapshot.portfolioAlarms ?? []).contains(where: \.enabled)
    }

    /// Neuer Portfolio-Alarm (scharf); Beträge in der Umrechnungswährung `currency`.
    func addPortfolioAlarm(kind: PortfolioAlarmKind, threshold: Double, currency: String, repeating: Bool) {
        guard PortfolioAlarmLogic.isValidThreshold(kind, threshold) else { return }
        mutate({ s in
            var list = s.portfolioAlarms ?? []
            let id = max(s.nextPortfolioAlarmId ?? 1, (list.map(\.id).max() ?? 0) + 1)
            list.append(PortfolioAlarm(id: id, kind: kind, threshold: threshold,
                                       currency: kind.isValue ? currency : nil, repeating: repeating))
            s.portfolioAlarms = list
            s.nextPortfolioAlarmId = id + 1
        }, reloadWidgets: false)
        Task { _ = await Notifier.requestPermission() }
    }

    /// Ein-/Ausschalten; eingeschaltet wieder scharf (wie die Kursmarken der Paar-Alarme).
    func setPortfolioAlarmEnabled(_ id: Int64, _ enabled: Bool) {
        mutate({ s in
            guard var list = s.portfolioAlarms, let i = list.firstIndex(where: { $0.id == id }) else { return }
            list[i].enabled = enabled
            if enabled { list[i].referenceAt = 0 }
            s.portfolioAlarms = list
        }, reloadWidgets: false)
    }

    func deletePortfolioAlarm(_ id: Int64) {
        mutate({ s in s.portfolioAlarms?.removeAll { $0.id == id } }, reloadWidgets: false)
    }

    /// Nach jeder neuen Berechnung des Portfolio-Stands (Portfolio-Tab, volle Aktualisierung,
    /// Hintergrund): Alarme prüfen (`PortfolioAlarmLogic`), Zustand speichern, dann melden.
    /// Nur mit eingeschaltetem Portfolio — wie `PortfolioAlarmChecker` (Android).
    func evaluatePortfolioAlarms(_ widget: PortfolioWidgetSnapshot?) {
        guard let widget, hasActivePortfolioAlarms else { return }
        let current = settings
        let reading = PortfolioReading(total: widget.total, currency: widget.currency, totalUsdt: widget.totalUsdt,
                                       changePercent: widget.changePercent, empty: widget.empty)
        let now = TimeUtils.nowMillis
        var list = snapshot.portfolioAlarms ?? []
        var fired: [(alarm: PortfolioAlarm, value: Double)] = []
        var changed = false
        for i in list.indices {
            switch PortfolioAlarmLogic.decide(list[i], reading, now: now, cooldownMinutes: current.alarmCooldownMinutes) {
            case .nothing:
                break
            case .rearm:
                list[i].referenceAt = 0
                changed = true
            case .fire(let value):
                list[i].referenceAt = now
                list[i].lastTriggeredAt = now
                list[i].lastTriggeredValue = value
                list[i].enabled = PortfolioAlarmLogic.enabledAfterFire(repeating: list[i].repeating)
                changed = true
                fired.append((list[i], value))
            }
        }
        guard changed else { return }
        let updated = list
        mutate({ s in s.portfolioAlarms = updated }, reloadWidgets: false)
        // Erst nach dem Speichern melden (sonst wiederholt sich der Alarm, wenn iOS die App dazwischen beendet)
        for item in fired {
            Notifier.showPortfolioAlarm(item.alarm, measured: item.value, settings: current)
        }
    }

    // MARK: Probe-Alarm

    enum AlarmTestOutcome { case sent, sentDuringQuietHours, denied }

    /// Probe-Alarm wie ein echter Kursalarm (Ton, zeitkritisch, Ansage) — ohne Nachtruhe.
    /// Fragt einmal nach der Erlaubnis, falls noch nie gefragt.
    func sendTestAlarm() async -> AlarmTestOutcome {
        var status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = await Notifier.requestPermission()
            status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        }
        guard status == .authorized || status == .provisional || status == .ephemeral else { return .denied }
        let current = settings
        Notifier.showTestAlarm(settings: current)
        if current.ttsEnabled {
            Speaker.shared.speak(L("alarm_test_title") + ". " + L("alarm_test_text"), rate: current.ttsSpeechRate, flush: true)
        }
        return QuietHours.isQuietNow(current) ? .sentDuringQuietHours : .sent
    }

    func deleteAlarm(_ id: Int64) {
        mutate({ s in s.alarms.removeAll { $0.id == id } }, reloadWidgets: false)
    }

    func setAlarmEnabled(_ id: Int64, _ enabled: Bool) {
        mutate({ s in
            if let i = s.alarms.firstIndex(where: { $0.id == id }) {
                s.alarms[i].enabled = enabled
                // Wieder eingeschaltet: «Nahe am Hoch/Tief», Kursmarken, Funding und Open Interest melden
                // wieder (wie WatchDao.rearmAlarm)
                let condition = s.alarms[i].condition
                if enabled && (condition.isNearExtreme || condition.isPriceThreshold || condition.isDerivatives) {
                    s.alarms[i].referencePrice = nil
                    s.alarms[i].referenceAt = 0
                }
            }
        }, reloadWidgets: false)
    }

    // MARK: Portfolio

    /// Neu anlegen (id 0) oder ändern — wie `PortfolioRepository.save`.
    /// Coin wird vereinheitlicht (Grossbuchstaben), leere Notiz entfällt.
    /// Ungültige Einträge (ohne Coin, Menge ≤ 0) werden nicht gespeichert.
    @discardableResult
    func savePortfolioTx(_ tx: PortfolioTx) -> Int64? {
        var clean = tx
        clean.coin = PortfolioCalculator.normalizeCoin(tx.coin)
        clean.note = tx.note.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
        guard !clean.coin.isEmpty, clean.amount > 0, clean.amount.isFinite else { return nil }
        var file = portfolioFile
        let id: Int64
        if clean.id <= 0 {
            id = PortfolioStore.insert(clean, into: &file)
        } else {
            // Wie `@Update`: gibt es die Transaktion nicht mehr, passiert nichts
            guard PortfolioStore.update(clean, in: &file) else { return nil }
            id = clean.id
        }
        setPortfolioFile(file)
        return id
    }

    func deletePortfolioTx(_ id: Int64) {
        var file = portfolioFile
        file.transactions.removeAll { $0.id == id }
        setPortfolioFile(file)
    }

    /// Bestand eines Coins ohne die Transaktion `excludingId` (für die Verkaufs-Warnung).
    func portfolioHoldings(of coin: String, excludingId: Int64) -> Double {
        let trades = portfolio.filter { $0.id != excludingId }
        return PortfolioCalculator.position(coin, trades: trades, currentPrice: nil).holdings
    }

    /// Einmalige Übernahme des alten Bestands je Paar (`Watch.holdings`) ins
    /// Portfolio: je Coin ein Kauf ohne Preis (Mengen gleicher Coins addiert),
    /// danach wird der Bestand in der Merkliste geleert — wie
    /// `PortfolioRepository.migrateHoldingsOnce`. Merker «holdings_migrated».
    func migrateHoldingsOnce() {
        guard !PortfolioStore.holdingsMigrated else { return }
        importHoldings(onlyNewCoins: false)
        PortfolioStore.holdingsMigrated = true
    }

    /// Nach einer Wiederherstellung — wie Android: Transaktionen der Sicherung
    /// übernehmen (fehlt «portfolio», bleibt das bestehende stehen), dann den
    /// Bestand alter Sicherungen ins Portfolio übernehmen (nur Coins ohne Transaktion).
    func restorePortfolio(_ transactions: [PortfolioTx]?) {
        if let transactions {
            var file = PortfolioFile(transactions: [], nextId: portfolioFile.nextId)
            for tx in transactions {
                var item = tx
                item.coin = PortfolioCalculator.normalizeCoin(tx.coin)
                PortfolioStore.insert(item, into: &file)
            }
            setPortfolioFile(file)
        }
        importHoldings(onlyNewCoins: true)
    }

    /// Bestände der Merkliste in Käufe umwandeln und leeren. Erst das Portfolio
    /// speichern, dann die Merkliste — so geht bei einem Abbruch nichts verloren.
    private func importHoldings(onlyNewCoins: Bool) {
        var watches = snapshot.watches
        var file = portfolioFile
        PortfolioStore.importHoldings(watches: &watches, into: &file, onlyNewCoins: onlyNewCoins, now: TimeUtils.nowMillis)
        guard watches != snapshot.watches else { return }
        setPortfolioFile(file)
        mutate({ s in
            for i in s.watches.indices { s.watches[i].holdings = nil }
        }, reloadWidgets: false)
    }

    private func setPortfolioFile(_ file: PortfolioFile) {
        portfolioFile = file
        portfolio = PortfolioStore.newestFirst(file.transactions)
        PortfolioStore.save(file)
        // Portfolio-Widget mit den bekannten Kursen nachführen
        PortfolioWidgetStore.updateFromCache(transactions: file.transactions, currency: settings.portfolioCurrency)
    }

    // MARK: Favoriten der Auswahllisten

    func isFavorite(_ kind: FavoriteKind, _ item: String) -> Bool { favorites[kind]?.contains(item) ?? false }

    func toggleFavorite(_ kind: FavoriteKind, _ item: String) {
        var set = favorites[kind] ?? []
        if set.contains(item) { set.remove(item) } else { set.insert(item) }
        favorites[kind] = set
        SharedStorage.setFavorites(kind, set)
    }

    func setFavorites(_ kind: FavoriteKind, _ items: Set<String>) {
        favorites[kind] = items
        SharedStorage.setFavorites(kind, items)
    }

    // MARK: Aktualisieren

    func refreshAll() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        await waitForLiveApply()
        let liveIds = liveCoveredIds()
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, liveIds: liveIds)
        // Alles kommt live: Kurse sind aktuell — Zeit (Widget-Kopfzeile) wie nach einer Aktualisierung
        if outcome.checked == 0 && !liveIds.isEmpty && liveIds.count == snapshot.watches.count {
            SharedStorage.lastRefreshAt = TimeUtils.nowMillis
        }
        apply(outcome, full: true)
        analyzeActivity()
    }

    /// Nach unten ziehen bzw. Knopf oben: gesperrt, solange eine Aktualisierung läuft oder die
    /// letzte vollständige keine 15 s her ist (`RefreshDebounce`). Dann kein neuer Durchlauf —
    /// die Merkliste zeigt kurz «Gerade aktualisiert». Live-Takt und neue Paare laufen ohne Sperre.
    @discardableResult
    func refreshAllByUser() async -> RefreshDebounce.Decision {
        let decision = RefreshDebounce.decide(running: refreshing, lastFinishedAt: SharedStorage.lastRefreshAt,
                                              now: TimeUtils.nowMillis)
        if decision == .start { await refreshAll() }
        return decision
    }

    // MARK: Ungewöhnliche Aktivität

    /// Nach einer kompletten Aktualisierung: eigener Hintergrund-Task, damit die
    /// Aktualisierung (und ihr Kreisel) nicht auf die Auswertung wartet, die bis
    /// zu 20 s dauern kann. Prüft jedes Paar höchstens alle 10 Minuten.
    func analyzeActivity() {
        let watches = snapshot.watches
        let settings = self.settings
        // Nur mit Abnehmer: Mitteilung eingeschaltet oder App im Vordergrund (Karte, ⚡, «Warum»)
        if ActivityAnalysisGate.shouldRun(alertsEnabled: settings.activityAlerts, appVisible: appActive) {
            Task.detached(priority: .utility) { [weak self] in
                guard await ActivityMonitor.analyzeAll(watches: watches, settings: settings) else { return }
                await self?.reloadActivity()
            }
        }
        // Gas-Alarm (#167): eigener Task, höchstens alle 10 Minuten, nur wenn eingestellt
        Task.detached(priority: .utility) {
            await GasAlertCheck.runIfDue(settings: settings)
        }
    }

    /// Gespeicherte Ergebnisse neu übernehmen (z. B. nach der Hintergrund-Aktualisierung).
    func reloadActivity() {
        // Nicht mehr gehandelte Paare haben keine Signale (siehe `NotTraded`)
        let ids = Set(snapshot.watches.filter { !$0.isNotTraded }.map(\.id))
        activityReports = ActivityRepository.reports().filter { ids.contains($0.key) }
    }

    /// Daten für «Warum bewegt sich das?»; nil, wenn abgebrochen.
    nonisolated func explain(_ watch: Watch) async -> WhyReport? {
        await ActivityMonitor.explain(watch)
    }

    func refreshOne(_ watchId: Int64) async {
        guard !refreshingWatchIds.contains(watchId) else { return }
        refreshingWatchIds.insert(watchId)
        defer { refreshingWatchIds.remove(watchId) }
        await waitForLiveApply()
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, onlyWatchId: watchId)
        apply(outcome, full: false)
    }

    private func apply(_ outcome: PriceRefresher.Outcome, full: Bool, reloadWidgets: Bool = true) {
        // Uhrzeit und Dauer zuerst speichern, dann die Widgets neu laden —
        // sonst zeigt die Widget-Kopfzeile neue Kurse mit der alten Uhrzeit.
        // Ohne einen einzigen Kurs (z. B. offline) bleibt die Zeit der letzten
        // erfolgreichen Aktualisierung stehen.
        if full && outcome.failed < outcome.checked {
            SharedStorage.lastRefreshAt = TimeUtils.nowMillis
            SharedStorage.lastRefreshDuration = outcome.durationMillis
            lastRefreshMillis = outcome.durationMillis
        }
        // Bericht auch ohne einen einzigen Kurs (wie Android): dann zeigt er, welche Börse scheiterte
        if full, let report = outcome.report {
            SharedStorage.lastRefreshReport = report
            lastRefreshReport = report
        }
        // Mit dieser %-Basis gerechnet (Anzeige prüft das, siehe `changeView`)
        if full, let stamp = outcome.changeStamp {
            SharedStorage.changeStamp = stamp
            changeStamp = stamp
        }
        // Ein einzelnes Paar: nur Merkliste- und Einzel-Widgets neu laden. Das Portfolio-Widget
        // lädt sich selbst, wenn sich seine Momentaufnahme ändert; «Was gerade auffällt» hängt
        // nicht an einem Paar.
        let kinds: [String]? = full ? nil : [SharedStorage.watchlistWidgetKind, SharedStorage.singleWidgetKind]
        mutate({ s in outcome.apply(to: &s) }, reloadWidgets: reloadWidgets, widgetKinds: kinds)
        // Erst nach dem Speichern melden (sonst wiederholt sich ein Alarm, wenn iOS die App
        // dazwischen beendet) und die Alarm-Lease freigeben.
        outcome.deliverAlarms()
        // Portfolio-Alarme: nach jedem vollen Durchlauf mit frischen Portfolio-Kursen prüfen
        // (nur, wenn einer scharf ist — sonst holt nur der Portfolio-Tab die Kurse)
        if full && hasActivePortfolioAlarms {
            Task { [weak self] in
                let widget = await PortfolioWidgetStore.refresh()
                self?.evaluatePortfolioAlarms(widget)
            }
        }
        // Live-Aktivität (Sperrbildschirm) mit dem neuen Kurs
        let watches = snapshot.watches
        Task { await LiveActivityController.update(watches: watches) }
        // Tages-Basen: Bezüge, die nach dem Warten noch kamen, nachtragen statt «—»
        if let late = outcome.lateDayChanges, let stamp = outcome.changeStamp {
            let times = outcome.missingDayChange.mapValues { $0.time }
            Task { [weak self] in
                let changes = await late()
                guard !changes.isEmpty else { return }
                self?.fillLateDayChanges(changes, times: times, stamp: stamp)
            }
        }
        // Automatische Ansagen: Alarme (nie verworfen, vor Kursansagen), dann Kursansagen je Paar
        for item in outcome.speech {
            Speaker.shared.enqueue(item.text, rate: settings.ttsSpeechRate, alarm: item.flush)
        }
        for item in outcome.priceSpeech {
            Speaker.shared.enqueue(item.text, rate: settings.ttsSpeechRate, alarm: false, key: item.watchId)
        }
    }

    /// Späte Tages-Bezüge übernehmen — nur, solange Basis und Tag gleich sind und das Paar noch
    /// denselben Kurs ohne Veränderung hat; danach Widgets und Live-Aktivität neu.
    private func fillLateDayChanges(_ changes: [Int64: Double], times: [Int64: Int64], stamp: ChangeStamp) {
        // Basis und Tag unverändert, und kein neuerer Durchlauf hat inzwischen einen anderen Stempel gesetzt
        guard ChangeBasisMath.stamp(settings.changeBasis, now: TimeUtils.nowMillis) == stamp,
              changeStamp == stamp else { return }
        let fillable = snapshot.watches.contains { w in
            changes[w.id] != nil && w.change24h == nil && w.lastError == nil && times[w.id] == w.lastUpdate
        }
        guard fillable else { return }
        mutate({ s in
            for i in s.watches.indices {
                let w = s.watches[i]
                guard let change = changes[w.id], w.change24h == nil, w.lastError == nil,
                      times[w.id] == w.lastUpdate else { continue }
                s.watches[i].change24h = change
            }
        }, widgetKinds: [SharedStorage.watchlistWidgetKind, SharedStorage.singleWidgetKind])
        let watches = snapshot.watches
        Task { await LiveActivityController.update(watches: watches) }
    }

    // MARK: Live-Aktualisierung (solange die App offen ist)

    func setAppActive(_ active: Bool) {
        appActive = active
        if active { reloadFromDisk() }
        updateLiveTask()
        updateLiveStream()
    }

    // MARK: Live-Kurse per WebSocket (solange die Merkliste offen ist)

    /// App aktiv, Einstellung und Netz an den Strom weitergeben.
    private func updateLiveStream() {
        let active = appActive
        let enabled = settings.liveWebSocket
        let isOnline = online
        Task { await LivePriceStream.shared.setEnvironment(appActive: active, enabled: enabled, online: isOnline) }
    }

    /// Vom Strom: neuer Anzeige-Stand (nil = Kurse unverändert), Börsen mit Daten, Zeit je Paar.
    func setLive(quotes: [Int64: LiveQuote]?, exchanges: [String], tickAt: [Int64: Int64]) {
        if let quotes { LivePrices.shared.apply(quotes) }
        if exchanges != liveExchanges { liveExchanges = exchanges }
        liveTickAt = tickAt
    }

    /// Strom beendet: wieder die gespeicherten Kurse zeigen.
    func clearLive() {
        LivePrices.shared.clear()
        if !liveExchanges.isEmpty { liveExchanges = [] }
        liveTickAt = [:]
    }

    /// Live-Kurse gesammelt speichern und auswerten — derselbe Weg wie eine Aktualisierung
    /// (`PriceRefresher` mit `liveQuotes`): Alarme ohne doppelte Meldungen, Kurs-Mitteilungen.
    /// Läuft gerade eine Aktualisierung: false, der Strom versucht es beim nächsten Mal.
    func applyLive(_ quotes: [Int64: LiveQuote], reloadWidgets: Bool) async -> Bool {
        // Läuft eine Aktualisierung (alle oder ein Paar): nicht anstellen — der Puffer behält je
        // Paar nur den neuesten Kurs, der Strom versucht es beim nächsten Mal (zusammengeführt)
        guard !refreshing, !liveApplying, refreshingWatchIds.isEmpty else { return false }
        liveApplying = true
        defer { liveApplying = false }
        let now = TimeUtils.nowMillis
        let rolling = !settings.changeBasis.isDay
        let stampCurrent = changeStamp == ChangeBasisMath.stamp(settings.changeBasis, now: now)
        var byId: [Int64: Watch] = [:]
        for w in snapshot.watches { byId[w.id] = w }
        var chosen: [Int64: LiveQuote] = [:]
        for (id, quote) in quotes {
            guard let watch = byId[id] else { continue }
            let change = LiveRules.chooseChange(
                rollingBasis: rolling, stampCurrent: stampCurrent,
                exchangeRolling: LiveExchange.from(marketKey: watch.marketKey)?.rollingChange == true,
                live: quote.change24h, existing: watch.change24h)
            chosen[id] = LiveQuote(price: quote.price, change24h: change, time: quote.time)
        }
        guard !chosen.isEmpty else { return true }
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, liveQuotes: chosen)
        // Zeit zuerst (Widget-Kopfzeile), die Dauer des letzten Durchlaufs bleibt
        if reloadWidgets && outcome.checked > outcome.failed { SharedStorage.lastRefreshAt = TimeUtils.nowMillis }
        apply(outcome, full: false, reloadWidgets: reloadWidgets)
        return true
    }

    /// Speichert der Strom gerade (höchstens ein paar hundert ms): abwarten, damit nicht zwei
    /// Durchläufe denselben Stand auswerten (Alarme doppelt) — wie der Mutex in Android.
    private func waitForLiveApply() async {
        while liveApplying {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    /// Paare mit frischem Live-Kurs, die die REST-Abfrage diesmal auslässt (`LiveRules.skipRest`).
    private func liveCoveredIds() -> Set<Int64> {
        guard !liveTickAt.isEmpty else { return [] }
        let now = TimeUtils.nowMillis
        let rolling = !settings.changeBasis.isDay
        let stampUnchanged = changeStamp == ChangeBasisMath.stamp(settings.changeBasis, now: now)
        // Volumen-, Funding- und Open-Interest-Alarme hängen nicht am Live-Kurs (`applyLive` prüft sie
        // nicht): solche Paare weiter per REST abfragen, sonst bliebe der Alarm bei offener Merkliste stumm
        let volumeIds = Set(snapshot.alarms.filter {
            $0.enabled && ($0.condition == .VOLUME_SPIKE || $0.condition.isDerivatives)
        }.map(\.watchId))
        var ids = Set<Int64>()
        for w in snapshot.watches where !volumeIds.contains(w.id) && LiveRules.skipRest(
            lastTickAt: liveTickAt[w.id], now: now, rollingBasis: rolling, stampUnchanged: stampUnchanged,
            exchangeRolling: LiveExchange.from(marketKey: w.marketKey)?.rollingChange == true) {
            ids.insert(w.id)
        }
        return ids
    }

    private func updateLiveTask() {
        liveTask?.cancel()
        liveTask = nil
        guard appActive, settings.liveService else { return }
        let seconds = max(settings.liveIntervalSeconds, AppSettings.minLiveIntervalSeconds)
        liveTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshAll()
                try? await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
            }
        }
    }

    // MARK: Einstellungen

    private func settingsChanged(from old: AppSettings) {
        if old.liveService != settings.liveService || old.liveIntervalSeconds != settings.liveIntervalSeconds {
            updateLiveTask()
        }
        if old.liveWebSocket != settings.liveWebSocket {
            updateLiveStream()
        }
        if old.backgroundUpdates != settings.backgroundUpdates || old.backgroundIntervalMinutes != settings.backgroundIntervalMinutes
            || old.zoneAlerts != settings.zoneAlerts || old.fearGreedBelow != settings.fearGreedBelow || old.fearGreedAbove != settings.fearGreedAbove {
            BackgroundRefresh.schedule(settings: settings)
        }
        if old.accentColor != settings.accentColor {
            // Nur auf ausdrückliche Wahl der Akzentfarbe (App Review 4.6); Hell/Dunkel
            // übernimmt das Icon selbst über die Varianten im Asset-Katalog.
            AppIconSwitcher.apply(accent: settings.accentColor)
        }
        if old.accentColor != settings.accentColor || old.darkMode != settings.darkMode {
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.priceColorScheme != settings.priceColorScheme {
            // Kursfarben: App sofort (Environment), Widgets über eine neue Timeline
            PriceColors.scheme = settings.priceColorScheme
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.priceColorsInverted != settings.priceColorsInverted {
            // Farben tauschen: App sofort (Environment), Widgets über eine neue Timeline
            PriceColors.inverted = settings.priceColorsInverted
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.highContrast != settings.highContrast {
            // Hoher Kontrast: App über die Wurzel (Environment, Trait), Widgets neu zeichnen
            HighContrast.setting = settings.highContrast
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.portfolioCurrency != settings.portfolioCurrency {
            // Portfolio-Widget in der neuen Umrechnungswährung
            Task { await PortfolioWidgetStore.refresh() }
        }
        if old.changeBasis != settings.changeBasis {
            // %-Basis: bis neu gerechnet ist «—» (Stempel passt nicht); gleich neu rechnen,
            // Widgets, Portfolio-Widget und Live-Aktivität folgen
            WidgetCenter.shared.reloadAllTimelines()
            Task { [weak self] in
                await self?.refreshAll()
                await PortfolioWidgetStore.refresh()
            }
        }
        if old.hidePortfolioAmounts != settings.hidePortfolioAmounts {
            // Portfolio-Widget: Beträge zeigen bzw. als «•••»
            WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
        }
        if old.appLock != settings.appLock {
            if !settings.appLock { AppLock.shared.disabled() }
            // Portfolio-Widget: Werte zeigen bzw. verbergen
            WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
        }
        if !old.priceNotifications && settings.priceNotifications {
            Task { _ = await Notifier.requestPermission() }
        }
        if !settings.priceNotifications {
            snapshot.watches.forEach { Notifier.cancelPrice($0.id) }
        }
    }

    /// Nach einer Wiederherstellung: alles neu übernehmen.
    func replaceAll(snapshot newSnapshot: SharedStorage.Snapshot, settings newSettings: AppSettings?, favorites newFavorites: [FavoriteKind: Set<String>]) {
        // Alte ⚡-Ergebnisse gehören zu den alten Paaren — sonst könnte ein
        // Signal kurz beim falschen Paar stehen, wenn die Ids übereinstimmen.
        snapshot.watches.forEach { Notifier.cancelActivity($0.id) }
        ActivityRepository.retain([])
        activityReports = [:]
        mutate { s in s = newSnapshot }
        // Paar der Live-Aktivität gibt es evtl. nicht mehr (beendet sie dann)
        let watches = snapshot.watches
        Task { await LiveActivityController.update(watches: watches) }
        for (k, v) in newFavorites { setFavorites(k, v) }
        if let newSettings { settings = newSettings }
        dropMissingWatchlistGroup()
    }
}

/// Wechselt das App-Icon zur gewählten Akzentfarbe — nur nach einer Wahl in den
/// Einstellungen, nie automatisch (Hell/Dunkel: Varianten im Asset-Katalog).
enum AppIconSwitcher {
    @MainActor
    static func apply(accent: AccentColor) {
        guard UIApplication.shared.supportsAlternateIcons,
              UIApplication.shared.applicationState == .active else { return }
        let name = accent.iconName
        guard UIApplication.shared.alternateIconName != name else { return }
        UIApplication.shared.setAlternateIconName(name) { _ in }
    }
}
