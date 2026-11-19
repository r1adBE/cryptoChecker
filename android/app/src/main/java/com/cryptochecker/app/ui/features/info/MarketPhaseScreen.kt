package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.domain.activity.CandleSeries
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.market.UnusualReport
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.AppMenuHead
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.LoadTopBar
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.navigation.OnTabReselect
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.rememberReduceMotion
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.resources.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Tab «Markt»: Jetzt (Puls, Auffälliges), Einordnung (Fear & Greed, Bitcoin-Kennzahlen, Dominanz, Halving) und Daten.
 * Zeigt sofort den Zwischenspeicher, lädt Abgelaufenes still nach (siehe [InfoViewModel]);
 * nach unten ziehen lädt alles neu. [viewModel] gilt für die ganze Activity (AppNavHost).
 *
 * Die Karten erscheinen beim ersten Öffnen ruhig von oben nach unten ([MarketReveal]):
 * noch nicht gezeigte Karten sind gar nicht da (darunter kann nichts verschoben werden),
 * nur eine kleine Ladezeile unter der letzten. Einmal gezeigt, bleibt alles stehen.
 *
 * Aufbau von «Jetzt» (gleich wie iOS): oben EINE Heldenkarte — Crypto Pulse mit
 * «Kurz gesagt» als Körper ([CryptoPulseCard], [rememberPlainSummary]); darüber nur die Status-Pille.
 * Darunter ruhig und kompakt Fear & Greed ([FearGreedRow], compact) und «Gewinner und Verlierer 24 h»
 * ([MarketMoversCard], je drei, «Alle zeigen»). Dann «Mehr Marktdaten» ([MarketMoreToggle], Standard zu,
 * gemerkt) mit «Heute auffällig», dem Wirtschaftsdaten-Hinweis (Termin in ±2 h) und zwei Zeilen
 * ([MarketSectionRow]) zu den Unterseiten «Stimmung & Zyklus» und «Marktdaten» ([MarketSectionScreen],
 * die Karten unverändert). Deren Daten laden schon beim Öffnen des Tabs — die Unterseite steht sofort.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MarketPhaseScreen(
    viewModel: InfoViewModel = hiltViewModel(),
    /**
     * Merkliste (dieselbe Instanz wie dort, AppNavHost): «Rückgängig» des Banners nach «Löschen» auf einer Paarseite,
     * die von hier geöffnet wurde.
     */
    watchlistViewModel: WatchlistViewModel = hiltViewModel(),
    /**
     * «Heute auffällig» → Coin nicht in der Merkliste und von Binance (COIN/USDT) nicht geführt:
     * Hinzufügen-Tab mit dieser Suche (sonst die Vorschau auf der Paarseite).
     */
    onOpenExplorer: (String) -> Unit = {},
    /** Tipp auf einen Coin: Paarseite des Eintrags (Id) bzw. Vorschau (Id 0, Symbol). */
    onOpenPair: (Long, String?) -> Unit = { _, _ -> },
    /** ⋯ › App-Logo und Name: Einstellungen › «Über die App». */
    onOpenAbout: () -> Unit = {},
    /** Zeile «Einordnung» bzw. «Daten»: eigene Unterseite mit den Karten (zurück führt hierher). */
    onOpenSection: (MarketSection) -> Unit = {},
    /** Offener Sprung von aussen (Mitteilung «Wirtschaftstermine»); null = keiner. */
    jump: MarketJump? = null,
    /** [jump] ist entschieden: die Navigation löscht ihn (kein zweiter Sprung). */
    onJumpHandled: () -> Unit = {},
    /** Sprung zum Wirtschaftsdaten-Hinweis auf der Unterseite «Daten» (die Seite scrollt einmal dorthin). */
    onOpenMacroData: () -> Unit = { onOpenSection(MarketSection.DATA) },
) {
    val fearGreed by viewModel.fearGreed.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    val dataAsOf by viewModel.dataAsOf.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val pullRefreshing by viewModel.pullRefreshing.collectAsStateWithLifecycle()
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()
    val unusual by viewModel.unusual.collectAsStateWithLifecycle()
    val macroEvents by viewModel.macroEvents.collectAsStateWithLifecycle()
    val watches by viewModel.watches.collectAsStateWithLifecycle()
    val stamps by viewModel.stamps.collectAsStateWithLifecycle()
    val moreExpanded by viewModel.moreExpanded.collectAsStateWithLifecycle()
    val marketTotals by viewModel.marketTotals.collectAsStateWithLifecycle()
    // «Kurz gesagt» für die Heldenkarte (null = noch nicht bereit oder kein gültiger Satz)
    val plain = rememberPlainSummary(pulse, marketTotals, fearGreed, unusual, stamps)
    val scope = rememberCoroutineScope()
    // «… entfernt» mit «Rückgängig» nach «Löschen» auf der Paarseite (wie in der Merkliste)
    val banner = rememberWatchlistBanner(scope)
    MarketCoinUiEffects(watchlistViewModel, banner)
    // Paar nicht übernommen bzw. nicht geändert, weil die Merkliste gesperrt ist: kurzer Hinweis mit Warn-Haptik
    com.cryptochecker.app.ui.components.WatchlistLockedHint { banner.show(it, SnackbarKind.ERROR) }
    val instant = rememberInstantReveal(viewModel, reveal)
    val motion = !rememberReduceMotion()

    // Tipp auf einen Coin aus «Heute auffällig» bzw. «Gewinner und Verlierer 24 h»: dasselbe wie ein Tipp in der
    // Merkliste — die Seite des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert); führt
    // Binance das Paar nicht, die Suche auf der Seite «Paar hinzufügen»
    fun openCoin(symbol: String) {
        val watch = watchFor(watches, symbol)
        if (watch != null) {
            onOpenPair(watch.id, null)
        } else {
            scope.launch {
                val preview = viewModel.previewWatch(symbol)
                if (preview != null) onOpenPair(0L, preview.baseAsset) else onOpenExplorer(symbol)
            }
        }
    }

    val scrollState = rememberScrollState()
    // Erneutes Tippen auf den Tab «Markt»: an den Anfang (wie iOS)
    OnTabReselect(ScreenRoute.MarketPhase) {
        if (motion) scrollState.animateScrollTo(0) else scrollState.scrollTo(0)
    }
    // Sprung von aussen (Mitteilung «Wirtschaftstermine»): Hinweis auf «Jetzt» (Termin in ±2 h; er steht
    // unter «Mehr Marktdaten», das dafür aufgeht) oder auf der Unterseite «Daten» — entschieden, sobald
    // die Daten stehen. «Mehr Marktdaten» geht dafür nur für diesen Besuch auf ([moreForJump]); die
    // gemerkte Einstellung bleibt, wie sie ist (wie iOS).
    var moreForJump by remember { mutableStateOf(false) }
    val showMore = moreExpanded || moreForJump
    var macroFocus by remember { mutableStateOf(false) }
    val macroAnchor = remember { MacroAnchor() }
    var macroHintHeight by remember { mutableIntStateOf(0) }
    // Der Sprung bleibt offen, bis der Hinweis wirklich steht (aufgeklappt, alles darüber erschienen,
    // gezeichnet); dann den Hinweis an den oberen Rand scrollen (wie iOS, anchor .top) und erst dann löschen
    val macroReady = reveal.macroHintReady(showMore) && macroHintHeight > 0
    MarketMacroJumpEffects(
        jump = jump,
        macroEvents = macroEvents,
        reveal = reveal,
        macroFocus = macroFocus,
        macroReady = macroReady,
        macroAnchor = macroAnchor,
        scrollState = scrollState,
        motion = motion,
        onJumpHandled = onJumpHandled,
        onOpenMacroData = onOpenMacroData,
        onFocusNow = {
            moreForJump = true
            macroFocus = true
        },
        onFocusDone = { macroFocus = false },
    )

    // Tab geöffnet oder App wieder im Vordergrund: nur Abgelaufenes neu laden
    LifecycleResumeEffect(viewModel) {
        viewModel.onOpen()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(banner.host) },
        topBar = { MarketTopBar(viewModel, motion, pullRefreshing, onOpenAbout) }
    ) { padding ->
        MarketNowContent(
            padding = padding,
            pullRefreshing = pullRefreshing,
            scrollState = scrollState,
            viewModel = viewModel,
            dataAsOf = dataAsOf,
            pulse = pulse,
            online = online,
            motion = motion,
            reveal = reveal,
            instant = instant,
            plain = plain,
            fearGreed = fearGreed,
            stamps = stamps,
            unusual = unusual,
            macroEvents = macroEvents,
            macroAnchor = macroAnchor,
            showMore = showMore,
            onToggleMore = {
                // Zuklappen beendet auch das Aufklappen aus der Mitteilung
                moreForJump = false
                viewModel.setMoreExpanded(!showMore)
            },
            onMacroHintHeight = { macroHintHeight = it },
            onOpenCoin = { openCoin(it) },
            onOpenSection = onOpenSection,
        )
    }
}

/** Lage von Sichtbereich und Wirtschaftsdaten-Hinweis für den Sprung (kein Zustand: löst nichts neu aus). */
internal class MacroAnchor {
    var viewport: LayoutCoordinates? = null
    var hint: LayoutCoordinates? = null
}

/**
 * Paar der Merkliste zu einem Coin: Spot zuerst (wie die Karte, USDT/USD vor anderen
 * Quotes), sonst das Perpetual; null = nicht beobachtet. Das Aktionsblatt zeigt dann genau
 * dieses Paar (Futures-Paar: Futures-Kerzen).
 */
private fun watchFor(watches: List<WatchEntity>, symbol: String): WatchEntity? =
    CandleSeries.pickWatch(
        // Nicht mehr gehandelte Paare zählen nicht als beobachtet (kein «Was bewegt sich mit?» auf alten Daten)
        items = watches.filterNot { it.isNotTraded },
        symbol = symbol,
        base = { it.baseAsset },
        quote = { it.quoteAsset },
        isSpot = { it.contractType == FuturesContractType.NONE },
        isPerpetual = {
            it.contractType == FuturesContractType.PERPETUAL || it.contractType == FuturesContractType.INVERSE_PERPETUAL
        },
    )

/** ⋯ in der Leiste von [MarketPhaseScreen]: App (→ «Über die App») und Aktualisieren. */
@Composable
private fun MarketMenuAction(refreshing: Boolean, onOpenAbout: () -> Unit, onRefresh: () -> Unit) {
    // ⋯ wie in Merkliste und Portfolio: App (→ «Über die App»), Aktualisieren
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AppMenuHead(
                refreshing = refreshing,
                onClose = { menuOpen = false },
                onOpenAbout = onOpenAbout,
                onRefresh = onRefresh
            )
        }
    }
}

/**
 * «Mehr Marktdaten» auf «Jetzt» ([MarketPhaseScreen]): Schalter, «Heute auffällig», der Wirtschaftsdaten-Hinweis
 * (Ziel des Sprungs) und die zwei Unterseiten.
 */
@Composable
internal fun MarketMoreSection(
    reveal: MarketReveal.State,
    instant: Set<MarketRevealSlot>,
    motion: Boolean,
    showMore: Boolean,
    onToggleMore: () -> Unit,
    unusual: LoadState<UnusualReport>,
    fearGreed: LoadState<FearGreed>,
    macroEvents: List<MacroEvent>,
    macroAnchor: MacroAnchor,
    onMacroHintHeight: (Int) -> Unit,
    onOpenCoin: (String) -> Unit,
    onRetryUnusual: () -> Unit,
    onOpenSection: (MarketSection) -> Unit,
) {
    if (reveal.isShownOnNow(MarketRevealSlot.FEAR_GREED)) {
        MarketMoreToggle(expanded = showMore, onToggle = onToggleMore)
        if (showMore) {
            // «Heute auffällig»: Tippen öffnet dasselbe wie ein Tipp in der Merkliste — das
            // Aktionsblatt des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert);
            // führt Binance das Paar nicht, die Suche auf der Seite «Paar hinzufügen»
            Reveal(MarketRevealSlot.UNUSUAL, reveal, instant, motion, onNow = true) {
                UnusualCard(
                    state = unusual,
                    onOpen = { row -> onOpenCoin(row.symbol) },
                    onRetry = onRetryUnusual
                )
            }
            // Ziel des Sprungs aus der Mitteilung «Wirtschaftstermine» (Termin in ±2 h)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { macroAnchor.hint = it }
                    .onSizeChanged { onMacroHintHeight(it.height) }
            ) {
                MacroHintRow(macroEvents, atTop = true)
            }
            // Zugeklappt ist der Hinweis weg: Höhe zurücksetzen (onSizeChanged meldet das nicht)
            DisposableEffect(Unit) { onDispose { onMacroHintHeight(0) } }
            MarketSectionRow(
                section = MarketSection.CONTEXT,
                summary = fearGreedSummary(fearGreed),
                onClick = { onOpenSection(MarketSection.CONTEXT) }
            )
            MarketSectionRow(
                section = MarketSection.DATA,
                summary = macroSummary(macroEvents),
                onClick = { onOpenSection(MarketSection.DATA) }
            )
        }
    }
}

/**
 * Sprung von aussen zum Wirtschaftsdaten-Hinweis ([MarketPhaseScreen]): entscheiden, sobald die Daten stehen, und den
 * Hinweis an den oberen Rand scrollen, sobald er steht.
 */
@Composable
private fun MarketMacroJumpEffects(
    jump: MarketJump?,
    macroEvents: List<MacroEvent>,
    reveal: MarketReveal.State,
    macroFocus: Boolean,
    macroReady: Boolean,
    macroAnchor: MacroAnchor,
    scrollState: ScrollState,
    motion: Boolean,
    onJumpHandled: () -> Unit,
    onOpenMacroData: () -> Unit,
    onFocusNow: () -> Unit,
    onFocusDone: () -> Unit,
) {
    LaunchedEffect(jump, macroEvents, reveal.shown) {
        if (jump != MarketJump.MACRO || !reveal.isShown(MarketRevealSlot.COIN)) return@LaunchedEffect
        onJumpHandled()
        if (macroRegister(macroEvents) == MarketSection.NOW) {
            onFocusNow()
        } else {
            onOpenMacroData()
        }
    }
    LaunchedEffect(macroFocus, macroReady) {
        if (!macroFocus || !macroReady) return@LaunchedEffect
        withFrameNanos { }
        val hint = macroAnchor.hint?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val viewport = macroAnchor.viewport?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val target = scrollState.value + viewport.localPositionOf(hint, Offset.Zero).y.roundToInt()
        if (motion) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
        onFocusDone()
    }
}

/** Karten, die ohne Animation erscheinen; liefert einen Wert, liest also wie zuvor im Aufrufer. */
@Composable
private fun rememberInstantReveal(viewModel: InfoViewModel, reveal: MarketReveal.State): Set<MarketRevealSlot> {
    // Was beim Betreten des Tabs schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel)
    // Auf «Jetzt» zählt nur, was dort schon stand (Fear & Greed erst unter den Karten darüber)
    val revealedOnEntry = remember {
        viewModel.reveal.value.let { r -> r.shown.filterTo(mutableSetOf()) { r.isShownOnNow(it) } }
    }
    return reveal.instant + revealedOnEntry
}

/** Titel «Markt» mit Menü; der Ladebalken ganz oben wie in der Merkliste ([LoadTopBar]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketTopBar(viewModel: InfoViewModel, motion: Boolean, pullRefreshing: Boolean, onOpenAbout: () -> Unit) {
    val loadProgress by viewModel.loadProgress.collectAsStateWithLifecycle()
    LoadTopBar(loadProgress, !motion) {
        TopAppBar(
            title = { TabTitle(stringResource(R.string.tab_market_phase), R.drawable.ic_tab_market) },
            actions = {
                MarketMenuAction(
                    refreshing = pullRefreshing,
                    onOpenAbout = onOpenAbout,
                    onRefresh = viewModel::refreshAll,
                )
            }
        )
    }
}
