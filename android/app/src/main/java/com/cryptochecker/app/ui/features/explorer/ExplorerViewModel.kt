package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.market.DexPool
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.exceptions.MarketError
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.ui.features.explorer.dto.MarketPairsUpdateState
import com.cryptochecker.app.ui.features.watchlist.groupsOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val marketRepository: MarketRepository,
    private val watchRepository: WatchRepository,
    private val favoritesRepository: FavoritesRepository,
    private val httpLogger: HttpLogger,
    settingsRepository: com.cryptochecker.app.settings.SettingsRepository,
    /** Allererstes Paar: ruhiger Erst-Moment in der Merkliste. */
    private val addMoments: com.cryptochecker.app.data.AddMoments,
) : ViewModel() {

    // ---- Suche über alle Börsen
    private val pairsCache = java.util.concurrent.ConcurrentHashMap<String, MarketPairsInfo>()
    private var searchMarkets: List<MarketInfo> = emptyList()
    private var preloadJob: Job? = null
    private var searchJob: Job? = null
    private val _searchQuery = MutableStateFlow("")
    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    private val _searchProgress = MutableStateFlow<SearchProgress?>(null)
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()
    val searchProgress: StateFlow<SearchProgress?> = _searchProgress.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isNotBlank()) ensurePairsLoaded()
        runSearch()
    }

    /** Bestehende Gruppen der Merkliste, für «In Gruppe». */
    val groups: StateFlow<List<String>> = watchRepository.observeWatches()
        .map { groupsOf(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Paare, die schon in der Merkliste stehen (Schlüssel siehe [watchKey]), für das Häkchen in der Suche. */
    val watchedKeys: StateFlow<Set<String>> = watchRepository.observeWatches()
        .map { list -> list.map { watchKey(it.marketKey, it.baseAsset, it.quoteAsset, it.contractType.name) }.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Gruppe für neu hinzugefügte Paare; null = «Keine Gruppe». Gilt nur bis die App beendet wird. */
    val targetGroup: StateFlow<String?> = sessionTargetGroup.asStateFlow()

    fun setTargetGroup(group: String?) {
        sessionTargetGroup.value = group?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Ein Treffer direkt in die Merkliste. */
    fun addSearchHit(hit: SearchHit) {
        viewModelScope.launch {
            val id = watchRepository.addWatch(hit.market, hit.pair, sessionTargetGroup.value)
            _addToWatchlistState.value =
                if (id == null) AddToWatchlistState.ALREADY_IN_LIST else AddToWatchlistState.ADDED
            if (id != null) addMoments.afterExplorerAdd(id)
        }
    }

    private fun runSearch() {
        searchJob?.cancel()
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) {
            _searchHits.value = emptyList()
            return
        }
        val markets = searchMarkets
        searchJob = viewModelScope.launch {
            delay(150)
            _searchHits.value = withContext(Dispatchers.Default) {
                PairSearch.find(query, markets, pairsCache)
            }
        }
    }

    /**
     * Paarlisten aller Börsen bereitstellen: gespeicherte sofort, fehlende im
     * Hintergrund laden (höchstens vier gleichzeitig). Treffer erscheinen
     * nach und nach. Nur einmal pro Sitzung.
     */
    private fun ensurePairsLoaded() {
        if (preloadJob != null) return
        preloadJob = viewModelScope.launch {
            // Sofort «suche…» zeigen, nicht kurz «keine Treffer»
            _searchProgress.value = SearchProgress(0, 0)
            val markets = runCatching { marketRepository.getMarketList() }.getOrDefault(emptyList())
                .filter { it.key != DEX_KEY }
            if (markets.isEmpty()) {
                // Fehlgeschlagen: bei der nächsten Eingabe erneut versuchen
                _searchProgress.value = null
                preloadJob = null
                runSearch()
                return@launch
            }
            searchMarkets = markets
            _searchProgress.value = SearchProgress(0, markets.size)
            val permits = kotlinx.coroutines.sync.Semaphore(4)
            var done = 0
            markets.map { market ->
                async {
                    permits.acquire()
                    try {
                        val info = withContext(Dispatchers.IO) {
                            runCatching {
                                var info = marketRepository.getMarketCurrencyPairsInfo(market)
                                if (info.pairs.isEmpty() && marketRepository.isMarketSupportsUpdatePairs(market)) {
                                    marketRepository.updateMarketCurrencyPairs(market)
                                    info = marketRepository.getMarketCurrencyPairsInfo(market)
                                }
                                info
                            }.getOrNull()
                        }
                        if (info != null) pairsCache[market.key] = info
                    } finally {
                        permits.release()
                    }
                    done++
                    _searchProgress.value = SearchProgress(done, markets.size)
                    runSearch()
                }
            }.awaitAll()
            _searchProgress.value = null
        }
    }

    /** Entwickleroption: HTTP-Protokoll unten anzeigen. */
    val showHttpLog: StateFlow<Boolean> = settingsRepository.settings
        .map { it.showHttpLog && it.developerUnlocked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _marketTicker = MutableStateFlow<MarketTickerResult?>(null)

    private val _currentMarket = MutableStateFlow<MarketInfo?>(null)

    private val _marketPairsUpdateTrigger = MutableSharedFlow<Unit>(1)
    private val _marketPairsUpdateState = MutableStateFlow(MarketPairsUpdateState())

    private val _currentBaseAsset = MutableStateFlow<String?>(null)
    private val _currentQuoteAsset = MutableStateFlow<String?>(null)
    private val _currentContractType = MutableStateFlow<FuturesContractType?>(null)
    private val _bulkPairs = MutableStateFlow<List<CurrencyPairInfo>>(emptyList())
    private val _bulkPairsMessage = MutableStateFlow<String?>(null)
    private val _addToWatchlistState = MutableStateFlow<AddToWatchlistState?>(null)
    private val _bulkAddState = MutableStateFlow<BulkAddState?>(null)
    private val _bulkQuote = MutableStateFlow<String?>(null)

    private val _reloadMarketsTrigger = MutableSharedFlow<Unit>(1)

    // ---- Favoriten der Auswahllisten (langes Drücken)
    val favoriteMarkets: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.MARKET)
    val favoriteCoins: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.COIN)
    val favoriteQuotes: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.QUOTE)

    fun toggleFavorite(kind: FavoriteKind, item: String) = favoritesRepository.toggle(kind, item)

    // ---- DEX-Suche (nur für DexScreener)
    private val _dexResults = MutableStateFlow<List<DexPool>>(emptyList())
    private val _dexSearching = MutableStateFlow(false)
    private val _dexMessage = MutableStateFlow<DexMessage?>(null)
    val dexResults: StateFlow<List<DexPool>> = _dexResults.asStateFlow()
    val dexSearching: StateFlow<Boolean> = _dexSearching.asStateFlow()
    val dexMessage: StateFlow<DexMessage?> = _dexMessage.asStateFlow()
    private var dexSearchJob: Job? = null

    private var _currentMarketPairsInfo: StateFlow<MarketPairsInfo?> = _currentMarket
        .combine(_marketPairsUpdateTrigger){ market, _ -> market }
        .map { market ->
            if (market == null) null
            else marketRepository.getMarketCurrencyPairsInfo(market)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Gegenwährungen der Börse für «Alle …-Paare», häufigste zuerst. */
    private val _bulkQuotes: StateFlow<List<String>> = _currentMarketPairsInfo
        .map { it?.bulkQuoteCurrencies.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var _currentMarketCanUpdatePairs: StateFlow<Boolean> = _currentMarket
        .map { market ->
            if (market == null) false
            else marketRepository.isMarketSupportsUpdatePairs(market)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var _syncPairsJob: Job? = null

    private var _baseAssets: StateFlow<List<String>> = _currentMarketPairsInfo
        .map { pairsInfo -> pairsInfo?.baseCurrencies?.toList() ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var _quoteAssets: StateFlow<List<String>> = _currentMarketPairsInfo
        .combine(_currentBaseAsset) { pairsInfo, baseAsset ->
            if (baseAsset == null || pairsInfo == null)
                emptyList()
            else
                pairsInfo.getQuoteCurrencies(baseAsset).toList()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var _contractTypes: StateFlow<List<FuturesContractType>> =
        combine(
            _currentMarketPairsInfo,
            _currentBaseAsset,
            _currentQuoteAsset,
        )
         { pairsInfo, baseCurrency, quoteCurrency ->
            if (baseCurrency == null || quoteCurrency == null || pairsInfo == null)
                emptyList()
            else
                pairsInfo.getAvailableFuturesContractsTypes(baseCurrency, quoteCurrency)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var _currentMarketPair: StateFlow<CurrencyPairInfo?> =
        combine(
            _currentMarketPairsInfo,
            _currentBaseAsset,
            _currentQuoteAsset,
            _currentContractType
        ) {
            pairs, baseCurrency, quoteCurrency, futuresContractType ->
            if(pairs != null && baseCurrency != null && quoteCurrency != null )
                pairs.getCurrencyPairInfo(baseCurrency, quoteCurrency, futuresContractType ?: FuturesContractType.NONE)
            else
                null
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Muss vor dem init-Block stehen: Kotlin führt Initialisierer in
    // Deklarationsreihenfolge aus, und der Sammler unten schreibt sofort
    // hinein, sobald der Logger schon eine Nachricht gepuffert hat.
    private val _httpLogText = MutableStateFlow("")
    val httpLogText = _httpLogText.asStateFlow()

    init {
        viewModelScope.launch {
            _marketPairsUpdateTrigger.emit(Unit)
            _reloadMarketsTrigger.emit(Unit)

            launch {
                _baseAssets.collectLatest { newBaseAssets ->
                    if( _currentBaseAsset.value == null || !newBaseAssets.contains(_currentBaseAsset.value)) {
                        _currentBaseAsset.value = newBaseAssets.firstOrNull()
                    }
                }
            }

            launch {
                _quoteAssets.collectLatest { newQuoteAssets ->
                    if( _currentQuoteAsset.value == null || !newQuoteAssets.contains(_currentQuoteAsset.value)) {
                        _currentQuoteAsset.value = newQuoteAssets.firstOrNull()
                    }
                }
            }

            launch {
                // Neue Börse oder neu synchronisiert: USDT vorwählen, sonst die häufigste.
                _currentMarketPairsInfo.collectLatest { info ->
                    val quotes = info?.bulkQuoteCurrencies.orEmpty()
                    if (_bulkQuote.value == null || _bulkQuote.value !in quotes) {
                        _bulkQuote.value = info?.defaultBulkQuote
                    }
                }
            }

            launch {
                _contractTypes.collectLatest { contractTypes ->
                    if( _currentContractType.value == null || !contractTypes.contains(_currentContractType.value)) {
                        _currentContractType.value = contractTypes.firstOrNull()
                    }
                }
            }

            launch {
                _currentMarketPair.collectLatest { pair ->
                    _marketTicker.value = null
                    // Kurs gleich laden, sobald ein Paar gewählt ist (kurz entprellt,
                    // weil Coin und Gegenwährung oft kurz nacheinander wechseln).
                    val market = _currentMarket.value
                    if (pair != null && market != null && market.key != DEX_KEY) {
                        delay(300)
                        _marketTicker.value = try {
                            marketRepository.getMarketTicker(market, pair)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            MarketTickerResult(
                                com.cryptochecker.app.data.TickerImpl(),
                                com.cryptochecker.marketdata.model.CheckerInfo(
                                    pair.currencyBase, pair.currencyCounter, pair.currencyPairId, pair.contractType
                                ),
                                e.message ?: e.javaClass.simpleName
                            )
                        }
                    }
                }
            }

            // Handle HTTP log messages
            launch {
                val lineMaxLength = 400
                val messageCountLimit = 10

                val messageList = mutableListOf<String>()
                httpLogger.messageFlow.collect {
                    if(messageList.size == messageCountLimit){
                        messageList.removeAt(0)
                    }

                    fun formatLogLine(line: String): String{
                        if(line.length <= lineMaxLength)
                            return line
                        return line.take(lineMaxLength) + "..."
                    }

                    messageList.add(formatLogLine(it))
                    _httpLogText.value = messageList.joinToString("\n")
                }
            }
        }
    }

    val uiState: StateFlow<ExplorerUiState> = _reloadMarketsTrigger
        .map {
            val marketList =
                try {
                    marketRepository.getMarketList().sortedBy { x -> x.name.lowercase() }
                } catch (e: Exception) {
                    return@map ExplorerUiState.Error(e)
                }

            // Keine Börse vorwählen: Der Nutzer wählt bewusst, erst dann
            // wird «Sync» aktiv. Eine verschwundene Börse wird abgewählt.
            _currentMarket.value?.also {
                if (!marketList.contains(it)) _currentMarket.value = null
            }

            ExplorerUiState.Success(
                ExplorerViewState(
                    markets = marketList,
                    currentMarket = _currentMarket.asStateFlow(),
                    canUpdatePairs = _currentMarketCanUpdatePairs,

                    currentMarketPairsInfo = _currentMarketPairsInfo,
                    marketPairsUpdateState = _marketPairsUpdateState.asStateFlow(),

                    currentBaseAsset = _currentBaseAsset.asStateFlow(),
                    currentQuoteAsset = _currentQuoteAsset.asStateFlow(),
                    currentContractType = _currentContractType.asStateFlow(),

                    baseAssets = _baseAssets,
                    quoteAssets = _quoteAssets,
                    contractTypes = _contractTypes,

                    marketTicker = _marketTicker.asStateFlow(),
                    bulkPairs = _bulkPairs.asStateFlow(),
                    bulkPairsMessage = _bulkPairsMessage.asStateFlow(),
                    addToWatchlistState = _addToWatchlistState.asStateFlow(),
                    bulkAddState = _bulkAddState.asStateFlow(),
                    bulkQuotes = _bulkQuotes,
                    bulkQuote = _bulkQuote.asStateFlow(),
                )
            )
        }
//        .catch { e ->
//            emit(UiState.Error(e))
//        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            //started = SharingStarted.Eagerly,
            initialValue = ExplorerUiState.Loading
        )

    fun getTicker(market: MarketInfo) {
        _currentMarketPair.value?.also {
            viewModelScope.launch {
                _marketTicker.value = null
                _marketTicker.value = try {
                    marketRepository.getMarketTicker(market, it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Fehler anzeigen statt endlos «Kurs wird geladen…»
                    MarketTickerResult(
                        com.cryptochecker.app.data.TickerImpl(),
                        com.cryptochecker.marketdata.model.CheckerInfo(
                            it.currencyBase, it.currencyCounter, it.currencyPairId, it.contractType
                        ),
                        e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun setCurrentMarket(market: MarketInfo?) {
        if (_currentMarket.value != market) clearSelectionResults()
        _currentMarket.value = market
        if (market != null) autoLoadPairs(market)
    }

    /**
     * Noch keine Paare gespeichert? Dann gleich laden — «Sync» ist für den
     * Nutzer kein eigener Schritt, sondern einfach «Paare werden geladen…».
     */
    private fun autoLoadPairs(market: MarketInfo) {
        viewModelScope.launch {
            val empty = runCatching { marketRepository.getMarketCurrencyPairsInfo(market).pairs.isEmpty() }
                .getOrDefault(false)
            if (empty && marketRepository.isMarketSupportsUpdatePairs(market) &&
                _currentMarket.value == market && !_marketPairsUpdateState.value.isInProgress
            ) {
                syncCurrencyPairs()
            }
        }
    }

    fun setBulkQuote(quote: String) {
        if (_bulkQuote.value != quote) {
            // Die Sammelliste gilt nur für die Gegenwährung, für die sie erstellt wurde.
            _bulkPairs.value = emptyList()
            _bulkPairsMessage.value = null
            _bulkAddState.value = null
        }
        _bulkQuote.value = quote
    }

    fun setCurrentBaseAsset(asset: String?) {
        _currentBaseAsset.value = asset
    }

    fun setCurrentQuoteAsset(asset: String?) {
        _currentQuoteAsset.value = asset
    }

    fun setCurrentContractType(contractType: FuturesContractType?) {
        if (_currentContractType.value != contractType) {
            // Die Sammelliste gilt nur für den Kontrakttyp, für den sie erstellt wurde.
            _bulkPairs.value = emptyList()
            _bulkPairsMessage.value = null
        }
        _currentContractType.value = contractType
    }

    /**
     * Nach einem Börsenwechsel gehören Sammelliste, Kurs und Meldungen zur
     * alten Börse. Bleiben sie stehen, würde „Alle … Paare hinzufügen“
     * fremde Paare unter der neuen Börse in die Watchlist schreiben.
     */
    private fun clearSelectionResults() {
        _bulkPairs.value = emptyList()
        _bulkPairsMessage.value = null
        _bulkAddState.value = null
        _addToWatchlistState.value = null
        _marketTicker.value = null
        _dexResults.value = emptyList()
        _dexMessage.value = null
    }

    @Synchronized
    fun syncCurrencyPairs() {
        _syncPairsJob?.cancel()
        _syncPairsJob = null

        _currentMarket.value?.also { market ->
            _marketPairsUpdateState.value = MarketPairsUpdateState(true)
            _syncPairsJob = viewModelScope.launch {
                Timber.d("Start market sync: ${market.key}")
                try {
                    marketRepository.updateMarketCurrencyPairs(market)
                    runCatching { pairsCache[market.key] = marketRepository.getMarketCurrencyPairsInfo(market) }
                    _marketPairsUpdateTrigger.emit(Unit)
                    _marketPairsUpdateState.value = MarketPairsUpdateState()
                } catch (ex: MarketError) {
                    _marketPairsUpdateState.value = MarketPairsUpdateState(error = ex.message)
                }
            }.also {
                it.invokeOnCompletion { exception: Throwable? ->
                     Timber.d(exception, "Market sync completed: %s", market.key)
                }
            }
        }
    }

    fun clearCurrencyPairsUpdateError() {
        _syncPairsJob?.cancel()
        _syncPairsJob = null

        _marketPairsUpdateState.value = MarketPairsUpdateState(false)
    }

    /**
     * Sammelt alle Paare der gewählten Gegenwährung (Standard USDT) der
     * Börse zum aktuellen Kontrakttyp — für Spot wie für Futures.
     */
    fun applyAllPairsOfMarket() {
        val pairsInfo = _currentMarketPairsInfo.value
        val contractType = _currentContractType.value
        val quote = _bulkQuote.value

        val filtered = if (pairsInfo == null || quote == null) emptyList()
            else pairsInfo.pairsWithQuote(quote, contractType)
        _bulkPairs.value = filtered

        if (filtered.isEmpty()) {
            _bulkPairsMessage.value = "empty"
            return
        }

        _bulkPairsMessage.value = filtered.size.toString()
        filtered.firstOrNull()?.let {
            _currentQuoteAsset.value = it.currencyCounter
            _currentBaseAsset.value = it.currencyBase
        }
    }

    fun selectBulkPair(pair: CurrencyPairInfo) {
        _currentBaseAsset.value = pair.currencyBase
        _currentQuoteAsset.value = pair.currencyCounter
        _currentContractType.value = pair.contractType
    }

    /** Übernimmt das gerade gewählte Paar in die Watchlist. */
    fun addCurrentPairToWatchlist() {
        val market = _currentMarket.value
        val pair = _currentMarketPair.value

        if (market == null || pair == null) {
            _addToWatchlistState.value = AddToWatchlistState.NO_PAIR_SELECTED
            return
        }

        viewModelScope.launch {
            val id = watchRepository.addWatch(market, pair, sessionTargetGroup.value)
            _addToWatchlistState.value =
                if (id == null) AddToWatchlistState.ALREADY_IN_LIST
                else AddToWatchlistState.ADDED
            if (id != null) addMoments.afterExplorerAdd(id)
        }
    }

    /**
     * Übernimmt alle gefundenen USDT-Perpetuals in die Watchlist.
     * Paare, die bereits darin stehen, werden übersprungen — samt ihrer Alarme,
     * denn die hängen am bestehenden Eintrag und bleiben unberührt.
     */
    fun addAllPairsToWatchlist() {
        val market = _currentMarket.value
        val pairs = _bulkPairs.value

        if (market == null || pairs.isEmpty()) {
            _addToWatchlistState.value = AddToWatchlistState.NO_PAIR_SELECTED
            return
        }

        if (_bulkAddState.value?.running == true) return

        viewModelScope.launch {
            _bulkAddState.value = BulkAddState(added = 0, skipped = 0, running = true)
            val result = watchRepository.addWatches(market, pairs, sessionTargetGroup.value)
            _bulkAddState.value = BulkAddState(added = result.added, skipped = result.skipped)
            // Viele Paare auf einmal: kein Erst-Moment, aber das erste Paar ist erledigt
            if (result.added > 0) addMoments.markFirstPairAdded()
        }
    }

    /** Sucht Pools zu Token-Name, Symbol oder Adresse. */
    fun searchDex(query: String) {
        if (query.isBlank()) return
        dexSearchJob?.cancel()
        dexSearchJob = viewModelScope.launch {
            _dexSearching.value = true
            _dexMessage.value = null
            val result = runCatching { marketRepository.searchDexPools(query) }
            _dexSearching.value = false
            result.onSuccess { pools ->
                _dexResults.value = pools.distinctBy { it.pairId }.take(MAX_DEX_RESULTS)
                if (pools.isEmpty()) _dexMessage.value = DexMessage.NoResults
            }.onFailure {
                Timber.w(it, "DEX-Suche fehlgeschlagen")
                _dexResults.value = emptyList()
                _dexMessage.value = DexMessage.Failed
            }
        }
    }

    /** Übernimmt einen Pool in die Watchlist. */
    fun addDexPool(pool: DexPool) {
        val market = _currentMarket.value ?: return
        viewModelScope.launch {
            val id = watchRepository.addWatch(
                market,
                CurrencyPairInfo(pool.baseSymbol, pool.watchQuote, pool.pairId),
                sessionTargetGroup.value
            )
            _dexMessage.value =
                if (id == null) DexMessage.AlreadyInList(pool.baseSymbol)
                else DexMessage.Added(pool.baseSymbol)
            if (id != null) addMoments.afterExplorerAdd(id)
        }
    }

    fun clearDexMessage() {
        _dexMessage.value = null
    }

    fun clearBulkAddState() {
        _bulkAddState.value = null
    }

    fun clearAddToWatchlistState() {
        _addToWatchlistState.value = null
    }

    fun retryLoadMarketList() {
        viewModelScope.launch {
            _reloadMarketsTrigger.emit(Unit)
        }
    }
}

private const val MAX_DEX_RESULTS = 25

/**
 * Zuletzt gewählte Ziel-Gruppe beim Hinzufügen — bewusst nur im Speicher:
 * gilt für die laufende Sitzung, beim nächsten Start wieder «Keine Gruppe».
 */
private val sessionTargetGroup = MutableStateFlow<String?>(null)

/** Rückmeldung der DEX-Suche. */
sealed interface DexMessage {
    data object NoResults : DexMessage
    data object Failed : DexMessage
    data class Added(val symbol: String) : DexMessage
    data class AlreadyInList(val symbol: String) : DexMessage
}

sealed interface ExplorerUiState {
    object Loading : ExplorerUiState

    data class Success(val data: ExplorerViewState) : ExplorerUiState

    data class Error(val exception: Throwable) : ExplorerUiState
}

private const val DEX_KEY = "DexScreener"
