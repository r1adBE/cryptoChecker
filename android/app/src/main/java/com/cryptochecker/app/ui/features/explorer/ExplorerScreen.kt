package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.marketdata.model.market.DexPool
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.features.watchlist.friendlyError
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.marketdata.util.FormatUtilsBase
import com.cryptochecker.app.ui.components.LogBox
import com.cryptochecker.app.ui.components.Ticker
import com.cryptochecker.app.ui.features.error.ErrorScreen
import com.cryptochecker.app.ui.features.error.ErrorScreenViewState
import com.cryptochecker.app.ui.features.loading.LoadingScreen
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
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
    val gap = 12.dp
    var showBulkList by rememberSaveable { mutableStateOf(false) }
    // Auf-/Zugeklappt merkt sich die Sitzung (nicht nur dieser Bildschirm), siehe ExplorerSections
    val showPrecise = ExplorerSections.precise
    val showBulk = ExplorerSections.bulk
    val context = LocalContext.current

    val markets = viewState.markets
    // Anzeigename („Crypto.com“, „Bybit Futures“) statt interner Kennung.
    val marketNames = markets.map { market -> market.name }

    val currentMarket by viewState.currentMarket.collectAsStateWithLifecycle()
    val currentMarketIndex = markets.indexOf(currentMarket)

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
    // Neue Paare haben Meldungen an — Erlaubnis jetzt fragen, nicht beim Start.
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest()
    var confirmBulk by remember { mutableStateOf(false) }

    // Schrittweise freischalten, damit klar ist, was als Nächstes kommt:
    // 1. Börse wählen (Paare laden automatisch) → 2. Paar wählen → hinzufügen.
    val isDex = currentMarket?.key == DEX_MARKET_KEY
    val hasPairs = baseAssets.isNotEmpty()
    val syncing = marketPairsUpdateState.isInProgress
    val pairSelected = hasPairs && currentBaseAsset != null && currentQuoteAsset != null
    val actionsEnabled = pairSelected && !syncing

    // Rückmeldungen als Snackbar statt Text, der das Layout verschiebt.
    val viewLabel = stringResource(R.string.action_view)
    val addedText = stringResource(R.string.explorer_added_to_watchlist)
    val alreadyText = stringResource(R.string.explorer_already_in_watchlist)
    val noPairText = stringResource(R.string.explorer_no_pair_selected)
    LaunchedEffect(addToWatchlistState) {
        val state = addToWatchlistState ?: return@LaunchedEffect
        if (state == AddToWatchlistState.ADDED) requestNotifications()
        // Erst nach dem Schliessen zurücksetzen — sonst bricht der neue
        // Schlüssel die laufende Snackbar sofort ab.
        try {
            val result = snackbar.showSnackbar(
                message = when (state) {
                    AddToWatchlistState.ADDED -> addedText
                    AddToWatchlistState.ALREADY_IN_LIST -> alreadyText
                    AddToWatchlistState.NO_PAIR_SELECTED -> noPairText
                },
                actionLabel = if (state == AddToWatchlistState.NO_PAIR_SELECTED) null else viewLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            onAddToWatchlistMessageShown()
        }
    }
    // DEX-Rückmeldungen ebenfalls als Snackbar
    val dexMessage = dex.message
    val dexAddedText = (dexMessage as? DexMessage.Added)?.let { stringResource(R.string.dex_added, it.symbol) }
    val dexAlreadyText = (dexMessage as? DexMessage.AlreadyInList)?.let { stringResource(R.string.dex_already, it.symbol) }
    val dexFailedText = stringResource(R.string.something_went_wrong)
    LaunchedEffect(dexMessage) {
        val text = when (dexMessage) {
            is DexMessage.Added -> dexAddedText
            is DexMessage.AlreadyInList -> dexAlreadyText
            DexMessage.Failed -> dexFailedText
            else -> null
        } ?: return@LaunchedEffect
        if (dexMessage is DexMessage.Added) requestNotifications()
        try {
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = if (dexMessage is DexMessage.Failed) null else viewLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            dex.onMessageShown()
        }
    }

    LaunchedEffect(bulkAddState) {
        val state = bulkAddState?.takeIf { !it.running } ?: return@LaunchedEffect
        if (state.added > 0) requestNotifications()
        try {
            val result = snackbar.showSnackbar(
                message = context.resources.getQuantityString(R.plurals.explorer_bulk_added, state.added, state.added, state.skipped),
                actionLabel = viewLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onOpenWatchlist()
        } finally {
            onBulkAddMessageShown()
        }
    }

    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_title, bulkPairs.size, bulkPairs.size)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.explorer_bulk_confirm_text,
                        bulkPairs.size,
                        bulkPairs.size,
                        bulkQuote.orEmpty(),
                        currentMarket?.name.orEmpty()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulk = false
                    onAddAllPairsClick()
                }) { Text(stringResource(R.string.explorer_add_to_watchlist)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulk = false }) { Text(stringResource(R.string.action_cancel)) }
            }
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
            // ── Schritt 1: Börse ─────────────────────────────────────────
            StepCard {
                StepHeader(
                    number = 1,
                    title = stringResource(R.string.market_screen_market),
                    active = true,
                    done = currentMarket != null && (hasPairs || isDex)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ComboBox(
                        modifier = Modifier.weight(1f),
                        itemList = marketNames,
                        selectedIndex = currentMarketIndex,
                        label = stringResource(id = R.string.market_screen_market),
                        searchable = true,
                        emptyText = stringResource(R.string.explorer_select_placeholder),
                        favorites = favorites.markets,
                        onToggleFavorite = { favorites.onToggle(FavoriteKind.MARKET, it) },
                        onValueChange = { marketIndex -> onMarketChanged(markets[marketIndex]) }
                    )
                    if (!isDex) {
                        Spacer(modifier = Modifier.size(8.dp))
                        // Paare neu laden — erst aktiv, wenn eine Börse gewählt ist.
                        FilledTonalIconButton(
                            onClick = onSyncCurrencyPairsClick,
                            enabled = canUpdatePairs && !syncing,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.market_screen_sync)
                            )
                        }
                    }
                }

                when {
                    currentMarket == null -> StepHint(stringResource(R.string.explorer_select_market_hint))
                    isDex -> Unit
                    syncing -> Column(modifier = Modifier.padding(top = gap)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                        StepHint(stringResource(R.string.explorer_loading_pairs))
                    }
                    marketPairsUpdateState.error != null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.check_error_generic_prefix, friendlyError(marketPairsUpdateState.error.orEmpty())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onSyncCurrencyPairsClick) { Text(stringResource(R.string.action_retry)) }
                    }
                    hasPairs -> currentMarketPairsInfo?.let { info ->
                        StepHint(
                            pluralStringResource(
                                R.plurals.explorer_pairs_status,
                                info.size,
                                info.size,
                                if (info.lastSyncDate > 0)
                                    FormatUtilsBase.formatSameDayTimeOrDate(context, info.lastSyncDate)
                                else stringResource(R.string.checker_add_dynamic_currency_pairs_dialog_last_sync_never)
                            )
                        )
                    }
                    !canUpdatePairs -> StepHint(stringResource(R.string.checker_add_check_currency_empty_warning_title))
                    else -> Unit
                }
            }

            if (currentMarket != null && isDex) {
                StepCard { DexSearchSection(dex, groupTarget) }
            }

            if (currentMarket != null && !isDex) {
                // ── Schritt 2: Paar ──────────────────────────────────────
                StepCard {
                    StepHeader(
                        number = 2,
                        title = stringResource(R.string.explorer_step_pair),
                        active = hasPairs,
                        done = pairSelected
                    )

                    if (hasPairs) {
                        // Untereinander statt gequetscht nebeneinander
                        ComboBox(
                            modifier = Modifier.fillMaxWidth(),
                            itemList = baseAssets,
                            selectedIndex = baseAssets.indexOf(currentBaseAsset),
                            label = stringResource(id = R.string.market_screen_base),
                            searchable = true,
                            favorites = favorites.coins,
                            onToggleFavorite = { favorites.onToggle(FavoriteKind.COIN, it) },
                            onValueChange = { index -> onBaseAssetChanged(baseAssets[index]) }
                        )
                        ComboBox(
                            modifier = Modifier.fillMaxWidth().padding(top = gap),
                            itemList = quoteAssets,
                            selectedIndex = quoteAssets.indexOf(currentQuoteAsset),
                            label = stringResource(id = R.string.market_screen_quote),
                            searchable = true,
                            favorites = favorites.quotes,
                            onToggleFavorite = { favorites.onToggle(FavoriteKind.QUOTE, it) },
                            onValueChange = { index -> onQuoteAssetChanged(quoteAssets[index]) }
                        )

                        // Kontrakt nur bei Futures; auswählbar nur bei mehreren Laufzeiten.
                        val hasContractTypes = contactTypes.isNotEmpty() &&
                            !(contactTypes.size == 1 && contactTypes[0] == FuturesContractType.NONE)
                        if (hasContractTypes) {
                            ComboBox(
                                modifier = Modifier.fillMaxWidth().padding(top = gap),
                                itemList = contactTypes.map { getContractTypeName(it) },
                                selectedIndex = contactTypes.indexOf(currentContractType),
                                label = stringResource(id = R.string.market_screen_contract_type),
                                enabled = contactTypes.size > 1,
                                onValueChange = { index -> onContractTypeChanged(contactTypes[index]) }
                            )
                        }

                        StepHint(stringResource(R.string.hint_favorites_list))
                    } else if (!syncing) {
                        // Antippbar ist nur der Knopf — der Text sagt nicht mehr «hier tippen»
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.explorer_no_pairs_yet),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onSyncCurrencyPairsClick, enabled = canUpdatePairs) {
                                Text(stringResource(R.string.market_screen_sync))
                            }
                        }
                    }

                    // Kurs lädt automatisch, sobald ein Paar gewählt ist — über dem Knopf
                    if (pairSelected) {
                        Column(modifier = Modifier.padding(top = gap)) {
                            val result = marketTicker
                            when {
                                result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = stringResource(R.string.explorer_price_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = Spacing.sm)
                                    )
                                }
                                result.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.check_error_generic_prefix, friendlyError(result.error.orEmpty())),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { currentMarket?.also { onTestMarketButtonClick(it) } }) {
                                        Text(stringResource(R.string.action_retry))
                                    }
                                }
                                else -> with(result.ticker) {
                                    Ticker(
                                        timestamp = timestamp,
                                        last = last,
                                        high = high,
                                        low = low,
                                        ask = ask,
                                        bid = bid,
                                        volBase = vol,
                                        volQuote = volQuote,
                                        currencyBase = result.pairInfo.currencyBase,
                                        currencyQuote = result.pairInfo.currencyCounter,
                                    )
                                }
                            }
                        }
                    }

                    // Ziel-Gruppe für das neue Paar
                    if (hasPairs) GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = gap))
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = actionsEnabled,
                        onClick = onAddToWatchlistClick
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringResource(id = R.string.explorer_add_to_watchlist),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                // ── Mehrere Paare auf einmal ─────────────────────────────
                if (hasPairs && bulkQuotes.isNotEmpty()) {
                    StepCard {
                        // Expertenfunktion: eingeklappt, bis man sie öffnet
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { ExplorerSections.bulk = !showBulk }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.explorer_bulk_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer { rotationZ = if (showBulk) 90f else 0f }
                            )
                        }
                        if (showBulk) {
                        Spacer(modifier = Modifier.size(gap))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ComboBox(
                                modifier = Modifier.weight(1f),
                                itemList = bulkQuotes,
                                selectedIndex = bulkQuotes.indexOf(bulkQuote),
                                label = stringResource(id = R.string.market_screen_quote),
                                searchable = true,
                                favorites = favorites.quotes,
                                onToggleFavorite = { favorites.onToggle(FavoriteKind.QUOTE, it) },
                                onValueChange = { index -> onBulkQuoteChanged(bulkQuotes[index]) }
                            )
                        }
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth().padding(top = gap),
                            enabled = bulkQuote != null && !syncing,
                            onClick = onApplyAllPairsClick
                        ) {
                            Text(text = stringResource(id = R.string.bulk_all_pairs, bulkQuote.orEmpty()))
                        }

                        if (bulkPairsMessage == "empty") {
                            StepHint(stringResource(R.string.bulk_all_pairs_empty, bulkQuote.orEmpty()))
                        }

                        if (bulkPairs.isNotEmpty()) {
                            // Zusammenfassung zuerst; die Liste nur auf Wunsch.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = gap)
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.bulk_all_pairs_applied, bulkPairs.size, bulkPairs.size, bulkQuote.orEmpty()),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { showBulkList = !showBulkList }) {
                                    Text(
                                        stringResource(
                                            if (showBulkList) R.string.explorer_bulk_hide_list
                                            else R.string.explorer_bulk_show_list
                                        )
                                    )
                                }
                            }

                            GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = 4.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                enabled = bulkAddState?.running != true && !syncing,
                                onClick = { confirmBulk = true }
                            ) {
                                if (bulkAddState?.running == true) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text(pluralStringResource(R.plurals.explorer_add_all_pairs, bulkPairs.size, bulkPairs.size))
                                }
                            }

                            if (showBulkList) {
                                StepHint(stringResource(R.string.explorer_bulk_tap_hint))
                                // Antippbare Vorschau: wählt das Paar oben in Schritt 2.
                                // Alle Paare: eigene Liste, scrollt in sich (lazy, auch bei Hunderten flüssig)
                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(bulkPairs, key = { "${it.currencyBase}/${it.currencyCounter}/${it.contractType}" }) { pair ->
                                    val selected = pair.currencyBase == currentBaseAsset &&
                                        pair.currencyCounter == currentQuoteAsset &&
                                        pair.contractType == currentContractType
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else Color.Transparent
                                            )
                                            .clickable { onSelectBulkPair(pair) }
                                            .padding(horizontal = Spacing.sm, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "${pair.currencyBase}/${pair.currencyCounter}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (pair.contractType != FuturesContractType.NONE) {
                                            Text(
                                                text = pair.contractType.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                }
                            }
                        }
                        }
                    }
                }
            }
            }

            // Runde 13b: Börse fehlt? → GitHub-Vorlage «Exchange request»
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

            // Entwickleroption: HTTP-Protokoll (Einstellungen → Entwickler)
            if (showHttpLog) {
                Spacer(modifier = Modifier.size(gap))
                LogBox(logText)
            }
            Spacer(modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Fläche für einen Schritt. */
@Composable
private fun StepCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StepHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp)
    )
}

/**
 * Auf-/Zuklappen bleibt für die ganze Sitzung (bis die App beendet wird), auch wenn
 * man den Tab wechselt. Standard: beides eingeklappt — die Suche ist der Hauptweg.
 */
internal object ExplorerSections {
    var precise by mutableStateOf(false)
    var bulk by mutableStateOf(false)
}

/** Kopfzeile eines aufklappbaren Bereichs: Titel und Pfeil, als Ganzes antippbar. */
@Composable
private fun SectionToggle(title: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .toggleable(value = expanded, role = Role.Button, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = Spacing.lg)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 90f else 0f }
        )
    }
}

@Composable
private fun getContractTypeName(contractType: FuturesContractType): String {
    if(contractType == FuturesContractType.NONE)
        return stringResource(id = R.string.market_screen_spot)

    return contractType.toString()
}


private const val DEX_MARKET_KEY = "DexScreener"

/** Zustand und Aktionen der Suche über alle Börsen. */
class SearchUi(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val progress: SearchProgress? = null,
    /** Schon in der Merkliste (Schlüssel siehe [watchKey]). */
    val watched: Set<String> = emptySet(),
    val onQueryChange: (String) -> Unit = {},
    val onAdd: (SearchHit) -> Unit = {},
) {
    fun isWatched(hit: SearchHit): Boolean = watchKey(
        hit.market.key, hit.pair.currencyBase, hit.pair.currencyCounter, hit.pair.contractType.name
    ) in watched
}

/** Gleiche Felder wie die Dublettenprüfung beim Hinzufügen (WatchRepository.addWatch). */
fun watchKey(marketKey: String, base: String, quote: String, contractType: String): String =
    "$marketKey|$base|$quote|$contractType"

/**
 * Suchfeld oben: «BTC», «ETH USDT». Treffer über alle Börsen; Antippen legt
 * das Paar direkt in die Merkliste.
 */
@Composable
private fun SearchField(search: SearchUi, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = search.query,
        onValueChange = search.onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (search.query.isNotEmpty()) {
                IconButton(onClick = { search.onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
            imeAction = ImeAction.Search
        ),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}

/** Treffer als eigene Liste, die den Bildschirm füllt. */
@Composable
private fun SearchResults(search: SearchUi, groupTarget: GroupTargetUi, modifier: Modifier = Modifier) {
    // Höchstens 50 Treffer — als normale Spalte, damit alles mit der Seite scrollt.
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        search.progress?.let { progress ->
            run {
                Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    )
                    Text(
                        text = stringResource(R.string.explorer_search_loading, progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }
        }
        if (search.hits.isEmpty() && search.progress == null) {
            run {
                Text(
                    text = stringResource(R.string.explorer_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        // Ziel-Gruppe gilt für jeden angetippten Treffer
        if (search.hits.isNotEmpty()) {
            GroupTargetSelector(groupTarget, modifier = Modifier.padding(bottom = 2.dp))
        }
        search.hits.forEach { hit ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { search.onAdd(hit) }
                    .padding(start = 16.dp, end = 4.dp, top = Spacing.sm, bottom = Spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${hit.pair.currencyBase}/${hit.pair.currencyCounter}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = listOfNotNull(
                            hit.market.name,
                            hit.pair.contractType.takeIf { it != FuturesContractType.NONE }?.toString()
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Schon in der Merkliste: Häkchen statt «+» (Screenreader: «Schon in der Merkliste»)
                val watched = search.isWatched(hit)
                IconButton(onClick = { search.onAdd(hit) }) {
                    Icon(
                        painterResource(if (watched) R.drawable.ic_check else R.drawable.ic_add),
                        contentDescription = stringResource(
                            if (watched) R.string.a11y_in_watchlist else R.string.explorer_add_to_watchlist
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Schritt-Kopf: Nummer im Kreis, aktiv bzw. erledigt in der Akzentfarbe,
 * noch nicht erreichbar blass. Zeigt auf einen Blick, was als Nächstes kommt.
 */
@Composable
private fun StepHeader(number: Int, title: String, active: Boolean, done: Boolean) {
    val lit = active || done
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (lit) accent else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = if (done) "✓" else LocaleNumbers.integer(number),
                style = MaterialTheme.typography.labelMedium,
                color = if (lit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (lit) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}

/** Favoriten der drei Auswahllisten. */
class FavoritesUi(
    val markets: Set<String> = emptySet(),
    val coins: Set<String> = emptySet(),
    val quotes: Set<String> = emptySet(),
    val onToggle: (FavoriteKind, String) -> Unit = { _, _ -> },
)

/** Zustand und Aktionen der DEX-Suche. */
class DexUi(
    val results: List<DexPool> = emptyList(),
    val searching: Boolean = false,
    val message: DexMessage? = null,
    val onSearch: (String) -> Unit = {},
    val onAdd: (DexPool) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * Suche nach Token auf dezentralen Börsen. Ein Pool = ein Paar auf einer
 * bestimmten DEX und Chain; der Kurs kommt in USD.
 */
@Composable
private fun DexSearchSection(dex: DexUi, groupTarget: GroupTargetUi) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dex_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dex_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { dex.onSearch(query) }),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                onClick = { dex.onSearch(query) },
                enabled = query.isNotBlank() && !dex.searching
            ) {
                Text(stringResource(R.string.dex_search))
            }
        }

        if (dex.searching) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(22.dp), strokeWidth = 2.dp)
        }

        // Treffer/Fehler kommen als Snackbar; inline nur «keine Treffer».
        if (dex.message == DexMessage.NoResults) {
            Text(
                text = stringResource(R.string.dex_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }

        if (dex.results.isNotEmpty()) {
            GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = 12.dp))
        }

        // Eigene, begrenzte Liste statt Zeilen im Seiten-Scroll
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(dex.results, key = { it.pairId }) { pool ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${pool.baseSymbol}/${pool.quoteSymbol}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = listOfNotNull(
                            BidiText.isolate("${pool.dexId} · ${pool.chainId}"),
                            pool.priceUsd?.let { "$" + formatDexPrice(it) },
                            pool.liquidityUsd?.let { stringResource(R.string.dex_liquidity, "$" + formatCompact(it)) }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { dex.onAdd(pool) }) {
                    Text(stringResource(R.string.dex_add))
                }
            }
        }
        }
    }
}

/** Kleinstpreise (Memecoins) mit genug Stellen, sonst zwei Nachkommastellen. */
private fun formatDexPrice(value: Double): String = when {
    value >= 1 -> "%,.2f".format(value)
    value >= 0.0001 -> "%.6f".format(value)
    // Ohne Nullen am Ende — auch mit arabischen/persischen Ziffern (trimEnd('0') fände sie nicht)
    else -> LocaleNumbers.decimal(value, 10, minDecimals = 0)
}

private fun formatCompact(value: Double): String = when {
    value >= 1e9 -> "%.1fB".format(value / 1e9)
    value >= 1e6 -> "%.1fM".format(value / 1e6)
    value >= 1e3 -> "%.0fK".format(value / 1e3)
    else -> "%.0f".format(value)
}
