import Accessibility
import SwiftUI

/// Favorit, Löschen mit «Rückgängig» und Reihenfolge — per Wischen, VoiceOver oder Ziehen. F-08,
/// Review UX3-02, IOS3-R04: jede Aktion fragt EINMAL vorher (`data.withWatchlistConfirm`); nach «Trotzdem» läuft der
/// normale Weg mit Banner, «Rückgängig», Haptik und Ansage, nach «Abbrechen» nichts davon. UX4-06 (wie
/// Android): Banner/Haptik nur nach dem Schreiben (gesperrt: nur der Hinweis), die Mehrfachauswahl endet erst dann.
extension WatchlistScreen {
    /// Wisch-Aktionen: nicht beim Sortieren (Ziehgriffe) und nicht mit VoiceOver.
    var swipeEnabled: Bool { !sorting && !selecting && !voiceOver }

    /// Nach rechts gewischt: Favorit an/aus, leichte Haptik, kurzer Banner.
    func swipeFavorite(_ watch: Watch) {
        data.withWatchlistConfirm {
            let nowFavorite = !watch.favorite
            withAnimation(reduceMotion ? nil : .spring(duration: 0.4)) { data.toggleFavorite(watch) }
            guard !data.watchlistWritesBlocked else { return }
            swipeFavoriteTick += 1
            banner = WatchlistBannerMessage(
                text: L(nowFavorite ? "favorite_added" : "favorite_removed", watch.displayPair)
            )
        }
    }

    /// Nach links gewischt, VoiceOver «Löschen» oder «Löschen» im Aktionsblatt: sofort löschen (samt
    /// Alarmen), Banner mit «Rückgängig» und Ansage. Ein weiteres Löschen, solange der Banner steht, fasst beide
    /// zusammen («2 Paare entfernt»); «Rückgängig» holt dann alle zurück.
    func swipeDelete(_ watch: Watch) {
        data.withWatchlistConfirm {
            var result: DeletedWatch?
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.deleteForUndo(watch) }
            guard let deleted = result else { return }
            swipeDeleteTick += 1
            let message = WatchlistBannerMessage.removed(deleted, after: banner) { all in
                undoRestore(all)
            }
            banner = message
            announce(message.text)
        }
    }

    /// Nach dem Fokuswechsel ansagen, sonst geht es unter.
    private func announce(_ text: String) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// Mehrere Paare entfernt (`deleted` leer = nichts geschehen): Haptik, Banner mit «Rückgängig» für alle, Ansage.
    private func showRemoved(_ deleted: [DeletedWatch], text: String) {
        guard !deleted.isEmpty else { return }
        swipeDeleteTick += 1
        banner = WatchlistBannerMessage(text: text, action: WatchlistBannerAction.undo { undoRestore(deleted) })
        announce(text)
    }

    /// «Nicht gehandelte Paare entfernen» bestätigt: alle auf einmal löschen, Banner mit
    /// «Rückgängig» (holt alle zurück) und Ansage.
    func removeNotTraded() {
        data.withWatchlistConfirm {
            var deleted: [DeletedWatch] = []
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { deleted = data.deleteNotTradedForUndo() }
            showRemoved(deleted, text: L("watchlist_removed_not_traded", count: deleted.count))
        }
    }

    /// «Merkliste leeren» bestätigt: alles auf einmal löschen, Banner mit «Rückgängig» (holt alle samt Alarmen,
    /// Gruppen und Notizen zurück) und Ansage — wie `removeNotTraded`, wie Android.
    func clearAll() {
        data.withWatchlistConfirm {
            var deleted: [DeletedWatch] = []
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { deleted = data.deleteAllForUndo() }
            showRemoved(deleted, text: L("watchlist_removed_count", count: deleted.count))
        }
    }

    /// «Rückgängig»: Paar mit Id, Platz, Gruppe, Notiz, Mitteilung, Favorit und Alarmen zurück.
    func undoDelete(_ deleted: DeletedWatch) {
        data.withWatchlistConfirm {
            var result = UndoRestoreResult()
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.restore(deleted) }
            showUndoFull(result)
        }
    }

    /// «Rückgängig» für mehrere Paare; mit Hinweis, wenn die Merkliste inzwischen voll ist.
    func undoRestore(_ deleted: [DeletedWatch]) {
        data.withWatchlistConfirm {
            var result = UndoRestoreResult()
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.restore(deleted) }
            showUndoFull(result)
        }
    }

    /// «n nicht zurückgeholt – Merkliste voll» (wie Android).
    func showUndoFull(_ result: UndoRestoreResult) {
        guard result.full > 0 else { return }
        banner = WatchlistBannerMessage(text: L("watchlist_undo_full", count: result.full), kind: .error,
                                        long: true)
    }

    func moveByAccessibility(_ watch: Watch, _ move: WatchMove) {
        data.withWatchlistConfirm {
            reorderTick += 1
            withAnimation(.spring(duration: 0.35)) {
                data.move(watch, move, group: data.selectedWatchlistGroup)
            }
        }
    }

    func commitOrder(_ list: [Watch]) {
        data.withWatchlistConfirm {
            reorderTick += 1
            data.reorder(list.map(\.id))
        }
    }

    /// «Nach Coin/Börse ordnen» im Sortiermodus (`AutoOrder`): die gezeigten Paare einmal ordnen und wie nach dem
    /// Ziehen als eigene Reihenfolge speichern — wie `ReorderState.autoOrder` (Android).
    func autoOrder(_ order: AutoOrder, _ shown: [Watch]) {
        let sorted = order.apply(shown, favorite: { $0.favorite }, name: { $0.displayName }, exchange: { $0.marketName })
        guard sorted.map(\.id) != shown.map(\.id) else { return }
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { commitOrder(sorted) }
    }

    func toggleFavorite(_ watch: Watch) {
        data.withWatchlistConfirm {
            withAnimation(.spring(duration: 0.4)) { data.toggleFavorite(watch) }
            if !data.watchlistWritesBlocked { Haptics.play(.lightImpact) }
        }
    }

    // MARK: Vorübergehende Ansichten und Mehrfachauswahl (wie Android `WatchlistUiState`)

    /// Tipp auf ⚡ bzw. den Status: Ansicht an, nochmals = aus.
    func toggleQuickView(_ view: QuickView) {
        withAnimation(.spring(duration: 0.35)) { quickView = quickView == view ? nil : view }
    }

    /// «Auswählen» im Menü: Suche und Sortieren zu, nichts ausgewählt.
    func startSelection() {
        if searching { closeSearch() }
        editMode = .inactive
        selectedIds = []
        withAnimation { selecting = true }
    }

    /// Lange drücken auf eine Zeile: wie «Auswählen», aber diese Zeile gleich angehakt.
    func startSelection(with id: Int64) {
        startSelection()
        selectedIds = [id]
    }

    func endSelection() {
        withAnimation { selecting = false }
        selectedIds = []
        askGroupForSelection = false
    }

    func toggleSelected(_ id: Int64) {
        Haptics.play(.selection)
        if selectedIds.contains(id) { selectedIds.remove(id) } else { selectedIds.insert(id) }
    }

    /// Leiste › «Favorit»: Stern bei allen setzen — oder wegnehmen, wenn schon alle Favoriten sind.
    func favoriteSelected() {
        let chosen = data.watches.filter { selectedIds.contains($0.id) }
        guard !chosen.isEmpty else { return }
        let makeFavorite = !chosen.allSatisfy(\.favorite)
        let ids = selectedIds
        // Eine Frage für alle ausgewählten Paare; «Abbrechen» lässt die Auswahl stehen
        data.withWatchlistConfirm {
            withAnimation(reduceMotion ? nil : .spring(duration: 0.4)) { data.setFavorite(ids: ids, makeFavorite) }
            guard !data.watchlistWritesBlocked else { return }
            endSelection()
            Haptics.play(.lightImpact)
        }
    }

    /// Aktionsblatt › «Deaktivieren»/«Aktivieren»: kurzes Banner («BTC/USDT deaktiviert»);
    /// `before` = Stand vor dem Wechsel.
    func disabledChanged(_ before: Watch) {
        banner = WatchlistBannerMessage(
            text: L(before.disabled ? "watch_enabled_banner" : "watch_disabled_banner", before.displayName)
        )
    }

    /// Leiste › «Deaktivieren» bzw. «Aktivieren» (wenn alle ausgewählten deaktiviert sind).
    func disableSelected(_ disabled: Bool) {
        let ids = selectedIds
        guard !ids.isEmpty else { return }
        data.withWatchlistConfirm {
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { data.setDisabled(ids: ids, disabled) }
            guard !data.watchlistWritesBlocked else { return }
            endSelection()
            Haptics.play(.lightImpact)
        }
    }

    /// Leiste › «Gruppe» gewählt: alle Ausgewählten in `group` (nil = keine Gruppe).
    func groupSelected(_ group: String?) {
        let ids = selectedIds
        askGroupForSelection = false
        data.withWatchlistConfirm {
            withAnimation(.spring(duration: 0.35)) { data.setGroup(ids: ids, group) }
            if !data.watchlistWritesBlocked { endSelection() }
        }
    }

    /// Leiste › «Löschen»: alle auf einmal (samt Alarmen), Banner mit «Rückgängig» für alle.
    func deleteSelected() {
        let ids = selectedIds
        data.withWatchlistConfirm {
            var deleted: [DeletedWatch] = []
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { deleted = data.deleteForUndo(ids: ids) }
            if !deleted.isEmpty { endSelection() }
            showRemoved(deleted, text: L("watchlist_removed_count", count: deleted.count))
        }
    }
}
