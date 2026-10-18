package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.ExperimentalFoundationApi
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.notification.MacroTexts
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import java.time.ZoneId

/** Titel der Unterseite bzw. ihrer Zeile im Markt-Tab. */
@Composable
private fun sectionTitle(section: MarketSection): String = stringResource(
    when (section) {
        MarketSection.NOW -> R.string.market_section_now
        MarketSection.CONTEXT -> R.string.market_section_context
        MarketSection.DATA -> R.string.market_section_data
    }
)

/**
 * Zeile im Markt-Tab unter «Jetzt», die eine Unterseite öffnet: Titel (Überschrift wie die Karten),
 * darunter eine kurze Wertzeile, rechts der Pfeil. Ein Element für den Screenreader (Schaltfläche).
 * Wie `MarketSectionRow` (iOS).
 */
@Composable
internal fun MarketSectionRow(section: MarketSection, summary: String, onClick: () -> Unit) {
    val title = sectionTitle(section)
    AppCard(modifier = Modifier.padding(top = 12.dp), padding = CardPadding.None) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { heading() }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = SectionTitle.style,
                    color = SectionTitle.color,
                    modifier = Modifier.sectionTitleMarker()
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm).size(20.dp)
            )
        }
    }
}

/**
 * «Mehr Marktdaten» im Markt-Tab unter Fear & Greed: klappt die zwei Zeilen zu den Unterseiten auf
 * bzw. zu (ganze Zeile tippbar, mind. 48 dp, Pfeil in der Themenfarbe). Screenreader: Knopf mit
 * Zustand «aufgeklappt»/«zugeklappt» und der passenden Aktion. Wie `MarketMoreToggle` (iOS).
 */
@Composable
internal fun MarketMoreToggle(expanded: Boolean, onToggle: () -> Unit) {
    val title = stringResource(R.string.market_more_data)
    val state = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = state
                role = Role.Button
                heading()
                onClick { onToggle(); true }
                if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
            }
            .padding(horizontal = Spacing.lg, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        ExpandChevronIcon(expanded = expanded)
    }
}

/** Wertzeile «Einordnung»: «Fear & Greed 62 · Gier»; ohne Wert eine kurze Beschreibung. */
@Composable
internal fun fearGreedSummary(state: LoadState<FearGreed>): String {
    val fg = (state as? LoadState.Loaded)?.value ?: return stringResource(R.string.market_context_summary)
    return stringResource(
        R.string.market_context_value,
        stringResource(R.string.insights_fng_title),
        LocaleNumbers.integer(fg.value),
        fearGreedLabel(fg.value)
    )
}

/** Wertzeile «Daten»: nächster Wirtschaftstermin (heute/morgen), sonst eine kurze Beschreibung. */
@Composable
internal fun macroSummary(events: List<MacroEvent>): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val hint = remember(events, now / 60_000L) { MacroCalendar.hint(events, now, ZoneId.systemDefault()) }
    return hint?.let { MacroTexts.hint(context, it) } ?: stringResource(R.string.market_data_summary)
}

/**
 * Unterseite «Einordnung» bzw. «Daten» des Markt-Tabs (G-10): dieselben Karten wie zuvor im
 * Register, mit Zurück. [viewModel] ist dieselbe Instanz wie im Tab (Activity) — die Daten sind
 * meist schon geladen, die Seite steht sofort. Nach unten ziehen lädt alles neu.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MarketSectionScreen(
    section: MarketSection,
    viewModel: InfoViewModel,
    onBack: () -> Unit,
    /** Aus der Mitteilung «Wirtschaftstermine» geöffnet (Routen-Argument): einmal zum Hinweis scrollen. */
    macroJump: Boolean = false,
    /** Zum Hinweis gescrollt: die Navigation löscht [macroJump] (auch nach Prozess-Neustart kein zweites Mal). */
    onMacroJumpHandled: () -> Unit = {},
) {
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()
    val pullRefreshing by viewModel.pullRefreshing.collectAsStateWithLifecycle()
    val macroEvents by viewModel.macroEvents.collectAsStateWithLifecycle()
    // Was beim Öffnen schon stand, erscheint ohne Animation
    val revealedOnEntry = remember { viewModel.reveal.value.shown }
    val instant = reveal.instant + revealedOnEntry
    val motion = !rememberReduceMotion()
    val scrollState = rememberScrollState()
    val macroRequester = remember { BringIntoViewRequester() }

    // App wieder im Vordergrund: Abgelaufenes nachladen (wie im Tab)
    LifecycleResumeEffect(viewModel) {
        viewModel.onOpen()
        onPauseOrDispose { }
    }

    // Sprung aus der Mitteilung «Wirtschaftstermine»: zum Hinweis scrollen, sobald er steht
    val scrollToMacro = section == MarketSection.DATA && macroJump
    LaunchedEffect(scrollToMacro, reveal.shown, macroEvents) {
        if (!scrollToMacro || !reveal.isShown(MarketRevealSlot.COIN)) return@LaunchedEffect
        withFrameNanos { }
        macroRequester.bringIntoView()
        onMacroJumpHandled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sectionTitle(section), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
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
                    .readableWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when (section) {
                    MarketSection.CONTEXT -> ContextCards(viewModel, reveal, instant, motion)
                    MarketSection.DATA -> DataCards(viewModel, reveal, instant, motion, macroRequester)
                    MarketSection.NOW -> Unit
                }
                // Ruhige Ladezeile unter der letzten sichtbaren Karte, bis alle stehen
                if (MarketSections.loading(section, reveal.shown)) RevealLoadingRow()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Einordnung — je Eintrag eine Karte: Fear & Greed, Bitcoin-Kennzahlen, Dominanz, Altcoin-Saison,
 * Zyklus/Halving. Tippen klappt die Details der Karte auf.
 */
@Composable
private fun ContextCards(
    viewModel: InfoViewModel,
    reveal: com.cryptochecker.app.domain.market.MarketReveal.State,
    instant: Set<MarketRevealSlot>,
    motion: Boolean,
) {
    val fearGreed by viewModel.fearGreed.collectAsStateWithLifecycle()
    val market by viewModel.market.collectAsStateWithLifecycle()
    val dominance by viewModel.dominance.collectAsStateWithLifecycle()
    val altSeason by viewModel.altSeason.collectAsStateWithLifecycle()
    val altSeasonAsOf by viewModel.altSeasonAsOf.collectAsStateWithLifecycle()
    val altSeasonRefreshing by viewModel.altSeasonRefreshing.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val halvingEstimate by viewModel.halvingEstimate.collectAsStateWithLifecycle()
    val stamps by viewModel.stamps.collectAsStateWithLifecycle()
    Reveal(MarketRevealSlot.FEAR_GREED, reveal, instant, motion) {
        FearGreedRow(
            fearGreed,
            onRetry = viewModel::refreshAll,
            stamp = stamps[CycleSource.FEAR_GREED]
        )
    }
    Reveal(MarketRevealSlot.PHASE, reveal, instant, motion) {
        MarketPhaseRow(
            state = market,
            onRetry = { viewModel.loadMarket(force = true) },
            stamp = stamps[CycleSource.MARKET]
        )
    }
    Reveal(MarketRevealSlot.DOMINANCE, reveal, instant, motion) {
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
    Reveal(MarketRevealSlot.HALVING, reveal, instant, motion) {
        HalvingRow(viewModel.halving, history, onRetry = viewModel::refreshAll, estimate = halvingEstimate)
    }
}

/** Daten — Karten: Krypto-Markt (Marktkapitalisierung, Volumen), Gas, Wirtschaftsdaten, Coin. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DataCards(
    viewModel: InfoViewModel,
    reveal: com.cryptochecker.app.domain.market.MarketReveal.State,
    instant: Set<MarketRevealSlot>,
    motion: Boolean,
    macroRequester: BringIntoViewRequester,
) {
    val marketTotals by viewModel.marketTotals.collectAsStateWithLifecycle()
    val gas by viewModel.gas.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val macroEvents by viewModel.macroEvents.collectAsStateWithLifecycle()
    val coins by viewModel.coins.collectAsStateWithLifecycle()
    val selectedCoin by viewModel.selectedCoin.collectAsStateWithLifecycle()
    val favoriteCoins by viewModel.favoriteCoins.collectAsStateWithLifecycle()
    val coin by viewModel.coin.collectAsStateWithLifecycle()
    val stamps by viewModel.stamps.collectAsStateWithLifecycle()
    Reveal(MarketRevealSlot.MARKET_TOTALS, reveal, instant, motion) {
        MarketTotalsRow(
            marketTotals,
            currency = settings.portfolioCurrency,
            onRetry = viewModel::refreshAll,
            stamp = stamps[CycleSource.GLOBAL]
        )
    }
    Reveal(MarketRevealSlot.GAS, reveal, instant, motion) {
        GasSummaryRow(
            state = gas,
            ethAlertGwei = settings.gasAlertEthTenths / 10.0,
            btcAlertSat = settings.gasAlertBtc,
            onRetry = { viewModel.loadGas(force = true) },
            stamp = stamps[CycleSource.GAS]
        )
    }
    Reveal(MarketRevealSlot.COIN, reveal, instant, motion) {
        // Ziel des Sprungs aus der Mitteilung «Wirtschaftstermine»
        Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(macroRequester)) {
            MacroHintRow(macroEvents, atTop = false)
        }
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
