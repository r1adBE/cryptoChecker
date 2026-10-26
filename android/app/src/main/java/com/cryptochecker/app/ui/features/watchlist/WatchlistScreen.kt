@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.annotation.SuppressLint
import androidx.compose.ui.zIndex
import com.cryptochecker.app.domain.watch.FirstAlarmCard
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.ui.components.LocalRowHighlight
import com.cryptochecker.app.ui.components.RowHighlight
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.util.StartupClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Die Merkliste: fester Kopf (Gruppen, Glocke, Menü, Status mit Lupe und «+» bzw. Suche), darunter Puls,
 * Aktivitätskarte und die Paare. Aufgeteilt nach Aufgaben — Kopf [WatchlistHeader], Status
 * [WatchlistStatusRow], Liste [WatchlistList] und Zeile [WatchlistItem], Blätter und Rückfragen
 * [WatchlistSheets], Zustand aus dem ViewModel [WatchlistUiState] (einmal gesammelt) und des Bildschirms
 * [WatchlistScreenState], Banner [WatchlistBanner], Sprungknopf [WatchlistJump], fester Kopf und Liste samt
 * Mehrfachauswahl [WatchlistBody], Wege in die Merkliste [WatchlistNavigationEffects], Start-Auswahl
 * [WatchlistStarterPicker]. Hier werden Zustand und Ableitungen gesammelt und zusammengesetzt.
 */
@Composable
fun WatchlistScreen(
    onAddClick: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    onOpenAllAlarms: () -> Unit = {},
    /** Einstellungen › Alarme › Markt-Meldungen (Empfindlichkeit); früher «Anpassen» in der Aktivitätskarte. */
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
    val onBellClick = rememberBellClick(alarmTotal, watches, onOpenAllAlarms, onOpenAlarms, onAddClick)
    val latestAlarmTrigger = state.content.latestAlarmTrigger
    val alarmFiredAt = state.content.alarmFiredAt
    val refreshing = live.refreshing
    // Eine Frische-Schwelle für Änderung, Zeile und Screenreader
    val staleAfter = display.staleAfterMillis
    val outdatedAfter = display.staleAfterMillis
    // Mini-Chart nur mit genug Breite, damit der Paarname nicht gequetscht wird ([sparklinesFit])
    // Bewusst die Bildschirmbreite aus der Configuration; ein Umbau auf containerSize wäre eine eigene Änderung
    @SuppressLint("ConfigurationScreenWidthHeight")
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val fetchSparklines = sparklinesFit(display.sparklineEnabled, screenWidthDp, fontScale)

    // Uhr für «vor 2 Min.» — alle 30 Sekunden neu ([rememberWatchlistClock])
    val now = rememberWatchlistClock()
    // Paare am Live-Strom: eigene Frische-Schwelle (2 Min.) und «Live» nur, wenn alle dabei sind (K-1)
    val liveIds = live.liveWatchIds
    // Live-Kurse (WebSocket) für die Paare der Ansicht, solange die Merkliste zu sehen ist
    // Als State (nicht «by»): gelesen wird nur in den Zeilen bzw. im Aktionsblatt ([WithLiveQuote]),
    // ein Tick setzt also nicht den ganzen Bildschirm neu zusammen
    val livePrices = viewModel.livePrices.collectAsStateWithLifecycle()
    val markFallbackIds by viewModel.markFallbackIds.collectAsStateWithLifecycle()
    val exchangePauses by viewModel.exchangePauses.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        viewModel.setLiveVisible(true)
        // Tage mit offener App zählen (erster Gebrauch, [com.cryptochecker.app.domain.watch.WatchlistOnboarding])
        viewModel.recordUsageDay()
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
    val signals = rememberActivitySignals(watches, activity, now, sensitivity)
    val notTradedIds = signals.notTradedIds
    val activeSignals = signals.activeSignals
    val hot = signals.hot

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
    val visible = rememberVisibleWatches(ui, groupVisible, hot, now, staleAfter, liveIds, activityChip)
    // Mehrfachauswahl: nur Paare, die es noch gibt und die zu sehen sind
    val selectedIds = remember(ui.selectedIds, visible) {
        val ids = visible.mapTo(HashSet()) { it.id }
        ui.selectedIds.filterTo(HashSet()) { it in ids }
    }
    // Live-Kurse für die gezeigten Paare (auch in der ⚡-Ansicht über alle Gruppen); deaktivierte nie
    val livePairs = remember(visible) { DisabledView.active(visible) { it.disabled }.map { it.toLivePair() } }
    LaunchedEffect(livePairs) { viewModel.setLivePairs(livePairs) }
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
    // Alarm löst bei offener App aus: Glocke im Kopf pulsiert einmal (ohne Bewegung: nicht)
    val bellScale = rememberBellPulse(latestAlarmTrigger, reduceMotion)
    // Erst-Moment (Start-Merkliste, allererstes Paar), siehe [rememberAddMoment]
    val moment = rememberAddMoment(viewModel, watches, loaded, alarmCounts, banner, onOpenAlarms) { alarmTapped ->
        if (askNotificationsAfterHint) {
            askNotificationsAfterHint = false
            if (!alarmTapped) requestNotifications()
        }
    }

    // Alles aktualisieren (nach unten ziehen, Menü). Eben erst aktualisiert (unter 15 s): kein neuer
    // Durchlauf — der Menüpunkt ist dann grau mit Countdown ([WatchlistMenu]); wer zieht, sieht den Kreisel
    // kurz drehen und dann «Gerade aktualisiert · vor N s» (die Sperre bleibt intern, RefreshDebounce.pullEchoSeconds).
    var pullEcho by remember { mutableStateOf(false) }
    val requestRefresh: () -> Unit = {
        val decision = viewModel.refreshAllByUser()
        val ago = RefreshDebounce.pullEchoSeconds(decision, viewModel.lastFullRefreshAt(), System.currentTimeMillis())
        if (ago != null && !pullEcho) scope.launch {
            pullEcho = true
            delay(RefreshDebounce.PULL_ECHO_MILLIS)
            pullEcho = false
            banner.show(resources.getString(R.string.watchlist_refresh_just_done, ago))
        }
    }
    // Wischen, Screenreader-Aktion und Aktionen-Blatt «Löschen» (ohne Rückfrage, mit «Rückgängig», weiteres Löschen als
    // Sammel-Banner) bzw. nach rechts wischen (Favorit): Haptik und Banner (ARC5-R01) über Ereignisse im
    // aktuellen Bildschirm ([WatchlistUiEffects] in [WatchlistSheets])
    val uiEvents = rememberWatchUiEvents(WATCHLIST_UI_EVENTS)
    val deleteWithUndo: (WatchEntity) -> Unit = { uiEvents.deleteWithUndo(viewModel, it) }
    val favoriteWithBanner: (WatchEntity) -> Unit = { uiEvents.favoriteWithBanner(viewModel, it) }

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

    // Keine Kopfzeile mit Logo und App-Namen mehr (kostete eine ganze Zeile): ihre Knöpfe
    // stehen rechts in der Gruppen-Zeile oben in der Liste. Den Bildschirmtitel bekommt der
    // Screenreader als paneTitle; den Abstand zur Statusleiste liefert das Scaffold-Padding.
    val screenTitle = stringResource(R.string.tab_watchlist)
    // %-Basis für Änderungen, Puls und Aktionsblatt; passt der Stempel der gespeicherten Werte nicht
    // (Basis gewechselt, neuer Tag — die 30-s-Uhr prüft das), «—» bis neu gerechnet ist
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
        LocalMarkFallback provides markFallbackIds,
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
            // «Mehrere Paare auf einmal» läuft noch: Balken oben, über der Liste
            WatchlistBulkAdd(banner, Modifier.padding(padding).zIndex(1f))

            if (!loaded) {
                SkeletonList(modifier = Modifier.padding(padding))
                return@Scaffold
            }

            if (watches.isEmpty()) {
                WatchlistStarterPicker(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    starter = starter,
                    viewModel = viewModel,
                    onAddClick = onAddClick,
                    // Glocke nur, wenn sie mehr kann als «+» (Alarme vorhanden, z. B. im Portfolio)
                    onOpenAlarms = onBellClick.takeIf { (alarmTotal ?: 0) > 0 },
                    // Mitteilungs-Erlaubnis erst nach dem Banner (siehe askNotificationsAfterHint)
                    onStartersAdded = { askNotificationsAfterHint = true },
                )
                return@Scaffold
            }

            // Liste nach unten ziehen = alles aktualisieren (wie der Knopf oben).
            PullToRefreshBox(
                isRefreshing = refreshing || pullEcho,
                onRefresh = requestRefresh,
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
                    moment = moment,
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
                    requestRefresh = requestRefresh,
                    deleteWithUndo = deleteWithUndo,
                    favoriteWithBanner = favoriteWithBanner,
                )
            }
        }
    }
}
