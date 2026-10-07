@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.domain.watch.WatchPulse
import androidx.compose.ui.graphics.Brush
import com.cryptochecker.app.ui.components.GroupNameDialog
import com.cryptochecker.app.ui.components.NoteDialog
import com.cryptochecker.app.ui.components.canonicalGroupName
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import kotlinx.coroutines.delay
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.data.SparklineRepository
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.WidgetManualDialog
import com.cryptochecker.app.widget.WidgetKind
import com.cryptochecker.app.widget.WidgetPinner
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.ui.features.portfolio.PortfolioQuickAddSheet
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun WatchlistScreen(
    onAddClick: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    onOpenAllAlarms: () -> Unit = {},
    viewModel: WatchlistViewModel = hiltViewModel(),
) {
    val watchesOrNull by viewModel.watchesOrNull.collectAsStateWithLifecycle()
    val watches = watchesOrNull.orEmpty()
    val groupFilter by viewModel.groupFilter.collectAsStateWithLifecycle()
    val loaded = watchesOrNull != null && groupFilter != null
    val groups = groupFilter?.groups.orEmpty()
    val selectedGroup = groupFilter?.selected
    // Sichtbar: alle Paare oder nur die der gewählten Gruppe
    val visible = remember(watches, selectedGroup) {
        if (selectedGroup == null) watches else watches.filter { it.groupName == selectedGroup }
    }
    val alarmCounts by viewModel.alarmCounts.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val lastRefresh by viewModel.lastRefreshMillis.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastRefreshReport.collectAsStateWithLifecycle()
    val showGestureHint by viewModel.showGestureHint.collectAsStateWithLifecycle()
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val outdatedAfter by viewModel.outdatedAfterMillis.collectAsStateWithLifecycle()
    val portfolioEnabled by viewModel.portfolioEnabled.collectAsStateWithLifecycle()
    val sheetChartLine by viewModel.sheetChartLine.collectAsStateWithLifecycle()
    val convertTarget by viewModel.convertTarget.collectAsStateWithLifecycle()
    val convertRates by viewModel.convertRates.collectAsStateWithLifecycle()
    val sparklineEnabled by viewModel.sparklineEnabled.collectAsStateWithLifecycle()
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
    val activeAlarms = alarmCounts.values.sum()

    // Ungewöhnliche Aktivität: nur noch gültige Signale, je Paar stärkstes zuerst
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val activeSignals = remember(activity, now) {
        activity.mapValues { it.value.active(now) }.filterValues { it.isNotEmpty() }
    }
    // Paare der aktuellen Ansicht mit Signalen, starke zuerst, sonst Listenreihenfolge
    val hot = remember(visible, activeSignals) {
        visible.filter { it.id in activeSignals }
            .sortedByDescending { activeSignals[it.id]?.firstOrNull()?.severity?.ordinal ?: 0 }
    }
    val requestNotifications = rememberNotificationPermissionRequest()

    var askClearAll by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Sortiermodus: Griff und «ganz nach oben/unten» an den Karten.
    var sortMode by rememberSaveable { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    // Paar, dessen Aktionen gerade offen sind (Tipp auf die Karte).
    var actionsFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Paar, das gerade ins Portfolio übernommen wird (Erfassen-Blatt).
    var portfolioFor by remember { mutableStateOf<WatchEntity?>(null) }
    // Paar, dessen «Warum bewegt sich das?» offen ist.
    var whyFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Suche in der Merkliste (Lupe rechts neben dem Status) — wird nicht gespeichert.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val closeSearch = { searching = false; query = "" }
    // Gruppe bearbeiten (lange auf den Chip drücken) bzw. neue Gruppe über «+»
    var editGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var editGroupIsNew by rememberSaveable { mutableStateOf(false) }
    var askNewGroup by remember { mutableStateOf(false) }
    // Zurück-Taste schliesst zuerst die Suche
    BackHandler(enabled = searching) { closeSearch() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Erst-Moment (Start-Merkliste, allererstes Paar): einmal abspielen, sobald die
    // neuen Zeilen da sind. Nur dieser Zustandswechsel wird animiert, nichts Dauerhaftes.
    val starterCoins by viewModel.starterCoins.collectAsStateWithLifecycle()
    val starterDeselected by viewModel.starterDeselected.collectAsStateWithLifecycle()
    val starterPrices by viewModel.starterPrices.collectAsStateWithLifecycle()
    val starterAdding by viewModel.starterAdding.collectAsStateWithLifecycle()
    val pendingMoment by viewModel.addMoment.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    var shownMoment by remember { mutableStateOf<AddMoment?>(null) }
    val readyMoment = pendingMoment?.takeIf { m ->
        loaded && watches.any { w -> m.indexOf(w.marketKey, w.baseAsset, w.quoteAsset) != null }
    }
    val moment = readyMoment ?: shownMoment
    LaunchedEffect(shownMoment) {
        val m = shownMoment ?: return@LaunchedEffect
        delay(AddMoment.totalMillis(m.pairs.size))
        if (shownMoment == m) shownMoment = null
    }
    // Banner nach dem Wischen: «BTC/USDT entfernt» mit «Rückgängig» bzw. Favorit an/aus.
    // Ein neues Banner ersetzt das alte; das Löschen davor bleibt dann bestehen.
    val accessibilityManager = LocalAccessibilityManager.current
    val undoLabel = stringResource(R.string.action_undo)
    var bannerJob by remember { mutableStateOf<Job?>(null) }
    // Banner mit Aktion (z. B. «Rückgängig», «Alarm setzen») für [millis]
    val showActionBanner: (String, String, Long, () -> Unit) -> Unit = { text, label, millis, onAction ->
        bannerJob?.cancel()
        bannerJob = scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            // Mit Screenreader ggf. länger (Systemeinstellung «Zeit für Aktionen»)
            val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                millis, containsIcons = false, containsText = true, containsControls = true
            ) ?: millis
            val result = withTimeoutOrNull(timeout) {
                snackbar.showSnackbar(text, actionLabel = label, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
    val showBanner: (String, (() -> Unit)?) -> Unit = { text, onUndo ->
        if (onUndo == null) {
            bannerJob?.cancel()
            bannerJob = scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
            }
        } else {
            showActionBanner(text, undoLabel, UNDO_MILLIS, onUndo)
        }
    }
    // «… wird jetzt überwacht»; haben die neuen Paare noch keinen Alarm, mit «Alarm setzen»
    // (öffnet die Alarme des ersten neuen Paars — wie im Aktionsblatt). Kein Banner danach.
    val alarmActionLabel = stringResource(R.string.add_alarm_action)
    LaunchedEffect(readyMoment) {
        val m = readyMoment ?: return@LaunchedEffect
        shownMoment = m
        // Ein leichtes Signal je Aktion, nicht je Zeile
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
        val text = context.getString(R.string.pair_added_watching, AddMoment.subject(m.pairs))
        val added = watches.filter { w -> m.indexOf(w.marketKey, w.baseAsset, w.quoteAsset) != null }
        val first = m.pairs.firstOrNull()?.let { p ->
            added.firstOrNull { p.matches(it.marketKey, it.baseAsset, it.quoteAsset) }
        }
        // Der SnackbarHost meldet Text und Aktion dem Screenreader (Live-Region)
        if (first != null && added.none { (alarmCounts[it.id] ?: 0) > 0 }) {
            showActionBanner(text, alarmActionLabel, ALARM_HINT_MILLIS) { onOpenAlarms(first.id) }
        } else {
            showBanner(text, null)
        }
        viewModel.consumeAddMoment(m)
    }
    // Nach links wischen, Screenreader-Aktion «Löschen» und «Löschen» im Aktionen-Blatt:
    // ohne Rückfrage, mit «Rückgängig» (derselbe Weg überall)
    val deleteWithUndo: (WatchEntity) -> Unit = { watch ->
        viewModel.deleteWithUndo(watch)
        showBanner(context.getString(R.string.watchlist_removed, watch.displayName)) { viewModel.undoDelete(watch.id) }
    }
    // Nach rechts wischen: Favorit an/aus mit kurzem Banner
    val favoriteWithBanner: (WatchEntity) -> Unit = { watch ->
        viewModel.toggleFavorite(watch)
        val text = context.getString(
            if (watch.favorite) R.string.favorite_removed else R.string.favorite_added,
            watch.displayName,
        )
        showBanner(text, null)
    }

    val reorder = remember { ReorderState(listState, scope) }
    SideEffect {
        reorder.source = visible
        reorder.onDrop = viewModel::reorder
    }
    // Nach dem Ablegen die lokale Reihenfolge halten, bis die Datenbank sie
    // bestätigt — sonst springt die Karte kurz an den alten Platz zurück.
    LaunchedEffect(visible) {
        val local = reorder.items
        if (reorder.draggingId == null && local != null &&
            (local.map { it.id } == visible.map { it.id } || local.size != visible.size)
        ) {
            reorder.items = null
        }
    }
    val ordered = reorder.items ?: visible
    // Aktive Suche filtert zusätzlich zur gewählten Gruppe
    val trimmedQuery = query.trim()
    val filtering = searching && trimmedQuery.isNotEmpty()
    val shown = remember(ordered, trimmedQuery, filtering) {
        if (filtering) ordered.filter { it.matchesSearch(trimmedQuery) } else ordered
    }

    // Keine Kopfzeile mit Logo und App-Namen mehr (kostete eine ganze Zeile): ihre Knöpfe
    // stehen rechts in der Gruppen-Zeile oben in der Liste. Den Bildschirmtitel bekommt der
    // Screenreader als paneTitle; den Abstand zur Statusleiste liefert das Scaffold-Padding.
    val screenTitle = stringResource(R.string.tab_watchlist)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = screenTitle },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (showReport) {
            AlertDialog(
                onDismissRequest = { showReport = false },
                title = { Text(stringResource(R.string.watchlist_refresh_report)) },
                text = {
                    Text(
                        text = lastReport.ifEmpty { stringResource(R.string.watchlist_refresh_report_empty) },
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showReport = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            )
        }

        if (askClearAll) {
            ConfirmDialog(
                title = stringResource(R.string.watchlist_clear),
                text = stringResource(R.string.watchlist_clear_confirm),
                confirm = stringResource(R.string.watchlist_clear),
                onConfirm = { viewModel.deleteAll(); askClearAll = false },
                onDismiss = { askClearAll = false }
            )
        }

        // Aktionen eines Paars als Blatt von unten
        actionsFor?.let { id -> watches.firstOrNull { it.id == id } }?.let { watch ->
            run {
                WatchActionsSheet(
                    watch = watch,
                    alarmCount = alarmCounts[watch.id] ?: 0,
                    onDismiss = { actionsFor = null },
                    onNotificationChange = {
                        if (it) requestNotifications()
                        viewModel.setNotificationEnabled(watch, it)
                    },
                    onTtsChange = { viewModel.setTtsEnabled(watch, it) },
                    onToggleFavorite = { viewModel.toggleFavorite(watch) },
                    onOpenAlarms = { actionsFor = null; onOpenAlarms(watch.id) },
                    onRefresh = { viewModel.refreshOne(watch.id) },
                    // Wie nach links wischen: sofort löschen, «Rückgängig» im Banner (keine Rückfrage)
                    onDelete = { actionsFor = null; deleteWithUndo(watch) },
                    groups = groups,
                    onAddToPortfolio = if (portfolioEnabled) {
                        { actionsFor = null; portfolioFor = watch }
                    } else null,
                    onSetGroup = { viewModel.setGroup(watch, it) },
                    onSetNote = { viewModel.setNote(watch, it) },
                    loadFutures = viewModel::fetchFutures,
                    onWhy = { actionsFor = null; whyFor = watch.id },
                    chartLine = sheetChartLine,
                    onChartLineChange = viewModel::setSheetChartLine,
                    cachedChart = viewModel::cachedSheetChart,
                    loadChart = viewModel::loadSheetChart,
                )
            }
        }

        // Kurs nur vorbelegen, wenn die Quote praktisch USDT ist
        portfolioFor?.let { watch ->
            PortfolioQuickAddSheet(
                coin = watch.baseAsset,
                priceUsdt = watch.lastPrice?.takeIf {
                    it > 0.0 && watch.quoteAsset.uppercase() in USD_LIKE_QUOTES
                },
                onDismiss = { portfolioFor = null }
            )
        }

        if (askNewGroup) {
            GroupNameDialog(
                title = stringResource(R.string.group_add),
                initial = "",
                confirmText = stringResource(R.string.group_next),
                onConfirm = { entered ->
                    // Gibt es den Namen schon, wird einfach jene Gruppe bearbeitet
                    val name = canonicalGroupName(entered, groups)
                    askNewGroup = false
                    editGroupIsNew = name !in groups
                    editGroup = name
                },
                onDismiss = { askNewGroup = false }
            )
        }

        editGroup?.let { name ->
            GroupEditSheet(
                groupName = name,
                isNew = editGroupIsNew,
                watches = watches,
                groups = groups,
                onDone = { newName, ids ->
                    viewModel.saveGroup(if (editGroupIsNew) null else name, newName, ids)
                    editGroup = null
                },
                onDelete = {
                    viewModel.deleteGroup(name)
                    editGroup = null
                },
                onDismiss = { editGroup = null }
            )
        }

        whyFor?.let { id -> watches.firstOrNull { it.id == id } }?.let { watch ->
            WhySheet(
                watch = watch,
                signals = activeSignals[watch.id].orEmpty(),
                load = viewModel::explain,
                onDismiss = { whyFor = null }
            )
        }

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
            onRefresh = viewModel::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        // Puls ganz oben: wie viele steigen/fallen, Ø-Veränderung — nur für die gezeigte
        // Gruppe, nicht beim Suchen oder Sortieren
        val pulse = if (searching || sortMode) null else WatchPulse.of(visible.map { it.change24h })
        // Taucht der Puls neu auf (Gruppe gewechselt, Suche zu, zweiter Kurs da), hielte
        // LazyColumn die bisherige erste Zeile per Schlüssel oben fest und der Puls läge
        // unsichtbar darüber. Stand die Liste ganz oben, bleibt sie oben. Gelesen wird nur
        // beim Wechsel und ohne Beobachtung — Scrollen löst keinen Neuaufbau aus.
        val pulseShown = pulse != null
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
        // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
        ReadableInset { inset ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Kein schwebender Plus-Knopf mehr: Hinzufügen läuft über den Tab unten.
                contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Kopfzeile der Liste (ersetzt die frühere Leiste mit Logo und App-Namen):
                // kleines Logo ganz links (ohne App-Namen, für den Screenreader nur Zierde),
                // dahinter scrollen die Gruppen-Chips, die Knöpfe stehen fest am rechten Ende.
                // Die LazyRow der Chips schneidet in Laufrichtung ab, sie läuft also weder
                // unter das Logo noch unter die Knöpfe. Ohne Gruppen: Logo links, Knöpfe rechts.
                item(key = "groups") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(LocalAccentColor.current.logoRes(LocalDarkTheme.current)),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 10.dp).size(24.dp)
                        )
                        Box(Modifier.weight(1f)) {
                            // Chips mit mindestens einer Gruppe, oder ab zwei Paaren nur «+»,
                            // damit sich die erste Gruppe anlegen lässt
                            if (groups.isNotEmpty() || watches.size >= 2) {
                                GroupChips(
                                    groups = groups,
                                    selected = selectedGroup,
                                    onSelect = viewModel::selectGroup,
                                    onEdit = { group ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        editGroupIsNew = false
                                        editGroup = group
                                    },
                                    onAdd = { askNewGroup = true }
                                )
                            }
                        }
                        // Tippflächen 48 dp; um 12 dp nach aussen versetzt, damit die Symbole
                        // bündig mit dem Kartenrand stehen (Fläche ragt in den Seitenrand)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.offset(x = 12.dp)
                        ) {
                            if (sortMode) {
                                IconButton(onClick = { sortMode = false }) {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        contentDescription = stringResource(R.string.action_sort_done),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                // Glocke: alle Alarme, mit Zahl der aktiven
                                IconButton(onClick = onOpenAllAlarms) {
                                    BadgedBox(
                                        badge = {
                                            if (activeAlarms > 0) {
                                                Badge { Text(activeAlarms.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_notifications),
                                            contentDescription = stringResource(R.string.alarms_overview_title)
                                        )
                                    }
                                }
                                if (refreshing) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                } else {
                                    IconButton(onClick = viewModel::refreshAll) {
                                        Icon(
                                            painterResource(R.drawable.ic_refresh),
                                            contentDescription = stringResource(R.string.action_refresh)
                                        )
                                    }
                                }
                                // Seltenes im Überlaufmenü: Sortieren, Bericht, Alle löschen
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(
                                            painterResource(R.drawable.ic_more_vert),
                                            contentDescription = stringResource(R.string.action_more)
                                        )
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        if (visible.size > 1) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_sort)) },
                                                leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null) },
                                                onClick = { menuOpen = false; closeSearch(); sortMode = true }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.watchlist_refresh_report)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) },
                                            onClick = { menuOpen = false; showReport = true }
                                        )
                                        if (watches.isNotEmpty()) {
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(R.string.watchlist_clear),
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        painterResource(R.drawable.ic_delete), null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                onClick = { menuOpen = false; askClearAll = true }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Puls direkt unter der Kopfzeile, Abstände wie überall per spacedBy
                if (pulse != null) {
                    item(key = "pulse") {
                        WatchPulseLine(pulse = pulse, modifier = Modifier.animateItem())
                    }
                }

                item(key = "status") {
                    // Ehrlicher Status: wie viele Kurse sind veraltet? Der technische
                    // Bericht steht nur noch im Menü.
                    // «Nicht mehr gehandelt» ist kein Fehler: zählt weder als veraltet noch als gescheitert
                    val traded = visible.filterNot { isNotTraded(it.lastError) }
                    val staleCount = traded.count { it.lastUpdate <= 0 || now - it.lastUpdate > staleAfter }
                    val newest = traded.maxOfOrNull { it.lastUpdate } ?: 0L
                    // Keine Verbindung: der letzte Durchlauf scheiterte bei ALLEN Paaren am Netz
                    val offline = traded.isNotEmpty() && traded.all { isConnectionError(it.lastError) }
                    val failed = traded.count { it.lastError != null }
                    val warn = staleCount > 0 || offline || failed > 0
                    val tone = if (warn) MaterialTheme.colorScheme.error else PriceColors.ok
                    // Status links, Lupe rechts — beim Suchen wird die Zeile zum Suchfeld.
                    AnimatedContent(
                        targetState = searching,
                        transitionSpec = {
                            (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)))
                                .using(SizeTransform(clip = false))
                        },
                        contentAlignment = Alignment.CenterStart,
                        label = "status_search",
                        modifier = Modifier.fillMaxWidth()
                    ) { isSearching ->
                        if (isSearching) {
                            WatchSearchField(
                                query = query,
                                onQueryChange = { query = it },
                                onClose = closeSearch
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth().heightIn(min = SearchRowHeight)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clip(RoundedCornerShape(50))
                                        .background(tone.copy(alpha = 0.12f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(tone))
                                    Text(
                                        text = when {
                                            offline -> if (newest > 0) stringResource(R.string.watchlist_offline_since, ago(newest, now))
                                                else stringResource(R.string.watch_error_offline)
                                            staleCount > 0 -> pluralStringResource(R.plurals.watchlist_stale_count, staleCount, staleCount, traded.size)
                                            failed > 0 -> pluralStringResource(R.plurals.watchlist_failed_count, failed, failed, traded.size)
                                            newest > 0 -> stringResource(R.string.watchlist_all_fresh, ago(newest, now))
                                            else -> stringResource(R.string.watchlist_pull_to_refresh)
                                        },
                                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                // Lupe: in der Sortieransicht ausgeblendet
                                if (!sortMode) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(SearchRowHeight)
                                            .clip(RoundedCornerShape(50))
                                            .clickable { searching = true }
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_search),
                                            contentDescription = stringResource(R.string.watchlist_search_open),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // «⚡ Hier passiert gerade etwas» — nur mit Signalen in der aktuellen Ansicht,
                // beim Suchen ausgeblendet
                if (hot.isNotEmpty() && !sortMode && !searching) {
                    item(key = "activity") {
                        ActivityCard(hot = hot, onOpen = { whyFor = it.id })
                    }
                }

                if (sortMode) {
                    item(key = "sort_hint") {
                        HintText(stringResource(R.string.watchlist_sort_hint), highlight = true)
                    }
                } else if (showGestureHint) {
                    // Einmaliger Gesten-Hinweis, bleibt bis er weggeklickt wird.
                    item(key = "gesture_hint") {
                        GestureHint(onDismiss = viewModel::dismissGestureHint)
                    }
                }

                // Keine Treffer für die Suche
                if (filtering && shown.isEmpty()) {
                    item(key = "search_empty") {
                        Text(
                            text = stringResource(R.string.watchlist_search_empty, trimmedQuery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
                        )
                    }
                }

                items(shown, key = { it.id }) { watch ->
                    val dragging = reorder.draggingId == watch.id
                    val lift by animateFloatAsState(if (dragging) 1.03f else 1f, label = "lift")
                    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "elevation")

                    // Lange drücken = Sortiermodus an und Karte direkt ziehen.
                    // Während der Suche kein Sortieren — die Reihenfolge wäre mehrdeutig.
                    val dragModifier = if (searching) Modifier else Modifier.pointerInput(watch.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sortMode = true
                                reorder.start(watch.id)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                reorder.drag(amount.y)
                            },
                            onDragEnd = { reorder.end() },
                            onDragCancel = { reorder.end() }
                        )
                    }

                    // 24-Stunden-Verlauf: nur für sichtbare Zeilen (LazyColumn), danach alle 15 Min. neu.
                    // Fehler → kein Mini-Chart, keine Meldung.
                    val sparkline by produceState(
                        initialValue = if (fetchSparklines) viewModel.cachedSparkline(watch.baseAsset) else null,
                        watch.baseAsset,
                        fetchSparklines,
                    ) {
                        if (!fetchSparklines) {
                            value = null
                            return@produceState
                        }
                        var first = true
                        while (true) {
                            val closes = viewModel.sparkline(watch.baseAsset)
                            // Später fehlgeschlagene Abrufe lassen den letzten Verlauf stehen
                            if (closes != null || first) value = closes
                            first = false
                            delay(SparklineRepository.TTL_MILLIS)
                        }
                    }

                    // Teil des Erst-Moments? Dann Platz in der Staffelung, sonst null
                    val celebrateIndex = moment?.indexOf(watch.marketKey, watch.baseAsset, watch.quoteAsset)

                    // Wischen: nach links löschen, nach rechts Favorit (RTL gespiegelt); nicht beim Sortieren
                    SwipeActionsRow(
                        enabled = !sortMode,
                        favorite = watch.favorite,
                        onDelete = { deleteWithUndo(watch) },
                        onToggleFavorite = { favoriteWithBanner(watch) },
                        reduceMotion = reduceMotion,
                        modifier = (
                            if (dragging) Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    translationY = reorder.offset
                                    scaleX = lift
                                    scaleY = lift
                                }
                            else Modifier.animateItem()
                        ).then(dragModifier),
                    ) {
                        WatchRow(
                            watch = watch,
                            alarmCount = alarmCounts[watch.id] ?: 0,
                            now = now,
                            staleAfter = staleAfter,
                            outdatedAfter = outdatedAfter,
                            converted = convertedPrice(watch, convertTarget, convertRates),
                            // Im Sortiermodus ausgeblendet (Platz für Griff und Menü)
                            sparkline = sparkline.takeIf { fetchSparklines && !sortMode },
                            celebrateKey = moment?.id?.takeIf { celebrateIndex != null },
                            celebrateIndex = celebrateIndex,
                            reduceMotion = reduceMotion,
                            hasActivity = watch.id in activeSignals,
                            onActivityClick = { if (!sortMode) whyFor = watch.id },
                            elevation = elevation,
                            highlighted = dragging,
                            sortMode = sortMode,
                            onClick = { if (!sortMode) actionsFor = watch.id },
                            onToggleFavorite = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleFavorite(watch)
                            },
                            onMove = { viewModel.move(watch, it) },
                            // Screenreader: «Löschen» und «Favorit» wie Wischen
                            onDeleteAction = { deleteWithUndo(watch) },
                            onFavoriteAction = { favoriteWithBanner(watch) },
                            // Screenreader: «Nach oben/unten» wie Ziehen (nicht während der Suche)
                            canReorder = !searching,
                            // Am Griff ohne Warten ziehen
                            handleModifier = Modifier.pointerInput(watch.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        reorder.start(watch.id)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        reorder.drag(amount.y)
                                    },
                                    onDragEnd = { reorder.end() },
                                    onDragCancel = { reorder.end() }
                                )
                            }
                        )
                    }
                }
            }
        }
        }
    }
}

/** So lange bleibt «Rückgängig» nach dem Löschen per Wischen stehen (ohne Screenreader). */
private const val UNDO_MILLIS = 5_000L

/** So lange bleibt «… wird jetzt überwacht» mit «Alarm setzen» stehen (ohne Screenreader). */
private const val ALARM_HINT_MILLIS = 6_000L

@Composable
private fun HintText(text: String, highlight: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/** Gemeinsame Höhe von Statuszeile und Suchfeld, damit nichts springt. */
private val SearchRowHeight = 36.dp

/**
 * Trifft die Suche dieses Paar? Gross-/Kleinschreibung egal; mehrere Wörter
 * müssen alle passen (z. B. «btc kraken»). Geprüft werden Basis, Quote,
 * «BASIS/QUOTE», Vertragskürzel, Börse und Notiz.
 */
internal fun WatchEntity.matchesSearch(query: String): Boolean {
    val fields = listOfNotNull(
        baseAsset,
        quoteAsset,
        displayPair,
        displayName,
        FuturesContractType.getShortName(contractType),
        marketName,
        note,
    ).map { it.lowercase() }
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { token ->
        fields.any { it.contains(token) }
    }
}

/** Kompaktes Suchfeld anstelle der Statuszeile, mit Schliessen-Knopf rechts. */
@Composable
private fun WatchSearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(SearchRowHeight)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                RoundedCornerShape(50)
            )
            .padding(start = 12.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        ) {
            val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.watchlist_search_hint),
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SearchRowHeight)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClose)
        ) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.watchlist_search_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Kurzer Hinweis zu den Gesten mit Schliessen-Knopf. */
@Composable
private fun GestureHint(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.watch_gesture_hint_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** «gerade eben» bzw. «vor 2 Min.» in der Sprache des Geräts. */
@Composable
private fun ago(millis: Long, now: Long): String =
    if (now - millis < 60_000) stringResource(R.string.time_just_now)
    else DateUtils.getRelativeTimeSpanString(
        millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Kompakte Zeile: Stern, Paar, Börse, Kurs und Prozent-Pille.
 * Tippen öffnet die Aktionen sofort (kein Doppeltippen mehr, das jeden Tipp ~0,3 s
 * verzögert hätte); Favorit per Stern, Wischen nach rechts oder Aktionen-Menü;
 * lange drücken startet das Sortieren.
 */
@Composable
private fun WatchRow(
    watch: WatchEntity,
    alarmCount: Int,
    now: Long,
    staleAfter: Long,
    onClick: () -> Unit,
    /** Ohne Fehler, aber älter als das: «veraltet» statt der normalen Zeitzeile. */
    outdatedAfter: Long = Long.MAX_VALUE,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    highlighted: Boolean = false,
    sortMode: Boolean = false,
    onMove: (WatchMove) -> Unit = {},
    /** Screenreader-Aktion «Löschen» (wie nach links wischen); null = keine. */
    onDeleteAction: (() -> Unit)? = null,
    /** Screenreader-Aktion «Favorit hinzufügen/entfernen» (wie nach rechts wischen); null = keine. */
    onFavoriteAction: (() -> Unit)? = null,
    /** TalkBack-Aktionen «Nach oben»/«Nach unten» anbieten. */
    canReorder: Boolean = false,
    handleModifier: Modifier = Modifier,
    hasActivity: Boolean = false,
    onActivityClick: () -> Unit = {},
    /** Kurs in der Umrechnungswährung, z. B. «≈ 61’234 CHF»; null = nichts zeigen. */
    converted: String? = null,
    /** 24-Stunden-Verlauf (Schlusskurse) für das Mini-Chart; null = keines zeigen. */
    sparkline: List<Double>? = null,
    /** Erst-Moment, zu dem diese Zeile gehört ([AddMoment.id]); null = keiner. */
    celebrateKey: Long? = null,
    /** Platz der Zeile in der Staffelung des Erst-Moments. */
    celebrateIndex: Int? = null,
    /** Animationen im System ausgeschaltet: nur erscheinen, nichts gleitet oder zeichnet. */
    reduceMotion: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary

    // Erst-Moment: Zeile blendet ein, Kurs erscheint sanft, Mini-Chart zeichnet sich,
    // kurz ein Häkchen. Startwerte gelten schon beim ersten Zeichnen, damit nichts aufblitzt.
    val animate = celebrateKey != null && !reduceMotion
    val enter = remember(watch.id) { Animatable(if (animate) 0f else 1f) }
    val priceReveal = remember(watch.id) { Animatable(if (animate) 0f else 1f) }
    val draw = remember(watch.id) { Animatable(if (animate) 0f else 1f) }
    val check = remember(watch.id) { Animatable(0f) }
    LaunchedEffect(celebrateKey) {
        if (celebrateKey == null) {
            // Moment vorbei (oder abgebrochen): Endzustand, nichts bleibt halb stehen
            enter.snapTo(1f)
            priceReveal.snapTo(1f)
            check.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion) {
            // Ohne Bewegung: Häkchen erscheint und verschwindet ohne Übergang
            check.snapTo(1f)
            delay(AddMoment.CHECK_HOLD_MILLIS)
            check.snapTo(0f)
            return@LaunchedEffect
        }
        // War die Zeile schon sichtbar (Startwert 1), bleibt sie stehen — nur das Häkchen kommt
        delay(AddMoment.staggerDelay(celebrateIndex ?: 0))
        enter.animateTo(1f, tween(AddMoment.ENTER_MILLIS))
        check.animateTo(1f, tween(200))
        delay(AddMoment.CHECK_HOLD_MILLIS)
        check.animateTo(0f, tween(AddMoment.CHECK_FADE_MILLIS))
    }
    // Kurs: kurzer Übergang, sobald der erste Kurs da ist (oder gleich nach dem Einblenden)
    val hasPrice = watch.lastPrice != null
    LaunchedEffect(celebrateKey, hasPrice) {
        if (priceReveal.value >= 1f) return@LaunchedEffect
        if (celebrateKey == null || reduceMotion) {
            priceReveal.snapTo(1f)
            return@LaunchedEffect
        }
        if (!hasPrice) return@LaunchedEffect
        snapshotFlow { enter.value }.first { it >= 1f }
        priceReveal.animateTo(1f, tween(350))
    }
    // Mini-Chart: zeichnet sich von links nach rechts, sobald der Verlauf da ist
    val hasSparkline = sparkline != null && sparkline.size >= 2
    LaunchedEffect(celebrateKey, hasSparkline) {
        if (!hasSparkline || draw.value >= 1f) return@LaunchedEffect
        if (celebrateKey == null || reduceMotion) {
            draw.snapTo(1f)
            return@LaunchedEffect
        }
        snapshotFlow { enter.value }.first { it >= 1f }
        draw.animateTo(1f, tween(AddMoment.DRAW_MILLIS))
    }
    val stale = !isNotTraded(watch.lastError) && (watch.lastUpdate <= 0 || now - watch.lastUpdate > staleAfter)

    // Kurssprung kurz in der Kursfarbe aufblitzen lassen
    val flash = remember { Animatable(0f) }
    var shownPrice by remember(watch.id) { mutableStateOf(watch.lastPrice) }
    var flashUp by remember { mutableStateOf(true) }
    LaunchedEffect(watch.lastPrice) {
        val previous = shownPrice
        val current = watch.lastPrice
        shownPrice = current
        if (previous != null && current != null && current != previous) {
            flashUp = current > previous
            flash.snapTo(1f)
            // Kurz (früher 1 s): die rollenden Ziffern zeigen die Änderung schon
            flash.animateTo(0f, tween(durationMillis = 500))
        }
    }
    val flashColor = if (flashUp) PriceColors.up else PriceColors.down

    // Screenreader: die ganze Zeile als ein Satz (Paar, Kurs, Änderung, ≈, Verlauf, Notiz,
    // Alarme, Zustand). Favorit, ⚡ und Menü bleiben eigene Knöpfe; Tippen bleibt.
    val context = LocalContext.current
    // Letzter Abruf gescheitert, aber es gibt einen älteren Kurs: «vor 41 Min · Binance nicht erreichbar»
    val failed = watch.lastError != null && !isNotTraded(watch.lastError)
    val unreachableText = if (failed && watch.lastUpdate > 0) {
        stringResource(R.string.watchlist_row_unreachable, ago(watch.lastUpdate, now), watch.marketName)
    } else {
        null
    }
    // Kein Fehler, aber deutlich älter als das Intervall: «Binance · vor 2 Std. · veraltet»
    val outdatedText = if (!failed && !isNotTraded(watch.lastError) && watch.lastUpdate > 0 &&
        now - watch.lastUpdate > outdatedAfter
    ) {
        stringResource(R.string.watchlist_row_outdated, "${watch.marketName} · ${ago(watch.lastUpdate, now)}")
    } else {
        null
    }
    val warningText = unreachableText ?: outdatedText
    // Technischer Grund nur, wenn er mehr sagt als «nicht erreichbar» (z. B. Paar unbekannt)
    val showErrorLine = watch.lastError != null &&
        (unreachableText == null || !isConnectionError(watch.lastError))
    val errorText = watch.lastError?.takeIf { showErrorLine }?.let { friendlyError(it) }
    val staleText = if (warningText == null && stale && watch.lastUpdate > 0) {
        stringResource(R.string.a11y_stale, ago(watch.lastUpdate, now))
    } else {
        null
    }
    val chartPeriod = stringResource(R.string.widget_range_24h)
    val moveUpLabel = stringResource(R.string.a11y_move_up)
    val moveDownLabel = stringResource(R.string.a11y_move_down)
    val deleteLabel = stringResource(R.string.action_delete)
    val favoriteLabel = stringResource(if (watch.favorite) R.string.favorite_remove else R.string.favorite_add)
    val rowDescription = A11yText.row(
        context = context,
        pair = watch.displayName,
        market = watch.marketName,
        price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
        change24h = watch.change24h,
        extras = listOf(
            converted?.let { stringResource(R.string.a11y_converted, it.removePrefix("≈ ")) },
            sparkline?.takeIf { it.size >= 2 }?.let { A11yText.chart(context, chartPeriod, it) },
            watch.note?.let { stringResource(R.string.a11y_note, it) },
            if (alarmCount > 0) stringResource(R.string.a11y_alarm_count, alarmCount) else null,
            if (watch.notificationEnabled) stringResource(R.string.watchlist_notification) else null,
            warningText,
            errorText,
            staleText,
        ),
    )

    Card(
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = when {
            highlighted -> BorderStroke(1.5.dp, accent)
            watch.favorite -> BorderStroke(1.dp, accent.copy(alpha = 0.45f))
            // Feiner Rand, damit sich die Karten von der Fläche abheben
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        },
        modifier = modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 16.dp.toPx()
            }
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = rowDescription
                val actions = buildList {
                    // Wie die Wisch-Gesten; im Sortiermodus nur Verschieben
                    if (!sortMode) {
                        onFavoriteAction?.let { action -> add(CustomAccessibilityAction(favoriteLabel) { action(); true }) }
                        onDeleteAction?.let { action -> add(CustomAccessibilityAction(deleteLabel) { action(); true }) }
                    }
                    if (canReorder) {
                        add(CustomAccessibilityAction(moveUpLabel) { onMove(WatchMove.UP); true })
                        add(CustomAccessibilityAction(moveDownLabel) { onMove(WatchMove.DOWN); true })
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
        ) {
            if (sortMode) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = handleModifier
                        .padding(start = 6.dp, end = 6.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.12f))
                ) {
                    Icon(
                        painterResource(R.drawable.ic_drag_handle),
                        contentDescription = stringResource(R.string.sort_drag_handle),
                        tint = accent
                    )
                }
            } else if (watch.favorite) {
                // Stern nur bei Favoriten — kein leerer Umriss, der dem Kurs Breite nimmt
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        painterResource(R.drawable.ic_star),
                        contentDescription = stringResource(R.string.favorite_remove),
                        tint = accent
                    )
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }

            Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = watch.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { }
                    )
                    // Erst-Moment: kurzes Häkchen in der Akzentfarbe (das Banner sagt es dem Screenreader)
                    if (check.value > 0f) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp)
                                .graphicsLayer {
                                    alpha = check.value
                                    val scale = 0.7f + 0.3f * check.value
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clearAndSetSemantics { }
                        )
                    }
                    // ⚡ Ungewöhnliche Aktivität — Tipp öffnet «Warum bewegt sich das?»
                    if (hasActivity) {
                        ActivityBolt(onClick = onActivityClick, modifier = Modifier.padding(start = 2.dp))
                    }
                }
                // Im Zeilensatz enthalten, hier für den Screenreader ausgeblendet
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { }) {
                    // Warnung (nicht erreichbar / veraltet) ersetzt die normale Zeitzeile
                    if (warningText != null) {
                        Icon(
                            painterResource(R.drawable.ic_error),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp).size(13.dp)
                        )
                    }
                    Text(
                        text = when {
                            warningText != null -> warningText
                            watch.lastUpdate > 0 -> "${watch.marketName} · ${ago(watch.lastUpdate, now)}"
                            else -> watch.marketName
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            warningText != null -> MaterialTheme.colorScheme.error
                            stale && watch.lastUpdate > 0 -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Kleine Zeichen: Meldung an, Alarme scharf
                    if (watch.notificationEnabled) {
                        Icon(
                            painterResource(R.drawable.ic_notifications),
                            contentDescription = stringResource(R.string.watchlist_notification),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp).size(13.dp)
                        )
                    }
                    if (alarmCount > 0) {
                        Icon(
                            painterResource(R.drawable.ic_alarm_overview),
                            contentDescription = pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount),
                            tint = accent,
                            modifier = Modifier.padding(start = 6.dp).size(13.dp)
                        )
                        Text(
                            text = alarmCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
                watch.lastError?.takeIf { showErrorLine }?.let { error ->
                    Text(
                        text = friendlyError(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isNotTraded(error)) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
                // Eigene Notiz (#233), dezent unter dem Paar
                watch.note?.let { note ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { }
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_note),
                            contentDescription = stringResource(R.string.note_title),
                            tint = accent.copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            // Mini-Chart zwischen Paar und Kurs; feste Grösse, der Kurs wird nie schmaler
            if (sparkline != null && sparkline.size >= 2) {
                Sparkline(
                    values = sparkline,
                    progress = { draw.value },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        // 28 dp (früher 22): passt in die Zeilenhöhe, die der Stern-Knopf (48 dp) vorgibt
                        .size(width = 56.dp, height = 28.dp)
                        .alpha(if (stale) 0.5f else 1f)
                        .clearAndSetSemantics { }
                )
            }

            // Kurs bleibt auch im Sortiermodus sichtbar
            Column(
                horizontalAlignment = Alignment.End,
                // Veraltete Kurse abblassen
                modifier = Modifier.padding(start = 8.dp).alpha(if (stale) 0.5f else 1f).clearAndSetSemantics { }
            ) {
                // Geänderte Ziffern rollen (nach oben bei steigendem Kurs)
                RollingNumberText(
                    text = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                    value = watch.lastPrice,
                    style = MaterialTheme.typography.titleMedium.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = priceReveal.value
                            translationY = (1f - priceReveal.value) * 6.dp.toPx()
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(flashColor.copy(alpha = 0.28f * flash.value))
                        .padding(horizontal = 4.dp)
                )
                Box(Modifier.graphicsLayer { alpha = priceReveal.value }) {
                    if (watch.lastPrice != null) DayChangePill(change = watch.change24h)
                }
                if (converted != null) {
                    Text(
                        text = converted,
                        style = MaterialTheme.typography.labelSmall.amountNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp, end = 4.dp)
                    )
                }
            }

            // Sortiermodus: «ganz nach oben/unten» im Zeilenmenü statt als Symbole
            if (sortMode) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.action_more)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_move_top)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_move_top), null) },
                            onClick = { menu = false; onMove(WatchMove.TOP) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_move_bottom)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_move_bottom), null) },
                            onClick = { menu = false; onMove(WatchMove.BOTTOM) }
                        )
                    }
                }
            }
        }
    }
}

/** Mindestbreite des Bildschirms (dp, bei Schriftgrösse 100 %) für das Mini-Chart. */
private const val SPARKLINE_MIN_SCREEN_DP = 360

/**
 * Mini-Chart: Linie mit sanfter Fläche darunter (Verlauf von der Kursfarbe zu
 * transparent), ohne Achsen und Punkte. Farbe nach Richtung über die
 * ganze Reihe (letzter ≥ erster Wert = steigend), gemäss Einstellung «Kursfarben».
 * Hoher Kontrast: Fläche kräftiger (18 % statt 12 %), die Linie ist immer voll.
 * Für den Screenreader beschreibt die Zeile den Verlauf ([A11yText.chart]).
 */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, progress: () -> Float = { 1f }) {
    val up = values.last() >= values.first()
    val color = if (up) PriceColors.up else PriceColors.down
    val fillAlpha = if (LocalHighContrast.current) 0.18f else 0.12f
    Canvas(modifier = modifier) {
        val stroke = 1.75.dp.toPx()
        val inset = stroke
        val w = size.width - 2 * inset
        val h = size.height - 2 * inset
        if (w <= 0f || h <= 0f) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = max - min
        val path = Path()
        // Fläche: dieselben Punkte, dann am unteren Rand zurück
        val area = Path()
        var top = size.height
        values.forEachIndexed { i, v ->
            val x = inset + w * i / values.lastIndex
            // Flache Reihe: Linie in der Mitte
            val y = if (range > 0.0) inset + h - ((v - min) / range * h).toFloat() else inset + h / 2
            if (y < top) top = y
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(inset + w, size.height)
        area.lineTo(inset, size.height)
        area.close()
        // Erst-Moment: von links nach rechts aufdecken (1 = ganz)
        val shown = progress().coerceIn(0f, 1f)
        if (shown <= 0f) return@Canvas
        clipRect(right = size.width * shown) {
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)),
                    startY = top,
                    endY = size.height,
                ),
            )
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * «≈ 61’234 CHF» für die Merkliste: nur mit Zielwährung, bekanntem Faktor,
 * gültigem Kurs und wenn die Quote nicht schon die Zielwährung ist.
 */
private fun convertedPrice(watch: WatchEntity, target: String?, rates: Map<String, Double>): String? {
    if (target == null || CurrencyConversion.sameCurrency(watch.quoteAsset, target)) return null
    val price = watch.lastPrice?.takeIf { it > 0.0 } ?: return null
    val rate = rates[CurrencyConversion.normalize(watch.quoteAsset)] ?: return null
    val value = CurrencyConversion.convert(price, rate) ?: return null
    return "≈ " + PriceFormat.priceWithCurrency(value, target)
}

/**
 * Prozent-Änderung als Pille in der Kursfarbe (Grün/Rot bzw. Blau/Orange),
 * immer mit Vorzeichen — die Bedeutung hängt nie allein an der Farbe. Praktisch keine
 * Änderung: graues «0.00%» statt einer Lücke.
 */
@Composable
internal fun ChangePill(change: Double?) {
    if (change == null) return
    val formatted = PriceFormat.changePercent(change)
    // Pfeil folgt dem Vorzeichen, nie dem Farbtausch; bei 0.00% keiner
    val text = formatted?.let { "${PriceFormat.changeArrow(change)} $it" } ?: "0.00%"
    val color = if (formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(change)
    // Screenreader: «gestiegen um 2.35%» statt «+2.35%» (das «−» wird uneinheitlich gelesen)
    val spoken = A11yText.change(LocalContext.current, change)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Aktionen zu einem Paar: oben Kurs und Chart ([SheetPriceChart]), dann gross Alarm, Warum?
 * und Favorit; darunter als Liste Gruppe, Notiz, Portfolio, Aktualisieren, Vorlesen, Meldung
 * und Löschen.
 */
@Composable
private fun WatchActionsSheet(
    watch: WatchEntity,
    alarmCount: Int,
    onDismiss: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onTtsChange: (Boolean) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAlarms: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    loadFutures: suspend (WatchEntity) -> FuturesInfo? = { null },
    groups: List<String> = emptyList(),
    /** null = Portfolio ausgeschaltet, Eintrag verborgen. */
    onAddToPortfolio: (() -> Unit)? = null,
    onSetGroup: (String?) -> Unit = {},
    onWhy: () -> Unit = {},
    onSetNote: (String?) -> Unit = {},
    /** Chart als Linie statt Kerzen (zuletzt gewählt, für alle Paare gleich). */
    chartLine: Boolean = false,
    onChartLineChange: (Boolean) -> Unit = {},
    cachedChart: (WatchEntity, SheetChartRange) -> SheetChartResult? = { _, _ -> SheetChartResult.Unsupported },
    loadChart: suspend (WatchEntity, SheetChartRange) -> SheetChartResult = { _, _ -> SheetChartResult.Unsupported },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetContext = LocalContext.current
    var showWidgetManual by remember { mutableStateOf(false) }
    if (showWidgetManual) WidgetManualDialog(onDismiss = { showWidgetManual = false })
    var editGroup by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(false) }
    if (editNote) {
        NoteDialog(
            title = stringResource(R.string.note_title),
            initial = watch.note,
            onSave = { onSetNote(it); editNote = false },
            onDismiss = { editNote = false }
        )
    }
    if (editGroup) {
        GroupDialog(
            current = watch.groupName,
            groups = groups,
            onSelect = { onSetGroup(it); editGroup = false },
            onDismiss = { editGroup = false }
        )
    }
    // Futures-Kennzahlen nur für Perpetuals laden
    val futures by produceState<FuturesInfo?>(initialValue = null, watch.id) {
        value = if (watch.contractType == FuturesContractType.PERPETUAL) loadFutures(watch) else null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Mit dem Chart wird das Blatt auf kleinen Geräten höher als der Bildschirm
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            // Kopf: Paar, Börse, Kurs gross
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        watch.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        watch.marketName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                    style = MaterialTheme.typography.displaySmall.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold
                )
                Box(modifier = Modifier.padding(start = 12.dp)) {
                    if (watch.lastPrice != null) DayChangePill(change = watch.change24h)
                }
            }
            Text(
                text = watch.lastError?.let { friendlyError(it) }
                    ?: stringResource(R.string.watchlist_updated, PriceFormat.time(watch.lastUpdate)),
                style = MaterialTheme.typography.bodySmall,
                color = if (watch.lastError != null && !isNotTraded(watch.lastError)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Kurs-Chart (24h · 7T · 30T, Kerzen/Linie); ohne Kerzenquelle (DEX) ganz ausgeblendet
            SheetPriceChart(
                watch = watch,
                line = chartLine,
                onLineChange = onChartLineChange,
                cached = cachedChart,
                load = loadChart,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Die drei häufigsten Aktionen zuerst und gleich gross: Alarm, Warum?, Favorit
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // Gleich hoch, auch wenn ein Text umbricht
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 12.dp)
            ) {
                PrimarySheetAction(
                    icon = R.drawable.ic_notifications,
                    text = if (alarmCount > 0) pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount)
                    else stringResource(R.string.watch_action_alarm),
                    onClick = onOpenAlarms,
                    modifier = Modifier.weight(1f)
                )
                PrimarySheetAction(
                    icon = R.drawable.ic_lightbulb,
                    text = stringResource(R.string.watch_action_why),
                    onClick = onWhy,
                    modifier = Modifier.weight(1f)
                )
                PrimarySheetAction(
                    icon = if (watch.favorite) R.drawable.ic_star else R.drawable.ic_star_outline,
                    text = stringResource(R.string.watch_action_favorite),
                    onClick = onToggleFavorite,
                    checked = watch.favorite,
                    modifier = Modifier.weight(1f)
                )
            }

            watch.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { editNote = true }
                        .padding(12.dp)
                )
            }

            futures?.let { FuturesSection(it) }

            // Alles Weitere als schlichte Liste
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SheetAction(
                icon = R.drawable.ic_list,
                text = watch.groupName?.let { stringResource(R.string.group_value, it) }
                    ?: stringResource(R.string.group_title),
                onClick = { editGroup = true }
            )
            SheetAction(
                icon = R.drawable.ic_note,
                text = stringResource(if (watch.note.isNullOrBlank()) R.string.note_add else R.string.note_edit),
                onClick = { editNote = true }
            )
            onAddToPortfolio?.let { addToPortfolio ->
                SheetAction(
                    icon = R.drawable.ic_portfolio,
                    text = stringResource(R.string.portfolio_add_from_watch),
                    onClick = addToPortfolio
                )
            }
            SheetAction(
                icon = R.drawable.ic_refresh,
                text = stringResource(R.string.action_refresh),
                onClick = onRefresh
            )
            // Einzel-Widget mit diesem Paar auf den Startbildschirm (Runde 13b); kann der
            // Startbildschirm das nicht, eine kurze Anleitung
            SheetAction(
                icon = R.drawable.ic_widgets,
                text = stringResource(R.string.watch_action_add_widget),
                onClick = {
                    if (!WidgetPinner.request(sheetContext, WidgetKind.SINGLE, watch.id)) showWidgetManual = true
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SwitchRow(
                title = stringResource(R.string.watchlist_tts),
                checked = watch.ttsEnabled,
                onCheckedChange = onTtsChange
            )
            SwitchRow(
                title = stringResource(R.string.watchlist_notification),
                checked = watch.notificationEnabled,
                onCheckedChange = onNotificationChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SheetAction(
                icon = R.drawable.ic_delete,
                text = stringResource(R.string.action_delete),
                danger = true,
                onClick = onDelete
            )
        }
    }
}

/** Funding Rate, nächste Zahlung und Open Interest eines Perpetuals. */
@Composable
private fun FuturesSection(info: FuturesInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        // ⓘ blendet die Erklärungen zu Funding und Open Interest ein
        var showExplain by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.futures_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showExplain = !showExplain }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.explain_show),
                    tint = if (showExplain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showExplain) {
            Text(
                stringResource(R.string.futures_funding) + ": " + stringResource(R.string.explain_funding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                stringResource(R.string.futures_open_interest) + ": " + stringResource(R.string.explain_open_interest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.futures_funding),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val rate = info.fundingRatePercent
                Text(
                    text = rate?.let { "%+.4f %%".format(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                    // Positiv: Longs zahlen an Shorts (Markt überhitzt eher), negativ umgekehrt
                    color = if (rate == null) MaterialTheme.colorScheme.onSurface else PriceColors.forChange(rate)
                )
                info.nextFundingTime?.let { next ->
                    val minutes = ((next - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Text(
                        stringResource(R.string.futures_next_funding, (minutes / 60).toInt(), (minutes % 60).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.futures_open_interest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = info.openInterestUsd?.let { "$" + compactNumber(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.tabularNumbers()
                )
            }
        }
        Text(
            stringResource(R.string.futures_source, info.source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Quote-Währungen, deren Kurs als USDT-Preis übernommen werden kann. */
private val USD_LIKE_QUOTES = setOf("USDT", "USD", "USDC", "FDUSD")

private fun compactNumber(value: Double): String = when {
    value >= 1e9 -> "%.2f B".format(value / 1e9)
    value >= 1e6 -> "%.1f M".format(value / 1e6)
    value >= 1e3 -> "%.1f K".format(value / 1e3)
    else -> "%.0f".format(value)
}

/**
 * Gruppen-Auswahl oben: «Alle · Gruppe 1 · Gruppe 2 … · +».
 * Tippen filtert, lange drücken öffnet «Gruppe bearbeiten», «+» legt eine an.
 * Ohne Gruppen steht nur «+» da.
 */
@Composable
private fun GroupChips(
    groups: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (groups.isNotEmpty()) {
            item(key = "all") {
                GroupChip(
                    text = stringResource(R.string.group_all),
                    selected = selected == null,
                    onClick = { onSelect(null) }
                )
            }
            items(groups, key = { "group:$it" }) { group ->
                GroupChip(
                    text = group,
                    selected = selected == group,
                    onClick = { onSelect(group) },
                    onLongClick = { onEdit(group) }
                )
            }
        }
        item(key = "add") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(onClick = onAdd)
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.group_add),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Chip im Stil des Material-FilterChips, aber mit langem Drücken
 * (FilterChip kennt das nicht).
 */
@Composable
private fun GroupChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Gruppe wählen: bestehende Gruppen, «Keine Gruppe» oder eine neue anlegen.
 * Eine Auswahl gilt sofort; nur die neue Gruppe braucht «Speichern».
 */
@Composable
private fun GroupDialog(
    current: String?,
    groups: List<String>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                GroupOption(stringResource(R.string.group_none), current == null && !creating) { onSelect(null) }
                groups.forEach { group ->
                    GroupOption(group, current == group && !creating) { onSelect(group) }
                }
                GroupOption(stringResource(R.string.group_new), creating) { creating = true }
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_GROUP_NAME) },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(enabled = trimmed.isNotEmpty(), onClick = { onSelect(trimmed) }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private const val MAX_GROUP_NAME = 24

@Composable
private fun GroupOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Grosse Aktion oben im Aktionsblatt (drei nebeneinander): Symbol über kurzem Text.
 * [checked] != null = Umschalter (Favorit); der Screenreader sagt dann «ein»/«aus».
 */
@Composable
private fun PrimarySheetAction(
    icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
) {
    val action = if (checked != null) {
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (checked == true) 0.16f else 0.08f))
            .then(action)
            .padding(horizontal = 6.dp, vertical = 10.dp)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun SheetAction(icon: Int, text: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (danger) color else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

/**
 * Ziehen zum Sortieren ohne Zusatzbibliothek: Während des Ziehens gilt eine
 * lokale Reihenfolge; die gezogene Karte folgt dem Finger, tauscht mit der
 * Karte unter ihrer Mitte (nur innerhalb Favoriten bzw. übrige) und scrollt
 * am Rand mit. Beim Loslassen wird die Reihenfolge gespeichert.
 */
private class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var items by mutableStateOf<List<WatchEntity>?>(null)
    var draggingId by mutableStateOf<Long?>(null)
    var offset by mutableFloatStateOf(0f)

    /** Aktuelle Liste aus der Datenbank, jede Komposition neu gesetzt. */
    var source: List<WatchEntity> = emptyList()
    var onDrop: (List<Long>) -> Unit = {}

    private var startOrder: List<Long> = emptyList()

    fun start(id: Long) {
        val current = items ?: source
        items = current
        startOrder = current.map { it.id }
        draggingId = id
        offset = 0f
    }

    fun drag(dy: Float) {
        val id = draggingId ?: return
        val list = items ?: return
        offset += dy

        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val center = current.offset + offset + current.size / 2f

        val target = visible.firstOrNull { info ->
            info.key is Long && info.key != id &&
                center >= info.offset && center <= info.offset + info.size
        }
        if (target != null) {
            val from = list.indexOfFirst { it.id == id }
            val to = list.indexOfFirst { it.id == target.key }
            // Favoriten bleiben oben: nur innerhalb der eigenen Gruppe tauschen.
            if (from >= 0 && to >= 0 && list[from].favorite == list[to].favorite) {
                // Betrifft der Tausch die oberste sichtbare Karte, hält LazyColumn
                // sonst die Scrollposition an deren Schlüssel fest — die Liste springt.
                val first = listState.firstVisibleItemIndex
                if (current.index == first || target.index == first) {
                    val firstOffset = listState.firstVisibleItemScrollOffset
                    scope.launch { listState.scrollToItem(first, firstOffset) }
                }
                items = list.toMutableList().apply { add(to, removeAt(from)) }
                offset += current.offset - target.offset
            }
        }

        // Am Rand mitscrollen, damit auch lange Listen sortierbar sind.
        val info = listState.layoutInfo
        val top = current.offset + offset
        val bottom = top + current.size
        val edge = 120f
        val step = when {
            bottom > info.viewportEndOffset - edge -> 18f
            top < info.viewportStartOffset + edge -> -18f
            else -> 0f
        }
        if (step != 0f) {
            scope.launch {
                val consumed = listState.scrollBy(step)
                offset += consumed
            }
        }
    }

    fun end() {
        val list = items
        draggingId = null
        offset = 0f
        if (list != null && list.map { it.id } != startOrder) {
            onDrop(list.map { it.id })
        } else {
            items = null
        }
    }
}


/** Netzwerk-Fehler (kein Internet, Server nicht erreichbar, Zeitüberschreitung)? */
internal fun isConnectionError(error: String?): Boolean {
    if (error == null) return false
    val e = error.lowercase()
    return CONNECTION_HINTS.any { it in e }
}

private val CONNECTION_HINTS = listOf(
    "unknownhost", "unable to resolve host", "connectexception", "failed to connect",
    "sockettimeout", "timeout", "timed out", "noroutetohost", "network is unreachable",
    "connection reset", "connection refused", "ssl", "eof",
)

/** Paar wird an der Börse nicht mehr gehandelt — ein Zustand, kein Fehler. */
internal fun isNotTraded(error: String?): Boolean =
    error == com.cryptochecker.app.domain.refresh.NOT_TRADED_MARKER

/** Technische Fehlermeldung → verständlicher Text (z. B. «Keine Verbindung»). */
@Composable
internal fun friendlyError(error: String): String = when {
    isNotTraded(error) -> stringResource(R.string.watch_not_traded)
    isConnectionError(error) -> stringResource(R.string.watch_error_offline)
    error == UserFriendlyMarketError.EMPTY_RESPONSE || error == UserFriendlyMarketError.NO_TICKER_DATA ->
        stringResource(R.string.market_data_empty_error)
    error == UserFriendlyMarketError.UNKNOWN_EMPTY -> stringResource(R.string.something_went_wrong)
    // Früher auf Deutsch gespeichert; ältere Einträge zeigen so ebenfalls den übersetzten Text
    error == UserFriendlyMarketError.MARKET_UNAVAILABLE || error == LEGACY_MARKET_UNAVAILABLE ->
        stringResource(R.string.market_unavailable_error)
    // Technische Meldungen (HTTP-Code, Ausnahmetext) nicht roh anzeigen; Details stehen im HTTP-Log
    else -> stringResource(R.string.something_went_wrong)
}

private const val LEGACY_MARKET_UNAVAILABLE = "Börse nicht verfügbar"
