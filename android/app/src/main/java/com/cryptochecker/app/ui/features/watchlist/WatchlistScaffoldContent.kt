// Inhalt unter dem Gerüst der Merkliste ([WatchlistScreen]). Eigene Datei, weil WatchlistScreen.kt sonst zu lang würde.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeView

/** Inhalt unter dem Gerüst der Merkliste: Blätter, Gerüst bzw. Start-Auswahl oder Kopf und Liste. Nicht neu startbar. */
@NonRestartableComposable
@Composable
internal fun WatchlistScaffoldContent(
    padding: PaddingValues,
    state: WatchlistUiState,
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    banner: WatchlistBanner,
    signals: ActivitySignals,
    changeView: ChangeView,
    pull: PullRefresh,
    starterMoment: StarterMoment,
    activityChip: Boolean,
    selectedIds: Set<Long>,
    shown: List<WatchEntity>,
    visible: List<WatchEntity>,
    groupVisible: List<WatchEntity>,
    listed: List<WatchEntity>,
    filtering: Boolean,
    trimmedQuery: String,
    now: Long,
    reduceMotion: Boolean,
    listState: LazyListState,
    jump: WatchlistJump,
    reorder: ReorderState,
    bellScale: Animatable<Float, AnimationVector1D>,
    livePrices: State<Map<Long, LiveQuote>>,
    fetchSparklines: Boolean,
    firstAlarmTarget: WatchEntity?,
    disabledCount: Int,
    onBellClick: () -> Unit,
    onOpenAllAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    onAddClick: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    onOpenPair: (Long) -> Unit,
    deleteWithUndo: (WatchEntity) -> Unit,
    favoriteWithBanner: (WatchEntity) -> Unit,
) {
    val watches = state.watches
    val alarmTotal = state.content.alarmTotal
    val notTradedIds = signals.notTradedIds
    val activeSignals = signals.activeSignals
    WatchlistSheets(
        ui = ui,
        state = state,
        viewModel = viewModel,
        watches = watches,
        groups = state.groups,
        notTradedCount = notTradedIds.size,
        rollingBasis = !state.display.changeBasis.isDay,
        activeSignals = activeSignals,
        now = now,
        banner = banner,
    )
    // «Mehrere Paare auf einmal» läuft noch: Balken oben, über der Liste
    WatchlistBulkAdd(banner, Modifier.padding(padding).zIndex(1f))

    // Noch nicht geladen (Gerüst) bzw. leer (Start-Auswahl): [showsNotListed]
    if (showsNotListed(
            state.loaded,
            watches.isEmpty(),
            Modifier.padding(padding),
            state.starter,
            viewModel,
            onAddClick,
            // Glocke nur, wenn sie mehr kann als «+» (Alarme vorhanden, z. B. im Portfolio)
            onOpenAlarms = onBellClick.takeIf { (alarmTotal ?: 0) > 0 },
            onStartersAdded = starterMoment.askAfterHint,
        )
    ) {
        return
    }

    // Liste nach unten ziehen = alles aktualisieren (wie der Knopf oben).
    PullToRefreshBox(
        isRefreshing = state.live.refreshing || pull.echo,
        onRefresh = pull.request,
        modifier = Modifier.fillMaxSize().padding(padding)
    ) {
        // Puls ganz oben: wie viele steigen/fallen, Ø-Veränderung — nur für die gezeigte
        // Gruppe, nicht beim Suchen oder Sortieren
        // Nur Paare mit Kurs (und noch gehandelt) zählen als «ohne 24h-Wert». Gemerkt, damit
        // nicht jede Neuzusammensetzung (z. B. die 30-s-Uhr) hunderte Paare neu durchgeht.
        // Erster Gebrauch: kein Puls, bis es einen Alarm gibt bzw. nach einigen Tagen (WatchlistOnboarding)
        val onboarding = state.onboarding
        val pulse = rememberWatchPulse(visible, ui.searching, ui.sortMode, changeView, onboarding.pulse)
        KeepTopWhenPulseAppears(pulseShown = pulse != null, listState = listState)
        // Kopf und Liste: [WatchlistBody]
        WatchlistBody(
            state = state,
            ui = ui,
            viewModel = viewModel,
            onboarding = onboarding,
            activityChip = activityChip,
            selectedIds = selectedIds,
            shown = shown,
            visible = visible,
            groupVisible = groupVisible,
            notTradedIds = notTradedIds,
            listed = listed,
            filtering = filtering,
            trimmedQuery = trimmedQuery,
            now = now,
            reduceMotion = reduceMotion,
            listState = listState,
            jump = jump,
            reorder = reorder,
            pulse = pulse,
            bellScale = bellScale,
            moment = starterMoment.moment,
            livePrices = livePrices,
            fetchSparklines = fetchSparklines,
            activeSignals = activeSignals,
            firstAlarmTarget = firstAlarmTarget,
            disabledCount = disabledCount,
            banner = banner,
            onBellClick = onBellClick,
            onOpenAllAlarms = onOpenAllAlarms,
            onOpenAbout = onOpenAbout,
            onAddClick = onAddClick,
            onOpenAlarms = onOpenAlarms,
            onOpenPair = onOpenPair,
            requestRefresh = pull.request,
            deleteWithUndo = deleteWithUndo,
            favoriteWithBanner = favoriteWithBanner,
        )
    }
}
