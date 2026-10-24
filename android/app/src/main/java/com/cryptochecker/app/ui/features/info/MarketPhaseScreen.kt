package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.components.AppMenuHead
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.CandleSeries
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.app.ui.theme.SnackbarKind

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
     * Aktionen der Merkliste für das Aktionsblatt aus «Heute auffällig» — dieselbe Instanz wie die
     * Merkliste (AppNavHost), damit Tippen dort und hier genau dasselbe tut.
     */
    watchlistViewModel: WatchlistViewModel = hiltViewModel(),
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
    /**
     * «Heute auffällig» → Coin nicht in der Merkliste und von Binance (COIN/USDT) nicht geführt:
     * Hinzufügen-Tab mit dieser Suche (sonst die Vorschau im Aktionsblatt).
     */
    onOpenExplorer: (String) -> Unit = {},
    /** Aktionsblatt › Alarm: Seite «Alarme» des Paars (zurück öffnet das Blatt wieder). */
    onOpenAlarms: (Long) -> Unit = {},
    /** ⋯ › App-Logo und Name: Einstellungen › «Über». */
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    val dataAsOf by viewModel.dataAsOf.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val pullRefreshing by viewModel.pullRefreshing.collectAsStateWithLifecycle()
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()
    val unusual by viewModel.unusual.collectAsStateWithLifecycle()
    val macroEvents by viewModel.macroEvents.collectAsStateWithLifecycle()
    val watches by viewModel.watches.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val stamps by viewModel.stamps.collectAsStateWithLifecycle()
    val moreExpanded by viewModel.moreExpanded.collectAsStateWithLifecycle()
    val marketTotals by viewModel.marketTotals.collectAsStateWithLifecycle()
    // «Kurz gesagt» für die Heldenkarte (null = noch nicht bereit oder kein gültiger Satz)
    val plain = rememberPlainSummary(pulse, marketTotals, fearGreed, unusual, stamps)
    // Aktionsblatt (bzw. Vorschau) und «Was bewegt sich mit?» für einen Coin aus «Heute auffällig»
    val coinSheets = rememberMarketCoinState()
    val scope = rememberCoroutineScope()
    // «… entfernt» mit «Rückgängig» nach «Löschen» im Aktionsblatt (wie in der Merkliste)
    val banner = rememberWatchlistBanner(scope)
    // Paar nicht übernommen bzw. nicht geändert, weil die Merkliste gesperrt ist: kurzer Hinweis mit Warn-Haptik
    com.cryptochecker.app.ui.components.WatchlistLockedHint { banner.show(it, SnackbarKind.ERROR) }
    // Was beim Betreten des Tabs schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel)
    // Auf «Jetzt» zählt nur, was dort schon stand (Fear & Greed erst unter den Karten darüber)
    val revealedOnEntry = remember {
        viewModel.reveal.value.let { r -> r.shown.filterTo(mutableSetOf()) { r.isShownOnNow(it) } }
    }
    val instant = reveal.instant + revealedOnEntry
    val motion = !rememberReduceMotion()

    // Tipp auf einen Coin aus «Heute auffällig» bzw. «Gewinner und Verlierer 24 h»: dasselbe wie ein Tipp in der
    // Merkliste — das Aktionsblatt des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert); führt
    // Binance das Paar nicht, die Suche auf der Seite «Paar hinzufügen»
    fun openCoin(symbol: String) {
        val watch = watchFor(watches, symbol)
        if (watch != null) {
            coinSheets.openStored(watch.id)
        } else {
            scope.launch {
                val preview = viewModel.previewWatch(symbol)
                if (preview != null) coinSheets.openPreview(preview) else onOpenExplorer(symbol)
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
    LaunchedEffect(jump, macroEvents, reveal.shown) {
        if (jump != MarketJump.MACRO || !reveal.isShown(MarketRevealSlot.COIN)) return@LaunchedEffect
        onJumpHandled()
        if (macroRegister(macroEvents) == MarketSection.NOW) {
            moreForJump = true
            macroFocus = true
        } else {
            onOpenMacroData()
        }
    }
    // Der Sprung bleibt offen, bis der Hinweis wirklich steht (aufgeklappt, alles darüber erschienen,
    // gezeichnet); dann den Hinweis an den oberen Rand scrollen (wie iOS, anchor .top) und erst dann löschen
    val macroReady = reveal.macroHintReady(showMore) && macroHintHeight > 0
    LaunchedEffect(macroFocus, macroReady) {
        if (!macroFocus || !macroReady) return@LaunchedEffect
        withFrameNanos { }
        val hint = macroAnchor.hint?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val viewport = macroAnchor.viewport?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val target = scrollState.value + viewport.localPositionOf(hint, Offset.Zero).y.roundToInt()
        if (motion) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
        macroFocus = false
    }

    // Tab geöffnet oder App wieder im Vordergrund: nur Abgelaufenes neu laden
    LifecycleResumeEffect(viewModel) {
        viewModel.onOpen()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(banner.host) },
        topBar = {
            TopAppBar(
                title = { TabTitle(stringResource(R.string.tab_market_phase), R.drawable.ic_tab_market) },
                actions = {
                    // ⋯ wie in Merkliste und Portfolio: App (→ «Über»), Aktualisieren
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
                                refreshing = pullRefreshing,
                                onClose = { menuOpen = false },
                                onOpenAbout = onOpenAbout,
                                onRefresh = viewModel::refreshAll
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = viewModel::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Sichtbereich (vor dem Scrollen): Bezug für den Sprung zum Wirtschaftsdaten-Hinweis
                    .onGloballyPositioned { macroAnchor.viewport = it }
                    .verticalScroll(scrollState)
                    // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                    .readableWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Statt der Überschrift «Jetzt» der Zustand wie in der Merkliste: «Alles aktuell ·
                // vor 2 Min.» bzw. während des Neuladens «Stand 14:05 · wird aktualisiert…».
                // Der Platz bleibt immer reserviert — darunter springt nichts.
                MarketStatusPill(
                    refreshingSince = dataAsOf,
                    updatedAt = (pulse as? LoadState.Loaded)?.value?.time,
                    online = online,
                    animate = motion
                )
                // 1. Eine Heldenkarte: Crypto Pulse mit «Kurz gesagt» als Körper (einzige betonte Karte).
                // Beim Laden ein form-gleicher Platzhalter, bei Fehler die kompakte Zeile — der Rest
                // darunter erscheint trotzdem.
                Reveal(MarketRevealSlot.PULSE, reveal, instant, motion, onNow = true) {
                    CryptoPulseCard(pulse, plain = plain, onRetry = { viewModel.loadPulse(force = true) })
                }
                // 2. Darunter ruhig und kompakt: Fear & Greed (dieselbe Quelle wie auf «Stimmung & Zyklus») und
                // «Gewinner und Verlierer 24 h» (je drei, «Alle zeigen»; Tippen wie bei «Heute auffällig»)
                Reveal(MarketRevealSlot.FEAR_GREED, reveal, instant, motion, onNow = true) {
                    FearGreedRow(
                        fearGreed,
                        onRetry = viewModel::refreshAll,
                        first = false,
                        stamp = stamps[CycleSource.FEAR_GREED],
                        compact = true
                    )
                    MarketMoversCard(pulse, onOpen = { coin -> openCoin(coin.symbol) })
                }
                // 3. «Mehr Marktdaten» (Standard zu, gemerkt): «Heute auffällig», der Wirtschaftsdaten-Hinweis
                // (nur bei einem Termin in ±2 h, sonst unter «Daten») und die zwei Unterseiten — deren Daten
                // laden schon hier (Öffnen sofort)
                if (reveal.isShownOnNow(MarketRevealSlot.FEAR_GREED)) {
                    MarketMoreToggle(expanded = showMore, onToggle = {
                        // Zuklappen beendet auch das Aufklappen aus der Mitteilung
                        moreForJump = false
                        viewModel.setMoreExpanded(!showMore)
                    })
                    if (showMore) {
                        // «Heute auffällig»: Tippen öffnet dasselbe wie ein Tipp in der Merkliste — das
                        // Aktionsblatt des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert);
                        // führt Binance das Paar nicht, die Suche auf der Seite «Paar hinzufügen»
                        Reveal(MarketRevealSlot.UNUSUAL, reveal, instant, motion, onNow = true) {
                            UnusualCard(
                                state = unusual,
                                onOpen = { row -> openCoin(row.symbol) },
                                onRetry = { viewModel.loadUnusual(force = true) }
                            )
                        }
                        // Ziel des Sprungs aus der Mitteilung «Wirtschaftstermine» (Termin in ±2 h)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { macroAnchor.hint = it }
                                .onSizeChanged { macroHintHeight = it.height }
                        ) {
                            MacroHintRow(macroEvents, atTop = true)
                        }
                        // Zugeklappt ist der Hinweis weg: Höhe zurücksetzen (onSizeChanged meldet das nicht)
                        DisposableEffect(Unit) { onDispose { macroHintHeight = 0 } }
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
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alles Sichtbare auf
                // «Jetzt» steht (verborgenes «Heute auffällig» zählt nicht)
                if (reveal.nowLoading(showMore)) RevealLoadingRow()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    MarketCoinSheets(
        state = coinSheets,
        viewModel = viewModel,
        watchlist = watchlistViewModel,
        lockViewModel = lockViewModel,
        watches = watches,
        activity = activity,
        sensitivity = settings.activitySensitivity,
        banner = banner,
        onOpenAlarms = onOpenAlarms,
    )
}

/** Lage von Sichtbereich und Wirtschaftsdaten-Hinweis für den Sprung (kein Zustand: löst nichts neu aus). */
private class MacroAnchor {
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
