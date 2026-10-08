package com.cryptochecker.app.ui.features.info

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.CandleSeries
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.WhySheet
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Eigener Tab für die Marktphase: Zone, Skala, Scores und Indikatoren.
 * Zeigt sofort den Zwischenspeicher, lädt Abgelaufenes still nach (siehe [InfoViewModel]);
 * nach unten ziehen lädt alles neu. [viewModel] gilt für die ganze Activity (AppNavHost).
 *
 * Die Karten erscheinen beim ersten Öffnen ruhig von oben nach unten ([MarketReveal]):
 * noch nicht gezeigte Karten sind gar nicht da (darunter kann nichts verschoben werden),
 * nur eine kleine Ladezeile unter der letzten. Einmal gezeigt, bleibt alles stehen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketPhaseScreen(
    viewModel: InfoViewModel = hiltViewModel(),
    /** «Heute auffällig» → Coin nicht in der Merkliste: Hinzufügen-Tab mit dieser Suche. */
    onOpenExplorer: (String) -> Unit = {},
) {
    val market by viewModel.market.collectAsStateWithLifecycle()
    val fearGreed by viewModel.fearGreed.collectAsStateWithLifecycle()
    val dominance by viewModel.dominance.collectAsStateWithLifecycle()
    val marketTotals by viewModel.marketTotals.collectAsStateWithLifecycle()
    val altSeason by viewModel.altSeason.collectAsStateWithLifecycle()
    val altSeasonAsOf by viewModel.altSeasonAsOf.collectAsStateWithLifecycle()
    val altSeasonRefreshing by viewModel.altSeasonRefreshing.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val coin by viewModel.coin.collectAsStateWithLifecycle()
    val coins by viewModel.coins.collectAsStateWithLifecycle()
    val selectedCoin by viewModel.selectedCoin.collectAsStateWithLifecycle()
    val favoriteCoins by viewModel.favoriteCoins.collectAsStateWithLifecycle()
    val gas by viewModel.gas.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    val dataAsOf by viewModel.dataAsOf.collectAsStateWithLifecycle()
    val stamps by viewModel.stamps.collectAsStateWithLifecycle()
    val pullRefreshing by viewModel.pullRefreshing.collectAsStateWithLifecycle()
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()
    val unusual by viewModel.unusual.collectAsStateWithLifecycle()
    val macroEvents by viewModel.macroEvents.collectAsStateWithLifecycle()
    val watches by viewModel.watches.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    // «Warum?»-Blatt für einen beobachteten Coin aus «Heute auffällig»
    var whyFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Was beim Betreten des Tabs schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel)
    val revealedOnEntry = remember { viewModel.reveal.value.count }
    val instantThrough = maxOf(reveal.instant, revealedOnEntry)
    val motion = !rememberReduceMotion()

    // «Einordnung» und «Daten»: immer zugeklappt (mit Zusammenfassung), bis der Nutzer aufklappt;
    // in dieser App-Sitzung Gewähltes gilt weiter ([MarketSections])
    val sectionChoice by viewModel.sectionChoice.collectAsStateWithLifecycle()
    val contextExpanded = MarketSections.expanded(MarketSection.CONTEXT, sectionChoice[MarketSection.CONTEXT])
    val dataExpanded = MarketSections.expanded(MarketSection.DATA, sectionChoice[MarketSection.DATA])
    // Beim Aufklappen schon erschienene Zeilen klappen nur auf (kein zweites Einblenden von unten)
    var contextInstant by remember { mutableIntStateOf(0) }
    var dataInstant by remember { mutableIntStateOf(0) }
    val toggleContext = {
        if (!contextExpanded) contextInstant = viewModel.reveal.value.count
        viewModel.setSectionExpanded(MarketSection.CONTEXT, !contextExpanded)
    }
    val toggleData = {
        if (!dataExpanded) dataInstant = viewModel.reveal.value.count
        viewModel.setSectionExpanded(MarketSection.DATA, !dataExpanded)
    }


    // Tab geöffnet oder App wieder im Vordergrund: nur Abgelaufenes neu laden
    LifecycleResumeEffect(viewModel) {
        viewModel.onOpen()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_market_phase)) }) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = viewModel::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                    .readableWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Während im Hintergrund aktualisiert wird: von wann die gezeigten Daten sind.
                // Der Platz bleibt immer reserviert — darunter springt nichts.
                AsOfLine(dataAsOf, animate = motion)
                // 1. Jetzt: «Was passiert gerade?», «Heute auffällig» — als Karten. Beim Laden
                //    form-gleiche Platzhalter, bei Fehler eine kompakte Zeile.
                Reveal(MarketRevealSlot.PULSE, reveal, instantThrough, motion) {
                    SectionHeader(R.string.market_section_now)
                    // Wirtschaftsdaten nur bei einem Termin in ±2 h hier oben, sonst unter «Daten»
                    MacroHintRow(macroEvents, atTop = true)
                    CryptoPulseCard(pulse, onRetry = { viewModel.loadPulse(force = true) })
                }
                // «Heute auffällig»: Tippen öffnet «Warum?» (in der Merkliste) oder die Suche
                Reveal(MarketRevealSlot.UNUSUAL, reveal, instantThrough, motion) {
                    UnusualCard(
                        state = unusual,
                        isWatched = { symbol -> watchFor(watches, symbol) != null },
                        onOpen = { row ->
                            val watch = watchFor(watches, row.symbol)
                            if (watch != null) whyFor = watch.id else onOpenExplorer(row.symbol)
                        },
                        onRetry = { viewModel.loadUnusual(force = true) }
                    )
                }
                // 2. Einordnung — Zeilen ohne Karte: Fear & Greed, Marktphase, Dominanz,
                //    Altcoin-Saison, Zyklus/Halving. Tippen klappt die Details einer Zeile auf.
                //    Zugeklappt: Überschrift mit Zusammenfassung; die Zeilen werden dann gar nicht
                //    aufgebaut, ihre Daten laden aber weiter (bleiben frisch fürs Aufklappen).
                Reveal(MarketRevealSlot.HEADER_CONTEXT, reveal, instantThrough, motion) {
                    MarketSectionHeader(
                        title = stringResource(R.string.market_section_context),
                        summary = contextSummary(fearGreed, market),
                        expanded = contextExpanded,
                        onToggle = toggleContext,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                CollapsibleSection(contextExpanded, motion) {
                    val contextThrough = maxOf(instantThrough, contextInstant)
                    Reveal(MarketRevealSlot.FEAR_GREED, reveal, contextThrough, motion) {
                        FearGreedRow(
                            fearGreed,
                            onRetry = viewModel::refreshAll,
                            divider = false,
                            stamp = stamps[CycleSource.FEAR_GREED]
                        )
                    }
                    Reveal(MarketRevealSlot.PHASE, reveal, contextThrough, motion) {
                        MarketPhaseRow(
                            cycle = viewModel.cycle,
                            state = market,
                            onRetry = { viewModel.loadMarket(force = true) },
                            stamp = stamps[CycleSource.MARKET]
                        )
                    }
                    Reveal(MarketRevealSlot.DOMINANCE, reveal, contextThrough, motion) {
                        DominanceRows(
                            dominance,
                            altSeason,
                            onRetry = viewModel::refreshAll,
                            altSeasonAsOf = altSeasonAsOf,
                            altSeasonRefreshing = altSeasonRefreshing,
                            onRefreshAltSeason = viewModel::refreshAltSeason,
                            dominanceStamp = stamps[CycleSource.GLOBAL],
                            altSeasonStamp = stamps[CycleSource.ALT_SEASON]
                        )
                    }
                    Reveal(MarketRevealSlot.HALVING, reveal, contextThrough, motion) {
                        HalvingRow(viewModel.cycle, history, onRetry = viewModel::refreshAll)
                    }
                }
                // 3. Daten — Zeilen: Krypto-Markt (Marktkapitalisierung, Volumen), Gas,
                //    Wirtschaftsdaten, Coin
                Reveal(MarketRevealSlot.HEADER_DATA, reveal, instantThrough, motion) {
                    MarketSectionHeader(
                        title = stringResource(R.string.market_section_data),
                        summary = dataSummary(marketTotals),
                        expanded = dataExpanded,
                        onToggle = toggleData,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                CollapsibleSection(dataExpanded, motion) {
                    val dataThrough = maxOf(instantThrough, dataInstant)
                    Reveal(MarketRevealSlot.MARKET_TOTALS, reveal, dataThrough, motion) {
                        MarketTotalsRow(
                            marketTotals,
                            currency = settings.portfolioCurrency,
                            onRetry = viewModel::refreshAll,
                            divider = false,
                            stamp = stamps[CycleSource.GLOBAL]
                        )
                    }
                    Reveal(MarketRevealSlot.GAS, reveal, dataThrough, motion) {
                        GasSummaryRow(
                            state = gas,
                            ethAlertGwei = settings.gasAlertEthTenths / 10.0,
                            btcAlertSat = settings.gasAlertBtc,
                            onRetry = { viewModel.loadGas(force = true) },
                            stamp = stamps[CycleSource.GAS]
                        )
                    }
                    Reveal(MarketRevealSlot.COIN, reveal, dataThrough, motion) {
                        MacroHintRow(macroEvents, atTop = false)
                        CoinRow(
                            coins = coins,
                            selected = selectedCoin,
                            favorites = favoriteCoins,
                            onSelect = viewModel::selectCoin,
                            onToggleFavorite = viewModel::toggleFavoriteCoin,
                            state = coin,
                            onRetry = { viewModel.loadCoin(force = true) },
                            stamp = stamps[CycleSource.COIN]
                        )
                    }
                }
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alle stehen
                if (reveal.count < MarketReveal.COUNT) RevealLoadingRow()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    whyFor?.let { id -> watches.firstOrNull { it.id == id && !it.isNotTraded } }?.let { watch ->
        WhySheet(
            watch = watch,
            // Wie in der Merkliste nach der gewählten Empfindlichkeit
            signals = com.cryptochecker.app.domain.activity.ActivityAnalyzer.applySensitivity(
                activity[watch.id]?.active(System.currentTimeMillis()).orEmpty(),
                settings.activitySensitivity,
            ),
            load = viewModel::explain,
            onDismiss = { whyFor = null }
        )
    }
}

/**
 * Paar der Merkliste zu einem Coin: Spot zuerst (wie die Karte, USDT/USD vor anderen
 * Quotes), sonst das Perpetual; null = nicht beobachtet. Das Blatt rechnet dann mit
 * den Daten genau dieses Paars (Futures-Paar: Futures-Kerzen).
 */
private fun watchFor(watches: List<WatchEntity>, symbol: String): WatchEntity? =
    CandleSeries.pickWatch(
        // Nicht mehr gehandelte Paare zählen nicht als beobachtet (kein «Warum?» auf alten Daten)
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
 * Ein Teil des Tabs ([slot]): erst da, wenn [MarketReveal] ihn freigibt — vorher weder
 * gezeichnet noch im Bedienungshilfen-Baum. Erscheint mit Einblenden und 8 dp von unten
 * (220 ms), ausser er stand schon ([instantThrough]) oder Bewegung ist reduziert.
 * Die Animation wirkt nur auf die Ebene (graphicsLayer), nicht auf das Layout.
 */
@Composable
private fun Reveal(
    slot: MarketRevealSlot,
    reveal: MarketReveal.State,
    instantThrough: Int,
    motion: Boolean,
    content: @Composable () -> Unit,
) {
    if (reveal.count > slot.ordinal) {
        RevealItem(animate = motion && slot.ordinal >= instantThrough, content = content)
    }
}

/**
 * Inhalt eines zuklappbaren Abschnitts: offen aufgebaut, zu gar nicht (weder gezeichnet noch
 * im Bedienungshilfen-Baum). Auf-/Zuklappen innerhalb des Scrollinhalts so lang wie das
 * Überblenden einer Zeile; ohne Animation bei reduzierter Bewegung.
 */
@Composable
private fun CollapsibleSection(expanded: Boolean, motion: Boolean, content: @Composable () -> Unit) {
    // Ausdrücklich die Funktion ohne Empfänger (nicht ColumnScope.AnimatedVisibility)
    androidx.compose.animation.AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.fillMaxWidth(),
        enter = if (motion) {
            expandVertically(tween(MarketReveal.SWAP_MILLIS)) + fadeIn(tween(MarketReveal.SWAP_MILLIS))
        } else EnterTransition.None,
        exit = if (motion) {
            shrinkVertically(tween(MarketReveal.SWAP_MILLIS)) + fadeOut(tween(MarketReveal.SWAP_MILLIS))
        } else ExitTransition.None,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/** Zusammenfassung von «Einordnung» im zugeklappten Zustand: «Gier 72 · Neutral». */
@Composable
private fun contextSummary(fearGreed: LoadState<FearGreed>, market: MarketState): String {
    val fg = (fearGreed as? LoadState.Loaded)?.value
    val report = (market as? MarketState.Loaded)?.report
    return MarketSections.summary(
        listOf(
            fg?.let { "${fearGreedLabel(it.value)} ${LocaleNumbers.integer(it.value)}" },
            report?.let { stringResource(zoneLabel(it.zone)) },
        )
    )
}

/** Zusammenfassung von «Daten» im zugeklappten Zustand: «Marktkapitalisierung +1.20%». */
@Composable
private fun dataSummary(marketTotals: LoadState<MarketTotals>): String {
    val change = (marketTotals as? LoadState.Loaded)?.value?.changePercent24h
    return MarketSections.summary(
        listOf(
            change?.let {
                stringResource(R.string.market_cap_label) + " " + (PriceFormat.changePercent(it) ?: PriceFormat.zeroPercent())
            },
        )
    )
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
private fun RevealLoadingRow() {
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
 * «Stand … · wird aktualisiert …»: immer eine Zeile hoch, auch ohne Text — nur Inhalt und
 * Deckkraft wechseln (beim Ausblenden bleibt der letzte Stand stehen, bis er unsichtbar ist).
 */
@Composable
private fun AsOfLine(at: Long?, animate: Boolean) {
    val last = remember { arrayOfNulls<Long>(1) }
    if (at != null) last[0] = at
    val shownAt = at ?: last[0]
    val visibility by animateFloatAsState(
        targetValue = if (at != null) 1f else 0f,
        animationSpec = tween(if (animate) 200 else 0),
        label = "as_of"
    )
    Text(
        text = if (shownAt != null) stringResource(R.string.cycle_data_as_of, dataTime(shownAt)) else " ",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = Spacing.xs)
            .graphicsLayer { alpha = visibility }
            .then(
                if (at != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                else Modifier.clearAndSetSemantics { }
            )
    )
}

/**
 * Kleine Abschnittsüberschrift über «Jetzt» (nicht zuklappbar); für Screenreader eine
 * Überschrift. «Einordnung» und «Daten» haben [MarketSectionHeader].
 */
@Composable
private fun SectionHeader(@StringRes textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.4.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

/** Uhrzeit, bei älteren Daten (nicht von heute) mit Datum. */
@Composable
private fun dataTime(millis: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, millis, flags)
}
