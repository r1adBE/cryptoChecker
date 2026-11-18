// Gerüst und Inhalt des Portfolio-Tabs ([PortfolioScreen]). Eigene Datei, weil PortfolioScreen.kt sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.HeaderAddButton
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.resources.R

/**
 * Gerüst des Portfolio-Tabs (Leiste, «+», Liste). Nicht neu startbar: liest den Zustand wie zuvor im Rumpf von
 * [PortfolioScreen] (gleiche Neuzusammensetzung, gleicher Start der Flows).
 */
@NonRestartableComposable
@Composable
internal fun PortfolioScaffold(
    viewModel: PortfolioViewModel,
    banner: WatchlistBanner,
    listState: LazyListState,
    reduceMotion: Boolean,
    transactions: List<PortfolioTxEntity>?,
    watchedAddresses: List<WatchedAddress>,
    currency: String,
    hideAmounts: Boolean,
    changeBasis: ChangeBasis,
    txCounts: Map<String, Int>,
    showClosed: Boolean,
    onShowClosedChange: (Boolean) -> Unit,
    onDeleteCoin: (String) -> Unit,
    onSheetChange: (TxDraft?) -> Unit,
    onOpenCoin: (String) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTaxWorksheet: () -> Unit,
    onPickCurrency: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenCsvImport: () -> Unit,
    onOpenImports: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenAlarm: () -> Unit,
    onAskClear: () -> Unit,
    costGapsPending: Int,
    onOpenCostGaps: () -> Unit,
) {
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    val hasTransactions = !transactions.isNullOrEmpty()
    // Inhalt auch nur mit Adressen (Bestand ohne Kaufpreis)
    val hasData = hasTransactions || watchedAddresses.isNotEmpty()
    // CSV-Importläufe für ⋯ › «Importe» (neueste zuerst)
    val importBatches = remember(transactions) { PortfolioImportBatch.summarize(transactions.orEmpty().map { it.importBatch }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TabTitle(stringResource(R.string.portfolio_title), R.drawable.ic_portfolio) },
                actions = {
                    // «+» oben (verdeckt keine Beträge), wie «+» der Merkliste
                    if (hasData) HeaderAddButton(stringResource(R.string.portfolio_add_tx), onClick = { onSheetChange(TxDraft()) })
                    PortfolioToolbarActions(
                        hasData = hasData,
                        hasTransactions = hasTransactions,
                        hideAmounts = hideAmounts,
                        refreshing = refreshing,
                        currency = currency,
                        importBatches = importBatches,
                        menuOpen = menuOpen,
                        onMenuOpenChange = { menuOpen = it },
                        viewModel = viewModel,
                        onOpenAbout = onOpenAbout,
                        onPickCurrency = onPickCurrency,
                        onOpenAddresses = onOpenAddresses,
                        onOpenCsvImport = onOpenCsvImport,
                        onOpenImports = onOpenImports,
                        onOpenExport = onOpenExport,
                        onOpenTaxWorksheet = onOpenTaxWorksheet,
                        onOpenAlarm = onOpenAlarm,
                        onAskClear = onAskClear,
                    )
                }
            )
        },
        snackbarHost = { AppSnackbarHost(banner.host) },
    ) { padding ->
        PortfolioContent(
            padding = padding,
            viewModel = viewModel,
            listState = listState,
            reduceMotion = reduceMotion,
            transactions = transactions,
            watchedAddresses = watchedAddresses,
            refreshing = refreshing,
            hasData = hasData,
            currency = currency,
            hideAmounts = hideAmounts,
            changeBasis = changeBasis,
            txCounts = txCounts,
            showClosed = showClosed,
            onShowClosedChange = onShowClosedChange,
            onDeleteCoin = onDeleteCoin,
            onSheetChange = onSheetChange,
            onOpenCoin = onOpenCoin,
            onOpenAddresses = onOpenAddresses,
            onOpenCsvImport = onOpenCsvImport,
            costGapsPending = costGapsPending,
            onOpenCostGaps = onOpenCostGaps,
        )
    }
}

/** Inhalt unter der Leiste: Platzhalter, leerer Zustand oder die Liste. Nicht neu startbar (liest wie zuvor im Inhalt). */
@NonRestartableComposable
@Composable
internal fun PortfolioContent(
    padding: PaddingValues,
    viewModel: PortfolioViewModel,
    listState: LazyListState,
    reduceMotion: Boolean,
    transactions: List<PortfolioTxEntity>?,
    watchedAddresses: List<WatchedAddress>,
    refreshing: Boolean,
    hasData: Boolean,
    currency: String,
    hideAmounts: Boolean,
    changeBasis: ChangeBasis,
    txCounts: Map<String, Int>,
    showClosed: Boolean,
    onShowClosedChange: (Boolean) -> Unit,
    onDeleteCoin: (String) -> Unit,
    onSheetChange: (TxDraft?) -> Unit,
    onOpenCoin: (String) -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenCsvImport: () -> Unit,
    costGapsPending: Int,
    onOpenCostGaps: () -> Unit,
) {
    // Stablecoins mit gebrückten Varianten (USDC.e …): Zeile mit «≈» ([AddressBalance.bridgedStables])
    val bridgedStables = remember(watchedAddresses) {
        com.cryptochecker.app.domain.portfolio.AddressBalance.bridgedStables(watchedAddresses)
    }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    // Faktor USDT → Anzeigewährung: Devisenkurs × USDT/USD (USDT zum Marktkurs)
    val fxRate by viewModel.displayFxRate.collectAsStateWithLifecycle()
    // «Seit Kauf»/Einstand in der Anzeigewährung zum Kurs der Erwerbstage
    val costFx by viewModel.costFx.collectAsStateWithLifecycle()
    val todayPercent by viewModel.todayPercent.collectAsStateWithLifecycle()
    val todayAmount by viewModel.todayAmount.collectAsStateWithLifecycle()
    val todaySkipped by viewModel.todaySkipped.collectAsStateWithLifecycle()
    val weekChange by viewModel.weekChange.collectAsStateWithLifecycle()
    val yearChange by viewModel.yearChange.collectAsStateWithLifecycle()
    val periodScaled by viewModel.periodScaled.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyRange by viewModel.historyRange.collectAsStateWithLifecycle()
    val historyExpanded by viewModel.historyExpanded.collectAsStateWithLifecycle()
    val historyView by viewModel.historyView.collectAsStateWithLifecycle()
    val historyCandles by viewModel.historyCandles.collectAsStateWithLifecycle()
    val coinPnl by viewModel.coinPnl.collectAsStateWithLifecycle()
    val historyPnl by viewModel.historyPnl.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val depegReading by viewModel.depegReading.collectAsStateWithLifecycle()
    val depegThreshold by viewModel.depegThresholdTenths.collectAsStateWithLifecycle()
    // Uhr für «vor 2 Min.» und den Wechsel nach «veraltet» (alle 30 s, wie der Markt-Tab)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // FIFO je Wallet: Abgänge über den Bestand ihrer Wallet (Hinweis unter der Gesamtkarte); im ViewModel
    // abseits des Hauptthreads gerechnet (PortfolioCoinDetailFlow)
    val walletShortfalls by remember(viewModel) { viewModel.walletShortfallsFlow() }.collectAsStateWithLifecycle(emptyList())

    val current = summary
    if (transactions == null || current == null) {
        SkeletonList(modifier = Modifier.padding(padding))
        return
    }
    if (!hasData) {
        EmptyPortfolio(
            modifier = Modifier.fillMaxSize().padding(padding),
            currency = currency,
            onAdd = { onSheetChange(TxDraft()) },
            onAddAddress = onOpenAddresses,
            onImportCsv = onOpenCsvImport,
        )
        return
    }
    PortfolioList(
        modifier = Modifier.fillMaxSize().padding(padding),
        viewModel = viewModel,
        listState = listState,
        current = current,
        watchedAddresses = watchedAddresses,
        prices = prices,
        refreshing = refreshing,
        hideAmounts = hideAmounts,
        now = now,
        staleAfter = staleAfter,
        online = online,
        reduceMotion = reduceMotion,
        currency = currency,
        fxRate = fxRate,
        costFx = costFx,
        todayPercent = todayPercent,
        todayAmount = todayAmount,
        todaySkipped = todaySkipped,
        weekChange = weekChange,
        yearChange = yearChange,
        periodScaled = periodScaled,
        walletShortfalls = walletShortfalls,
        onShortfall = { id ->
            if (id == COST_GAPS_HINT_ID) {
                onOpenCostGaps()
            } else {
                transactions.firstOrNull { it.id == id }?.let { onSheetChange(it.toDraft()) }
            }
        },
        depegReading = depegReading,
        depegThreshold = depegThreshold,
        history = history,
        historyRange = historyRange,
        historyExpanded = historyExpanded,
        historyView = historyView,
        historyCandles = historyCandles,
        historyPnl = historyPnl,
        coinPnl = coinPnl,
        changeBasis = changeBasis,
        txCounts = txCounts,
        bridgedStables = bridgedStables,
        showClosed = showClosed,
        onShowClosedChange = onShowClosedChange,
        onDeleteCoin = onDeleteCoin,
        onOpenCoin = onOpenCoin,
        costGapsPending = costGapsPending,
        onOpenCostGaps = onOpenCostGaps,
    )
}
