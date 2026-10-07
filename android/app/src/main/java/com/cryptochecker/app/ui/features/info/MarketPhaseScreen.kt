package com.cryptochecker.app.ui.features.info

import android.text.format.DateUtils
import androidx.annotation.StringRes
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.WhySheet
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
    val history by viewModel.history.collectAsStateWithLifecycle()
    val coin by viewModel.coin.collectAsStateWithLifecycle()
    val coins by viewModel.coins.collectAsStateWithLifecycle()
    val selectedCoin by viewModel.selectedCoin.collectAsStateWithLifecycle()
    val favoriteCoins by viewModel.favoriteCoins.collectAsStateWithLifecycle()
    val gas by viewModel.gas.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    val dataAsOf by viewModel.dataAsOf.collectAsStateWithLifecycle()
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
                // 1. Jetzt: «Was passiert gerade?», Stimmung, Krypto-Markt (Marktkapitalisierung,
                //    Volumen). Beim Laden form-gleiche Platzhalter, bei Fehler eine kompakte Zeile.
                Reveal(MarketRevealSlot.PULSE, reveal, instantThrough, motion) {
                    SectionHeader(R.string.market_section_now)
                    // Wichtige US-Wirtschaftsdaten heute: kompakte Zeile über dem Pulse
                    MacroHintRow(macroEvents)
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
                Reveal(MarketRevealSlot.FEAR_GREED, reveal, instantThrough, motion) {
                    FearGreedCard(fearGreed, onRetry = viewModel::refreshAll)
                }
                Reveal(MarketRevealSlot.MARKET_TOTALS, reveal, instantThrough, motion) {
                    MarketTotalsCard(
                        marketTotals,
                        currency = settings.portfolioCurrency,
                        onRetry = viewModel::refreshAll
                    )
                }
                // 2. Einordnung: Marktphase, Dominanz (mit Altcoin-Saison), Zyklus/Halving
                Reveal(MarketRevealSlot.HEADER_CONTEXT, reveal, instantThrough, motion) {
                    SectionHeader(R.string.market_section_context)
                }
                Reveal(MarketRevealSlot.PHASE, reveal, instantThrough, motion) {
                    MarketPhaseCard(
                        cycle = viewModel.cycle,
                        state = market,
                        onRetry = { viewModel.loadMarket(force = true) }
                    )
                }
                Reveal(MarketRevealSlot.DOMINANCE, reveal, instantThrough, motion) {
                    DominanceCard(dominance, altSeason, onRetry = viewModel::refreshAll)
                }
                Reveal(MarketRevealSlot.HALVING, reveal, instantThrough, motion) {
                    HalvingCard(viewModel.cycle, history, onRetry = viewModel::refreshAll)
                }
                // 3. Daten: Coin, Gas
                Reveal(MarketRevealSlot.HEADER_DATA, reveal, instantThrough, motion) {
                    SectionHeader(R.string.market_section_data)
                }
                Reveal(MarketRevealSlot.COIN, reveal, instantThrough, motion) {
                    CoinCard(
                        coins = coins,
                        selected = selectedCoin,
                        favorites = favoriteCoins,
                        onSelect = viewModel::selectCoin,
                        onToggleFavorite = viewModel::toggleFavoriteCoin,
                        state = coin,
                        onRetry = { viewModel.loadCoin(force = true) }
                    )
                }
                Reveal(MarketRevealSlot.GAS, reveal, instantThrough, motion) {
                    GasCard(
                        state = gas,
                        ethAlertGwei = settings.gasAlertEthTenths / 10.0,
                        btcAlertSat = settings.gasAlertBtc,
                        onRetry = { viewModel.loadGas(force = true) }
                    )
                }
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alle stehen
                if (reveal.count < MarketReveal.COUNT) RevealLoadingRow()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    whyFor?.let { id -> watches.firstOrNull { it.id == id } }?.let { watch ->
        WhySheet(
            watch = watch,
            signals = activity[watch.id]?.active(System.currentTimeMillis()).orEmpty(),
            load = viewModel::explain,
            onDismiss = { whyFor = null }
        )
    }
}

/** Paar der Merkliste zu einem Coin (Spot zuerst); null = nicht beobachtet. */
private fun watchFor(watches: List<WatchEntity>, symbol: String): WatchEntity? =
    watches.filter { it.baseAsset.equals(symbol, ignoreCase = true) }
        .minByOrNull { if (it.contractType == FuturesContractType.NONE) 0 else 1 }

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
            .padding(start = 4.dp, bottom = 6.dp)
            .graphicsLayer { alpha = visibility }
            .then(
                if (at != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                else Modifier.clearAndSetSemantics { }
            )
    )
}

/** Kleine Abschnittsüberschrift über einer Kartengruppe; für Screenreader eine Überschrift. */
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
