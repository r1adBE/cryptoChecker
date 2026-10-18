package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.components.DataStatusPill
import com.cryptochecker.app.ui.components.DataStatusTone
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableLongStateOf
import com.cryptochecker.app.ui.components.AppMenuHead
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Tab «Markt»: Jetzt (Puls, Auffälliges), Einordnung (Fear & Greed, Bitcoin-Kennzahlen, Dominanz, Halving) und Daten.
 * Zeigt sofort den Zwischenspeicher, lädt Abgelaufenes still nach (siehe [InfoViewModel]);
 * nach unten ziehen lädt alles neu. [viewModel] gilt für die ganze Activity (AppNavHost).
 *
 * Die Karten erscheinen beim ersten Öffnen ruhig von oben nach unten ([MarketReveal]):
 * noch nicht gezeigte Karten sind gar nicht da (darunter kann nichts verschoben werden),
 * nur eine kleine Ladezeile unter der letzten. Einmal gezeigt, bleibt alles stehen.
 *
 * Ganz oben «Kurz gesagt» ([MarketPlainSummaryCard], drei Sätze in Alltagssprache), dann
 * «Jetzt» (Puls, «Heute auffällig») und Fear & Greed, darunter «Mehr Marktdaten»
 * ([MarketMoreToggle], Standard zu, gemerkt) mit zwei Zeilen ([MarketSectionRow]) zu den
 * Unterseiten «Stimmung & Zyklus» und «Marktdaten» ([MarketSectionScreen], die Karten
 * unverändert). Deren Daten laden schon beim Öffnen des Tabs — die Unterseite steht sofort.
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
    // Aktionsblatt (bzw. Vorschau) und «Was bewegt sich mit?» für einen Coin aus «Heute auffällig»
    val coinSheets = rememberMarketCoinState()
    val scope = rememberCoroutineScope()
    // «… entfernt» mit «Rückgängig» nach «Löschen» im Aktionsblatt (wie in der Merkliste)
    val banner = rememberWatchlistBanner(scope)
    // Was beim Betreten des Tabs schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel)
    // Auf «Jetzt» zählt nur, was dort schon stand (Fear & Greed erst unter den Karten darüber)
    val revealedOnEntry = remember {
        viewModel.reveal.value.let { r -> r.shown.filterTo(mutableSetOf()) { r.isShownOnNow(it) } }
    }
    val instant = reveal.instant + revealedOnEntry
    val motion = !rememberReduceMotion()

    // Unter «Jetzt» zwei Zeilen zu den Unterseiten «Einordnung» und «Daten» (G-10)
    val scrollState = rememberScrollState()
    // Erneutes Tippen auf den Tab «Markt»: an den Anfang (wie iOS)
    OnTabReselect(ScreenRoute.MarketPhase) {
        if (motion) scrollState.animateScrollTo(0) else scrollState.scrollTo(0)
    }
    // Sprung von aussen (Mitteilung «Wirtschaftstermine»): Hinweis oben («Jetzt», Termin in ±2 h)
    // oder auf der Unterseite «Daten» — entschieden, sobald die Daten stehen
    LaunchedEffect(jump, macroEvents, reveal.shown) {
        if (jump != MarketJump.MACRO || !reveal.isShown(MarketRevealSlot.COIN)) return@LaunchedEffect
        onJumpHandled()
        if (macroRegister(macroEvents) == MarketSection.NOW) {
            scrollState.scrollTo(0)
        } else {
            onOpenMacroData()
        }
    }


    // Tab geöffnet oder App wieder im Vordergrund: nur Abgelaufenes neu laden
    LifecycleResumeEffect(viewModel) {
        viewModel.onOpen()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(banner.host) },
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
                // «Jetzt»: «Was passiert gerade?», «Heute auffällig» — als Karten. Beim Laden
                // form-gleiche Platzhalter, bei Fehler eine kompakte Zeile.
                Reveal(MarketRevealSlot.PULSE, reveal, instant, motion, onNow = true) {
                    // «Kurz gesagt»: drei Sätze in Alltagssprache aus den Daten des Tabs, sobald
                    // Puls, «Heute auffällig» und Fear & Greed stehen
                    MarketPlainSummaryCard(pulse, marketTotals, fearGreed, unusual, stamps)
                    // Wirtschaftsdaten nur bei einem Termin in ±2 h hier oben, sonst unter «Daten»
                    MacroHintRow(macroEvents, atTop = true)
                    CryptoPulseCard(pulse, onRetry = { viewModel.loadPulse(force = true) })
                }
                // «Heute auffällig»: Tippen öffnet dasselbe wie ein Tipp in der Merkliste — das
                // Aktionsblatt des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert);
                // führt Binance das Paar nicht, die Suche auf der Seite «Paar hinzufügen»
                Reveal(MarketRevealSlot.UNUSUAL, reveal, instant, motion, onNow = true) {
                    UnusualCard(
                        state = unusual,
                        onOpen = { row ->
                            val watch = watchFor(watches, row.symbol)
                            if (watch != null) {
                                coinSheets.openStored(watch.id)
                            } else {
                                scope.launch {
                                    val preview = viewModel.previewWatch(row.symbol)
                                    if (preview != null) coinSheets.openPreview(preview) else onOpenExplorer(row.symbol)
                                }
                            }
                        },
                        onRetry = { viewModel.loadUnusual(force = true) }
                    )
                }
                // Fear & Greed direkt hier (dieselbe Karte wie auf «Stimmung & Zyklus», gleiche Quelle)
                Reveal(MarketRevealSlot.FEAR_GREED, reveal, instant, motion, onNow = true) {
                    FearGreedRow(
                        fearGreed,
                        onRetry = viewModel::refreshAll,
                        first = false,
                        stamp = stamps[CycleSource.FEAR_GREED]
                    )
                }
                // Darunter «Mehr Marktdaten» (Standard zu, gemerkt) mit den zwei Unterseiten;
                // ihre Daten laden schon hier (Öffnen sofort)
                if (reveal.isShownOnNow(MarketRevealSlot.FEAR_GREED)) {
                    MarketMoreToggle(expanded = moreExpanded, onToggle = { viewModel.setMoreExpanded(!moreExpanded) })
                    if (moreExpanded) {
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
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis «Jetzt» und
                // Fear & Greed stehen
                if (MarketSections.loading(MarketSection.NOW, reveal.shown) ||
                    !reveal.isShownOnNow(MarketRevealSlot.FEAR_GREED)
                ) RevealLoadingRow()
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

/**
 * Ein Teil des Tabs ([slot]): erst da, wenn [MarketReveal] ihn freigibt (auf «Jetzt», [onNow],
 * erst unter den Teilen darüber) — vorher weder gezeichnet noch im Bedienungshilfen-Baum.
 * Erscheint mit Einblenden und 8 dp von unten (220 ms), ausser er stand schon ([instant]) oder
 * Bewegung ist reduziert.
 * Die Animation wirkt nur auf die Ebene (graphicsLayer), nicht auf das Layout.
 */
@Composable
internal fun Reveal(
    slot: MarketRevealSlot,
    reveal: MarketReveal.State,
    instant: Set<MarketRevealSlot>,
    motion: Boolean,
    onNow: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (if (onNow) reveal.isShownOnNow(slot) else reveal.isShown(slot)) {
        RevealItem(animate = motion && slot !in instant, content = content)
    }
}

@Composable
private fun RevealItem(animate: Boolean, content: @Composable () -> Unit) {
    // Nur beim ersten Erscheinen massgebend; danach bleibt die Karte einfach stehen
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(MarketReveal.ENTER_MILLIS, easing = LinearOutSlowInEasing))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Wert erst beim Zeichnen gelesen: kein Neuaufbau je Bild
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            }
    ) {
        content()
    }
}

/** Kleine Ladezeile fester Höhe unter der letzten sichtbaren Karte. */
@Composable
internal fun RevealLoadingRow() {
    val description = stringResource(R.string.loading_hint)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(40.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

/**
 * Zustand oben im Markt-Tab wie in der Merkliste (statt einer Überschrift «Jetzt»): Punkt und Satz
 * in einer Pille — grün «Alles aktuell · vor 2 Min.» (Stand von «Was gerade auffällt»), während
 * still neu geladen wird neutral «Stand 14:05 · wird aktualisiert…» (ältester gezeigter Stand).
 * Ohne Netz (wie die Merkliste, [com.cryptochecker.app.util.ConnectivityMonitor]) neutral «Offline ·
 * Stand 14:05» — die Karten zeigen weiter den letzten Stand.
 * Immer gleich hoch, auch ohne Stand — darunter springt nichts. Wie iOS `statusPill`.
 */
@Composable
private fun MarketStatusPill(refreshingSince: Long?, updatedAt: Long?, online: Boolean, animate: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val refreshing = refreshingSince != null && online
    val asOf = updatedAt?.takeIf { it > 0 } ?: refreshingSince
    val text = when {
        !online && asOf != null -> stringResource(R.string.offline_status_since, dataTime(asOf))
        !online -> stringResource(R.string.offline_status)
        refreshingSince != null -> stringResource(R.string.cycle_data_as_of, dataTime(refreshingSince))
        updatedAt != null && updatedAt > 0 ->
            stringResource(R.string.watchlist_all_fresh, com.cryptochecker.app.ui.features.watchlist.ago(updatedAt, now))
        else -> null
    }
    val tone = if (!online || refreshing || text == null) DataStatusTone.NEUTRAL else DataStatusTone.FRESH
    val visibility by animateFloatAsState(
        targetValue = if (text != null) 1f else 0f,
        animationSpec = tween(if (animate) 200 else 0),
        label = "market_status"
    )
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(bottom = Spacing.xs)
            .graphicsLayer { alpha = visibility }
    ) {
        if (text != null) {
            // Gleiche Pille wie im Portfolio-Tab (Aussehen wie die Status-Pille der Merkliste)
            DataStatusPill(
                text = text,
                tone = tone,
                reduceMotion = !animate,
                modifier = if (refreshing || !online) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
            )
        }
    }
}

/** Uhrzeit, bei älteren Daten (nicht von heute) mit Datum. */
@Composable
private fun dataTime(millis: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, millis, flags)
}
