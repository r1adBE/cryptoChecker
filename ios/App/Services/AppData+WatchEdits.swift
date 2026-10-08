import Foundation
import SwiftUI

/// Merkliste bearbeiten: hinzufügen, Mitteilung, Sprachausgabe, Notiz, Gruppe, Favorit,
/// Reihenfolge; dazu die Favoriten der Auswahllisten.
extension AppData {
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

    // MARK: Bearbeiten

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
}
