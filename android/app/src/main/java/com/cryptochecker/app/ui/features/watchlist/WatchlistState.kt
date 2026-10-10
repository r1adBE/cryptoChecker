package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.QuickView

/**
 * Was der Merkliste-Bildschirm sich merkt: Sortiermodus, Suche, offene Blätter und Rückfragen.
 * Die mit [rememberWatchlistUiState] gespeicherten Felder überstehen Drehen und Prozess-Neustart;
 * Suche, Rückfragen und das Portfolio-Blatt nicht (bewusst — sie zeigen Momentanes).
 */
@Stable
internal class WatchlistUiState(
    sortModeState: MutableState<Boolean>,
    actionsForState: MutableState<Long?>,
    whyForState: MutableState<Long?>,
    editGroupState: MutableState<String?>,
    editGroupIsNewState: MutableState<Boolean>,
    returnToActionsState: MutableState<Long?>,
) {
    /** Sortiermodus: Griff und «ganz nach oben/unten» an den Karten. */
    var sortMode by sortModeState

    /** Paar, dessen Aktionen gerade offen sind (Tipp auf die Karte). */
    var actionsFor by actionsForState

    /**
     * Aus dem Aktionsblatt zu «Alarme» oder «Warum?» gewechselt: Zurück öffnet das Blatt dieses
     * Paars wieder (Merkliste › Paar › Alarme › Zurück = Paar › Zurück = Merkliste).
     */
    var returnToActions by returnToActionsState

    /** Zurück aus «Alarme» bzw. «Warum?»: Aktionsblatt wieder öffnen, falls von dort gekommen. */
    fun reopenActions() {
        val id = returnToActions ?: return
        returnToActions = null
        actionsFor = id
    }

    /** Paar, dessen «Warum bewegt sich das?» offen ist. */
    var whyFor by whyForState

    /** Gruppe, die gerade bearbeitet wird (lange auf den Chip drücken bzw. neue über «+»). */
    var editGroup by editGroupState

    /** [editGroup] ist neu (noch ohne Paare gespeichert). */
    var editGroupIsNew by editGroupIsNewState

    /** Paar, das gerade ins Portfolio übernommen wird (Erfassen-Blatt). */
    var portfolioFor by mutableStateOf<WatchEntity?>(null)

    /** Suche in der Merkliste (Lupe rechts neben dem Status) — wird nicht gespeichert. */
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")

    /** ⚡ bzw. «nur veraltete» ([QuickView]); null = Gruppen-Auswahl gilt. Nicht gespeichert. */
    var quickView by mutableStateOf<QuickView?>(null)

    /** Mehrfachauswahl (⋯ › Auswählen): Häkchen an den Zeilen, unten Favorit · Gruppe · Löschen. */
    var selecting by mutableStateOf(false)
    var selectedIds by mutableStateOf(emptySet<Long>())

    /** Gruppe für die ausgewählten Paare wählen (Leiste der Mehrfachauswahl). */
    var askGroupForSelection by mutableStateOf(false)

    /** Rückfragen und Blätter aus Kopf und Menü. */
    var askClearAll by mutableStateOf(false)
    var askRemoveNotTraded by mutableStateOf(false)
    var askNewGroup by mutableStateOf(false)
    var showReport by mutableStateOf(false)

    fun closeSearch() {
        searching = false
        query = ""
    }

    /** «Sortieren» im Menü: Suche zu, Sortiermodus an (für die ganze Ansicht, ohne ⚡/veraltet). */
    fun startSort() {
        closeSearch()
        endSelection()
        quickView = null
        sortMode = true
    }

    /** «Auswählen» im Menü: Suche und Sortieren zu, nichts ausgewählt. */
    fun startSelection() {
        closeSearch()
        sortMode = false
        selectedIds = emptySet()
        selecting = true
    }

    /** Lange drücken auf eine Zeile: wie «Auswählen», aber diese Zeile gleich angehakt. */
    fun startSelectionWith(id: Long) {
        startSelection()
        selectedIds = setOf(id)
    }

    fun endSelection() {
        selecting = false
        selectedIds = emptySet()
        askGroupForSelection = false
    }

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    /** Tipp auf ⚡ bzw. den Status: Ansicht an, nochmals = aus. */
    fun toggleQuickView(view: QuickView) {
        quickView = if (quickView == view) null else view
    }

    /** Bestehende Gruppe bearbeiten (lange auf den Chip drücken). */
    fun startEditingGroup(name: String) {
        editGroupIsNew = false
        editGroup = name
    }

    /** Nach «Neue Gruppe»: gibt es den Namen schon, wird einfach jene Gruppe bearbeitet. */
    fun newGroupNamed(name: String, existing: List<String>) {
        askNewGroup = false
        editGroupIsNew = name !in existing
        editGroup = name
    }
}

@Composable
internal fun rememberWatchlistUiState(): WatchlistUiState {
    val sortMode = rememberSaveable { mutableStateOf(false) }
    val actionsFor = rememberSaveable { mutableStateOf<Long?>(null) }
    val whyFor = rememberSaveable { mutableStateOf<Long?>(null) }
    val editGroup = rememberSaveable { mutableStateOf<String?>(null) }
    val editGroupIsNew = rememberSaveable { mutableStateOf(false) }
    val returnToActions = rememberSaveable { mutableStateOf<Long?>(null) }
    return remember { WatchlistUiState(sortMode, actionsFor, whyFor, editGroup, editGroupIsNew, returnToActions) }
}
