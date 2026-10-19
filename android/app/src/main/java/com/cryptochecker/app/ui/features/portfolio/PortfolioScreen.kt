@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.lazy.rememberLazyListState
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.ui.components.AppFab
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner

/**
 * Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+». Bedienung wie in der
 * Merkliste: nach unten ziehen oder ⋯ › «Aktualisieren» lädt die Kurse neu; einen Coin nach
 * links wischen löscht nach Rückfrage alle seine Transaktionen (Banner mit «Rückgängig»); ⋯ › «Portfolio
 * leeren» löscht nach Rückfrage alles.
 */
@Composable
fun PortfolioScreen(
    onOpenCoin: (String) -> Unit,
    onOpenAbout: () -> Unit = {},
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val watchedAddresses by viewModel.watchedAddresses.collectAsStateWithLifecycle()
    // Stablecoins mit gebrückten Varianten (USDC.e …): Zeile mit «≈» ([AddressBalance.bridgedStables])
    val bridgedStables = remember(watchedAddresses) {
        com.cryptochecker.app.domain.portfolio.AddressBalance.bridgedStables(watchedAddresses)
    }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currency by viewModel.currency.collectAsStateWithLifecycle()
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
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val coinPnl by viewModel.coinPnl.collectAsStateWithLifecycle()
    val historyPnl by viewModel.historyPnl.collectAsStateWithLifecycle()
    val changeBasis by viewModel.changeBasis.collectAsStateWithLifecycle()
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

    LaunchedEffect(Unit) { viewModel.start() }
    // Beim Öffnen des Tabs Kurse auffrischen (60 s Zwischenspeicher)
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    var sheet by remember { mutableStateOf<TxDraft?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickCurrency by remember { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    // Saveable: Der Dialog muss die Dateiauswahl (eigene Activity) überstehen
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    // Neuer Alarm «Portfolio-Wert»
    var alarmOpen by rememberSaveable { mutableStateOf(false) }
    // «Portfolio leeren» — Rückfrage
    var askClear by remember { mutableStateOf(false) }
    // ⋯ › «Adressen» (beobachtete öffentliche Adressen)
    var addressesOpen by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «CSV importieren» (Relai, Swissquote)
    var csvImportOpen by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «Importe»: Importläufe mit «Rückgängig»
    var importsOpen by rememberSaveable { mutableStateOf(false) }
    // Einträge ohne Einstand (Karte, Hinweise unter der Gesamtkarte)
    var costGapsOpen by rememberSaveable { mutableStateOf(false) }
    val costGaps = rememberCostGaps(viewModel, transactions)
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()
    val listState = rememberLazyListState()
    // Erneutes Tippen auf den Tab «Portfolio»: an den Anfang der Liste (wie iOS)
    OnTabReselect(ScreenRoute.Portfolio) {
        if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
    // Adresse mit später aufgetauchter Überschneidung: einmal automatisch «Nur anzeigen» — sichtbar, mit «Rückgängig»
    val overlapNotice by viewModel.addressOverlapNotice.collectAsStateWithLifecycle()
    // Bei offenem Adressen-Blatt erst danach (das Banner läge hinter dem Blatt)
    LaunchedEffect(overlapNotice, addressesOpen) {
        val notice = overlapNotice?.takeIf { !addressesOpen } ?: return@LaunchedEffect
        viewModel.consumeAddressOverlapNotice()
        val text = resources.getString(
            R.string.portfolio_address_overlap_auto, notice.names.joinToString(", "), notice.coins.joinToString(", "),
        )
        banner.show(text) { viewModel.undoAddressOverlap(notice) }
    }
    // Einmal: früher automatisch «Nur anzeigen» ohne passende Menge zählt wieder — sichtbar, mit «Rückgängig»
    val restoredNotice by viewModel.addressOverlapRestored.collectAsStateWithLifecycle()
    LaunchedEffect(restoredNotice, addressesOpen) {
        val notice = restoredNotice?.takeIf { !addressesOpen } ?: return@LaunchedEffect
        viewModel.consumeAddressOverlapRestored()
        val text = resources.getString(R.string.portfolio_address_overlap_restored, notice.names.joinToString(", "))
        banner.show(text) { viewModel.undoAddressOverlapRestored(notice) }
    }

    // Kein Schlüssel: Portfolio nur im Arbeitsspeicher, nie unverschlüsselt gespeichert — sagen, nicht still (wie iOS
    // «portfolio_save_pending»); der nächste Start versucht es erneut
    val storageInMemory by DatabaseEncryptionNotice.inMemory.collectAsStateWithLifecycle()
    val portfolioInMemory = PortfolioDatabase.DB_NAME in storageInMemory
    // Variante: Datei mit gespeichertem Stand ist da, nur der Schlüssel war gerade nicht lesbar — der Stand kommt nach
    // einem Neustart zurück, Änderungen dieses Laufs nicht
    val storageKept by DatabaseEncryptionNotice.storedKept.collectAsStateWithLifecycle()
    val portfolioKept = PortfolioDatabase.DB_NAME in storageKept
    val closeLabel = stringResource(R.string.action_close)
    LaunchedEffect(portfolioInMemory, portfolioKept, addressesOpen) {
        if (!portfolioInMemory || addressesOpen) return@LaunchedEffect
        val text = if (portfolioKept) R.string.portfolio_storage_kept_in_memory else R.string.portfolio_storage_in_memory
        banner.showAction(resources.getString(text), closeLabel, IN_MEMORY_NOTICE_MILLIS) {}
    }

    PortfolioScreenDialogs(
        viewModel = viewModel,
        banner = banner,
        transactions = transactions,
        currency = currency,
        changeBasis = changeBasis,
        hideAmounts = hideAmounts,
        sheet = sheet,
        onSheetChange = { sheet = it },
        addressesOpen = addressesOpen,
        onAddressesOpenChange = { addressesOpen = it },
        csvImportOpen = csvImportOpen,
        onCsvImportOpenChange = { csvImportOpen = it },
        importsOpen = importsOpen,
        onImportsOpenChange = { importsOpen = it },
        pickCurrency = pickCurrency,
        onPickCurrencyChange = { pickCurrency = it },
        exportOpen = exportOpen,
        onExportOpenChange = { exportOpen = it },
        alarmOpen = alarmOpen,
        onAlarmOpenChange = { alarmOpen = it },
        askClear = askClear,
        onAskClearChange = { askClear = it },
    )

    if (costGapsOpen) {
        PortfolioCostGapsSheet(
            viewModel, transactions.orEmpty(),
            onEdit = { tx -> costGapsOpen = false; sheet = tx.toDraft() },
            onDismiss = { costGapsOpen = false },
        )
    }

    val hasTransactions = !transactions.isNullOrEmpty()
    // Inhalt auch nur mit Adressen (Bestand ohne Kaufpreis)
    val hasData = hasTransactions || watchedAddresses.isNotEmpty()
    // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
    // Tausch zählt bei beiden Coins
    val txCounts = remember(transactions) {
        transactions.orEmpty().flatMap { tx -> tx.coins().map { PortfolioCalculator.normalizeCoin(it) }.distinct() }
            .groupingBy { it }.eachCount()
    }
    // CSV-Importläufe für ⋯ › «Importe» (neueste zuerst)
    val importBatches = remember(transactions) { PortfolioImportBatch.summarize(transactions.orEmpty().map { it.importBatch }) }
    // FIFO je Wallet: Abgänge über den Bestand ihrer Wallet (Hinweis unter der Gesamtkarte)
    val costMethod by viewModel.costMethod.collectAsStateWithLifecycle()
    val calcTransactions by viewModel.calcTransactions.collectAsStateWithLifecycle()
    val walletShortfalls = remember(calcTransactions, costMethod) {
        PortfolioCalculator.walletShortfalls(calcTransactions.orEmpty().map { it.toTrade() }, costMethod)
    }

    // Wischen auf einem Coin: erst Rückfrage mit der Zahl der Transaktionen, dann alle weg, «Rückgängig» im Banner
    var askDeleteCoin by rememberSaveable { mutableStateOf<String?>(null) }
    fun deleteCoin(coin: String) {
        viewModel.deleteCoin(coin) { removed ->
            val text = resources.getQuantityString(R.plurals.portfolio_coin_removed, removed.size, coin, removed.size)
            banner.show(text) { viewModel.restore(removed) }
        }
    }
    askDeleteCoin?.let { coin ->
        DeleteCoinDialog(
            coin = coin,
            count = txCounts[PortfolioCalculator.normalizeCoin(coin)] ?: 0,
            onConfirm = { askDeleteCoin = null; deleteCoin(coin) },
            onDismiss = { askDeleteCoin = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TabTitle(stringResource(R.string.portfolio_title), R.drawable.ic_portfolio) },
                actions = {
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
                        onPickCurrency = { pickCurrency = true },
                        onOpenAddresses = { addressesOpen = true },
                        onOpenCsvImport = { csvImportOpen = true },
                        onOpenImports = { importsOpen = true },
                        onOpenExport = { exportOpen = true },
                        onOpenAlarm = { alarmOpen = true },
                        onAskClear = { askClear = true },
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(banner.host) },
        floatingActionButton = {
            if (hasData) {
                AppFab(onClick = { sheet = TxDraft() }, contentDescription = stringResource(R.string.portfolio_add_tx))
            }
        }
    ) { padding ->
        val current = summary
        if (transactions == null || current == null) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (!hasData) {
            EmptyPortfolio(
                modifier = Modifier.fillMaxSize().padding(padding),
                currency = currency,
                onAdd = { sheet = TxDraft() },
                onAddAddress = { addressesOpen = true },
                onImportCsv = { csvImportOpen = true },
            )
            return@Scaffold
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
                if (id == COST_GAPS_HINT_ID) costGapsOpen = true
                else transactions?.firstOrNull { it.id == id }?.let { sheet = it.toDraft() }
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
            onShowClosedChange = { showClosed = it },
            onDeleteCoin = { askDeleteCoin = it },
            onOpenCoin = onOpenCoin,
            costGapsPending = costGaps.pending.size,
            onOpenCostGaps = { costGapsOpen = true },
        )
    }
}

/** Hinweis «Portfolio nur im Arbeitsspeicher»: lang genug zum Lesen, mit «Schliessen». */
private const val IN_MEMORY_NOTICE_MILLIS = 12_000L
