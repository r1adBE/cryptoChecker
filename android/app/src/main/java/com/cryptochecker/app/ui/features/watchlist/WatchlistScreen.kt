@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.ui.platform.LocalResources
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.domain.watch.ActivityView
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.convert.CurrencyConversion
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.cryptochecker.app.ui.components.RowHighlight
import com.cryptochecker.app.ui.components.LocalRowHighlight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.util.StartupClock
import kotlinx.coroutines.delay

/**
 * Die Merkliste: fester Kopf (Gruppen, Glocke, Menü, Status mit Lupe und «+» bzw. Suche), darunter Puls,
 * Aktivitätskarte und die Paare. Aufgeteilt nach Aufgaben — Kopf [WatchlistHeader], Status
 * [WatchlistStatusRow], Liste [WatchlistList] und Zeile [WatchlistItem], Blätter und Rückfragen
 * [WatchlistSheets], Zustand [WatchlistUiState], Banner [WatchlistBanner], Sprungknopf
 * [WatchlistJump]. Hier wird nur zusammengesetzt.
 */
@Composable
fun WatchlistScreen(
    onAddClick: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    onOpenAllAlarms: () -> Unit = {},
    /** Einstellungen → Markt-Meldungen (Empfindlichkeit); früher «Anpassen» in der Aktivitätskarte. */
    onOpenActivitySettings: () -> Unit = {},
    /** Logo mit App-Namen im Überlaufmenü: «Über die App». */
    onOpenAbout: () -> Unit = {},
    /** «Warum?» aus einer Alarm-Meldung: «Warum bewegt sich das?» dieses Paars öffnen. */
    openWhyWatchId: Long? = null,
    onOpenWhyHandled: () -> Unit = {},
    viewModel: WatchlistViewModel = hiltViewModel(),
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
) {
    val watchesOrNull by viewModel.watchesOrNull.collectAsStateWithLifecycle()
    val watches = watchesOrNull.orEmpty()
    val groupFilter by viewModel.groupFilter.collectAsStateWithLifecycle()
    val loaded = watchesOrNull != null && groupFilter != null
    val groups = groupFilter?.groups.orEmpty()
    val selectedGroup = groupFilter?.selected
    // Ansicht der Gruppen-Chips: alle Paare, die Favoriten (★) oder nur die der gewählten Gruppe
    val groupVisible = remember(watches, selectedGroup) {
        if (selectedGroup == null) watches
        else watches.filter { WatchFilter.matches(selectedGroup, it.groupName, it.favorite) }
    }
    val alarmCounts by viewModel.alarmCounts.collectAsStateWithLifecycle()
    val latestAlarmTrigger by viewModel.latestAlarmTrigger.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val showGestureHint by viewModel.showGestureHint.collectAsStateWithLifecycle()
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val outdatedAfter by viewModel.outdatedAfterMillis.collectAsStateWithLifecycle()
    val convertTarget by viewModel.convertTarget.collectAsStateWithLifecycle()
    val convertRates by viewModel.convertRates.collectAsStateWithLifecycle()
    val sparklineEnabled by viewModel.sparklineEnabled.collectAsStateWithLifecycle()
    val activityCardEnabled by viewModel.activityCardEnabled.collectAsStateWithLifecycle()
    // Mini-Chart nur mit genug Breite, damit der Paarname nicht gequetscht wird;
    // grössere Schrift braucht entsprechend mehr Platz.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val fetchSparklines = sparklineEnabled && screenWidthDp >= SPARKLINE_MIN_SCREEN_DP * fontScale.coerceAtLeast(1f)

    // Uhr für «vor 2 Min.» — alle 30 Sekunden neu
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // Kein Netz: ruhige Statuszeile statt Fehlerzuständen (es wird dann nicht aktualisiert)
    val online by viewModel.online.collectAsStateWithLifecycle()
    // Live-Kurse (WebSocket) für die Paare der Ansicht, solange die Merkliste zu sehen ist
    val liveExchanges by viewModel.liveExchanges.collectAsStateWithLifecycle()
    // Als State (nicht «by»): gelesen wird nur in den Zeilen bzw. im Aktionsblatt ([WithLiveQuote]),
    // ein Tick setzt also nicht den ganzen Bildschirm neu zusammen
    val livePrices = viewModel.livePrices.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        viewModel.setLiveVisible(true)
        onStopOrDispose { viewModel.setLiveVisible(false) }
    }

    // App-Start messen: erstes Bild der Merkliste aus dem Zwischenspeicher (nur lokal, Bericht «Ablauf»)
    ReportDrawnWhen { loaded }
    LaunchedEffect(loaded) {
        if (loaded) {
            withFrameNanos { }
            StartupClock.onFirstFrame()?.let(viewModel::recordAppStart)
        }
    }

    // Ungewöhnliche Aktivität: nur noch gültige Signale, je Paar stärkstes zuerst
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val sensitivity by viewModel.activitySensitivity.collectAsStateWithLifecycle()
    // Nach der gewählten Empfindlichkeit neu beurteilt (gleiche Schwellen wie die Meldungen)
    // Nicht mehr gehandelte Paare: kein ⚡, nicht in der Karte (auch bevor die Auswertung aufräumt)
    val notTradedIds = remember(watches) { NotTraded.ids(watches, { it.id }, { it.lastError }) }
    val activeSignals = remember(activity, now, sensitivity, notTradedIds) {
        NotTraded.withoutIds(activity, notTradedIds)
            .mapValues { ActivityAnalyzer.applySensitivity(it.value.active(now), sensitivity) }
            .filterValues { it.isNotEmpty() }
    }
    // ⚡-Chip: Paare mit Signalen (alle Gruppen), starke zuerst, sonst Listenreihenfolge; mit
    // Empfindlichkeit «Weniger» nur die Paare der stärksten Coins
    val hot = remember(watches, activeSignals, sensitivity) {
        ActivityView.pick(
            watches.filter { it.id in activeSignals }
                .sortedByDescending { activeSignals[it.id]?.firstOrNull()?.severity?.ordinal ?: 0 },
            { it.baseAsset },
            sensitivity.maxCardCoins,
        )
    }

    // Leere Merkliste: Start-Auswahl der grössten Coins
    val starterCoins by viewModel.starterCoins.collectAsStateWithLifecycle()
    val starterDeselected by viewModel.starterDeselected.collectAsStateWithLifecycle()
    val starterPrices by viewModel.starterPrices.collectAsStateWithLifecycle()
    val starterAdding by viewModel.starterAdding.collectAsStateWithLifecycle()
    val starterLogosReady by viewModel.starterLogosReady.collectAsStateWithLifecycle()

    val ui = rememberWatchlistUiState()
    // ⚡ nur mit Paaren, bei denen gerade etwas passiert (abschaltbar unter Einstellungen › Merkliste)
    val activityChip = activityCardEnabled && hot.isNotEmpty()
    // Gezeigt: die Gruppen-Ansicht oder vorübergehend ⚡ bzw. «nur veraltete» ([QuickView])
    val visible = remember(groupVisible, hot, ui.quickView, now, staleAfter) {
        when (ui.quickView) {
            QuickView.ACTIVITY -> hot
            QuickView.STALE -> groupVisible.filter { WatchlistStatus.isStale(it, now, staleAfter) }
            null -> groupVisible
        }
    }
    // Vorübergehende Ansicht endet von selbst, wenn nichts mehr hineinpasst
    LaunchedEffect(ui.quickView, activityChip, visible.isEmpty()) {
        when (ui.quickView) {
            QuickView.ACTIVITY -> if (!activityChip) ui.quickView = null
            QuickView.STALE -> if (visible.isEmpty()) ui.quickView = null
            null -> Unit
        }
    }
    // Mehrfachauswahl: nur Paare, die es noch gibt und die zu sehen sind
    val selectedIds = remember(ui.selectedIds, visible) {
        val ids = visible.mapTo(HashSet()) { it.id }
        ui.selectedIds.filterTo(HashSet()) { it in ids }
    }
    // Live-Kurse für die gezeigten Paare (auch in der ⚡-Ansicht über alle Gruppen)
    val livePairs = remember(visible) { visible.map { it.toLivePair() } }
    LaunchedEffect(livePairs) { viewModel.setLivePairs(livePairs) }
    // Zurück aus «Alarme» (eigene Seite): Die Merkliste kommt wieder ins Bild — war das
    // Aktionsblatt der Ausgangspunkt, öffnet es sich wieder
    LaunchedEffect(Unit) { if (ui.whyFor == null) ui.reopenActions() }
    // Aus der Alarm-Meldung («Warum?»): Blatt öffnen, sobald die Merkliste da ist (nicht gehandelte Paare zeigen keins)
    LaunchedEffect(openWhyWatchId) {
        val id = openWhyWatchId ?: return@LaunchedEffect
        ui.whyFor = id
        onOpenWhyHandled()
    }
    // Zurück-Taste schliesst zuerst die Suche
    BackHandler(enabled = ui.searching) { ui.closeSearch() }
    // … bzw. beendet den Sortiermodus (wie «Fertig»)
    BackHandler(enabled = ui.sortMode && !ui.searching) { ui.sortMode = false }
    // … bzw. die Mehrfachauswahl
    BackHandler(enabled = ui.selecting) { ui.endSelection() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val reduceMotion = rememberReduceMotion()
    val banner = rememberWatchlistBanner(scope)
    // Alarm löst bei offener App aus: Glocke im Kopf pulsiert einmal (ohne Bewegung: nicht)
    val bellScale = rememberBellPulse(latestAlarmTrigger, reduceMotion)
    // Erst-Moment (Start-Merkliste, allererstes Paar), siehe [rememberAddMoment]
    val moment = rememberAddMoment(viewModel, watches, loaded, alarmCounts, banner, onOpenAlarms)

    // Alles aktualisieren (nach unten ziehen, Menü). Eben erst aktualisiert (unter 15 s): kein
    // neuer Durchlauf und keine Meldung — der Menüpunkt ist dann grau ([WatchlistViewModel.canRefreshNow]).
    val requestRefresh: () -> Unit = { viewModel.refreshAllByUser() }
    // Nach links wischen, Screenreader-Aktion «Löschen» und «Löschen» im Aktionen-Blatt:
    // ohne Rückfrage, mit «Rückgängig» (derselbe Weg überall)
    val deleteWithUndo: (WatchEntity) -> Unit = { watch ->
        viewModel.deleteWithUndo(watch)
        banner.show(resources.getString(R.string.watchlist_removed, watch.displayName)) { viewModel.undoDelete(watch.id) }
    }
    // Nach rechts wischen: Favorit an/aus mit kurzem Banner
    val favoriteWithBanner: (WatchEntity) -> Unit = { watch ->
        viewModel.toggleFavorite(watch)
        val text = resources.getString(
            if (watch.favorite) R.string.favorite_removed else R.string.favorite_added,
            watch.displayName,
        )
        banner.show(text)
    }

    val reorder = rememberReorderState(listState, scope, visible, viewModel::reorder)
    val ordered = reorder.items ?: visible
    // Aktive Suche filtert zusätzlich zur gewählten Gruppe
    val trimmedQuery = ui.query.trim()
    val filtering = ui.searching && trimmedQuery.isNotEmpty()
    val shown = remember(ordered, trimmedQuery, filtering) {
        if (filtering) ordered.filter { it.matchesSearch(trimmedQuery) } else ordered
    }
    // Kein Sprungknopf in der Mehrfachauswahl: unten steht dann die Leiste
    val jump = rememberWatchlistJump(listState, shown.size, ui.sortMode || ui.selecting, reduceMotion, scope)

    // Keine Kopfzeile mit Logo und App-Namen mehr (kostete eine ganze Zeile): ihre Knöpfe
    // stehen rechts in der Gruppen-Zeile oben in der Liste. Den Bildschirmtitel bekommt der
    // Screenreader als paneTitle; den Abstand zur Statusleiste liefert das Scaffold-Padding.
    val screenTitle = stringResource(R.string.tab_watchlist)
    // %-Basis für Pillen, Puls und Aktionsblatt; passt der Stempel der gespeicherten Werte nicht
    // (Basis gewechselt, neuer Tag — die 30-s-Uhr prüft das), «—» bis neu gerechnet ist
    val changeBasis by viewModel.changeBasis.collectAsStateWithLifecycle()
    val changeStamp by viewModel.changeStamp.collectAsStateWithLifecycle()
    val showChangePeriod by viewModel.showChangePeriod.collectAsStateWithLifecycle()
    val changeView = remember(changeBasis, changeStamp, now, showChangePeriod) {
        ChangeView.of(changeStamp, changeBasis, now, showPeriod = showChangePeriod)
    }
    // Sortieren nach Spalte (nicht im Sortiermodus): Favoriten oben, unter sich sortiert; Kurse
    // über CHF verglichen (im Hintergrund umgerechnet, auch ohne «≈ Umrechnung»)
    val columnSort by viewModel.columnSort.collectAsStateWithLifecycle()
    val sortRates by viewModel.sortRates.collectAsStateWithLifecycle()
    val listed = remember(shown, columnSort, sortRates, changeView, ui.sortMode) {
        if (ui.sortMode) shown
        else ColumnSort.apply(
            shown,
            columnSort,
            favorite = { it.favorite },
            name = { it.displayName },
            value = { w ->
                val price = w.lastPrice?.takeIf { it > 0.0 }
                when {
                    price == null -> null
                    CurrencyConversion.sameCurrency(w.quoteAsset, SORT_CURRENCY) -> price
                    else -> CurrencyConversion.convert(price, sortRates[CurrencyConversion.normalize(w.quoteAsset)])
                }
            },
            change = { it.shownChange(changeView) },
        )
    }
    // ★/⚡ in den Zeilen nur farbig, solange der passende Chip gewählt ist
    val rowHighlight = RowHighlight(
        favorites = ui.quickView == null && WatchFilter.isFavorites(selectedGroup),
        activity = ui.quickView == QuickView.ACTIVITY,
    )
    CompositionLocalProvider(LocalChangeView provides changeView, LocalRowHighlight provides rowHighlight) {
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = screenTitle
            // testTags als Ressourcen-Id: für den Baseline-Profile-Generator (UiAutomator)
            testTagsAsResourceId = true
        },
        snackbarHost = { SnackbarHost(banner.host) },
    ) { padding ->
        WatchlistSheets(
            ui = ui,
            viewModel = viewModel,
            lockViewModel = lockViewModel,
            watches = watches,
            groups = groups,
            notTradedCount = notTradedIds.size,
            alarmCounts = alarmCounts,
            livePrices = livePrices,
            liveExchanges = liveExchanges,
            rollingBasis = !changeBasis.isDay,
            activeSignals = activeSignals,
            now = now,
            banner = banner,
            onOpenAlarms = onOpenAlarms,
            onDelete = deleteWithUndo,
        )

        if (!loaded) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        if (watches.isEmpty()) {
            StarterPicker(
                modifier = Modifier.fillMaxSize().padding(padding),
                coins = starterCoins,
                deselected = starterDeselected,
                prices = starterPrices,
                quote = viewModel.starterQuote,
                adding = starterAdding,
                logosReady = starterLogosReady,
                onLoad = viewModel::loadStarterCoins,
                onToggle = viewModel::toggleStarter,
                onToggleAll = viewModel::toggleAllStarters,
                onAdd = viewModel::addSelectedStarters,
                onAddClick = onAddClick,
            )
            return@Scaffold
        }

        // Liste nach unten ziehen = alles aktualisieren (wie der Knopf oben).
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = requestRefresh,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        // Puls ganz oben: wie viele steigen/fallen, Ø-Veränderung — nur für die gezeigte
        // Gruppe, nicht beim Suchen oder Sortieren
        // Nur Paare mit Kurs (und noch gehandelt) zählen als «ohne 24h-Wert». Gemerkt, damit
        // nicht jede Neuzusammensetzung (z. B. die 30-s-Uhr) hunderte Paare neu durchgeht.
        val pulse = remember(visible, ui.searching, ui.sortMode, changeView) {
            if (ui.searching || ui.sortMode) null
            else WatchPulse.of(
                // Nicht gehandelte Paare zählen weder als steigend/fallend noch als «ohne 24h-Wert»
                changes = visible.map { it.shownChange(changeView) },
                hasPrice = visible.map { it.lastPrice != null && !isNotTraded(it.lastError) },
            )
        }
        KeepTopWhenPulseAppears(pulseShown = pulse != null, listState = listState)
        // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
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
                        groups = groups,
                        selectedGroup = selectedGroup,
                        hasFavorites = watches.any { it.favorite },
                        onSelectGroup = { group ->
                            ui.quickView = null
                            viewModel.selectGroup(group)
                        },
                        hasActivity = activityChip && !ui.sortMode,
                        quickView = ui.quickView,
                        onToggleActivity = { ui.toggleQuickView(QuickView.ACTIVITY) },
                        canRefresh = viewModel::canRefreshNow,
                        selecting = ui.selecting,
                        selectedCount = selectedIds.size,
                        allSelected = shown.isNotEmpty() && selectedIds.size == shown.size,
                        onSelectAll = { ui.selectedIds = shown.mapTo(HashSet()) { it.id } },
                        onSelectionDone = ui::endSelection,
                        canSelect = visible.isNotEmpty(),
                        onStartSelection = ui::startSelection,
                        onEditGroup = { group ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ui.startEditingGroup(group)
                        },
                        onAddGroup = { ui.askNewGroup = true },
                        sortMode = ui.sortMode,
                        onSortDone = { ui.sortMode = false },
                        activeAlarms = alarmCounts.values.sum(),
                        bellScale = { bellScale.value },
                        onOpenAllAlarms = onOpenAllAlarms,
                        onOpenAbout = onOpenAbout,
                        refreshing = refreshing,
                        onRefresh = requestRefresh,
                        canSort = visible.size > 1,
                        // Manuelles Sortieren zeigt die eigene Reihenfolge: Spalten-Sortierung aus
                        onSort = {
                            viewModel.clearColumnSort()
                            ui.startSort()
                        },
                        onShowReport = { ui.showReport = true },
                        canClear = watches.isNotEmpty(),
                        notTradedCount = notTradedIds.size,
                        onRemoveNotTraded = { ui.askRemoveNotTraded = true },
                        onClearAll = { ui.askClearAll = true },
                    )
                    WatchlistStatusRow(
                        // «Nur veraltete»: der Status zählt weiter über die ganze Gruppe («10 von 30»)
                        visible = if (ui.quickView == QuickView.STALE) groupVisible else visible,
                        now = now,
                        staleAfter = staleAfter,
                        online = online,
                        live = liveExchanges.isNotEmpty(),
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
                    )
                }
                // Feine Linie unter dem festen Kopf, sobald die Liste darunter gescrollt ist
                val listScrolled by remember { derivedStateOf { listState.canScrollBackward } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (listScrolled) 0.6f else 0f)
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
                        showGestureHint = showGestureHint,
                        onDismissGestureHint = viewModel::dismissGestureHint,
                        noSearchMatch = filtering && shown.isEmpty(),
                        query = trimmedQuery,
                        groupEmpty = visible.isEmpty() && !filtering,
                        favoritesEmpty = visible.isEmpty() && !filtering && WatchFilter.isFavorites(selectedGroup),
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
                            moment = moment,
                            reduceMotion = reduceMotion,
                            livePrices = livePrices,
                            rollingBasis = !changeBasis.isDay,
                            alarmCount = alarmCounts[watch.id] ?: 0,
                            now = now,
                            staleAfter = staleAfter,
                            outdatedAfter = outdatedAfter,
                            convertTarget = convertTarget,
                            convertRates = convertRates,
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
                        SelectionBar(
                            count = selectedIds.size,
                            allFavorites = chosen.isNotEmpty() && chosen.all { it.favorite },
                            onFavorite = {
                                viewModel.setFavorite(selectedIds, !chosen.all { it.favorite })
                                ui.endSelection()
                            },
                            onGroup = { ui.askGroupForSelection = true },
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
    }
    }
}

/**
 * Erst-Moment (Start-Merkliste, allererstes Paar): einmal abspielen, sobald die neuen Zeilen da
 * sind — ein leichtes Haptik-Signal und das Banner «… wird jetzt überwacht»; haben die neuen
 * Paare noch keinen Alarm, mit «Alarm setzen» (öffnet die Alarme des ersten neuen Paars — wie
 * im Aktionsblatt). Nur dieser Zustandswechsel wird animiert, nichts Dauerhaftes.
 * @return der laufende Moment (für die Staffelung der Zeilen) oder null
 */
@Composable
private fun rememberAddMoment(
    viewModel: WatchlistViewModel,
    watches: List<WatchEntity>,
    loaded: Boolean,
    alarmCounts: Map<Long, Int>,
    banner: WatchlistBanner,
    onOpenAlarms: (Long) -> Unit,
): AddMoment? {
    val pendingMoment by viewModel.addMoment.collectAsStateWithLifecycle()
    val view = LocalView.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    var shownMoment by remember { mutableStateOf<AddMoment?>(null) }
    val readyMoment = pendingMoment?.takeIf { m ->
        loaded && watches.any { w -> m.indexOf(w.marketKey, w.baseAsset, w.quoteAsset) != null }
    }
    LaunchedEffect(shownMoment) {
        val m = shownMoment ?: return@LaunchedEffect
        delay(AddMoment.totalMillis(m.pairs.size))
        if (shownMoment == m) shownMoment = null
    }
    val alarmActionLabel = stringResource(R.string.add_alarm_action)
    LaunchedEffect(readyMoment) {
        val m = readyMoment ?: return@LaunchedEffect
        shownMoment = m
        // Ein leichtes Signal je Aktion, nicht je Zeile
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
        val text = resources.getString(R.string.pair_added_watching, AddMoment.subject(m.pairs))
        val added = watches.filter { w -> m.indexOf(w.marketKey, w.baseAsset, w.quoteAsset) != null }
        val first = m.pairs.firstOrNull()?.let { p ->
            added.firstOrNull { p.matches(it.marketKey, it.baseAsset, it.quoteAsset) }
        }
        // Der SnackbarHost meldet Text und Aktion dem Screenreader (Live-Region)
        if (first != null && added.none { (alarmCounts[it.id] ?: 0) > 0 }) {
            banner.showAction(text, alarmActionLabel, ALARM_HINT_MILLIS) { onOpenAlarms(first.id) }
        } else {
            banner.show(text)
        }
        viewModel.consumeAddMoment(m)
    }
    return readyMoment ?: shownMoment
}

/** So lange bleibt «… wird jetzt überwacht» mit «Alarm setzen» stehen (ohne Screenreader). */
private const val ALARM_HINT_MILLIS = 6_000L

/**
 * Taucht der Puls neu auf (Gruppe gewechselt, Suche zu, zweiter Kurs da), hielte LazyColumn
 * die bisherige erste Zeile per Schlüssel oben fest und der Puls läge unsichtbar darüber.
 * Stand die Liste ganz oben, bleibt sie oben. Gelesen wird nur beim Wechsel und ohne
 * Beobachtung — Scrollen löst keinen Neuaufbau aus.
 */
@Composable
private fun KeepTopWhenPulseAppears(pulseShown: Boolean, listState: LazyListState) {
    val pulseWasShown = remember { booleanArrayOf(pulseShown) }
    if (pulseShown != pulseWasShown[0]) {
        pulseWasShown[0] = pulseShown
        if (pulseShown) {
            Snapshot.withoutReadObservation {
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    listState.requestScrollToItem(0)
                }
            }
        }
    }
}
