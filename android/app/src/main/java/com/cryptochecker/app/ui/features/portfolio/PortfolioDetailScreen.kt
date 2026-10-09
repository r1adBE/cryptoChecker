@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers

/**
 * Ein Coin: Kennzahlen und seine Transaktionen. Tipp = bearbeiten; nach links wischen = löschen,
 * mit «Rückgängig» im Banner (wie in der Merkliste).
 */
@Composable
fun PortfolioDetailScreen(
    coin: String,
    onBack: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val symbol = remember(coin) { PortfolioCalculator.normalizeCoin(coin) }
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    val all = transactions
    val own = remember(all, symbol) {
        all.orEmpty().filter { PortfolioCalculator.normalizeCoin(it.coin) == symbol }
    }
    val position = remember(all, prices, symbol) {
        PortfolioCalculator.position(symbol, all.orEmpty().map { it.toTrade() }, prices.prices[symbol])
    }

    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()
    val removedText = stringResource(R.string.portfolio_tx_removed)

    // Letzte Transaktion gelöscht: zurück zur Übersicht — erst wenn «Rückgängig» vorbei ist
    val bannerShown = banner.host.currentSnackbarData != null
    LaunchedEffect(all != null && own.isEmpty(), bannerShown) {
        if (all != null && own.isEmpty() && !bannerShown) onBack()
    }

    var sheet by remember { mutableStateOf<TxDraft?>(null) }

    fun deleteTx(tx: PortfolioTxEntity) {
        banner.show(removedText) { viewModel.restore(listOf(tx)) }
        viewModel.delete(tx.id)
    }

    sheet?.let { draft ->
        PortfolioTxSheet(initial = draft, viewModel = viewModel, onDismiss = { sheet = null })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(symbol, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(banner.host) },
        floatingActionButton = {
            // «+» wählt den Coin schon vor
            FloatingActionButton(onClick = { sheet = TxDraft(coin = symbol) }) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.portfolio_add_tx)
                )
            }
        }
    ) { padding ->
        if (all == null) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
            ReadableInset { inset ->
                CompositionLocalProvider(LocalHidePortfolioAmounts provides hideAmounts) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "summary") { PositionCard(position) }
                        item(key = "tx_header") {
                            Text(
                                stringResource(R.string.portfolio_transactions),
                                style = SectionTitle.style,
                                color = SectionTitle.color,
                                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp).sectionTitleMarker()
                            )
                        }
                        items(own, key = { it.id }) { tx ->
                            SwipeActionsRow(
                                enabled = true,
                                favorite = false,
                                onDelete = { deleteTx(tx) },
                                onToggleFavorite = null,
                                reduceMotion = reduceMotion,
                                modifier = Modifier.animateItem()
                            ) {
                                TxRow(
                                    tx = tx,
                                    onClick = { sheet = tx.toDraft() },
                                    onDelete = { deleteTx(tx) }
                                )
                            }
                        }
                        item(key = "disclaimer") { PortfolioDisclaimer() }
                    }
                }
            }
        }
    }
}

/** Kennzahlen eines Coins in zwei Spalten. */
@Composable
private fun PositionCard(p: CoinPosition) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(p.coin, size = 44.dp, portfolio = true, modifier = Modifier.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.portfolio_value),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        p.value?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Metric(
                    label = stringResource(R.string.portfolio_holdings),
                    value = maskAmount(PortfolioFormat.amount(p.holdings, p.coin)),
                    modifier = Modifier.weight(1f)
                )
                Metric(
                    label = stringResource(R.string.portfolio_avg_price),
                    value = PortfolioFormat.price(p.avgCost),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Metric(
                    label = stringResource(R.string.portfolio_current_price),
                    value = PortfolioFormat.price(p.currentPrice),
                    modifier = Modifier.weight(1f)
                )
                Metric(
                    label = stringResource(R.string.portfolio_invested),
                    value = p.costBasis?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Metric(
                        label = stringResource(R.string.portfolio_unrealized),
                        value = p.unrealized?.let { maskAmount(PortfolioFormat.signedUsdt(it)) } ?: "—",
                        valueColor = plColor(p.unrealized)
                    )
                    if (p.unrealized != null) {
                        PlPill(p.unrealizedPercent, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (!PortfolioFormat.isZero(p.realized)) {
                    Metric(
                        label = stringResource(R.string.portfolio_realized),
                        value = maskAmount(PortfolioFormat.signedUsdt(p.realized)),
                        valueColor = plColor(p.realized),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Wie «Ø Kaufpreis» gerechnet wird (Durchschnitt, nicht FIFO)
            PortfolioHint(stringResource(R.string.portfolio_avg_price_hint))
            if (p.priceMissing) {
                PortfolioHint(stringResource(R.string.portfolio_price_missing_hint))
            }
            if (p.oversold) {
                Text(
                    stringResource(R.string.portfolio_oversold),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** Transaktion: Art und Datum, Menge × Preis, Summe. */
@Composable
private fun TxRow(tx: PortfolioTxEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    // Screenreader: Löschen (sonst nur per Wischen) als eigene Aktion der Zeile
    val deleteLabel = stringResource(R.string.action_delete)
    val typeColor = if (tx.type == PortfolioTxType.BUY) PriceColors.up else PriceColors.down
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true }) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(tx.type.labelRes()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = typeColor
                    )
                    Text(
                        " · " + PortfolioFormat.date(tx.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    maskAmount(PortfolioFormat.amount(tx.amount, tx.coin)) + " × " +
                        (tx.priceUsdt?.let { PortfolioFormat.price(it) }
                            ?: stringResource(R.string.portfolio_price_missing)),
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                tx.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                tx.priceUsdt?.let { maskAmount(PortfolioFormat.usdt(it * tx.amount)) } ?: "—",
                style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
