// Was die Kopfzeile der Merkliste zeigt ([WatchlistHeaderState]) und was sie auslöst ([WatchlistHeaderActions]) —
// statt über 40 einzelner Parameter von [WatchlistHeader]. [headerStateOf] rechnet den Zustand aus dem
// ViewModel-Stand und dem, was der Bildschirm sich merkt.
// Eigene Datei, weil es reine Daten und Regeln ohne Android-Bezug sind (Unit-Tests in WatchlistHeaderStateTest).
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.QuickView

/** Zustand der Kopfzeile: Gruppen-Chips, Mehrfachauswahl, Sortiermodus, Glocke und Menü. */
internal data class WatchlistHeaderState(
    val groups: List<String>,
    val selectedGroup: String?,
    val hasFavorites: Boolean,
    /** «Token»-Chip zeigen (TradFi- und andere Paare vorhanden). */
    val hasTokens: Boolean = false,
    /** «Alpha»-Chip zeigen (Alpha- und andere Paare vorhanden). */
    val hasAlpha: Boolean = false,
    /** ⚡-Chip zeigen (Paare, bei denen gerade etwas passiert). */
    val hasActivity: Boolean = false,
    val quickView: QuickView? = null,
    val sortMode: Boolean = false,
    /** Mehrfachauswahl: statt der Chips «n ausgewählt», rechts «Alle auswählen» und «Fertig». */
    val selecting: Boolean = false,
    val selectedCount: Int = 0,
    val allSelected: Boolean = false,
    val canSelect: Boolean = false,
    /** Aktive Alarme (Glocke gefüllt). */
    val activeAlarms: Int = 0,
    /**
     * Alle Alarme, zu denen die Glocke führt (wie [com.cryptochecker.app.domain.watch.AlarmBellRoute]); null = noch nicht
     * geladen. Nur für die Beschriftung: 0 → «Neuer Alarm», sonst «Alarme (n)».
     */
    val alarmTotal: Int? = null,
    val refreshing: Boolean = false,
    val canSort: Boolean = false,
    val canClear: Boolean = false,
    val notTradedCount: Int = 0,
    /** Suche offen: dann kein Hinweis in der leeren Chip-Zeile. */
    val searching: Boolean = false,
)

/** Was die Kopfzeile auslöst (Chips, Glocke, Menü, Mehrfachauswahl, Sortiermodus). */
internal data class WatchlistHeaderActions(
    val onSelectGroup: (String?) -> Unit,
    /** Langes Drücken auf einen Gruppen-Chip. */
    val onEditGroup: (String) -> Unit,
    val onAddGroup: () -> Unit,
    val onSortDone: () -> Unit,
    /** Glocke: Übersicht bzw. ohne Alarm gleich zum Anlegen ([com.cryptochecker.app.domain.watch.AlarmBellRoute]). */
    val onBellClick: () -> Unit,
    /** Menü «Alle Alarme»: immer die Übersicht. */
    val onOpenAllAlarms: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onRefresh: () -> Unit,
    /**
     * Ende der letzten vollständigen Aktualisierung (0 = noch keine). Bei offenem Menü gefragt: Eben erst
     * fertig, ist «Aktualisieren» grau und zählt die Sekunden herunter ([com.cryptochecker.app.domain.refresh.RefreshDebounce]).
     */
    val lastFullRefreshAt: () -> Long,
    val onSort: () -> Unit,
    val onShowReport: () -> Unit,
    val onRemoveNotTraded: () -> Unit,
    val onClearAll: () -> Unit,
    val onToggleActivity: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onSelectionDone: () -> Unit = {},
    val onStartSelection: () -> Unit = {},
)

/**
 * Zustand der Kopfzeile aus dem ViewModel-Stand [state] und dem Bildschirm: [activityChip] = ⚡ möglich
 * (Karte eingeschaltet und Paare mit Signalen), [shownCount] = Paare nach Suche, [visibleCount] = Paare der
 * Ansicht (Gruppe bzw. ⚡/veraltet), [notTradedCount] = nicht mehr gehandelte Paare der ganzen Merkliste.
 */
internal fun headerStateOf(
    state: WatchlistUiState,
    activityChip: Boolean,
    quickView: QuickView?,
    sortMode: Boolean,
    selecting: Boolean,
    searching: Boolean,
    selectedCount: Int,
    shownCount: Int,
    visibleCount: Int,
    notTradedCount: Int,
): WatchlistHeaderState = WatchlistHeaderState(
    groups = state.groups,
    selectedGroup = state.selectedGroup,
    hasFavorites = state.watches.any { it.favorite },
    hasTokens = state.content.groupFilter?.tokens == true,
    hasAlpha = state.content.groupFilter?.alpha == true,
    // Kein ⚡ im Sortiermodus (er zeigt die ganze Ansicht in eigener Reihenfolge)
    hasActivity = activityChip && !sortMode,
    quickView = quickView,
    sortMode = sortMode,
    selecting = selecting,
    selectedCount = selectedCount,
    allSelected = shownCount > 0 && selectedCount == shownCount,
    canSelect = visibleCount > 0,
    activeAlarms = state.content.alarmCounts.values.sum(),
    alarmTotal = state.content.alarmTotal,
    refreshing = state.live.refreshing,
    canSort = visibleCount > 1,
    canClear = state.watches.isNotEmpty(),
    notTradedCount = notTradedCount,
    searching = searching,
)
