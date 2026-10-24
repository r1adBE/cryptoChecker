import Foundation
import SwiftUI

/// Lesen: Paare, Alarme, Gruppen und Altersgrenzen der Kurse — ohne Änderung am Stand.
extension AppData {
    // MARK: Lesen

    /// Anzeige-Reihenfolge: Favoriten zuerst, dann sortOrder, dann id (`displayOrder`).
    /// Gespeicherte Kurse (mit den Live-Kursen nachgeführt); den Live-Kurs legt jede
    /// Zeile selbst darüber (`LivePrices`, `WatchlistLiveRow`), damit ein Tick nur sie neu zeichnet.
    /// Einmal je Stand gerechnet (`WatchlistReadCache`, gilt bis zur nächsten Übernahme in `snapshot`).
    var watches: [Watch] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.watches { return cached }
        let sorted = Self.displayOrder(snapshot.watches)
        readCache.watches = sorted
        return sorted
    }

    static func displayOrder(_ watches: [Watch]) -> [Watch] {
        watches.sorted {
            if $0.favorite != $1.favorite { return $0.favorite }
            if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
            return $0.id < $1.id
        }
    }

    /// Alarme nach Id; einmal je Stand gerechnet.
    var alarms: [Alarm] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.alarms { return cached }
        let sorted = snapshot.alarms.sorted { $0.id < $1.id }
        readCache.alarms = sorted
        return sorted
    }

    // MARK: Gruppen

    /// Vorhandene Gruppen, alphabetisch ohne Gross/Klein — wie `groupsOf` in Android.
    /// Eine Gruppe gibt es, solange mindestens ein Paar sie nutzt. Einmal je Stand gerechnet.
    var watchGroups: [String] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.groups { return cached }
        let groups = Self.groups(of: snapshot.watches)
        readCache.groups = groups
        return groups
    }

    static func groups(of watches: [Watch]) -> [String] {
        Array(Set(watches.compactMap(\.groupName)))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    /// Gewählte Ansicht der Merkliste; nil = «Alle» (auch wenn es die Gruppe nicht mehr gibt),
    /// `WatchFilter.favorites` = «FAV» (gibt es immer), `WatchFilter.tokens` = «Token» (nur mit
    /// TradFi- und anderen Paaren, `WatchFilter.showsTokens`), `WatchFilter.alpha` = «Alpha» (nur mit
    /// Alpha- und anderen Paaren, `WatchFilter.showsAlpha`).
    var selectedWatchlistGroup: String? {
        guard let group = settings.watchlistGroup else { return nil }
        guard WatchFilter.isAvailable(group, in: snapshot.watches) else { return nil }
        return group
    }

    /// «Token»-Chip zeigen: mindestens ein Paar in «TradFi» und mindestens eines nicht.
    var hasTokenFilter: Bool {
        WatchFilter.showsTokens(snapshot.watches)
    }

    /// «Alpha»-Chip zeigen: mindestens ein Alpha-Token und mindestens ein anderes Paar
    /// (`WatchFilter.showsAlpha`, gespeicherte Alpha-Liste).
    var hasAlphaFilter: Bool {
        WatchFilter.showsAlpha(snapshot.watches)
    }

    /// Art je Paar (Id → `AssetKind`) für das Symbol vor der Glocke; Listen einmal gelesen. Je Stand und gleichen
    /// Listen (TradFi, Alpha, Namen) nur einmal je Paar gerechnet — ändert sich eine Liste, neu.
    var watchKinds: [Int64: AssetKind] {
        readCache.align(snapshotGeneration)
        let key = WatchlistReadCache.KindsKey(tradFiPairs: CoinLogoStore.tradFiPairs,
                                              alphaSymbols: CoinLogoStore.alphaSymbols,
                                              alphaNames: CoinLogoStore.alphaNames,
                                              names: TokenIdentityStore.shared.version)
        if let cached = readCache.kinds, cached.key == key { return cached.value }
        var out: [Int64: AssetKind] = [:]
        for w in snapshot.watches {
            out[w.id] = w.assetKind(tradFiPairs: key.tradFiPairs, alphaSymbols: key.alphaSymbols,
                                    alphaNames: key.alphaNames,
                                    pairName: TokenIdentityStore.shared.pairName(marketKey: w.marketKey, base: w.baseAsset))
        }
        readCache.kinds = (key, out)
        return out
    }

    /// Sichtbare Paare: alle, die Favoriten («FAV») oder nur die der gewählten Gruppe (Anzeige-Reihenfolge).
    var visibleWatches: [Watch] {
        guard let group = selectedWatchlistGroup else { return watches }
        // «Alpha»: Listen einmal lesen statt je Paar (Arten zwischengespeichert, `watchKinds`)
        if WatchFilter.isAlpha(group) {
            let kinds = watchKinds
            return watches.filter { kinds[$0.id] == .ALPHA }
        }
        // Gruppe, Favoriten, «Token»: hängen nur am Stand — einmal je Stand und Gruppe gefiltert
        readCache.align(snapshotGeneration)
        if let cached = readCache.visible, cached.group == group { return cached.value }
        let shown = watches.filter { WatchFilter.matches(group, $0) }
        readCache.visible = (group, shown)
        return shown
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
        guard !watchlistWriteRefused() else { return }
        // Vor dem Speichern umstellen — sonst gälte die alte Gruppe als verschwunden («Alle»)
        if let oldName, oldName != name, !memberIds.isEmpty, settings.watchlistGroup == oldName {
            settings.watchlistGroup = name
        }
        // Merkliste-Widgets mit der alten Gruppe folgen dem neuen Namen (ihre Einrichtung lässt sich
        // nicht umschreiben; wie `WidgetPrefs.replaceGroup` in Android) — vor `mutate`, das die Widgets
        // nach dem Speichern neu laden lässt
        if let oldName, oldName != name {
            if memberIds.isEmpty {
                WidgetGroupSelection.recordDelete(oldName)
            } else {
                WidgetGroupSelection.recordRename(old: oldName, new: name)
            }
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
        guard !watchlistWriteRefused() else { return }
        if settings.watchlistGroup == name { settings.watchlistGroup = nil }
        // Merkliste-Widgets, die der Gruppe über eine Umbenennung folgten, zeigen wieder «Alle»
        WidgetGroupSelection.recordDelete(name)
        mutate { s in
            for i in s.watches.indices where s.watches[i].groupName == name {
                s.watches[i].groupName = nil
            }
        }
    }

    /// Verschwindet die gewählte Gruppe (letztes Paar entfernt oder umgruppiert) oder gilt «Token»
    /// nicht mehr (keine TradFi- bzw. keine anderen Paare), gilt wieder «Alle».
    /// Merkliste gesperrt (`watchlistLocked`): Sie steht nur leer im Speicher — die Gruppe gibt es noch, Wahl behalten.
    func dropMissingWatchlistGroup() {
        guard !watchlistLocked, let group = settings.watchlistGroup,
              !WatchFilter.isAvailable(group, in: snapshot.watches) else { return }
        settings.watchlistGroup = nil
    }

    func watch(_ id: Int64) -> Watch? {
        guard let found = snapshot.watches.first(where: { $0.id == id }) else { return nil }
        // Im `body` gelesen beobachtet die Ansicht nur den Live-Kurs dieses einen Paars
        return found.withLive(LivePrices.shared.quote(for: id), rollingBasis: !settings.changeBasis.isDay)
    }

    /// Alarme eines Paars nach Id (je Stand einmal nach Paar gruppiert).
    func alarms(for watchId: Int64) -> [Alarm] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.alarmsByWatch { return cached[watchId] ?? [] }
        let grouped = Dictionary(grouping: alarms, by: \.watchId)
        readCache.alarmsByWatch = grouped
        return grouped[watchId] ?? []
    }

    var alarmsWithWatch: [AlarmWithWatch] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.alarmsWithWatch { return cached }
        let byId = Dictionary(snapshot.watches.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let list = alarms.compactMap { a in byId[a.watchId].map { AlarmWithWatch(alarm: a, watch: $0) } }
        readCache.alarmsWithWatch = list
        return list
    }

    /// Anzahl aktiver Alarme je Paar (einmal je Stand).
    var activeAlarmCounts: [Int64: Int] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.activeAlarmCounts { return cached }
        let counts = Dictionary(grouping: snapshot.alarms.filter(\.enabled), by: \.watchId).mapValues(\.count)
        readCache.activeAlarmCounts = counts
        return counts
    }

    /// Letzte Auslösung je Paar (Epoch-ms; auch abgeschaltete Einmal-Alarme) — für «Alarm ausgelöst»
    /// im VoiceOver-Satz der Zeile (`A11ySpeech.firedRecently`), wie Android `alarmFiredAt`. Einmal je Stand.
    var alarmFiredAt: [Int64: Int64] {
        readCache.align(snapshotGeneration)
        if let cached = readCache.alarmFiredAt { return cached }
        let fired = Dictionary(grouping: snapshot.alarms.filter { $0.lastTriggeredAt > 0 }, by: \.watchId)
            .mapValues { list in list.map(\.lastTriggeredAt).max() ?? 0 }
        readCache.alarmFiredAt = fired
        return fired
    }

    /// Letzte Auslösung über alle Alarme (Puls der Glocke); 0 = keine. Einmal je Stand.
    var lastAlarmTriggeredAt: Int64 {
        readCache.align(snapshotGeneration)
        if let cached = readCache.lastTriggered { return cached }
        let last = snapshot.alarms.map(\.lastTriggeredAt).max() ?? 0
        readCache.lastTriggered = last
        return last
    }

    /// Ab diesem Alter gilt ein Kurs als veraltet.
    var staleAfterMillis: Int64 {
        // Eine Frische-Schwelle für Änderung, abgeblasste Zeile, rote Zeit, «veraltet» und Screenreader
        // (wie Android): dieselbe Regel wie `outdatedAfterMillis`
        outdatedAfterMillis
    }

    /// Ab diesem Alter steht in der Zeile «veraltet» (abgefragte Paare, beide Modi): 3 × Hintergrund-
    /// Intervall, mindestens 15 Minuten (`LiveStatus.pollingStaleAfterMillis`). Paare am Live-Strom
    /// gelten je Paar schon nach 2 Minuten als veraltet (`staleAfterMillis(for:)`), wie Android.
    var outdatedAfterMillis: Int64 {
        LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: settings.backgroundIntervalMinutes)
    }

    /// Schwelle eines Paars: am Live-Strom (`RefreshState.liveWatchIds`) 2 Minuten, sonst `staleAfterMillis` (K-1).
    /// Liest den Live-Stand nur: Eine Ansicht, die bei dessen Wechsel neu zeichnen muss, beobachtet `refreshState`.
    func staleAfterMillis(for watchId: Int64) -> Int64 {
        LiveStatus.staleAfterMillis(onLiveSocket: refreshState.liveWatchIds.contains(watchId),
                                    pollingAfter: staleAfterMillis)
    }
}

/// Zwischenspeicher der abgeleiteten Listen von `AppData` (AppData+Reading.swift): gilt für genau einen Stand
/// (`AppData.snapshotGeneration` — jede Übernahme in `snapshot` zählt: Paare, Sortierung, Gruppe, Favorit, Alarm, Kurse).
/// Ein anderer Stand leert alles (`align`); jede Liste wird beim nächsten Zugriff neu gerechnet — gleich wie direkt.
struct WatchlistReadCache {
    /// Eingaben von `watchKinds` ausser dem Stand.
    struct KindsKey: Equatable {
        let tradFiPairs: Set<String>
        let alphaSymbols: Set<String>
        let alphaNames: [String: String]
        /// `TokenIdentityStore.version`.
        let names: Int
    }

    private(set) var generation: Int?
    var watches: [Watch]?
    var alarms: [Alarm]?
    var groups: [String]?
    var visible: (group: String, value: [Watch])?
    var kinds: (key: KindsKey, value: [Int64: AssetKind])?
    var alarmsByWatch: [Int64: [Alarm]]?
    var alarmsWithWatch: [AlarmWithWatch]?
    var activeAlarmCounts: [Int64: Int]?
    var alarmFiredAt: [Int64: Int64]?
    var lastTriggered: Int64?

    /// Zum Stand `generation` passend machen: anderer Stand → alles vergessen.
    mutating func align(_ generation: Int) {
        guard self.generation != generation else { return }
        self = WatchlistReadCache()
        self.generation = generation
    }
}
