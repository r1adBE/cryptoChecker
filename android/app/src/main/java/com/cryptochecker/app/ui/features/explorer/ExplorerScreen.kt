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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.ui.components.LogBox
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.features.error.ErrorScreen
import com.cryptochecker.app.ui.features.error.ErrorScreenViewState
import com.cryptochecker.app.ui.features.loading.LoadingScreen
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.flow.StateFlow

/**
 * Seite «Paar hinzufügen» (seit Runde 31 kein eigener Tab) — erreichbar über «+» in der Merkliste,
 * die App-Verknüpfung, ein Widget oder «Heute auffällig»; [onBack] schliesst sie.
 */
@Composable
fun ExplorerScreen(
    onOpenWatchlist: () -> Unit = {},
    onBack: () -> Unit = {},
    explorerViewModel: ExplorerViewModel = hiltViewModel(),
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
    val favoriteMarkets by vm.favoriteMarkets.collectAsStateWithLifecycle()
    val favoriteCoins by vm.favoriteCoins.collectAsStateWithLifecycle()
    val favoriteQuotes by vm.favoriteQuotes.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val targetGroup by vm.targetGroup.collectAsStateWithLifecycle()
    val dexResults by vm.dexResults.collectAsStateWithLifecycle()
    val dexSearching by vm.dexSearching.collectAsStateWithLifecycle()
    val dexMessage by vm.dexMessage.collectAsStateWithLifecycle()
    val showHttpLog by vm.showHttpLog.collectAsStateWithLifecycle()

    when (val state = pageState) {
        is ExplorerLoad.Loading -> PlainExplorerPage(onBack) { LoadingScreen() }

        // Auch Laden und Fehler mit Titel und Zurück (eigene Seite ohne Tableiste)
        is ExplorerLoad.Failed -> PlainExplorerPage(onBack) {
            ErrorScreen(errorScreenViewState = ErrorScreenViewState(state.error)) {
                vm.retryExchangeList()
            }
        }

        is ExplorerLoad.Ready -> ExplorerPage(
            selection = state.selection,
            httpLogText = vm.httpLogText,
            showHttpLog = showHttpLog,
            onOpenWatchlist = onOpenWatchlist,
            onBack = onBack,
            actions = PairActions(
                onExchangeChanged = vm::selectExchange,
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
                lists = searchLists,
                watched = watchedKeys,
                onQueryChange = vm::setSearchQuery,
                onPickCoin = vm::pickCoin,
                onAdd = vm::addSearchHit,
                onLoadLists = vm::loadMissingLists,
                recent = recentSearches,
                onConfirm = vm::confirmSearch,
                onClearRecent = vm::clearRecentSearches,
            ),
            favorites = FavoritesUi(
                markets = favoriteMarkets,
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
            ),
            groupTarget = GroupTargetUi(
                target = targetGroup,
                groups = groups,
                onSelect = vm::setTargetGroup,
            ),
        )
    }
}

/** Rückrufe für Börse, Paar und «Zur Merkliste». */
internal class PairActions(
    val onExchangeChanged: (MarketInfo?) -> Unit,
    val onCoinChanged: (String?) -> Unit,
    val onQuoteChanged: (String?) -> Unit,
    val onContractChanged: (FuturesContractType?) -> Unit,
    /** Kurs des gewählten Paars neu laden. */
    val onRetryPrice: () -> Unit,
    val onReloadPairs: () -> Unit,
    val onAdd: () -> Unit,
    /** Rückmeldung zu «Zur Merkliste» wurde gezeigt. */
    val onAddShown: () -> Unit,
)

/** Rückrufe für «Mehrere Paare auf einmal». */
internal class BulkActions(
    val onApply: () -> Unit,
    val onSelectPair: (MarketPair) -> Unit,
    val onAddAll: () -> Unit,
    val onAddAllShown: () -> Unit,
    val onQuoteChanged: (String) -> Unit,
    /** Liste offen: Kennzahlen für die Schnellfilter laden (eine Sammelabfrage). */
    val onNeedStats: () -> Unit,
)

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
 * Die Seite: oben die Coin-Suche (der Hauptweg: Coin finden, dann sein Paar), darunter eingeklappt
 * «Erweitert / nach Börse» in Schritten — Börse ([MarketStepCard]), Paar ([PairStepCard]) bzw. DEX-Suche,
 * mehrere Paare auf einmal ([BulkPairsCard]) — und «Börse fehlt?». Rückmeldungen als Snackbar ([ExplorerFeedback]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerPage(
    selection: ExplorerSelection,
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

    val logText by httpLogText.collectAsStateWithLifecycle()

    // Zurück mit gewähltem Coin: erst zu den Treffern, nicht gleich aus der Seite
    BackHandler(enabled = search.query.isNotBlank() && search.pickedCoin != null) { search.onPickCoin(null) }

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
    )

    if (askBulkConfirm) {
        BulkConfirmDialog(
            count = bulkPairs.size,
            quote = bulkQuote.orEmpty(),
            market = exchange?.name.orEmpty(),
            onConfirm = {
                askBulkConfirm = false
                bulk.onAddAll()
            },
            onDismiss = { askBulkConfirm = false },
        )
    }

    Scaffold(
        topBar = { ExplorerTopBar(onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
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

            val pickedCoin = search.pickedCoin
            when {
                // Angetippter Coin: Vorschlag oben, darunter seine übrigen Paare
                search.query.isNotBlank() && pickedCoin != null -> CoinPairs(pickedCoin, search, groupTarget)
                // Während der Suche nur die Treffer
                search.query.isNotBlank() -> SearchResults(search, groupTarget, onChooseManually = {
                    search.onQueryChange("")
                    ExplorerSections.precise = true
                })
                else -> BrowseContent(
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
 * Ohne Suchbegriff: «Zuletzt gesucht», ein Satz zur Coin-Suche, eingeklappt «Erweitert / nach Börse»
 * und «Börse fehlt?». Steht in der Spalte der Seite (kein eigenes Layout).
 */
@Composable
private fun BrowseContent(
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
    // «Zuletzt gesucht» als Chips (nur auf dem Gerät)
    RecentSearchChips(search, Modifier.padding(bottom = 8.dp))
    // Hauptweg ist die Coin-Suche; die Auswahl nach Börse liegt eingeklappt darunter
    StepHint(stringResource(R.string.explorer_search_intro), Modifier.padding(start = 4.dp, end = 4.dp))

    // Auf-/zugeklappt merkt sich die Sitzung, nicht nur dieser Bildschirm (ExplorerSections)
    val precise = ExplorerSections.precise
    SectionToggle(
        title = stringResource(R.string.explorer_precise_title),
        expanded = precise,
        onToggle = { ExplorerSections.precise = !precise },
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    if (precise) {
        PreciseSelection(
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
    }

    ExchangeMissingRow()

    // Entwickleroption: HTTP-Protokoll (Einstellungen → Entwickler)
    if (showHttpLog) {
        Spacer(modifier = Modifier.size(12.dp))
        LogBox(logText)
    }
    Spacer(modifier = Modifier.size(24.dp))
}
