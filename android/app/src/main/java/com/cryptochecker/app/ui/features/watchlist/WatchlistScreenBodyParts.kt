// Liste unter dem festen Kopf der Merkliste ([WatchlistBody]). Eigene Datei, weil WatchlistScreenBody.kt sonst zu lang
// würde.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.FirstAlarmCard
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.WatchlistFooter
import com.cryptochecker.app.domain.watch.WatchlistOnboarding
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer

/** Liste von [WatchlistBody] mit Sprungknopf und Leiste der Mehrfachauswahl (im Rahmen unter dem festen Kopf). */
@Composable
internal fun BoxScope.WatchlistListArea(
    state: WatchlistUiState,
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    haptics: HapticPlayer,
    inset: Dp,
    listState: LazyListState,
    jump: WatchlistJump,
    reorder: ReorderState,
    pulse: WatchPulse?,
    visible: List<WatchEntity>,
    shown: List<WatchEntity>,
    listed: List<WatchEntity>,
    filtering: Boolean,
    trimmedQuery: String,
    selectedIds: Set<Long>,
    now: Long,
    reduceMotion: Boolean,
    moment: AddMoment?,
    livePrices: State<Map<Long, LiveQuote>>,
    fetchSparklines: Boolean,
    activeSignals: Map<Long, List<ActivitySignal>>,
    firstAlarmTarget: WatchEntity?,
    onboarding: WatchlistOnboarding.Visible,
    banner: WatchlistBanner,
    onOpenAlarms: (Long) -> Unit,
    onOpenPair: (Long) -> Unit,
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
    // Eine Frische-Schwelle für Änderung, Zeile und Screenreader
    val staleAfter = display.staleAfterMillis
    val liveIds = live.liveWatchIds
    val columnSort = display.columnSort
    val changeBasis = display.changeBasis

    WatchlistList(
        listState = listState,
        inset = inset,
        // Platz unten für den Sprungknopf bzw. die Leiste der Mehrfachauswahl
        roomForJump = jump.eligible || ui.selecting,
        pulse = pulse,
        sortBar = if (!ui.sortMode && listed.size > 1) {
            { WatchlistSortBar(sort = columnSort, onTap = viewModel::tapColumn) }
        } else {
            null
        },
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
        // Gezeigte Zeilen von der ganzen Merkliste; in der Mehrfachauswahl steht unten die Leiste
        footer = WatchlistFooter.of(shown = listed.size, total = watches.size, selecting = ui.selecting),
    ) { watch, shape ->
        WatchlistItem(
            watch = watch,
            shape = shape,
            reorder = reorder,
            searching = ui.searching,
            sortMode = ui.sortMode,
            selecting = ui.selecting,
            selected = watch.id in selectedIds,
            onToggleSelected = {
                haptics.play(HapticEvent.SELECTION)
                ui.toggleSelected(watch.id)
            },
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
            // Am Live-Strom: 2 Min. ab dem letzten Takt, nicht ab dem letzten Kurswechsel (wie die Status-Pille)
            staleAfter = LiveStatus.staleAfterMillis(watch.id in liveIds, staleAfter),
            onLiveSocket = watch.id in liveIds,
            convertTarget = display.convertTarget,
            convertRates = live.convertRates,
            convertFxTimes = live.convertFxTimes,
            convertUsdtNotes = live.convertUsdtNotes,
            hasActivity = watch.id in activeSignals,
            onOpenWhy = { ui.whyFor = watch.id },
            onOpenActions = { onOpenPair(watch.id) },
            onMove = { viewModel.move(watch, it) },
            onDelete = { deleteWithUndo(watch) },
            onFavoriteWithBanner = { favoriteWithBanner(watch) },
            onStartSelection = { ui.startSelectionWith(watch.id) },
            onSourceHint = { text -> banner.show(text) },
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
    // Mehrfachauswahl: Leiste, Gruppe wählen, Vergleich ([WatchlistSelectionLayer])
    WatchlistSelectionLayer(ui, viewModel, watches, groups, selectedIds, inset)
}
