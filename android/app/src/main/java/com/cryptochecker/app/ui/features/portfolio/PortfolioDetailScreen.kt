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
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall
import com.cryptochecker.app.ui.components.AppFab
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.theme.SnackbarKind

/**
 * Ein Coin: Kennzahlen und seine Transaktionen. Tipp = bearbeiten; nach links wischen = löschen,
 * mit «Rückgängig» im Banner (wie in der Merkliste).
 */
@Composable
fun PortfolioDetailScreen(
    coin: String,
    onBack: () -> Unit,
    /** Die Instanz des Portfolio-Tabs (AppNavHost); eigene nur zur Not. */
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val symbol = remember(coin) { PortfolioCalculator.normalizeCoin(coin) }
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    // Ledger/FIFO, Adress-Guthaben und Treffer im ViewModel abseits des Hauptthreads (PortfolioCoinDetailFlow)
    val detail by remember(viewModel, symbol) { viewModel.coinDetailFlow(symbol) }.collectAsStateWithLifecycle(null)

    LaunchedEffect(Unit) { viewModel.start() }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    val all = detail?.all
    val own = detail?.own.orEmpty()
    val addressAmount = detail?.addressAmount

    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()
    val removedText = stringResource(R.string.portfolio_tx_removed)
    // Nicht geschrieben: gesperrt bzw. Fehler nennen (wie iOS) statt «entfernt»
    val resources = LocalResources.current

    // Letzte Transaktion gelöscht: zurück zur Übersicht — erst wenn «Rückgängig» vorbei ist. Auch nicht,
    // solange ein Löschen auf sein Ergebnis wartet, dessen Banner noch nicht steht oder «Rückgängig» noch schreibt
    val bannerData = banner.host.currentSnackbarData
    var deletesPending by remember { mutableIntStateOf(0) }
    var awaitingBanner by remember { mutableStateOf(false) }
    var restorePending by remember { mutableStateOf(false) }
    LaunchedEffect(bannerData) { if (bannerData != null) awaitingBanner = false }
    // «Rückgängig» geschrieben und wieder in der Liste: nicht mehr festhalten
    LaunchedEffect(own.isNotEmpty(), restorePending) { if (restorePending && own.isNotEmpty()) restorePending = false }
    // Coin nur aus einer Adresse: bleibt offen
    val nothingLeft = all != null && own.isEmpty() && addressAmount == null
    val holding = bannerData != null || deletesPending > 0 || awaitingBanner || restorePending
    LaunchedEffect(nothingLeft, holding) {
        if (nothingLeft && !holding) onBack()
    }

    var sheet by remember { mutableStateOf<TxDraft?>(null) }

    val haptics = rememberHaptics()
    fun undo(tx: PortfolioTxEntity) {
        restorePending = true
        viewModel.restore(listOf(tx)) { result ->
            if (result != PortfolioWriteResult.OK) {
                restorePending = false
                banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
            }
        }
    }
    fun deleteTx(tx: PortfolioTxEntity) {
        deletesPending++
        // Banner «entfernt» mit «Rückgängig» und Haptik erst nach dem Ergebnis (wie iOS)
        viewModel.delete(tx.id) { result ->
            deletesPending--
            if (result == PortfolioWriteResult.OK) {
                haptics.play(HapticEvent.DESTRUCTIVE)
                awaitingBanner = true
                banner.show(removedText) { undo(tx) }
            } else {
                banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
            }
        }
    }

    sheet?.let { draft ->
        PortfolioTxSheet(initial = draft, viewModel = viewModel, onDismiss = { sheet = null })
    }
    // «ohne Kaufpreis» → Einträge ohne Einstand dieses Coins; «Aus Adresse» → Einstand für Adressguthaben
    var costGapsOpen by remember { mutableStateOf(false) }
    var lotsFor by remember { mutableStateOf<Long?>(null) }
    if (costGapsOpen) {
        PortfolioCostGapsSheet(
            viewModel, all.orEmpty(), coin = symbol,
            onEdit = { tx -> costGapsOpen = false; sheet = tx.toDraft() },
            onDismiss = { costGapsOpen = false },
        )
    }
    lotsFor?.let { id -> PortfolioAddressLotsSheet(viewModel, id, onDismiss = { lotsFor = null }) }
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
        snackbarHost = { AppSnackbarHost(banner.host) },
        floatingActionButton = {
            // «+» wählt den Coin schon vor
            AppFab(onClick = { sheet = TxDraft(coin = symbol) }, contentDescription = stringResource(R.string.portfolio_add_tx))
        }
    ) { padding ->
        val d = detail
        if (d == null) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        val ledger = d.ledger
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
                        contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = Spacing.fabClearance),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "summary") {
                            PositionCard(
                                ledger.position, d.addressAmount, overlap = d.own.isNotEmpty(), matches = d.addressMatches,
                                onMissingCost = { costGapsOpen = true },
                                onAddressCost = d.lotAddress?.let { a -> { lotsFor = a.id } },
                            )
                        }
                        // FIFO je Wallet: Abgang über den Bestand seiner Wallet — antippen öffnet die Transaktion
                        if (ledger.walletShortfalls.isNotEmpty()) {
                            item(key = "shortfalls") {
                                WalletShortfallBanner(ledger.walletShortfalls) { id ->
                                    d.all.firstOrNull { it.id == id }?.let { sheet = it.toDraft() }
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
                        items(d.own, key = { it.id }) { tx ->
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
