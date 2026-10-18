@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.FirstAlarmCard
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import com.cryptochecker.app.domain.watch.AlarmBellRoute
import com.cryptochecker.app.ui.features.alarms.AlarmPairPicker
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
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
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.watch.ActivityView
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.LocalRowHighlight
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.RowHighlight
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.util.StartupClock
import kotlinx.coroutines.delay

/**
 * Die Merkliste: fester Kopf (Gruppen, Glocke, Menü, Status mit Lupe und «+» bzw. Suche), darunter Puls,
 * Aktivitätskarte und die Paare. Aufgeteilt nach Aufgaben — Kopf [WatchlistHeader], Status
 * [WatchlistStatusRow], Liste [WatchlistList] und Zeile [WatchlistItem], Blätter und Rückfragen
 * [WatchlistSheets], Zustand aus dem ViewModel [WatchlistUiState] (einmal gesammelt) und des Bildschirms
 * [WatchlistScreenState], Banner [WatchlistBanner], Sprungknopf [WatchlistJump]. Hier wird nur zusammengesetzt.
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
    /** «Was bewegt sich mit?» aus einer Alarm-Meldung: Blatt dieses Paars öffnen. */
    openWhyWatchId: Long? = null,
    onOpenWhyHandled: () -> Unit = {},
    /** Tipp auf eine Mitteilung bzw. Widget-Zeile: Aktionsblatt dieses Paars öffnen (wie iOS `watch/<id>`). */
    focusWatchId: Long? = null,
    onFocusHandled: () -> Unit = {},
    viewModel: WatchlistViewModel = hiltViewModel(),
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
) {
    // Alles aus dem ViewModel in einem Stand (ein Sammeln statt rund 30); die Live-Kurse unten getrennt
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val display = state.display
    val live = state.live
    val starter = state.starter
    val watches = state.watches
    val loaded = state.loaded
    val groups = state.groups
    val selectedGroup = state.selectedGroup
    // Ansicht der Gruppen-Chips: alle Paare, die Favoriten (★) oder nur die der gewählten Gruppe
    // Art je Paar (Symbol vor der Glocke, «Alpha»)
    val kinds = state.kinds
    val groupVisible = remember(watches, selectedGroup, kinds) { filterByGroup(watches, selectedGroup, kinds) }
    val alarmCounts = state.content.alarmCounts
    val alarmTotal = state.content.alarmTotal
    // Glocke (Kopf und Start-Auswahl): mit Alarmen die Übersicht, ohne einen einzigen gleich zum Anlegen —
    // ein Paar: dessen Alarm-Seite (öffnet den Editor), mehrere: erst wählen, leere Merkliste: Coin hinzufügen
    var pickAlarmPair by remember { mutableStateOf(false) }
    val onBellClick: () -> Unit = {
        when (AlarmBellRoute.of(alarmTotal, watches.size)) {
            AlarmBellRoute.OVERVIEW -> onOpenAllAlarms()
            AlarmBellRoute.EDITOR -> onOpenAlarms(watches.first().id)
            AlarmBellRoute.PICK_PAIR -> pickAlarmPair = true
            AlarmBellRoute.ADD_COIN -> onAddClick()
        }
    }
    if (pickAlarmPair) {
        AlarmPairPicker(
            watches = watches,
            onPick = { id -> pickAlarmPair = false; onOpenAlarms(id) },
            onDismiss = { pickAlarmPair = false },
        )
    }
    val latestAlarmTrigger = state.content.latestAlarmTrigger
    val alarmFiredAt = state.content.alarmFiredAt
    val refreshing = live.refreshing
    // Eine Frische-Schwelle für Änderung, Zeile und Screenreader
    val staleAfter = display.staleAfterMillis
    val outdatedAfter = display.staleAfterMillis
    // Mini-Chart nur mit genug Breite, damit der Paarname nicht gequetscht wird ([sparklinesFit])
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val fetchSparklines = sparklinesFit(display.sparklineEnabled, screenWidthDp, fontScale)

    // Uhr für «vor 2 Min.» — alle 30 Sekunden neu
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // Paare am Live-Strom: eigene Frische-Schwelle (2 Min.) und «Live» nur, wenn alle dabei sind (K-1)
    val liveIds = live.liveWatchIds
    // Live-Kurse (WebSocket) für die Paare der Ansicht, solange die Merkliste zu sehen ist
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
    val activity = state.content.activity
    val sensitivity = display.activitySensitivity
    // Nach der gewählten Empfindlichkeit neu beurteilt (gleiche Schwellen wie die Meldungen)
    // Nicht mehr gehandelte Paare: kein ⚡, nicht in der Karte (auch bevor die Auswertung aufräumt)
    val notTradedIds = remember(watches) { NotTraded.ids(watches, { it.id }, { it.lastError }) }
    // Deaktivierte Paare ebenso: kein ⚡, nicht in der Karte (auch bevor die Auswertung aufräumt)
    val quietIds = remember(watches, notTradedIds) {
        notTradedIds + DisabledView.only(watches) { it.disabled }.map { it.id }
    }
    val activeSignals = remember(activity, now, sensitivity, quietIds) {
        NotTraded.withoutIds(activity, quietIds)
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

    // Leere Merkliste: Start-Auswahl der grössten Coins ([StarterUiState])
    // Start-Auswahl: neue Paare haben Kurs-Mitteilungen an — Erlaubnis jetzt fragen (wie im Explorer)
    // Start-Auswahl übernommen: erst kurz erklären, dann die System-Abfrage
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest(
        com.cryptochecker.app.ui.components.NotificationAsk.PAIRS_ADDED
    )
    // Erst fragen, wenn das Banner «… wird jetzt überwacht» mit «Alarm setzen» weg ist (abgelaufen oder
    // weggewischt) — sonst verdeckt die Erklärung genau diesen ersten Schritt. Mit «Alarm setzen» getippt fragt
    // das Speichern des Alarms selbst (gleich wie iOS).
    var askNotificationsAfterHint by rememberSaveable { mutableStateOf(false) }

    val ui = rememberWatchlistScreenState()
    // ⚡ nur mit Paaren, bei denen gerade etwas passiert (abschaltbar unter Einstellungen › Merkliste)
    val activityChip = display.activityCardEnabled && hot.isNotEmpty()
    // Deaktivierte Paare der Gruppen-Ansicht: Hinweis «3 deaktiviert» neben dem Status (0 = keiner)
    val disabledCount = remember(groupVisible) { DisabledView.count(groupVisible) { it.disabled } }
    // Gezeigt: die Gruppen-Ansicht oder vorübergehend ⚡, «nur veraltete» bzw. «nur deaktivierte» ([QuickView])
    val visible = remember(groupVisible, hot, ui.quickView, now, staleAfter, liveIds) {
        when (ui.quickView) {
            QuickView.ACTIVITY -> hot
            QuickView.STALE -> groupVisible.filter { WatchlistStatus.isStale(it, now, staleAfter, liveIds) }
            QuickView.DISABLED -> DisabledView.only(groupVisible) { it.disabled }
            null -> groupVisible
        }
    }
    // Vorübergehende Ansicht endet von selbst, wenn nichts mehr hineinpasst
    LaunchedEffect(ui.quickView, activityChip, visible.isEmpty()) {
        when (ui.quickView) {
            QuickView.ACTIVITY -> if (!activityChip) ui.quickView = null
            QuickView.STALE, QuickView.DISABLED -> if (visible.isEmpty()) ui.quickView = null
            null -> Unit
        }
    }
    // Mehrfachauswahl: nur Paare, die es noch gibt und die zu sehen sind
    val selectedIds = remember(ui.selectedIds, visible) {
        val ids = visible.mapTo(HashSet()) { it.id }
        ui.selectedIds.filterTo(HashSet()) { it in ids }
    }
    // Live-Kurse für die gezeigten Paare (auch in der ⚡-Ansicht über alle Gruppen); deaktivierte nie
    val livePairs = remember(visible) { DisabledView.active(visible) { it.disabled }.map { it.toLivePair() } }
    LaunchedEffect(livePairs) { viewModel.setLivePairs(livePairs) }
    // Zurück aus «Alarme» (eigene Seite): Die Merkliste kommt wieder ins Bild — war das
    // Aktionsblatt der Ausgangspunkt, öffnet es sich wieder
    LaunchedEffect(Unit) { if (ui.whyFor == null) ui.reopenActions() }
    // Aus der Alarm-Meldung («Was bewegt sich mit?»): Blatt öffnen, sobald die Merkliste da ist (nicht gehandelte Paare zeigen keins)
    LaunchedEffect(openWhyWatchId) {
        val id = openWhyWatchId ?: return@LaunchedEffect
        ui.whyFor = id
        onOpenWhyHandled()
    }
    // Aus Mitteilung oder Widget-Zeile: Aktionsblatt des Paars, sobald die Merkliste geladen ist (wie iOS focus(on:))
    LaunchedEffect(focusWatchId, loaded) {
        val id = focusWatchId ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val watch = watches.firstOrNull { it.id == id }
        if (watch != null) {
            // Paar ausserhalb der gewählten Gruppe: wieder alle zeigen
            if (selectedGroup != null && groupVisible.none { it.id == id }) viewModel.selectGroup(null)
            ui.whyFor = null
            ui.returnToActions = null
            ui.actionsFor = id
        }
        onFocusHandled()
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
    // Erneutes Tippen auf den Tab «Merkliste»: an den Anfang der Liste (wie iOS)
    OnTabReselect(ScreenRoute.Watchlist) {
        if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
    val banner = rememberWatchlistBanner(scope)
    // Alarm löst bei offener App aus: Glocke im Kopf pulsiert einmal (ohne Bewegung: nicht)
    val bellScale = rememberBellPulse(latestAlarmTrigger, reduceMotion)
    // Erst-Moment (Start-Merkliste, allererstes Paar), siehe [rememberAddMoment]
    val moment = rememberAddMoment(viewModel, watches, loaded, alarmCounts, banner, onOpenAlarms) { alarmTapped ->
        if (askNotificationsAfterHint) {
            askNotificationsAfterHint = false
            if (!alarmTapped) requestNotifications()
        }
    }

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
    // Namen der Coins («bitcoin» findet BTC), einmal je Liste
    val searchNames = rememberSearchNames(visible)
    val shown = remember(ordered, trimmedQuery, filtering, searchNames) {
        if (filtering) ordered.filter { it.matchesSearch(trimmedQuery, searchNames[it.id]) } else ordered
    }
    // Kein Sprungknopf in der Mehrfachauswahl: unten steht dann die Leiste
    val jump = rememberWatchlistJump(listState, shown.size, ui.sortMode || ui.selecting, reduceMotion, scope)

    // Keine Kopfzeile mit Logo und App-Namen mehr (kostete eine ganze Zeile): ihre Knöpfe
    // stehen rechts in der Gruppen-Zeile oben in der Liste. Den Bildschirmtitel bekommt der
    // Screenreader als paneTitle; den Abstand zur Statusleiste liefert das Scaffold-Padding.
    val screenTitle = stringResource(R.string.tab_watchlist)
    // %-Basis für Änderungen, Puls und Aktionsblatt; passt der Stempel der gespeicherten Werte nicht
    // (Basis gewechselt, neuer Tag — die 30-s-Uhr prüft das), «—» bis neu gerechnet ist
    val changeBasis = display.changeBasis
    val changeStamp = live.changeStamp
    val showChangePeriod = display.showChangePeriod
    val changeView = remember(changeBasis, changeStamp, now, showChangePeriod) {
        ChangeView.of(changeStamp, changeBasis, now, showPeriod = showChangePeriod)
    }
    // Sortieren nach Spalte (nicht im Sortiermodus): Favoriten oben, unter sich sortiert; Kurse
    // über CHF verglichen (im Hintergrund umgerechnet, auch ohne «≈ Umrechnung»)
    val columnSort = display.columnSort
    val sortRates = live.sortRates
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
    // ebenso das Art-Symbol vor der Glocke (Token, TradFi, α)
    val rowHighlight = RowHighlight(
        favorites = ui.quickView == null && WatchFilter.isFavorites(selectedGroup),
        activity = ui.quickView == QuickView.ACTIVITY,
        tokens = ui.quickView == null && WatchFilter.isTokens(selectedGroup),
        tradFi = ui.quickView == null && selectedGroup == AutoGroup.TRADFI,
        alpha = ui.quickView == null && WatchFilter.isAlpha(selectedGroup),
    )
    CompositionLocalProvider(
        LocalChangeView provides changeView,
        LocalRowHighlight provides rowHighlight,
        com.cryptochecker.app.ui.components.LocalPriceNotificationsOn provides display.priceNotificationsOn,
    ) {
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
                state = state,
                viewModel = viewModel,
                lockViewModel = lockViewModel,
                watches = watches,
                groups = groups,
                notTradedCount = notTradedIds.size,
                alarmCounts = alarmCounts,
                livePrices = livePrices,
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
                    coins = starter.coins,
                    deselected = starter.deselected,
                    prices = starter.prices,
                    quote = starter.quote,
                    adding = starter.adding,
                    logosReady = starter.logosReady,
                    onLoad = viewModel::loadStarterCoins,
                    onToggle = viewModel::toggleStarter,
                    onToggleAll = viewModel::toggleAllStarters,
                    onAdd = {
                        val count = com.cryptochecker.app.domain.starter.StarterSelection.selected(starter.coins, starter.deselected).size
                        viewModel.addSelectedStarters()
                        // Mitteilungs-Erlaubnis erst nach dem Banner (siehe askNotificationsAfterHint)
                        if (count > 0) askNotificationsAfterHint = true
                    },
                    onAddClick = onAddClick,
                    // Glocke nur, wenn sie mehr kann als «+» (Alarme vorhanden, z. B. im Portfolio)
                    onOpenAlarms = onBellClick.takeIf { (alarmTotal ?: 0) > 0 },
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
                    else {
                        // Nicht gehandelte Paare zählen weder als steigend/fallend noch als «ohne 24h-Wert»,
                        // deaktivierte gar nicht
                        val counted = DisabledView.active(visible) { it.disabled }
                        WatchPulse.of(
                            changes = counted.map { it.shownChange(changeView) },
                            hasPrice = counted.map { it.lastPrice != null && !isNotTraded(it.lastError) },
                        )
                    }
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
                                    canRefresh = viewModel::canRefreshNow,
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
                                showGestureHint = display.showGestureHint,
                                onDismissGestureHint = viewModel::dismissGestureHint,
                                // Kein Alarm, aber Paare: bleibende Karte zusätzlich zum 6-s-Banner nach dem Hinzufügen
                                onSetFirstAlarm = (listed.firstOrNull() ?: watches.firstOrNull())?.takeIf {
                                    FirstAlarmCard.shouldShow(
                                        alarmCount = alarmTotal,
                                        watchCount = watches.size,
                                        dismissed = display.firstAlarmCardDismissed,
                                        busy = ui.searching || ui.sortMode || ui.selecting,
                                    )
                                }?.let { first -> { onOpenAlarms(first.id) } },
                                onDismissFirstAlarm = viewModel::dismissFirstAlarmCard,
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
        }
    }
}
