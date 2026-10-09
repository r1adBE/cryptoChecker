package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.ui.components.LogBox
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.features.error.ErrorScreen
import com.cryptochecker.app.ui.features.error.ErrorScreenViewState
import com.cryptochecker.app.ui.features.loading.LoadingScreen
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.flow.StateFlow

/**
 * Seite «Paar hinzufügen» (Runde 31: kein eigener Tab mehr) — geöffnet mit «+» in der Merkliste,
 * der App-Verknüpfung, einem Widget oder «Heute auffällig»; [onBack] schliesst sie.
 */
@Composable
fun ExplorerScreen(
    onOpenWatchlist: () -> Unit = {},
    onBack: () -> Unit = {},
    explorerViewModel: ExplorerViewModel = hiltViewModel(),
) {
    val uiState by explorerViewModel.uiState.collectAsStateWithLifecycle()
    val dexResults by explorerViewModel.dexResults.collectAsStateWithLifecycle()
    val dexSearching by explorerViewModel.dexSearching.collectAsStateWithLifecycle()
    val dexMessage by explorerViewModel.dexMessage.collectAsStateWithLifecycle()
    val favMarkets by explorerViewModel.favoriteMarkets.collectAsStateWithLifecycle()
    val favCoins by explorerViewModel.favoriteCoins.collectAsStateWithLifecycle()
    val favQuotes by explorerViewModel.favoriteQuotes.collectAsStateWithLifecycle()
    val showHttpLog by explorerViewModel.showHttpLog.collectAsStateWithLifecycle()
    val searchQuery by explorerViewModel.searchQuery.collectAsStateWithLifecycle()
    val searchHits by explorerViewModel.searchHits.collectAsStateWithLifecycle()
    val searchProgress by explorerViewModel.searchProgress.collectAsStateWithLifecycle()
    val watchedKeys by explorerViewModel.watchedKeys.collectAsStateWithLifecycle()
    val groups by explorerViewModel.groups.collectAsStateWithLifecycle()
    val targetGroup by explorerViewModel.targetGroup.collectAsStateWithLifecycle()

    when (uiState) {
        is ExplorerUiState.Success -> {
            val viewState = (uiState as ExplorerUiState.Success).data

            MarketScreenMain(
                viewState = viewState,
                httpLogText = explorerViewModel.httpLogText,
                showHttpLog = showHttpLog,
                onOpenWatchlist = onOpenWatchlist,
                onBack = onBack,

                onMarketChanged = explorerViewModel::setCurrentMarket,
                onBaseAssetChanged = explorerViewModel::setCurrentBaseAsset,
                onQuoteAssetChanged = explorerViewModel::setCurrentQuoteAsset,
                onContractTypeChanged = explorerViewModel::setCurrentContractType,

                onTestMarketButtonClick = explorerViewModel::getTicker,
                onSyncCurrencyPairsClick = explorerViewModel::syncCurrencyPairs,
                onApplyAllPairsClick = explorerViewModel::applyAllPairsOfMarket,
                onSelectBulkPair = explorerViewModel::selectBulkPair,
                onAddToWatchlistClick = explorerViewModel::addCurrentPairToWatchlist,
                onAddToWatchlistMessageShown = explorerViewModel::clearAddToWatchlistState,
                onAddAllPairsClick = explorerViewModel::addAllPairsToWatchlist,
                onBulkAddMessageShown = explorerViewModel::clearBulkAddState,
                onBulkQuoteChanged = explorerViewModel::setBulkQuote,
                search = SearchUi(
                    query = searchQuery,
                    hits = searchHits,
                    progress = searchProgress,
                    watched = watchedKeys,
                    onQueryChange = explorerViewModel::setSearchQuery,
                    onAdd = explorerViewModel::addSearchHit,
                ),
                favorites = FavoritesUi(
                    markets = favMarkets,
                    coins = favCoins,
                    quotes = favQuotes,
                    onToggle = explorerViewModel::toggleFavorite,
                ),
                dex = DexUi(
                    results = dexResults,
                    searching = dexSearching,
                    message = dexMessage,
                    onSearch = explorerViewModel::searchDex,
                    onAdd = explorerViewModel::addDexPool,
                    onMessageShown = explorerViewModel::clearDexMessage,
                ),
                groupTarget = GroupTargetUi(
                    target = targetGroup,
                    groups = groups,
                    onSelect = explorerViewModel::setTargetGroup,
                )
            )
        }

        // Auch beim Laden und bei Fehlern mit Titel und Zurück (eigene Seite ohne Tableiste)
        is ExplorerUiState.Error -> {
            Scaffold(topBar = { ExplorerTopBar(onBack) }) { padding ->
                Box(Modifier.padding(padding)) {
                    ErrorScreen(errorScreenViewState = ErrorScreenViewState((uiState as ExplorerUiState.Error).exception)) {
                        explorerViewModel.retryLoadMarketList()
                    }
                }
            }
        }
        is ExplorerUiState.Loading -> {
            Scaffold(topBar = { ExplorerTopBar(onBack) }) { padding ->
                Box(Modifier.padding(padding)) { LoadingScreen() }
            }
        }
    }
}

/** Kopf der Seite: «Paar hinzufügen» mit Zurück-Pfeil. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.shortcut_add)) },
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

/**
 * Die Seite: Suchfeld (Hauptweg), darunter eingeklappt die genaue Auswahl in Schritten —
 * Börse ([MarketStepCard]), Paar ([PairStepCard]) bzw. DEX-Suche, mehrere Paare auf einmal
 * ([BulkPairsCard]) — und «Börse fehlt?». Rückmeldungen als Snackbar ([ExplorerFeedback]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketScreenMain(
    viewState: ExplorerViewState,
    httpLogText: StateFlow<String>,
    showHttpLog: Boolean,
    onOpenWatchlist: () -> Unit,
    onBack: () -> Unit,

    onMarketChanged: (MarketInfo?) -> Unit,
    onBaseAssetChanged: (String?) -> Unit,
    onQuoteAssetChanged: (String?) -> Unit,
    onContractTypeChanged: (FuturesContractType?) -> Unit,

    onTestMarketButtonClick: (MarketInfo) -> Unit,
    onSyncCurrencyPairsClick: () -> Unit,
    onApplyAllPairsClick: () -> Unit,
    onSelectBulkPair: (com.cryptochecker.marketdata.model.CurrencyPairInfo) -> Unit,
    onAddToWatchlistClick: () -> Unit = {},
    onAddToWatchlistMessageShown: () -> Unit = {},
    onAddAllPairsClick: () -> Unit = {},
    onBulkAddMessageShown: () -> Unit = {},
    onBulkQuoteChanged: (String) -> Unit = {},
    favorites: FavoritesUi = FavoritesUi(),
    dex: DexUi = DexUi(),
    search: SearchUi = SearchUi(),
    groupTarget: GroupTargetUi = GroupTargetUi(),
) {
    // Auf-/Zugeklappt merkt sich die Sitzung (nicht nur dieser Bildschirm), siehe ExplorerSections
    val showPrecise = ExplorerSections.precise

    val markets = viewState.markets
    val currentMarket by viewState.currentMarket.collectAsStateWithLifecycle()
    val canUpdatePairs by viewState.canUpdatePairs.collectAsStateWithLifecycle()
    val currentMarketPairsInfo by viewState.currentMarketPairsInfo.collectAsStateWithLifecycle()

    val currentBaseAsset by viewState.currentBaseAsset.collectAsStateWithLifecycle()
    val currentQuoteAsset by viewState.currentQuoteAsset.collectAsStateWithLifecycle()
    val currentContractType by viewState.currentContractType.collectAsStateWithLifecycle()

    val baseAssets by viewState.baseAssets.collectAsStateWithLifecycle()
    val quoteAssets by viewState.quoteAssets.collectAsStateWithLifecycle()
    val contactTypes by viewState.contractTypes.collectAsStateWithLifecycle()

    val marketTicker by viewState.marketTicker.collectAsStateWithLifecycle()
    val marketPairsUpdateState by viewState.marketPairsUpdateState.collectAsStateWithLifecycle()
    val bulkPairs by viewState.bulkPairs.collectAsStateWithLifecycle()
    val bulkPairsMessage by viewState.bulkPairsMessage.collectAsStateWithLifecycle()
    val addToWatchlistState by viewState.addToWatchlistState.collectAsStateWithLifecycle()
    val bulkAddState by viewState.bulkAddState.collectAsStateWithLifecycle()
    val bulkQuotes by viewState.bulkQuotes.collectAsStateWithLifecycle()
    val bulkQuote by viewState.bulkQuote.collectAsStateWithLifecycle()

    val logText by httpLogText.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmBulk by remember { mutableStateOf(false) }
    var showBulkList by rememberSaveable { mutableStateOf(false) }

    // Schrittweise freischalten, damit klar ist, was als Nächstes kommt:
    // 1. Börse wählen (Paare laden automatisch) → 2. Paar wählen → hinzufügen.
    val isDex = currentMarket?.key == DEX_MARKET_KEY
    val hasPairs = baseAssets.isNotEmpty()
    val syncing = marketPairsUpdateState.isInProgress
    val pairSelected = hasPairs && currentBaseAsset != null && currentQuoteAsset != null

    ExplorerFeedback(
        snackbar = snackbar,
        addToWatchlistState = addToWatchlistState,
        dex = dex,
        bulkAddState = bulkAddState,
        onOpenWatchlist = onOpenWatchlist,
        onAddToWatchlistMessageShown = onAddToWatchlistMessageShown,
        onBulkAddMessageShown = onBulkAddMessageShown,
    )

    if (confirmBulk) {
        BulkConfirmDialog(
            count = bulkPairs.size,
            quote = bulkQuote.orEmpty(),
            market = currentMarket?.name.orEmpty(),
            onConfirm = {
                confirmBulk = false
                onAddAllPairsClick()
            },
            onDismiss = { confirmBulk = false },
        )
    }

    Scaffold(
        topBar = { ExplorerTopBar(onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        // Eine Seite, die als Ganzes scrollt — das Suchfeld scrollt mit.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                .readableWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SearchField(search)

            // Während der Suche nur die Treffer, sonst der Auswahl-Ablauf
            if (search.query.isNotBlank()) {
                SearchResults(search, groupTarget)
            } else {
                // Die Suche ist der Hauptweg; das genaue Auswählen ist eingeklappt.
                StepHint(stringResource(R.string.explorer_search_intro), Modifier.padding(start = 4.dp, end = 4.dp))

                SectionToggle(
                    title = stringResource(R.string.explorer_precise_title),
                    expanded = showPrecise,
                    onToggle = { ExplorerSections.precise = !showPrecise },
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )

                if (showPrecise) {
                    MarketStepCard(
                        markets = markets,
                        currentMarket = currentMarket,
                        isDex = isDex,
                        hasPairs = hasPairs,
                        syncing = syncing,
                        canUpdatePairs = canUpdatePairs,
                        updateError = marketPairsUpdateState.error,
                        pairsInfo = currentMarketPairsInfo,
                        favorites = favorites,
                        onMarketChanged = onMarketChanged,
                        onSync = onSyncCurrencyPairsClick,
                    )

                    if (currentMarket != null && isDex) {
                        StepCard { DexSearchSection(dex, groupTarget) }
                    }

                    if (currentMarket != null && !isDex) {
                        PairStepCard(
                            hasPairs = hasPairs,
                            pairSelected = pairSelected,
                            syncing = syncing,
                            canUpdatePairs = canUpdatePairs,
                            baseAssets = baseAssets,
                            quoteAssets = quoteAssets,
                            contractTypes = contactTypes,
                            currentBaseAsset = currentBaseAsset,
                            currentQuoteAsset = currentQuoteAsset,
                            currentContractType = currentContractType,
                            ticker = marketTicker,
                            favorites = favorites,
                            groupTarget = groupTarget,
                            onBaseAssetChanged = onBaseAssetChanged,
                            onQuoteAssetChanged = onQuoteAssetChanged,
                            onContractTypeChanged = onContractTypeChanged,
                            onRetryTicker = { currentMarket?.also { onTestMarketButtonClick(it) } },
                            onSync = onSyncCurrencyPairsClick,
                            onAdd = onAddToWatchlistClick,
                        )

                        // ── Mehrere Paare auf einmal ─────────────────────────────
                        if (hasPairs && bulkQuotes.isNotEmpty()) {
                            BulkPairsCard(
                                quotes = bulkQuotes,
                                quote = bulkQuote,
                                pairs = bulkPairs,
                                emptyMessage = bulkPairsMessage == "empty",
                                syncing = syncing,
                                adding = bulkAddState?.running == true,
                                currentBaseAsset = currentBaseAsset,
                                currentQuoteAsset = currentQuoteAsset,
                                currentContractType = currentContractType,
                                favorites = favorites,
                                groupTarget = groupTarget,
                                onQuoteChanged = onBulkQuoteChanged,
                                onApply = onApplyAllPairsClick,
                                onSelectPair = onSelectBulkPair,
                                onAddAll = { confirmBulk = true },
                                showBulkList = showBulkList,
                                onToggleList = { showBulkList = !showBulkList },
                            )
                        }
                    }
                }

                ExchangeMissingRow()

                // Entwickleroption: HTTP-Protokoll (Einstellungen → Entwickler)
                if (showHttpLog) {
                    Spacer(modifier = Modifier.size(12.dp))
                    LogBox(logText)
                }
                Spacer(modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Runde 13b: Börse fehlt? → GitHub-Vorlage «Exchange request». */
@Composable
private fun ExchangeMissingRow() {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.explorer_exchange_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = {
            runCatching {
                uriHandler.openUri(com.cryptochecker.app.ui.features.about.EXCHANGE_REQUEST_URL)
            }
        }) {
            Text(stringResource(R.string.about_request_exchange))
        }
    }
}
