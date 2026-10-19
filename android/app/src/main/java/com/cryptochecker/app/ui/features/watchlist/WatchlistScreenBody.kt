// Inhalt der Merkliste unter dem Puls: fester Kopf (Gruppen-Chips, Knöpfe, Status mit Lupe), feine Linie, die Liste
// der Paare mit Sprungknopf und die Leiste der Mehrfachauswahl samt Gruppen-Dialog.
// Eigene Datei, weil `WatchlistScreen` sonst zu lang würde; Zustand und Ableitungen bleiben dort, hier wird nur gezeichnet.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.FirstAlarmCard
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.WatchlistOnboarding
import com.cryptochecker.app.ui.components.ReadableInset

/**
 * Fester Kopf und Liste der Merkliste ([WatchlistScreen] setzt Zustand und Ableitungen; die Werte hier sind dieselben
 * wie dort). Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar.
 */
@Composable
internal fun WatchlistBody(
    state: WatchlistUiState,
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    onboarding: WatchlistOnboarding.Visible,
    activityChip: Boolean,
    selectedIds: Set<Long>,
    shown: List<WatchEntity>,
    visible: List<WatchEntity>,
    groupVisible: List<WatchEntity>,
    notTradedIds: Set<Long>,
    listed: List<WatchEntity>,
    filtering: Boolean,
    trimmedQuery: String,
    now: Long,
    reduceMotion: Boolean,
    listState: LazyListState,
    jump: WatchlistJump,
    reorder: ReorderState,
    pulse: WatchPulse?,
    bellScale: Animatable<Float, AnimationVector1D>,
    moment: AddMoment?,
    livePrices: State<Map<Long, LiveQuote>>,
    fetchSparklines: Boolean,
    activeSignals: Map<Long, List<ActivitySignal>>,
    firstAlarmTarget: WatchEntity?,
    disabledCount: Int,
    banner: WatchlistBanner,
    onBellClick: () -> Unit,
    onOpenAllAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    onAddClick: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    requestRefresh: () -> Unit,
    deleteWithUndo: (WatchEntity) -> Unit,
    favoriteWithBanner: (WatchEntity) -> Unit,
) {
    // Dieselben Werte wie in [WatchlistScreen] (aus demselben Stand)
    val display = state.display
    val live = state.live
    val watches = state.watches
    val groups = state.groups
    val selectedGroup = state.selectedGroup
    val kinds = state.kinds
    val alarmCounts = state.content.alarmCounts
    val alarmTotal = state.content.alarmTotal
    val alarmFiredAt = state.content.alarmFiredAt
    val staleAfter = display.staleAfterMillis
    val outdatedAfter = display.staleAfterMillis
    val liveIds = live.liveWatchIds
    val columnSort = display.columnSort
    val changeBasis = display.changeBasis
    val haptics = LocalHapticFeedback.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    ReadableInset { inset ->
        Column(modifier = Modifier.fillMaxSize()) {
            // Fest oben (scrollt nicht mit): Kopfzeile mit Gruppen-Chips und Knöpfen,
            // darunter Status und Lupe. Puls, Aktivitätskarte und Paare scrollen darunter.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = 8.dp)
            ) {
                WatchlistHeader(
                    state = headerStateOf(
                        state = state,
                        activityChip = activityChip,
                        quickView = ui.quickView,
                        sortMode = ui.sortMode,
                        selecting = ui.selecting,
                        searching = ui.searching,
                        selectedCount = selectedIds.size,
                        shownCount = shown.size,
                        visibleCount = visible.size,
                        notTradedCount = notTradedIds.size,
                    ),
                    actions = WatchlistHeaderActions(
                        onSelectGroup = { group ->
                            ui.quickView = null
                            viewModel.selectGroup(group)
                        },
                        onEditGroup = { group ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ui.startEditingGroup(group)
                        },
                        onAddGroup = { ui.askNewGroup = true },
                        onSortDone = { ui.sortMode = false },
                        onBellClick = onBellClick,
                        onOpenAllAlarms = onOpenAllAlarms,
                        onOpenAbout = onOpenAbout,
                        onRefresh = requestRefresh,
                        lastFullRefreshAt = viewModel::lastFullRefreshAt,
                        // Manuelles Sortieren zeigt die eigene Reihenfolge: Spalten-Sortierung aus
                        onSort = {
                            viewModel.clearColumnSort()
                            ui.startSort()
                        },
                        onShowReport = { ui.showReport = true },
                        onRemoveNotTraded = { ui.askRemoveNotTraded = true },
                        onClearAll = { ui.askClearAll = true },
                        onToggleActivity = { ui.toggleQuickView(QuickView.ACTIVITY) },
                        onSelectAll = { ui.selectedIds = shown.mapTo(HashSet()) { it.id } },
                        onSelectionDone = ui::endSelection,
                        onStartSelection = ui::startSelection,
                    ),
                    bellScale = { bellScale.value },
                )
                WatchlistStatusRow(
                    // «Nur veraltete» bzw. «nur deaktivierte»: der Status zählt weiter über die ganze Gruppe («10 von 30»)
                    visible = if (ui.quickView == QuickView.STALE || ui.quickView == QuickView.DISABLED) groupVisible else visible,
                    now = now,
                    staleAfter = staleAfter,
                    online = live.online,
                    liveIds = liveIds,
                    reduceMotion = reduceMotion,
                    searching = ui.searching,
                    query = ui.query,
                    onQueryChange = { ui.query = it },
                    onOpenSearch = { ui.searching = true },
                    onCloseSearch = ui::closeSearch,
                    onAddPair = onAddClick,
                    // Lupe und «+»: beim Sortieren und Auswählen ausgeblendet
                    searchAvailable = !ui.sortMode && !ui.selecting,
                    staleOnly = ui.quickView == QuickView.STALE,
                    onToggleStale = { ui.toggleQuickView(QuickView.STALE) },
                    // «3 deaktiviert» neben dem Status: Tipp zeigt nur die deaktivierten bzw. wieder alle
                    disabledCount = disabledCount,
                    disabledOnly = ui.quickView == QuickView.DISABLED,
                    onToggleDisabled = { ui.toggleQuickView(QuickView.DISABLED) },
                    addLabelled = watches.size <= ADD_PAIR_LABEL_MAX_PAIRS,
                )
            }
            // Feine Linie unter dem festen Kopf, sobald die Liste darunter gescrollt ist
            val listScrolled by remember { derivedStateOf { listState.canScrollBackward } }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        // Volle Token-Farbe (keine halbtransparenten Linien), unsichtbar ungescrollt
                        if (listScrolled) MaterialTheme.colorScheme.outlineVariant else Color.Transparent
                    )
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                WatchlistList(
                    listState = listState,
                    inset = inset,
                    // Platz unten für den Sprungknopf bzw. die Leiste der Mehrfachauswahl
                    roomForJump = jump.eligible || ui.selecting,
                    pulse = pulse,
                    sortBar = if (!ui.sortMode && listed.size > 1) {
                        { WatchlistSortBar(sort = columnSort, onTap = viewModel::tapColumn) }
                    } else null,
                    sortMode = ui.sortMode,
                    // Gilt für die gezeigten Paare wie das Ziehen (gewählte Gruppe; ⚡/Suche sind im Sortiermodus aus)
                    onAutoOrder = if (ui.sortMode && visible.size > 1) reorder::autoOrder else null,
                    showGestureHint = display.showGestureHint && onboarding.gestureHint,
                    onDismissGestureHint = viewModel::dismissGestureHint,
                    // Kein Alarm, aber Paare: bleibende Karte zusätzlich zum 6-s-Banner nach dem Hinzufügen
                    onSetFirstAlarm = firstAlarmTarget?.takeIf {
                        FirstAlarmCard.shouldShow(
                            alarmCount = alarmTotal,
                            watchCount = watches.size,
                            dismissed = display.firstAlarmCardDismissed,
                            busy = ui.searching || ui.sortMode || ui.selecting,
                        )
                    }?.let { first -> { onOpenAlarms(first.id) } },
                    onDismissFirstAlarm = viewModel::dismissFirstAlarmCard,
                    firstAlarmPair = firstAlarmTarget?.displayName,
                    noSearchMatch = filtering && shown.isEmpty(),
                    query = trimmedQuery,
                    groupEmpty = visible.isEmpty() && !filtering,
                    favoritesEmpty = visible.isEmpty() && !filtering && WatchFilter.isFavorites(selectedGroup),
                    // Eigene Gruppe (wie die Chips mit langem Drücken; TradFi ist automatisch)
                    onEditGroup = selectedGroup?.takeIf { ui.quickView == null && it in groups && it != AutoGroup.TRADFI }
                        ?.let { group -> { ui.startEditingGroup(group) } },
                    shown = listed,
                ) { watch, shape ->
                    WatchlistItem(
                        watch = watch,
                        shape = shape,
                        reorder = reorder,
                        searching = ui.searching,
                        sortMode = ui.sortMode,
                        selecting = ui.selecting,
                        selected = watch.id in selectedIds,
                        onToggleSelected = { ui.toggleSelected(watch.id) },
                        sparklines = fetchSparklines,
                        cachedSparkline = viewModel::cachedSparkline,
                        loadSparkline = viewModel::sparkline,
                        sparklineSource = viewModel::foreignSparklineSource,
                        changeSource = viewModel::foreignCandleSource,
                        moment = moment,
                        reduceMotion = reduceMotion,
                        livePrices = livePrices,
                        rollingBasis = !changeBasis.isDay,
                        alarmCount = alarmCounts[watch.id] ?: 0,
                        alarmFiredAt = alarmFiredAt[watch.id] ?: 0L,
                        kind = kinds[watch.id] ?: AssetKind.TOKEN,
                        now = now,
                        // Am Live-Strom: veraltet schon nach 2 Min. ohne Kurs (wie die Status-Pille)
                        staleAfter = LiveStatus.staleAfterMillis(watch.id in liveIds, staleAfter),
                        outdatedAfter = LiveStatus.staleAfterMillis(watch.id in liveIds, outdatedAfter),
                        convertTarget = display.convertTarget,
                        convertRates = live.convertRates,
                        convertFxTimes = live.convertFxTimes,
                        hasActivity = watch.id in activeSignals,
                        onOpenWhy = { ui.whyFor = watch.id },
                        onOpenActions = { ui.actionsFor = watch.id },
                        onMove = { viewModel.move(watch, it) },
                        onDelete = { deleteWithUndo(watch) },
                        onFavoriteWithBanner = { favoriteWithBanner(watch) },
                        onStartSelection = { ui.startSelectionWith(watch.id) },
                    )
                }
                // Rund, unten am Ende über der Tableiste; die Liste hat unten Platz dafür.
                // Steht ein Banner («… entfernt», «Rückgängig»), rückt der Knopf darüber.
                WatchlistJumpButton(
                    jump = jump,
                    bannerShown = banner.host.currentSnackbarData != null,
                    reduceMotion = reduceMotion,
                    endInset = inset,
                )
                // Mehrfachauswahl: Favorit · Gruppe · Löschen für die angehakten Paare
                if (ui.selecting) {
                    val chosen = watches.filter { it.id in selectedIds }
                    val allDisabled = chosen.isNotEmpty() && chosen.all { it.disabled }
                    SelectionBar(
                        count = selectedIds.size,
                        allFavorites = chosen.isNotEmpty() && chosen.all { it.favorite },
                        onFavorite = {
                            viewModel.setFavorite(selectedIds, !chosen.all { it.favorite })
                            ui.endSelection()
                        },
                        onGroup = { ui.askGroupForSelection = true },
                        // Alle deaktiviert: wieder aktivieren, sonst alle deaktivieren
                        allDisabled = allDisabled,
                        onToggleDisabled = {
                            viewModel.setDisabled(selectedIds.toList(), !allDisabled)
                            ui.endSelection()
                        },
                        onDelete = {
                            val ids = selectedIds.toList()
                            ui.endSelection()
                            viewModel.deleteSelectedWithUndo(ids) { count ->
                                if (count > 0) {
                                    banner.show(
                                        resources.getQuantityString(R.plurals.watchlist_removed_count, count, count)
                                    ) { viewModel.undoDelete(SELECTION_UNDO_KEY) }
                                }
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
                        onSelect = { group ->
                            viewModel.setGroup(selectedIds, group)
                            ui.endSelection()
                        },
                        onDismiss = { ui.askGroupForSelection = false },
                    )
                }
            }
        }
    }
}
