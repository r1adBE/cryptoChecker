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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.WatchlistOnboarding
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.features.alarms.AlarmReliabilityLine

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
    /** Tipp auf eine Zeile: Paarseite. */
    onOpenPair: (Long) -> Unit,
    requestRefresh: () -> Unit,
    deleteWithUndo: (WatchEntity) -> Unit,
    favoriteWithBanner: (WatchEntity) -> Unit,
) {
    val haptics = rememberHaptics()
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
                WatchlistFixedHead(
                    state = state,
                    ui = ui,
                    viewModel = viewModel,
                    haptics = haptics,
                    activityChip = activityChip,
                    selectedIds = selectedIds,
                    shown = shown,
                    visible = visible,
                    groupVisible = groupVisible,
                    notTradedIds = notTradedIds,
                    now = now,
                    reduceMotion = reduceMotion,
                    bellScale = bellScale,
                    disabledCount = disabledCount,
                    onBellClick = onBellClick,
                    onOpenAllAlarms = onOpenAllAlarms,
                    onOpenAbout = onOpenAbout,
                    onAddClick = onAddClick,
                    requestRefresh = requestRefresh,
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
                WatchlistListArea(
                    state = state,
                    ui = ui,
                    viewModel = viewModel,
                    haptics = haptics,
                    inset = inset,
                    listState = listState,
                    jump = jump,
                    reorder = reorder,
                    pulse = pulse,
                    visible = visible,
                    shown = shown,
                    listed = listed,
                    filtering = filtering,
                    trimmedQuery = trimmedQuery,
                    selectedIds = selectedIds,
                    now = now,
                    reduceMotion = reduceMotion,
                    moment = moment,
                    livePrices = livePrices,
                    fetchSparklines = fetchSparklines,
                    activeSignals = activeSignals,
                    firstAlarmTarget = firstAlarmTarget,
                    onboarding = onboarding,
                    banner = banner,
                    onOpenAlarms = onOpenAlarms,
                    onOpenPair = onOpenPair,
                    deleteWithUndo = deleteWithUndo,
                    favoriteWithBanner = favoriteWithBanner,
                )
            }
        }
    }
}

/** Fester Kopf von [WatchlistBody]: Kopfzeile, Status mit Lupe und die ruhigen Hinweiszeilen (in dessen Spalte). */
@Composable
private fun WatchlistFixedHead(
    state: WatchlistUiState,
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    haptics: HapticPlayer,
    activityChip: Boolean,
    selectedIds: Set<Long>,
    shown: List<WatchEntity>,
    visible: List<WatchEntity>,
    groupVisible: List<WatchEntity>,
    notTradedIds: Set<Long>,
    now: Long,
    reduceMotion: Boolean,
    bellScale: Animatable<Float, AnimationVector1D>,
    disabledCount: Int,
    onBellClick: () -> Unit,
    onOpenAllAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    onAddClick: () -> Unit,
    requestRefresh: () -> Unit,
) {
    val live = state.live
    val watches = state.watches
    // Eine Frische-Schwelle für Änderung, Zeile und Screenreader
    val staleAfter = state.display.staleAfterMillis
    val liveIds = live.liveWatchIds

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
                haptics.play(HapticEvent.LONG_PRESS)
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
        retryAt = live.widgetRetryAt,
    )
    // Logos laden noch (nach der Installation bzw. nach dem Leeren des Zwischenspeichers): eine ruhige Zeile
    if (!ui.sortMode && !ui.selecting && !ui.searching) WatchlistLogoHint(reduceMotion = reduceMotion)
    // Danach noch Coins ohne Logo (Initialen): «2 Coins ohne Logo» an derselben Stelle, mit ✕
    if (!ui.sortMode && !ui.selecting && !ui.searching) WatchlistMissingLogos(watches, reduceMotion = reduceMotion)
    // Börse stillgelegt (ExchangeRetirement): einmal erklären, mit ✕
    if (!ui.sortMode && !ui.selecting && !ui.searching) WatchlistRetiredNotice(watches, now = now, reduceMotion = reduceMotion)
    // Kommen die Alarme an? Eine ruhige Zeile, nur mit Alarmen (eigene Komponente)
    if (!ui.sortMode && !ui.selecting && !ui.searching) AlarmReliabilityLine(now = now)
}
