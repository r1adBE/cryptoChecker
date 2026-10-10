import Accessibility
import SwiftUI

/// Favorit, Löschen mit «Rückgängig» und Reihenfolge — per Wischen, VoiceOver oder Ziehen.
extension WatchlistScreen {
    /// Wisch-Aktionen: nicht beim Sortieren (Ziehgriffe) und nicht mit VoiceOver.
    var swipeEnabled: Bool { !sorting && !selecting && !voiceOver }

    /// Nach rechts gewischt: Favorit an/aus, leichte Haptik, kurzer Banner.
    func swipeFavorite(_ watch: Watch) {
        swipeFavoriteTick += 1
        let nowFavorite = !watch.favorite
        withAnimation(reduceMotion ? nil : .spring(duration: 0.4)) { data.toggleFavorite(watch) }
        banner = WatchlistBannerMessage(
            text: L(nowFavorite ? "favorite_added" : "favorite_removed", watch.displayPair),
            icon: nowFavorite ? "star.fill" : "star"
        )
    }

    /// Nach links gewischt, VoiceOver «Löschen» oder «Löschen» im Aktionsblatt: sofort löschen (samt
    /// Alarmen), Banner mit «Rückgängig» und Ansage. Ein weiteres Löschen ersetzt den
    /// Banner — das vorige bleibt dann gelöscht.
    func swipeDelete(_ watch: Watch) {
        swipeDeleteTick += 1
        var result: DeletedWatch?
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.deleteForUndo(watch) }
        guard let deleted = result else { return }
        let text = L("watchlist_removed", deleted.watch.displayPair)
        banner = WatchlistBannerMessage(text: text, icon: "trash", undo: deleted)
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// «Nicht gehandelte Paare entfernen» bestätigt: alle auf einmal löschen, Banner mit
    /// «Rückgängig» (holt alle zurück) und Ansage.
    func removeNotTraded() {
        var deleted: [DeletedWatch] = []
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { deleted = data.deleteNotTradedForUndo() }
        guard !deleted.isEmpty else { return }
        let removed = deleted
        swipeDeleteTick += 1
        let text = L("watchlist_removed_not_traded", count: removed.count)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "trash",
            action: WatchlistBannerAction(title: L("action_undo")) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(removed) }
            }
        )
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// «Rückgängig»: Paar mit Id, Platz, Gruppe, Notiz, Mitteilung, Favorit und Alarmen zurück.
    func undoDelete(_ deleted: DeletedWatch) {
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(deleted) }
    }

    func moveByAccessibility(_ watch: Watch, _ move: WatchMove) {
        reorderTick += 1
        withAnimation(.spring(duration: 0.35)) {
            data.move(watch, move, group: data.selectedWatchlistGroup)
        }
    }

    func commitOrder(_ list: [Watch]) {
        reorderTick += 1
        data.reorder(list.map(\.id))
    }

    func toggleFavorite(_ watch: Watch) {
        WatchlistHaptics.impact()
        withAnimation(.spring(duration: 0.4)) { data.toggleFavorite(watch) }
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
        WatchlistHaptics.selection()
        if selectedIds.contains(id) { selectedIds.remove(id) } else { selectedIds.insert(id) }
    }

    /// Leiste › «Favorit»: Stern bei allen setzen — oder wegnehmen, wenn schon alle Favoriten sind.
    func favoriteSelected() {
        let chosen = data.watches.filter { selectedIds.contains($0.id) }
        guard !chosen.isEmpty else { return }
        let makeFavorite = !chosen.allSatisfy(\.favorite)
        withAnimation(reduceMotion ? nil : .spring(duration: 0.4)) { data.setFavorite(ids: selectedIds, makeFavorite) }
        WatchlistHaptics.impact(.light)
        endSelection()
    }

    /// Leiste › «Gruppe» gewählt: alle Ausgewählten in `group` (nil = keine Gruppe).
    func groupSelected(_ group: String?) {
        withAnimation(.spring(duration: 0.35)) { data.setGroup(ids: selectedIds, group) }
        endSelection()
    }

    /// Leiste › «Löschen»: alle auf einmal (samt Alarmen), Banner mit «Rückgängig» für alle.
    func deleteSelected() {
        let ids = selectedIds
        endSelection()
        var deleted: [DeletedWatch] = []
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { deleted = data.deleteForUndo(ids: ids) }
        guard !deleted.isEmpty else { return }
        let removed = deleted
        swipeDeleteTick += 1
        let text = L("watchlist_removed_count", count: removed.count)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "trash",
            action: WatchlistBannerAction(title: L("action_undo")) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(removed) }
            }
        )
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }
}
