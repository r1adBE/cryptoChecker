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
    /// `WatchFilter.favorites` = «FAV» (gibt es immer).
    var selectedWatchlistGroup: String? {
        guard let group = settings.watchlistGroup else { return nil }
        if WatchFilter.isFavorites(group) { return group }
        guard snapshot.watches.contains(where: { $0.groupName == group }) else { return nil }
        return group
    }

    /// Sichtbare Paare: alle, die Favoriten («FAV») oder nur die der gewählten Gruppe (Anzeige-Reihenfolge).
    var visibleWatches: [Watch] {
        guard let group = selectedWatchlistGroup else { return watches }
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

    /// Verschwindet die gewählte Gruppe (letztes Paar entfernt oder umgruppiert),
    /// gilt wieder «Alle».
    func dropMissingWatchlistGroup() {
        guard let group = settings.watchlistGroup, !WatchFilter.isFavorites(group),
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

    /// Ab diesem Alter gilt ein Kurs als veraltet.
    var staleAfterMillis: Int64 {
        // Eine Frische-Schwelle für Pille, abgeblasste Zeile, rote Zeit, «veraltet» und Screenreader
        // (wie Android): dieselbe Regel wie `outdatedAfterMillis`
        outdatedAfterMillis
    }

    /// Ab diesem Alter steht in der Zeile «veraltet»: im Live-Modus (App vorne mit Live-Abfrage)
    /// nach 2 Minuten, sonst 3 × Intervall, mindestens 15 Minuten (`OutdatedRule`).
    var outdatedAfterMillis: Int64 {
        OutdatedRule.afterMillis(settings, live: appActive && settings.liveService)
    }
}
