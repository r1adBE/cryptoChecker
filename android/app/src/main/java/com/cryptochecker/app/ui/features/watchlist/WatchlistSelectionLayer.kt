package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.CompareMath
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.resources.R

/**
 * Mehrfachauswahl der Merkliste ([WatchlistBody]): Leiste mit Favorit · Gruppe · Deaktivieren · Vergleich · Löschen für
 * die angehakten Paare, Dialog «Gruppe» und der Vergleich. [inset]: Rand der lesbaren Breite.
 */
@Composable
internal fun BoxScope.WatchlistSelectionLayer(
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    watches: List<WatchEntity>,
    groups: List<String>,
    selectedIds: Set<Long>,
    inset: Dp,
) {
    // Rückmeldungen der Mehrfachauswahl nach der Rückfrage «nicht gespeichert» im aktuellen Bildschirm
    val uiEvents = rememberWatchUiEvents(WATCHLIST_UI_EVENTS)
    val selectionDone = arrayOf<WatchUiEvent>(WatchUiEvent.Haptic(HapticEvent.LIGHT_IMPACT), WatchUiEvent.EndSelection)
    if (ui.selecting) {
        val chosen = watches.filter { it.id in selectedIds }
        val allDisabled = chosen.isNotEmpty() && chosen.all { it.disabled }
        SelectionBar(
            count = selectedIds.size,
            allFavorites = chosen.isNotEmpty() && chosen.all { it.favorite },
            // Haptik und Ende der Auswahl erst nach dem Schreiben —
            // «Abbrechen» in der Rückfrage «nicht gespeichert» lässt die Auswahl stehen
            onFavorite = {
                viewModel.setFavorite(selectedIds, !chosen.all { it.favorite }) { if (it) uiEvents.send(*selectionDone) }
            },
            onGroup = { ui.askGroupForSelection = true },
            // Alle deaktiviert: wieder aktivieren, sonst alle deaktivieren
            allDisabled = allDisabled,
            onToggleDisabled = {
                viewModel.setDisabled(selectedIds.toList(), !allDisabled) { if (it) uiEvents.send(*selectionDone) }
            },
            // Auswahl bleibt stehen: zurück aus dem Vergleich lässt sie sich ändern
            onCompare = {
                if (CompareMath.canCompare(chosen.size)) ui.compareIds = chosen.map { it.id }
                else uiEvents.send(WatchUiEvent.Banner(BannerText(R.string.watchlist_compare_hint)))
            },
            onDelete = {
                viewModel.deleteSelectedWithUndo(selectedIds.toList()) { count ->
                    if (count > 0) uiEvents.send(
                        WatchUiEvent.EndSelection,
                        WatchUiEvent.Haptic(HapticEvent.DESTRUCTIVE),
                        WatchUiEvent.Banner(
                            BannerText(plural = R.plurals.watchlist_removed_count, count = count), SELECTION_UNDO_KEY,
                        ),
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp + inset, end = 16.dp + inset, bottom = 12.dp)
        )
    }
    if (ui.askGroupForSelection) {
        val chosen = watches.filter { it.id in selectedIds }
        GroupDialog(
            // Gleiche Gruppe bei allen: vorgewählt, sonst nichts
            current = chosen.map { it.groupName }.distinct().singleOrNull(),
            groups = groups,
            // Dialog zu, Auswahl erst nach dem Schreiben beenden
            onSelect = { group ->
                ui.askGroupForSelection = false
                viewModel.setGroup(selectedIds, group) { if (it) uiEvents.send(WatchUiEvent.EndSelection) }
            },
            onDismiss = { ui.askGroupForSelection = false },
        )
    }
    ui.compareIds?.let { ids -> ids.mapNotNull { id -> watches.firstOrNull { it.id == id } } }
        ?.takeIf { CompareMath.canCompare(it.size) }
        ?.let { chosen -> CompareScreen(chosen, viewModel::loadSheetChart) { ui.compareIds = null } }
}
