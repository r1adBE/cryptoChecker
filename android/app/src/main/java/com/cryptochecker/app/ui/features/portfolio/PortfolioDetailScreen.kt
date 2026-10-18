@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.ui.components.AppFab
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner

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
    // Mit Adress-Guthaben (Einzahlung ohne Kaufpreis) — wie die Übersicht
    val calcTransactions by viewModel.calcTransactions.collectAsStateWithLifecycle()
    val watchedAddresses by viewModel.watchedAddresses.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val costMethod by viewModel.costMethod.collectAsStateWithLifecycle()
    val feePrice by viewModel.feePrice.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    val all = transactions
    // Auch Tausche in diesen Coin («Tausch von BTC»)
    val own = remember(all, symbol) {
        all.orEmpty().filter { tx -> tx.coins().any { PortfolioCalculator.normalizeCoin(it) == symbol } }
    }
    // Kennzahlen, offene Posten und (FIFO je Wallet) Abgänge über den Bestand ihrer Wallet
    // Gebühr-Zeilen zum Tageskurs wie auf der Karte ([PortfolioViewModel.feePrice])
    val ledger = remember(calcTransactions, prices, symbol, costMethod, feePrice) {
        PortfolioCalculator.ledger(symbol, calcTransactions.orEmpty().map { it.toTrade() }, prices.prices[symbol], costMethod, feePrice)
    }
    val position = ledger.position
    val addressAmount = remember(watchedAddresses, symbol) {
        AddressBalance.amountFor(symbol, watchedAddresses)?.takeIf { it.signum() > 0 }
    }
    // Eigene Einzahlung/Umbuchung = Guthaben/Zugang einer zählenden Adresse (± 1 %): deutliche Warnung wie bei der Adresse
    val addressMatches = remember(watchedAddresses, own, symbol) {
        WatchedAddressRules.transferMatchesFor(symbol, watchedAddresses, own.map { it.toTrade() })
    }

    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()
    val removedText = stringResource(R.string.portfolio_tx_removed)

    // Letzte Transaktion gelöscht: zurück zur Übersicht — erst wenn «Rückgängig» vorbei ist
    val bannerShown = banner.host.currentSnackbarData != null
    // Coin nur aus einer Adresse: bleibt offen
    val nothingLeft = all != null && own.isEmpty() && addressAmount == null
    LaunchedEffect(nothingLeft, bannerShown) {
        if (nothingLeft && !bannerShown) onBack()
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
            AppFab(onClick = { sheet = TxDraft(coin = symbol) }, contentDescription = stringResource(R.string.portfolio_add_tx))
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
                        item(key = "summary") { PositionCard(position, addressAmount, overlap = own.isNotEmpty(), matches = addressMatches) }
                        // FIFO je Wallet: Abgang über den Bestand seiner Wallet — antippen öffnet die Transaktion
                        if (ledger.walletShortfalls.isNotEmpty()) {
                            item(key = "shortfalls") {
                                WalletShortfallBanner(ledger.walletShortfalls) { id ->
                                    all.orEmpty().firstOrNull { it.id == id }?.let { sheet = it.toDraft() }
                                }
                            }
                        }
                        // Offene Kauf-Posten (zugeklappt)
                        if (ledger.openLots.isNotEmpty()) {
                            item(key = "open_lots") { OpenLotsSection(ledger.openLots, symbol) }
                        }
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
                                    coin = symbol,
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

/**
 * FIFO je Wallet: «Bestand in Binance reicht nicht – fehlt eine Umbuchung?» je Wallet (erster Abgang); antippen öffnet die
 * betroffene Transaktion ([onOpen] mit ihrer Id).
 */
@Composable
private fun WalletShortfallBanner(shortfalls: List<PortfolioWalletShortfall>, onOpen: (Long) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            shortfalls.distinctBy { it.wallet.trim().lowercase() }.forEach { s ->
                val wallet = s.wallet.ifBlank { stringResource(R.string.tax_export_wallet_unspecified) }
                Text(
                    stringResource(R.string.portfolio_wallet_shortfall, wallet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(s.txId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}
