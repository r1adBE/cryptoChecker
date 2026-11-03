package com.cryptochecker.app.ui.features.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.HttpLogPanel
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.features.loading.SkeletonLoadingScreen
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

/**
 * Seite «Paar hinzufügen» (kein eigener Tab) — erreichbar über «+» in der Merkliste,
 * die App-Verknüpfung, ein Widget oder «Heute auffällig»; [onBack] schliesst sie.
 */
@Composable
fun ExplorerScreen(
    onOpenWatchlist: () -> Unit = {},
    onBack: () -> Unit = {},
    explorerViewModel: ExplorerViewModel = hiltViewModel(),
    /** Register der Paar-Auswahl; die Navigation gibt den Stand der Sitzung mit. */
    sections: ExplorerSectionsState = rememberExplorerSections(),
) {
    val vm = explorerViewModel
    val pageState by vm.pageState.collectAsStateWithLifecycle()

    // Suche
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val coinHits by vm.coinHits.collectAsStateWithLifecycle()
    val pairHits by vm.pairHits.collectAsStateWithLifecycle()
    val showPairHits by vm.showPairHits.collectAsStateWithLifecycle()
    val pickedCoin by vm.pickedCoin.collectAsStateWithLifecycle()
    val pickedCoinPairs by vm.pickedCoinPairs.collectAsStateWithLifecycle()
    val searchLists by vm.searchLists.collectAsStateWithLifecycle()
    val watchedKeys by vm.watchedKeys.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    // Favoriten, Gruppen, DEX, Protokoll
    val favoriteExchanges by vm.favoriteExchanges.collectAsStateWithLifecycle()
    val favoriteCoins by vm.favoriteCoins.collectAsStateWithLifecycle()
    val favoriteQuotes by vm.favoriteQuotes.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val targetGroup by vm.targetGroup.collectAsStateWithLifecycle()
    val dexResults by vm.dexResults.collectAsStateWithLifecycle()
    val dexSearching by vm.dexSearching.collectAsStateWithLifecycle()
    val dexMessage by vm.dexMessage.collectAsStateWithLifecycle()
    val dexPrefill by vm.dexPrefill.collectAsStateWithLifecycle()
    val showHttpLog by vm.showHttpLog.collectAsStateWithLifecycle()
    // Übersicht: Markt-Chip, zuletzt genutzte Börsen
    val market by vm.marketFilter.collectAsStateWithLifecycle()
    val recentExchanges by vm.recentExchanges.collectAsStateWithLifecycle()

    // Jedes Öffnen der Seite beginnt bei der Übersicht (wie iOS); Drehen behält die offene Börse
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            vm.closeExchange()
        }
    }

    when (val state = pageState) {
        is ExplorerLoad.Loading -> PlainExplorerPage(onBack) { SkeletonLoadingScreen() }

        is ExplorerLoad.Ready -> ExplorerPage(
            selection = state.selection,
            onOpenDex = vm::openDexSearch,
            onCloseExchange = vm::closeExchange,
            browse = BrowseUi(
                market = market,
                onMarket = vm::setMarketFilter,
                recent = recentExchanges,
                onOpenExchange = vm::openExchange,
            ),
            sections = sections,
            httpLogText = vm.httpLogText,
            showHttpLog = showHttpLog,
            onOpenWatchlist = onOpenWatchlist,
            onBack = onBack,
            actions = PairActions(
                onCoinChanged = vm::selectCoin,
                onQuoteChanged = vm::selectQuote,
                onContractChanged = vm::selectContract,
                onRetryPrice = vm::retryPrice,
                onReloadPairs = vm::reloadPairList,
                onAdd = vm::addSelectedPair,
                onAddShown = vm::clearAddResult,
            ),
            bulk = BulkActions(
                onApply = vm::collectBulkPairs,
                onSelectPair = vm::selectBulkPair,
                onAddAll = vm::addBulkPairs,
                onAddAllShown = vm::clearBulkAddResult,
                onAddAllClaimed = vm::markBulkAddClaimed,
                onUndo = vm::undoBulkAdd,
                onQuoteChanged = vm::setBulkQuote,
                onNeedStats = vm::loadBulkStats,
            ),
            search = SearchUi(
                query = searchQuery,
                coins = coinHits,
                pairs = pairHits,
                showPairs = showPairHits,
                pickedCoin = pickedCoin,
                pickedCoinPairs = pickedCoinPairs,
                // DEX-Pools haben keine Paarlisten: unter «DEX» kein Angebot, Listen zu laden
                lists = if (market == MarketFilter.DEX) SearchLists() else searchLists,
                watched = watchedKeys,
                onQueryChange = vm::setSearchQuery,
                onPickCoin = vm::pickCoin,
                onAdd = vm::addSearchHit,
                onLoadLists = vm::loadMissingLists,
                recent = recentSearches,
                onConfirm = vm::confirmSearch,
                onClearRecent = vm::clearRecentSearches,
                dexHit = dexSearchHit(searchQuery, state.selection, market),
                exchanges = exchangeHits(searchQuery, state.selection, market),
            ),
            favorites = FavoritesUi(
                exchanges = favoriteExchanges,
                coins = favoriteCoins,
                quotes = favoriteQuotes,
                onToggle = vm::toggleFavorite,
            ),
            dex = DexUi(
                results = dexResults,
                searching = dexSearching,
                message = dexMessage,
                onSearch = vm::searchDex,
                onAdd = vm::addDexPool,
                onMessageShown = vm::clearDexMessage,
                prefill = dexPrefill,
            ),
            groupTarget = GroupTargetUi(
                target = targetGroup,
                groups = groups,
                onSelect = vm::setTargetGroup,
            ),
        )
    }
}

/** Gerüst mit Kopf «Paar hinzufügen» für Laden und Fehler. */
@Composable
private fun PlainExplorerPage(onBack: () -> Unit, body: @Composable () -> Unit) {
    Scaffold(topBar = { ExplorerTopBar(onBack) }) { insets: PaddingValues ->
        Box(Modifier.padding(insets)) { body() }
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
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    )
}

/**
 * Die Seite: oben die Coin-Suche (der Hauptweg: Coin finden, dann sein Paar) und die Markt-Chips ([MarketChips]),
 * darunter ohne Suchbegriff die Übersicht ([BrowseSections]: zuletzt genutzte und alle Börsen) bzw. die Paare
 * der geöffneten Börse ([ExchangePairs]: Paar, mehrere Paare auf einmal oder DEX-Suche) — und «Börse fehlt?».
 * Rückmeldungen als Snackbar ([ExplorerFeedback]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerPage(
    selection: ExplorerSelection,
    onOpenDex: (String) -> Unit,
    onCloseExchange: () -> Unit,
    browse: BrowseUi,
    sections: ExplorerSectionsState,
    httpLogText: StateFlow<String>,
    showHttpLog: Boolean,
    onOpenWatchlist: () -> Unit,
    onBack: () -> Unit,
    actions: PairActions,
    bulk: BulkActions,
    search: SearchUi,
    favorites: FavoritesUi,
    dex: DexUi,
    groupTarget: GroupTargetUi,
) {
    val exchange by selection.exchange.collectAsStateWithLifecycle()
    val bulkPairs by selection.bulkPairs.collectAsStateWithLifecycle()
    val bulkQuote by selection.bulkQuote.collectAsStateWithLifecycle()
    val addResult by selection.addResult.collectAsStateWithLifecycle()
    val bulkAddResult by selection.bulkAddResult.collectAsStateWithLifecycle()
    val bulkNew by selection.bulkNew.collectAsStateWithLifecycle()

    val logText by httpLogText.collectAsStateWithLifecycle()

    // Zurück mit gewähltem Coin: erst zu den Treffern, nicht gleich aus der Seite; mit offener Börse zur Übersicht
    BackHandler(enabled = search.query.isNotBlank() && search.pickedCoin != null) { search.onPickCoin(null) }
    BackHandler(enabled = search.query.isBlank() && exchange != null, onBack = onCloseExchange)

    val snackbar = remember { SnackbarHostState() }
    var askBulkConfirm by remember { mutableStateOf(false) }
    // Liste «Alle …-Paare» aufgeklappt; bleibt über Suche und Einklappen hinweg erhalten
    var bulkListOpen by rememberSaveable { mutableStateOf(false) }

    ExplorerFeedback(
        snackbar = snackbar,
        addResult = addResult,
        dex = dex,
        bulkAddResult = bulkAddResult,
        onOpenWatchlist = onOpenWatchlist,
        onAddResultShown = actions.onAddShown,
        onBulkAddResultShown = bulk.onAddAllShown,
        onUndoBulk = bulk.onUndo,
        onBulkAddResultClaimed = bulk.onAddAllClaimed,
    )

    if (askBulkConfirm) {
        BulkConfirmDialog(
            // Titel nennt die neuen («312 Paare hinzufügen?»), der Text alle samt Hinweis auf vorhandene
            count = bulkNew,
            total = bulkPairs.size,
            quote = bulkQuote.orEmpty(),
            exchange = exchange?.name.orEmpty(),
            onConfirm = {
                askBulkConfirm = false
                bulk.onAddAll()
            },
            onDismiss = { askBulkConfirm = false },
        )
    }

    Scaffold(
        topBar = { ExplorerTopBar(onBack) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { insets ->
        // Die ganze Seite scrollt als eines, das Suchfeld mit
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                // Tablet/Querformat: höchstens 640 dp breit, mittig
                .readableWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SearchField(search)
            val exchangeOpen = search.query.isBlank() && exchange != null
            // Markt-Chips filtern Börsen und Treffer; bei offener Börse gehören sie nicht dazu
            if (!exchangeOpen) MarketChips(browse.market, browse.onMarket, Modifier.padding(bottom = Spacing.xs))

            val pickedCoin = search.pickedCoin
            when {
                // Angetippter Coin: Vorschlag oben, darunter seine übrigen Paare (Börsen nach dem Markt-Chip)
                search.query.isNotBlank() && pickedCoin != null -> CoinPairs(pickedCoin, search, groupTarget)
                // Während der Suche nur die Treffer
                search.query.isNotBlank() -> SearchResults(
                    search, groupTarget,
                    onChooseManually = { search.onQueryChange("") },
                    onOpenDex = onOpenDex,
                    exchangeRows = { list -> ExchangeRows(list, categoriesOf(selection), favorites, browse.onOpenExchange) },
                )
                else -> BrowseContent(
                    sections = sections,
                    browse = browse,
                    onCloseExchange = onCloseExchange,
                    search = search,
                    selection = selection,
                    actions = actions,
                    bulk = bulk,
                    favorites = favorites,
                    dex = dex,
                    groupTarget = groupTarget,
                    bulkListOpen = bulkListOpen,
                    onToggleBulkList = { bulkListOpen = !bulkListOpen },
                    onAskBulkConfirm = { askBulkConfirm = true },
                    showHttpLog = showHttpLog,
                    logText = logText,
                )
            }
        }
    }
}

/**
 * Ohne Suchbegriff: «Zuletzt gesucht», ein Satz zur Coin-Suche und die Übersicht der Börsen — oder, mit geöffneter
 * Börse, ihre Paare —, dann «Börse fehlt?». Steht in der Spalte der Seite (kein eigenes Layout).
 */
@Composable
private fun BrowseContent(
    sections: ExplorerSectionsState,
    browse: BrowseUi,
    onCloseExchange: () -> Unit,
    search: SearchUi,
    selection: ExplorerSelection,
    actions: PairActions,
    bulk: BulkActions,
    favorites: FavoritesUi,
    dex: DexUi,
    groupTarget: GroupTargetUi,
    bulkListOpen: Boolean,
    onToggleBulkList: () -> Unit,
    onAskBulkConfirm: () -> Unit,
    showHttpLog: Boolean,
    logText: String,
) {
    val exchange by selection.exchange.collectAsStateWithLifecycle()
    if (exchange != null) {
        ExchangePairs(
            sections = sections,
            onClose = onCloseExchange,
            selection = selection,
            actions = actions,
            bulk = bulk,
            favorites = favorites,
            dex = dex,
            groupTarget = groupTarget,
            bulkListOpen = bulkListOpen,
            onToggleBulkList = onToggleBulkList,
            onAskBulkConfirm = onAskBulkConfirm,
        )
    } else {
        // «Zuletzt gesucht» als Chips (nur auf dem Gerät)
        RecentSearchChips(search, Modifier.padding(bottom = Spacing.sm))
        // Hauptweg ist die Coin-Suche; darunter die Börsen
        StepHint(stringResource(R.string.explorer_search_intro), Modifier.padding(start = 4.dp, end = 4.dp))
        BrowseSections(browse, selection, favorites)
    }

    Spacer(modifier = Modifier.size(Spacing.lg))
    ExchangeMissingRow()

    // Entwickleroption: HTTP-Protokoll (Einstellungen › Über die App › Entwickler, nur Debug)
    if (showHttpLog) {
        Spacer(modifier = Modifier.size(12.dp))
        HttpLogPanel(logText)
    }
    Spacer(modifier = Modifier.size(24.dp))
}
