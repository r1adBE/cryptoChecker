import Foundation
import SwiftUI

/// Merkliste bearbeiten: hinzufügen, Mitteilung, Sprachausgabe, Notiz, Gruppe, Favorit,
/// Reihenfolge; dazu die Favoriten der Auswahllisten.
extension AppData {
    // MARK: Merkliste

    /// nil, wenn das Paar schon beobachtet wird oder die Merkliste voll ist (Grund: `tryAddWatch`).
    /// `group`: Gruppe für das NEUE Paar; ein bereits vorhandenes behält seine.
    @discardableResult
    func addWatch(exchange: Exchange, pair: ExchangePair, group: String? = nil) -> Int64? {
        if case .added(let id) = tryAddWatch(exchange: exchange, pair: pair, group: group) { return id }
        return nil
    }

    /// Paar anlegen: angelegt (mit Id), schon vorhanden oder Merkliste voll (`WatchlistLimits.maxPairs`).
    func tryAddWatch(exchange: Exchange, pair: ExchangePair, group: String? = nil) -> AddWatchOutcome {
        guard !watchlistWriteRefused() else { return .locked }
        var outcome = AddWatchOutcome.duplicate
        mutate { s in
            outcome = Self.insertOutcome(&s, exchange: exchange, pair: pair, group: group)
        }
        if case .added = outcome {
            markFirstPairAdded()
            noteTradFi(exchange: exchange, pair: pair)
        }
        return outcome
    }

    /// Mehrere Paare: vorhandene übersprungen; ist die Merkliste voll, kommen die übrigen nicht mehr dazu (`refused`).
    /// Ein Durchlauf in linearer Zeit (`WatchInsertion.insertAll`, gleiche Regeln wie `insertOutcome`).
    func addWatches(exchange: Exchange, pairs: [ExchangePair], group: String? = nil) -> BulkAddResult {
        guard !watchlistWriteRefused() else { return BulkAddResult(added: 0, skipped: 0, locked: true) }
        var result = BulkAddResult(added: 0, skipped: 0)
        mutate { s in
            result = WatchInsertion.insertAll(&s, exchange: exchange, pairs: pairs, group: group)
        }
        if result.added > 0 { markFirstPairAdded() }
        // TradFi-Paare gesammelt vormerken (ein Schreibvorgang statt je Paar)
        noteTradFi(exchange: exchange, pairs: pairs)
        return result
    }

    /// «Rückgängig» nach «Mehrere Paare»: die neu angelegten Paare `ids` wieder löschen, ausser denen, die inzwischen
    /// einen Alarm haben (auch ausgeschaltet; der Alarm ginge sonst ohne Hinweis mit) — wie Android
    /// `WatchRepository.deleteWatchesWithoutAlarms`.
    @discardableResult
    func deleteNewWatchesForUndo(ids: Set<Int64>) -> [DeletedWatch] {
        let withAlarms = Set(snapshot.alarms.map(\.watchId))
        return deleteForUndo(ids: ids.subtracting(withAlarms))
    }

    // MARK: Bearbeiten

    func setNotificationEnabled(_ watch: Watch, _ enabled: Bool) {
        guard !watchlistWriteRefused() else { return }
        let current = settings
        mutate({ s in
            guard let i = s.watches.firstIndex(where: { $0.id == watch.id }) else { return }
            s.watches[i].notificationEnabled = enabled
            // Deaktiviertes Paar: nur merken, gemeldet wird erst nach dem Aktivieren
            if enabled && s.watches[i].disabled {
                s.watches[i].notifiedPrice = nil
            } else if enabled {
                // Bewusst eingeschaltet: einmal zeigen (nur nach den Einstellungen, `PriceNotificationRule`),
                // Bezug auf den aktuellen Kurs setzen; nicht gezeigt → Bezug leer, die nächste Aktualisierung meldet
                let shown = Notifier.showPriceNow(s.watches[i], settings: current)
                s.watches[i].notifiedPrice = PriceNotificationRule.referenceAfterEnable(shown: shown, price: s.watches[i].lastPrice)
                s.watches[i].notifiedAt = shown ? EpochTime.nowMillis : s.watches[i].notifiedAt
            } else {
                Notifier.cancelPrice(watch.id)
                s.watches[i].notifiedPrice = nil
            }
        }, reloadWidgets: false)
    }

    /// «Alle an» / «Alle aus» unter Einstellungen › Alarme › Kursmitteilungen › Paare — je Paar wie `setNotificationEnabled`.
    /// Wie Android `SettingsViewModel.setAllPairNotifications`.
    func setAllNotifications(_ enabled: Bool) {
        guard !watchlistWriteRefused() else { return }
        let current = settings
        mutate({ s in
            for i in s.watches.indices where s.watches[i].notificationEnabled != enabled {
                s.watches[i].notificationEnabled = enabled
                if enabled && s.watches[i].disabled {
                    s.watches[i].notifiedPrice = nil
                } else if enabled {
                    // Nur nach den Einstellungen (`PriceNotificationRule`), wie `setNotificationEnabled`
                    let shown = Notifier.showPriceNow(s.watches[i], settings: current)
                    s.watches[i].notifiedPrice = PriceNotificationRule.referenceAfterEnable(shown: shown, price: s.watches[i].lastPrice)
                    s.watches[i].notifiedAt = shown ? EpochTime.nowMillis : s.watches[i].notifiedAt
                } else {
                    Notifier.cancelPrice(s.watches[i].id)
                    s.watches[i].notifiedPrice = nil
                }
            }
        }, reloadWidgets: false)
    }

    /// «Kurs vorlesen» für ein Paar — wie `WatchlistViewModel.setTtsEnabled` (Android). Einschalten,
    /// während die Sprachausgabe aus ist oder «Nur Alarme» gilt: beides passend umstellen, sonst
    /// bliebe der Schalter wirkungslos (`AppSettings.speaksPairPrices`).
    /// - Returns: true, wenn die Sprachausgabe dafür eingeschaltet wurde (kurzer Hinweis im Blatt).
    @discardableResult
    func setTtsEnabled(_ watch: Watch, _ enabled: Bool) -> Bool {
        guard !watchlistWriteRefused() else { return false }
        let turnOnSpeech = enabled && !settings.speaksPairPrices
        mutate({ s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].ttsEnabled = enabled }
        }, reloadWidgets: false)
        if turnOnSpeech {
            var updated = settings
            updated.ttsEnabled = true
            updated.ttsAlarmsOnly = false
            settings = updated
        }
        return turnOnSpeech
    }

    /// Notiz setzen; nil oder leer = keine Notiz. Widgets zeigen keine Notiz.
    func setNote(_ watch: Watch, _ note: String?) {
        guard !watchlistWriteRefused() else { return }
        let value = Watch.validNote(note)
        mutate({ s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].note = value }
        }, reloadWidgets: false)
    }

    /// Gruppe setzen; nil oder leer = keine Gruppe. Widgets neu zeichnen (Gruppen-Filter).
    func setGroup(_ watch: Watch, _ groupName: String?) {
        guard !watchlistWriteRefused() else { return }
        let value = Watch.validGroupName(groupName)
        mutate { s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].groupName = value }
        }
    }

    /// Mehrfachauswahl › «Gruppe»: alle Paare `ids` in `groupName` (nil = keine Gruppe).
    func setGroup(ids: Set<Int64>, _ groupName: String?) {
        guard !watchlistWriteRefused() else { return }
        let value = Watch.validGroupName(groupName)
        mutate { s in
            for i in s.watches.indices where ids.contains(s.watches[i].id) { s.watches[i].groupName = value }
        }
    }

    /// Mehrfachauswahl › «Favorit»: alle Paare `ids` zu Favoriten machen bzw. entfernen.
    func setFavorite(ids: Set<Int64>, _ favorite: Bool) {
        guard !watchlistWriteRefused() else { return }
        mutate { s in
            for i in s.watches.indices where ids.contains(s.watches[i].id) { s.watches[i].favorite = favorite }
        }
    }

    /// Aktionsblatt bzw. Mehrfachauswahl › «Deaktivieren»/«Aktivieren» — wie
    /// `WatchlistViewModel.setDisabled` (Android). Deaktiviert: Kurs- und Aktivitäts-Mitteilungen weg,
    /// keine Abfragen und Alarme mehr (Alarme bleiben gespeichert). Wieder aktiviert: Melde-Bezug
    /// leeren und sofort den Kurs holen — die Abfrage zeigt die Kurs-Mitteilung dann wieder nach den
    /// Einstellungen. Einzelne Paare gezielt, viele auf einmal mit einem ganzen Durchlauf.
    func setDisabled(ids: Set<Int64>, _ disabled: Bool) {
        guard !ids.isEmpty, !watchlistWriteRefused() else { return }
        mutate { s in
            for i in s.watches.indices where ids.contains(s.watches[i].id) {
                s.watches[i].disabled = disabled
                if !disabled {
                    s.watches[i].notifiedPrice = nil
                    s.watches[i].notifiedAt = 0
                }
            }
        }
        if disabled {
            for id in ids {
                Notifier.cancelPrice(id)
                Notifier.cancelActivity(id)
            }
            reloadActivity()
            return
        }
        Task { [weak self] in
            guard let self else { return }
            if ids.count <= Self.enableSingleRefreshMax {
                for id in ids { await self.refreshOne(id) }
            } else {
                await self.refreshAll()
            }
        }
    }

    /// Bis zu so vielen wieder aktivierten Paaren einzeln abfragen, darüber ein ganzer Durchlauf.
    static let enableSingleRefreshMax = 3

    /// Favorit an/aus (Stern, Wischen nach rechts, Aktionen-Menü).
    func toggleFavorite(_ watch: Watch) {
        guard !watchlistWriteRefused() else { return }
        mutate { s in
            if let i = s.watches.firstIndex(where: { $0.id == watch.id }) { s.watches[i].favorite.toggle() }
        }
    }

    /// Verschiebt ein Paar innerhalb seiner Abteilung (Favoriten bzw. übrige —
    /// Favoriten bleiben immer oben). Mit `group` nur unter den Paaren dieser
    /// Gruppe (gefilterte Ansicht); ausgeblendete Paare behalten ihren Platz.
    /// Danach wird die ganze Liste fortlaufend neu nummeriert — wie `WatchRepository.move`.
    func move(_ watch: Watch, _ move: WatchMove, group: String? = nil) {
        guard !watchlistWriteRefused() else { return }
        let all = watches
        guard let current = all.first(where: { $0.id == watch.id }) else { return }
        var section = all.filter { $0.favorite == current.favorite && WatchFilter.matches(group, $0) }
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
        guard !watchlistWriteRefused() else { return }
        let all = watches
        let byId = Dictionary(all.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
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
