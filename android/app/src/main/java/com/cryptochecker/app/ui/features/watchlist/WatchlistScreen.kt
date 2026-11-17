@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.annotation.SuppressLint
import android.content.res.Resources
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.FirstAlarmCard
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.LocalRowHighlight
import com.cryptochecker.app.ui.navigation.OnTabReselect
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.theme.rememberReduceMotion
import com.cryptochecker.resources.R
import kotlinx.coroutines.CoroutineScope

/**
 * Die Merkliste: fester Kopf (Gruppen, Glocke, Menü, Status mit Lupe und «+» bzw. Suche), darunter Puls,
 * Aktivitätskarte und die Paare. Aufgeteilt nach Aufgaben — Kopf [WatchlistHeader], Status
 * [WatchlistStatusRow], Liste [WatchlistList] und Zeile [WatchlistItem], Blätter und Rückfragen
 * [WatchlistSheets] (ein Paar selbst öffnet die Paarseite, [onOpenPair]), Zustand aus dem ViewModel [WatchlistUiState] (einmal gesammelt) und des Bildschirms
 * [WatchlistScreenState], Banner [WatchlistBanner], Sprungknopf [WatchlistJump], fester Kopf und Liste samt
 * Mehrfachauswahl [WatchlistBody], Wege in die Merkliste [WatchlistNavigationEffects], Start-Auswahl
 * [WatchlistStarterPicker]. Hier werden Zustand und Ableitungen gesammelt und zusammengesetzt.
 */
@Composable
fun WatchlistScreen(
    onAddClick: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    /** Tipp auf eine Zeile: Paarseite (eigene Seite mit Zurück). */
    onOpenPair: (Long) -> Unit = {},
    /** Mitteilung bzw. Widget-Zeile: Paarseite, sobald die Merkliste geladen ist. */
    onFocusPair: (Long) -> Unit = onOpenPair,
    onOpenAllAlarms: () -> Unit = {},
    /** Einstellungen › Alarme › Markt-Meldungen (Empfindlichkeit). */
    onOpenActivitySettings: () -> Unit = {},
    /** Logo mit App-Namen im Überlaufmenü: «Über die App». */
    onOpenAbout: () -> Unit = {},
    /** «Was bewegt sich mit?» aus einer Alarm-Meldung: Blatt dieses Paars öffnen. */
    openWhyWatchId: Long? = null,
    onOpenWhyHandled: () -> Unit = {},
    /** Tipp auf eine Mitteilung bzw. Widget-Zeile: Paarseite dieses Paars öffnen (wie iOS `watch/<id>`). */
    focusWatchId: Long? = null,
    onFocusHandled: () -> Unit = {},
    viewModel: WatchlistViewModel = hiltViewModel(),
) {
    // Alles aus dem ViewModel in einem Stand (ein Sammeln statt rund 30); die Live-Kurse unten getrennt
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val display = state.display
    val live = state.live
    val watches = state.watches
    val loaded = state.loaded
    val selectedGroup = state.selectedGroup
    // Ansicht der Gruppen-Chips: alle Paare, die Favoriten (★) oder nur die der gewählten Gruppe
    // Art je Paar (Symbol vor der Glocke, «Alpha»)
    val kinds = state.kinds
    val groupVisible = remember(watches, selectedGroup, kinds) { filterByGroup(watches, selectedGroup, kinds) }
    val alarmTotal = state.content.alarmTotal
    // Glocke (Kopf und Start-Auswahl): mit Alarmen die Übersicht, ohne einen einzigen gleich zum Anlegen —
    // ein Paar: dessen Alarm-Seite (öffnet den Editor), mehrere: erst wählen, leere Merkliste: Coin hinzufügen
    val onBellClick = rememberBellClick(alarmTotal, watches, onOpenAllAlarms, onOpenAlarms, onAddClick)
    // Eine Frische-Schwelle für Änderung, Zeile und Screenreader
    val staleAfter = display.staleAfterMillis

    // Uhr für «vor 2 Min.» — alle 30 Sekunden neu ([rememberWatchlistClock])
    val now = rememberWatchlistClock()
    // Paare am Live-Strom: eigene Frische-Schwelle (2 Min.) und «Live» nur, wenn alle dabei sind
    val liveIds = live.liveWatchIds
    WatchlistVisibleEffects(viewModel, loaded)

    // Ungewöhnliche Aktivität: nur noch gültige Signale, je Paar stärkstes zuerst
    // Nach der gewählten Empfindlichkeit neu beurteilt (gleiche Schwellen wie die Meldungen)
    // Nicht mehr gehandelte Paare: kein ⚡, nicht in der Karte (auch bevor die Auswertung aufräumt)
    val signals = rememberActivitySignals(watches, state.content.activity, now, display.activitySensitivity)
    val hot = signals.hot

    val ui = rememberWatchlistScreenState()
    // ⚡ nur mit Paaren, bei denen gerade etwas passiert (abschaltbar unter Einstellungen › Merkliste)
    val activityChip = display.activityCardEnabled && hot.isNotEmpty()
    // Deaktivierte Paare der Gruppen-Ansicht: Hinweis «3 deaktiviert» neben dem Status (0 = keiner)
    val disabledCount = remember(groupVisible) { DisabledView.count(groupVisible) { it.disabled } }
    // Gezeigt: die Gruppen-Ansicht oder vorübergehend ⚡, «nur veraltete» bzw. «nur deaktivierte» ([QuickView])
    val visible = rememberVisibleWatches(ui, groupVisible, hot, now, staleAfter, liveIds, activityChip)
    // Mehrfachauswahl: nur Paare, die es noch gibt und die zu sehen sind
    val selectedIds = remember(ui.selectedIds, visible) { visibleSelection(ui.selectedIds, visible) }
    WatchlistLivePairs(viewModel, visible)
    // Rückkehr aus «Alarme», Blatt aus einer Meldung bzw. Widget-Zeile, Zurück-Taste ([WatchlistNavigationEffects])
    WatchlistNavigationEffects(
        ui = ui,
        viewModel = viewModel,
        watches = watches,
        groupVisible = groupVisible,
        selectedGroup = selectedGroup,
        loaded = loaded,
        openWhyWatchId = openWhyWatchId,
        onOpenWhyHandled = onOpenWhyHandled,
        focusWatchId = focusWatchId,
        onFocusHandled = onFocusHandled,
        onFocusPair = onFocusPair,
    )

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val reduceMotion = rememberReduceMotion()
    // Erneutes Tippen auf den Tab «Merkliste»: an den Anfang der Liste (wie iOS)
    OnTabReselect(ScreenRoute.Watchlist) {
        if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
    val banner = rememberWatchlistBanner(scope)
    WatchlistScreenScaffold(
        state = state,
        viewModel = viewModel,
        ui = ui,
        listState = listState,
        scope = scope,
        resources = resources,
        reduceMotion = reduceMotion,
        banner = banner,
        now = now,
        visible = visible,
        groupVisible = groupVisible,
        signals = signals,
        activityChip = activityChip,
        selectedIds = selectedIds,
        disabledCount = disabledCount,
        onBellClick = onBellClick,
        onAddClick = onAddClick,
        onOpenAllAlarms = onOpenAllAlarms,
        onOpenAbout = onOpenAbout,
        onOpenAlarms = onOpenAlarms,
        onOpenPair = onOpenPair,
    )
}

/**
 * Banner, Aktualisieren, Sortieren und Suche der Merkliste samt Gerüst. Nicht neu startbar: liest den Zustand wie
 * zuvor im Rumpf von [WatchlistScreen] (gleiche Neuzusammensetzung, gleicher Start der Flows).
 */
@NonRestartableComposable
@Composable
private fun WatchlistScreenScaffold(
    state: WatchlistUiState,
    viewModel: WatchlistViewModel,
    ui: WatchlistScreenState,
    listState: LazyListState,
    scope: CoroutineScope,
    resources: Resources,
    reduceMotion: Boolean,
    banner: WatchlistBanner,
    now: Long,
    visible: List<WatchEntity>,
    groupVisible: List<WatchEntity>,
    signals: ActivitySignals,
    activityChip: Boolean,
    selectedIds: Set<Long>,
    disabledCount: Int,
    onBellClick: () -> Unit,
    onAddClick: () -> Unit,
    onOpenAllAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    onOpenPair: (Long) -> Unit,
) {
    val display = state.display
    val live = state.live
    val watches = state.watches
    val fetchSparklines = rememberSparklinesFit(display.sparklineEnabled)
    // Live-Kurse (WebSocket) für die Paare der Ansicht, solange die Merkliste zu sehen ist
    // Als State (nicht «by»): gelesen wird nur in den Zeilen bzw. im Aktionsblatt ([WithLiveQuote]),
    // ein Tick setzt also nicht den ganzen Bildschirm neu zusammen
    val livePrices = viewModel.livePrices.collectAsStateWithLifecycle()
    val markFallbackIds by viewModel.markFallbackIds.collectAsStateWithLifecycle()
    val hourlyCloseIds by viewModel.hourlyCloseIds.collectAsStateWithLifecycle()
    val exchangePauses by viewModel.exchangePauses.collectAsStateWithLifecycle()
    // Alarm löst bei offener App aus: Glocke im Kopf pulsiert einmal (ohne Bewegung: nicht)
    val bellScale = rememberBellPulse(state.content.latestAlarmTrigger, reduceMotion)
    // Erst-Moment (Start-Merkliste, allererstes Paar) samt Frage nach Mitteilungen ([rememberStarterMoment])
    val starterMoment = rememberStarterMoment(viewModel, watches, state.loaded, state.content.alarmCounts, banner, onOpenAlarms)

    // Alles aktualisieren (nach unten ziehen, Menü; [rememberPullRefresh])
    val pull = rememberPullRefresh(viewModel, scope, banner, resources)
    // Wischen, Screenreader-Aktion und Aktionen-Blatt «Löschen» (ohne Rückfrage, mit «Rückgängig», weiteres Löschen als
    // Sammel-Banner) bzw. nach rechts wischen (Favorit): Haptik und Banner über Ereignisse im
    // aktuellen Bildschirm ([WatchlistUiEffects] in [WatchlistSheets])
    val uiEvents = rememberWatchUiEvents(WATCHLIST_UI_EVENTS)

    val reorder = rememberReorderState(listState, scope, visible, viewModel::reorder)
    val ordered = reorder.items ?: visible
    // Aktive Suche filtert zusätzlich zur gewählten Gruppe
    val trimmedQuery = ui.query.trim()
    val filtering = ui.searching && trimmedQuery.isNotEmpty()
    // Namen der Coins («bitcoin» findet BTC), einmal je Liste
    val searchNames = rememberSearchNames(visible)
    // Suchschlüssel je Paar einmal gefaltet, solange die Suche offen ist; je Tastendruck nur die Anfrage
    val shown = rememberWatchSearchResult(ordered, if (filtering) trimmedQuery else "", searchNames, ui.searching)
    // Kein Sprungknopf in der Mehrfachauswahl: unten steht dann die Leiste
    val jump = rememberWatchlistJump(listState, shown.size, ui.sortMode || ui.selecting, reduceMotion, scope)

    // Ohne eigene Kopfzeile: den Titel bekommt der Screenreader als paneTitle (Knöpfe in der Gruppen-Zeile)
    val screenTitle = stringResource(R.string.tab_watchlist)
    // %-Basis für Änderungen; passt der Stempel nicht (Basis gewechselt, neuer Tag), «—» bis neu gerechnet
    val changeBasis = display.changeBasis
    val changeStamp = live.changeStamp
    val changeView = remember(changeBasis, changeStamp, now) { ChangeView.of(changeStamp, changeBasis, now) }
    // Sortieren nach Spalte (nicht im Sortiermodus): Favoriten oben, unter sich sortiert; Kurse
    // über CHF verglichen (im Hintergrund umgerechnet, auch ohne «≈ Umrechnung»)
    val columnSort = display.columnSort
    val sortRates = live.sortRates
    // Karte «Ersten Alarm setzen»: erstes Paar der gespeicherten Reihenfolge, nicht der sortierten Ansicht
    val firstAlarmTarget = remember(watches) { FirstAlarmCard.target(watches) { it.disabled } }
    val listed = rememberColumnSorted(shown, columnSort, sortRates, changeView, ui.sortMode)
    // ★/⚡ in den Zeilen nur farbig, solange der passende Chip gewählt ist
    // ebenso das Art-Symbol vor der Glocke (Token, TradFi, α)
    val rowHighlight = rowHighlightFor(ui.quickView, state.selectedGroup)
    CompositionLocalProvider(
        LocalChangeView provides changeView,
        LocalRowHighlight provides rowHighlight,
        com.cryptochecker.app.ui.components.LocalPriceNotificationsOn provides display.priceNotificationsOn,
        LocalMarkFallback provides markFallbackIds,
        LocalHourlyClose provides hourlyCloseIds,
        LocalExchangePauses provides exchangePauses,
    ) {
        Scaffold(
            modifier = Modifier.semantics {
                paneTitle = screenTitle
                // testTags als Ressourcen-Id: für den Baseline-Profile-Generator (UiAutomator)
                testTagsAsResourceId = true
            },
            snackbarHost = { AppSnackbarHost(banner.host) },
        ) { padding ->
            WatchlistScaffoldContent(
                padding = padding,
                state = state,
                ui = ui,
                viewModel = viewModel,
                banner = banner,
                signals = signals,
                changeView = changeView,
                pull = pull,
                starterMoment = starterMoment,
                activityChip = activityChip,
                selectedIds = selectedIds,
                shown = shown,
                visible = visible,
                groupVisible = groupVisible,
                listed = listed,
                filtering = filtering,
                trimmedQuery = trimmedQuery,
                now = now,
                reduceMotion = reduceMotion,
                listState = listState,
                jump = jump,
                reorder = reorder,
                bellScale = bellScale,
                livePrices = livePrices,
                fetchSparklines = fetchSparklines,
                firstAlarmTarget = firstAlarmTarget,
                disabledCount = disabledCount,
                onBellClick = onBellClick,
                onOpenAllAlarms = onOpenAllAlarms,
                onOpenAbout = onOpenAbout,
                onAddClick = onAddClick,
                onOpenAlarms = onOpenAlarms,
                onOpenPair = onOpenPair,
                deleteWithUndo = { uiEvents.deleteWithUndo(viewModel, it) },
                favoriteWithBanner = { uiEvents.favoriteWithBanner(viewModel, it) },
            )
        }
    }
}

/**
 * Mini-Chart nur mit genug Breite, damit der Paarname nicht gequetscht wird ([sparklinesFit]). Bewusst die
 * Bildschirmbreite aus der Configuration; ein Umbau auf containerSize wäre eine eigene Änderung.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun rememberSparklinesFit(enabled: Boolean): Boolean =
    sparklinesFit(enabled, LocalConfiguration.current.screenWidthDp, LocalDensity.current.fontScale)
