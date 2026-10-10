package com.cryptochecker.app.ui.features.info

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.features.portfolio.PortfolioQuickAddSheet
import com.cryptochecker.app.ui.features.watchlist.LocalChangeView
import com.cryptochecker.app.ui.features.watchlist.USD_LIKE_QUOTES
import com.cryptochecker.app.ui.features.watchlist.WatchActionsSheet
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.ui.features.watchlist.WhySheet
import com.cryptochecker.app.ui.features.watchlist.groupsOf
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import kotlinx.coroutines.delay

/**
 * Was der Markt-Tab zu einem Coin aus «Heute auffällig» offen hat — wie in der Merkliste
 * ([com.cryptochecker.app.ui.features.watchlist.WatchlistUiState]): das Aktionsblatt eines
 * gespeicherten Paars ([actionsFor]) bzw. die Vorschau eines Paars, das nicht in der Merkliste
 * steht ([previewSymbol], Binance «COIN/USDT», nur im Speicher), und «Warum?» dazu.
 * Ids und Symbol überstehen Drehen und Prozess-Neustart; die Vorschau wird dann neu aufgebaut.
 */
@Stable
internal class MarketCoinState(
    actionsForState: MutableState<Long?>,
    previewSymbolState: MutableState<String?>,
    whyForState: MutableState<Long?>,
    whyPreviewState: MutableState<Boolean>,
    returnToActionsState: MutableState<Long?>,
) {
    /** Gespeichertes Paar, dessen Aktionsblatt offen ist. */
    var actionsFor by actionsForState

    /** Coin, dessen Vorschau offen ist (nicht in der Merkliste). */
    var previewSymbol by previewSymbolState

    /** Vorschau-Paar selbst (nicht gespeichert, nur im Speicher; siehe [InfoViewModel.previewWatch]). */
    var previewBase by mutableStateOf<WatchEntity?>(null)

    /** «Warum?» eines gespeicherten Paars. */
    var whyFor by whyForState

    /** «Warum?» der Vorschau (das Blatt der Vorschau ist so lange zu). */
    var whyPreview by whyPreviewState

    /** Aus dem Aktionsblatt zu «Alarme» oder «Warum?» gewechselt: Zurück öffnet es wieder. */
    var returnToActions by returnToActionsState

    /**
     * Vorschau eben hinzugefügt: bis der neue Eintrag seinen ersten Kurs hat (höchstens
     * [ADD_HOLD_MILLIS]), bleibt die Vorschau mit «Zur Merkliste hinzugefügt.» stehen — kein «—».
     */
    var adding by mutableStateOf(false)

    /** Paar, das gerade ins Portfolio übernommen wird (Erfassen-Blatt). */
    var portfolioFor by mutableStateOf<WatchEntity?>(null)

    /** Tipp auf eine Zeile von «Heute auffällig», Coin in der Merkliste. */
    fun openStored(id: Long) {
        closePreview()
        actionsFor = id
    }

    /** Tipp auf eine Zeile von «Heute auffällig», Coin nicht in der Merkliste. */
    fun openPreview(watch: WatchEntity) {
        actionsFor = null
        adding = false
        whyPreview = false
        previewBase = watch
        previewSymbol = watch.baseAsset
    }

    fun closePreview() {
        previewSymbol = null
        previewBase = null
        whyPreview = false
        adding = false
    }

    /** Zurück aus «Alarme» bzw. «Warum?»: Aktionsblatt wieder öffnen, falls von dort gekommen. */
    fun reopenActions() {
        val id = returnToActions ?: return
        returnToActions = null
        actionsFor = id
    }
}

@Composable
internal fun rememberMarketCoinState(): MarketCoinState {
    val actionsFor = rememberSaveable { mutableStateOf<Long?>(null) }
    val previewSymbol = rememberSaveable { mutableStateOf<String?>(null) }
    val whyFor = rememberSaveable { mutableStateOf<Long?>(null) }
    val whyPreview = rememberSaveable { mutableStateOf(false) }
    val returnToActions = rememberSaveable { mutableStateOf<Long?>(null) }
    return remember { MarketCoinState(actionsFor, previewSymbol, whyFor, whyPreview, returnToActions) }
}

/**
 * Aktionsblatt und «Warum?» für «Heute auffällig» — dasselbe Blatt wie ein Tipp auf die Zeile in
 * der Merkliste ([WatchActionsSheet], gleiche Aktionen über [watchlist], dieselbe Instanz wie die
 * Merkliste). Löschen mit «Rückgängig» im [banner], «Alarme» als eigene Seite ([onOpenAlarms]),
 * zurück öffnet das Blatt wieder. Vorschau (Coin nicht in der Merkliste): Kurs aus einer
 * Ticker-Abfrage ohne Speichern, «Zur Merkliste hinzufügen» legt das Paar wie der Hinzufügen-Tab
 * an; danach wird daraus das normale Blatt des neuen Eintrags. Wie iOS `CycleCoinSheets`.
 */
@Composable
internal fun MarketCoinSheets(
    state: MarketCoinState,
    viewModel: InfoViewModel,
    watchlist: WatchlistViewModel,
    lockViewModel: PortfolioLockViewModel,
    watches: List<WatchEntity>,
    activity: Map<Long, ActivityReport>,
    sensitivity: ActivitySensitivity,
    banner: WatchlistBanner,
    onOpenAlarms: (Long) -> Unit,
) {
    val context = LocalContext.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val alarmCounts by watchlist.alarmCounts.collectAsStateWithLifecycle()
    val portfolioEnabled by watchlist.portfolioEnabled.collectAsStateWithLifecycle()
    val sheetChartLine by watchlist.sheetChartLine.collectAsStateWithLifecycle()
    val changeBasis by watchlist.changeBasis.collectAsStateWithLifecycle()
    val changeStamp by watchlist.changeStamp.collectAsStateWithLifecycle()
    val showChangePeriod by watchlist.showChangePeriod.collectAsStateWithLifecycle()
    val groups = remember(watches) { groupsOf(watches) }

    // Portfolio-Sperre wie in der Merkliste: Erfassen-Blatt erst nach dem Entsperren
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    val unlockReason = stringResource(R.string.portfolio_lock_reason)
    LaunchedEffect(portfolioAccess) {
        if (portfolioAccess != PortfolioAccess.OPEN) state.portfolioFor = null
    }

    // Zurück aus «Alarme» (eigene Seite): war das Aktionsblatt der Ausgangspunkt, öffnet es sich wieder
    LaunchedEffect(Unit) { if (state.whyFor == null) state.reopenActions() }

    // Vorschau nach Drehen/Prozess-Neustart: Paar neu aufbauen (Binance führt es nicht mehr: zu)
    val symbol = state.previewSymbol
    LaunchedEffect(symbol) {
        if (symbol != null && state.previewBase?.baseAsset != symbol) {
            val rebuilt = viewModel.previewWatch(symbol)
            if (rebuilt == null) state.closePreview() else state.previewBase = rebuilt
        }
    }
    // Kurs der Vorschau: eine Ticker-Abfrage, nichts wird gespeichert; «Erneut versuchen» lädt neu
    var previewReload by remember(symbol) { mutableIntStateOf(0) }
    val previewBase = state.previewBase
    val previewShown by produceState<WatchEntity?>(null, previewBase, previewReload) {
        val base = previewBase ?: return@produceState
        // Anderer Coin: nie kurz den Kurs des vorherigen zeigen
        if (value?.baseAsset != base.baseAsset) value = base
        // Älterer Kurs bleibt bei einem Fehler stehen (das Blatt bietet dann «Erneut versuchen» an)
        value = viewModel.previewQuote(value ?: base)
    }

    val stored = state.actionsFor?.let { id -> watches.firstOrNull { it.id == id } }
    // Eben hinzugefügt: warten, bis der neue Eintrag einen Kurs (oder Fehler) hat, höchstens kurz
    LaunchedEffect(state.adding, stored?.lastUpdate, stored?.lastError) {
        if (!state.adding) return@LaunchedEffect
        // Noch ohne Eintrag in der Liste bzw. ohne Kurs: kurz warten (danach so oder so weiter)
        if (stored == null || (stored.lastUpdate <= 0 && stored.lastError == null)) delay(ADD_HOLD_MILLIS)
        state.adding = false
    }
    val showStored = stored != null && !state.adding
    // Gewechselt: die Vorschau ist erledigt
    LaunchedEffect(showStored) { if (showStored && state.previewSymbol != null) state.closePreview() }

    val now = remember(changeStamp, changeBasis, stored?.lastUpdate) { System.currentTimeMillis() }
    val changeView = remember(changeBasis, changeStamp, now, showChangePeriod) {
        ChangeView.of(changeStamp, changeBasis, now, showPeriod = showChangePeriod)
    }
    // Vorschau: rollende 24 h aus dem Ticker — so heisst es auch neben der Pille
    val previewView = remember(showChangePeriod) {
        ChangeView(basis = ChangeBasis.ROLLING_24H, current = true, showPeriod = showChangePeriod)
    }

    val shown = when {
        showStored -> stored
        // Kurs erst, wenn er zu diesem Coin gehört (beim Wechsel nie ein Bild lang der vorherige)
        state.previewSymbol != null && !state.whyPreview ->
            previewShown?.takeIf { it.baseAsset == previewBase?.baseAsset } ?: previewBase
        else -> null
    }
    if (shown != null) {
        val isPreview = !showStored
        CompositionLocalProvider(LocalChangeView provides if (isPreview) previewView else changeView) {
            WatchActionsSheet(
                watch = shown,
                alarmCount = if (isPreview) 0 else alarmCounts[shown.id] ?: 0,
                onDismiss = {
                    state.actionsFor = null
                    state.closePreview()
                },
                onNotificationChange = {
                    if (it) requestNotifications()
                    watchlist.setNotificationEnabled(shown, it)
                },
                onTtsChange = { watchlist.setTtsEnabled(shown, it) },
                onToggleFavorite = { watchlist.toggleFavorite(shown) },
                // Zurück aus «Alarme» öffnet dieses Blatt wieder
                onOpenAlarms = {
                    state.returnToActions = shown.id
                    state.actionsFor = null
                    onOpenAlarms(shown.id)
                },
                onRefresh = { if (isPreview) previewReload++ else watchlist.refreshOne(shown.id) },
                // Wie in der Merkliste: sofort löschen, «Rückgängig» im Banner (keine Rückfrage)
                onDelete = {
                    state.actionsFor = null
                    watchlist.deleteWithUndo(shown)
                    banner.show(resources.getString(R.string.watchlist_removed, shown.displayName)) {
                        watchlist.undoDelete(shown.id)
                    }
                },
                groups = groups,
                onAddToPortfolio = if (portfolioEnabled && !isPreview) {
                    {
                        state.actionsFor = null
                        lockViewModel.requireUnlock(context.findFragmentActivity(), unlockReason) {
                            state.portfolioFor = shown
                        }
                    }
                } else null,
                onSetGroup = { watchlist.setGroup(shown, it) },
                onSetNote = { watchlist.setNote(shown, it) },
                loadFutures = watchlist::fetchFutures,
                onWhy = {
                    if (isPreview) {
                        state.whyPreview = true
                    } else {
                        state.returnToActions = shown.id
                        state.actionsFor = null
                        state.whyFor = shown.id
                    }
                },
                chartLine = sheetChartLine,
                onChartLineChange = watchlist::setSheetChartLine,
                cachedChart = watchlist::cachedSheetChart,
                loadChart = watchlist::loadSheetChart,
                satsRate = watchlist::satsRate,
                candleSource = if (isPreview) null else watchlist.foreignCandleSource(shown),
                preview = isPreview,
                adding = state.adding,
                onAddToWatchlist = add@{
                    val candidate = state.previewBase ?: return@add
                    if (state.adding) return@add
                    state.adding = true
                    watchlist.addPreview(candidate) { id, added ->
                        if (id == null) {
                            state.adding = false
                            return@addPreview
                        }
                        // Wie der Hinzufügen-Tab: neue Paare haben Meldungen an — Erlaubnis jetzt fragen
                        if (added) {
                            requestNotifications()
                        } else {
                            // Stand schon in der Merkliste: gleich dessen Blatt
                            state.adding = false
                        }
                        state.actionsFor = id
                    }
                },
            )
        }
    }

    // Kurs nur vorbelegen, wenn die Quote praktisch USDT ist
    state.portfolioFor?.let { watch ->
        PortfolioQuickAddSheet(
            coin = watch.baseAsset,
            priceUsdt = watch.lastPrice?.takeIf { it > 0.0 && watch.quoteAsset.uppercase() in USD_LIKE_QUOTES },
            onDismiss = { state.portfolioFor = null }
        )
    }

    // «Warum?» eines gespeicherten Paars (nicht mehr gehandelt: keins); zu = Aktionsblatt wieder
    state.whyFor?.let { id -> watches.firstOrNull { it.id == id && !it.isNotTraded } }?.let { watch ->
        WhySheet(
            watch = watch,
            // Wie in der Merkliste nach der gewählten Empfindlichkeit
            signals = ActivityAnalyzer.applySensitivity(
                activity[watch.id]?.active(System.currentTimeMillis()).orEmpty(),
                sensitivity,
            ),
            load = viewModel::explain,
            onDismiss = { state.whyFor = null; state.reopenActions() },
            candleSource = watchlist.foreignCandleSource(watch).takeIf { !changeBasis.isDay },
        )
    }

    // «Warum?» der Vorschau: ohne Signale (das Paar wird nicht überwacht); zu = Vorschau wieder
    if (state.whyPreview) {
        previewShown?.takeIf { !it.isNotTraded }?.let { watch ->
            WhySheet(
                watch = watch,
                signals = emptyList(),
                load = viewModel::explain,
                onDismiss = { state.whyPreview = false },
            )
        }
    }
}

/** So lange bleibt die Vorschau nach «Hinzufügen» höchstens stehen, bis der erste Kurs da ist. */
private const val ADD_HOLD_MILLIS = 6_000L
