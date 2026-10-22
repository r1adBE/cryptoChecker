import Foundation
import SwiftUI

/// Lesen: Paare, Alarme, Gruppen und Altersgrenzen der Kurse — ohne Änderung am Stand.
extension AppData {
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

    /// Art je Paar (Id → `AssetKind`) für das Symbol vor der Glocke; Listen einmal gelesen.
    var watchKinds: [Int64: AssetKind] {
        let tradFiPairs = CoinLogoStore.tradFiPairs
        let alphaSymbols = CoinLogoStore.alphaSymbols
        let alphaNames = CoinLogoStore.alphaNames
        var out: [Int64: AssetKind] = [:]
        for w in snapshot.watches {
            out[w.id] = w.assetKind(tradFiPairs: tradFiPairs, alphaSymbols: alphaSymbols, alphaNames: alphaNames,
                                    pairName: TokenIdentityStore.shared.pairName(marketKey: w.marketKey, base: w.baseAsset))
        }
        return out
    }

    /// Sichtbare Paare: alle, die Favoriten («FAV») oder nur die der gewählten Gruppe (Anzeige-Reihenfolge).
    var visibleWatches: [Watch] {
        guard let group = selectedWatchlistGroup else { return watches }
        // «Alpha»: Listen einmal lesen statt je Paar
        if WatchFilter.isAlpha(group) {
            let kinds = watchKinds
            return watches.filter { kinds[$0.id] == .ALPHA }
        }
        return watches.filter { WatchFilter.matches(group, $0) }
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
        guard !watchlistWriteRefused(retry: { [weak self] in
            _ = self?.applyGroupEdit(oldName: oldName, newName: newName, memberIds: memberIds)
        }) else { return }
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
        guard !watchlistWriteRefused(retry: { [weak self] in
            _ = self?.deleteGroup(name)
        }) else { return }
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

    func alarms(for watchId: Int64) -> [Alarm] { alarms.filter { $0.watchId == watchId } }

    var alarmsWithWatch: [AlarmWithWatch] {
        let byId = Dictionary(snapshot.watches.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return alarms.compactMap { a in byId[a.watchId].map { AlarmWithWatch(alarm: a, watch: $0) } }
    }

    /// Anzahl aktiver Alarme je Paar.
    var activeAlarmCounts: [Int64: Int] {
        Dictionary(grouping: snapshot.alarms.filter(\.enabled), by: \.watchId).mapValues(\.count)
    }

    /// Letzte Auslösung je Paar (Epoch-ms; auch abgeschaltete Einmal-Alarme) — für «Alarm ausgelöst»
    /// im VoiceOver-Satz der Zeile (`A11ySpeech.firedRecently`), wie Android `alarmFiredAt`.
    var alarmFiredAt: [Int64: Int64] {
        Dictionary(grouping: snapshot.alarms.filter { $0.lastTriggeredAt > 0 }, by: \.watchId)
            .mapValues { list in list.map(\.lastTriggeredAt).max() ?? 0 }
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

    /// Schwelle eines Paars: am Live-Strom (`liveWatchIds`) 2 Minuten, sonst `staleAfterMillis` (K-1).
    func staleAfterMillis(for watchId: Int64) -> Int64 {
        LiveStatus.staleAfterMillis(onLiveSocket: liveWatchIds.contains(watchId), pollingAfter: staleAfterMillis)
    }
}
