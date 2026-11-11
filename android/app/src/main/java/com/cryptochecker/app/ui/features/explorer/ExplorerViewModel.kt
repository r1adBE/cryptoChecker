package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.domain.settings.AssetScope
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.settings.scoped
import com.cryptochecker.app.ui.features.watchlist.groupsOf
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.DexPool
import com.cryptochecker.app.domain.util.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Zustand der Seite «Paar hinzufügen»: Coin-Suche (Coin zuerst, dann seine Paare über alle Börsen), Markt-Chips,
 * die Börsen und darin die Paar-Auswahl (Börse → Coin → Gegenwährung), «Alle …-Paare», DEX-Suche und das
 * Übernehmen in die Merkliste.
 *
 * Datenschutz der Suche: Sie läuft nur auf dem Gerät — gespeicherte Paarlisten und das Namensverzeichnis.
 * Fehlt die Liste einer Börse, wird sie nicht still geholt; die Seite bietet an, die vollständigen
 * öffentlichen Paarlisten zu laden ([loadMissingLists]) — dieselbe Antwort für alle, nie etwas zu einem Coin.
 *
 * Achtung Reihenfolge: Mehrere Flows starten sofort (`SharingStarted.Eagerly`) und lesen dabei
 * andere Felder — jedes abgeleitete Feld steht deshalb nach seinen Quellen, das Protokoll vor `init`.
 */
@HiltViewModel
class ExplorerViewModel @Inject constructor(
    internal val exchangeRepository: ExchangeRepository,
    internal val watchRepository: WatchRepository,
    private val favoritesRepository: FavoritesRepository,
    /** HTTP-Mitschnitt der Entwickleroption ([ExplorerHttpLog]). */
    private val httpLog: ExplorerHttpLog,
    private val settingsRepository: SettingsRepository,
    /** Allererstes Paar: ruhiger Erst-Moment in der Merkliste. */
    internal val addMoments: AddMoments,
    /** «Zuletzt gesucht», nur auf dem Gerät. */
    internal val recentSearchStore: RecentSearchStore,
    /** Gespeicherte Coin-Namen für die Suche über den Namen (kein eigener Abruf). */
    internal val coinLogoRepository: CoinLogoRepository,
    /** Coin-Namen je Börse (gespeichert): klar anderer Name als in der Alpha-Liste → kein «α». */
    internal val tokenIdentityStore: TokenIdentityStore,
    /** Start-Börse der Region und Volumen-Ranking der Börsen ([ExplorerExchangeOrder]). */
    internal val exchangeOrder: ExplorerExchangeOrder,
    /** Liquidität und Token-Adresse der DEX-Pools aus der Suche ([ExplorerDexStores]). */
    internal val dexStores: ExplorerDexStores,
    /** «Mehrere Paare auf einmal» app-weit (läuft nach Verlassen des Bildschirms weiter). */
    internal val bulkAddRunner: com.cryptochecker.app.data.BulkAddRunner,
    /** Paarliste der gewählten Börse nicht auf dem Main-Thread filtern. */
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // =============================================================================================
    // Grundzustand (einfache Felder, vor allem Abgeleiteten)
    // =============================================================================================

    internal val exchange = MutableStateFlow<ExchangeInfo?>(null)
    internal val coin = MutableStateFlow<String?>(null)
    internal val quote = MutableStateFlow<String?>(null)
    internal val contract = MutableStateFlow<FuturesContractType?>(null)
    internal val price = MutableStateFlow<ExchangeTickerResult?>(null)

    /** Neu auslösen = Paarliste der Börse neu lesen (nach einem Abgleich). */
    internal val pairListChanged = MutableSharedFlow<Unit>(replay = 1)
    internal val pairListSync = MutableStateFlow(PairListSync())
    internal var pairListSyncJob: Job? = null

    /** Neu auslösen = Börsenliste neu laden («Erneut» nach einem Fehler). */
    private val reloadExchanges = MutableSharedFlow<Unit>(replay = 1)

    /** «Alle …-Paare»: Liste, Gegenwährung, Kennzahlen und Stand der Aufnahme ([ExplorerBulkState]). */
    internal val bulk = ExplorerBulkState()
    internal val bulkAddDisplay = BulkAddDisplay(bulkAddRunner, bulk.add)
    internal val addResult = MutableStateFlow<AddResult?>(null)

    /** Suche über alle Börsen (nur gespeicherte Listen): Paarlisten, Namensverzeichnis und laufende Arbeit ([ExplorerSearchData]). */
    internal val searchData = ExplorerSearchData()
    internal val query = MutableStateFlow("")
    /** Treffer der Suche und der gewählte Coin ([ExplorerSearchHits]). */
    internal val hits = ExplorerSearchHits()

    /** DEX-Suche (nur DexScreener, [ExplorerDexState]). */
    internal val dex = ExplorerDexState()

    // =============================================================================================
    // Einstellungen
    // =============================================================================================

    /**
     * «Token & TradFi» (Einstellungen › Merkliste): welche Börsen und Paare Auswahl, Suche und
     * «Alle …-Paare» zeigen. Die Listen im Speicher bleiben vollständig; gefiltert wird erst beim
     * Anzeigen, damit ein Wechsel sofort wirkt.
     */
    internal val assetScope: StateFlow<AssetScope> = settingsRepository.settings
        .map { it.assetScope }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.assetScope)

    /**
     * «inkl. Alpha-Tokens» aus: Kürzel der Alpha-Tokens, die Auswahl und Suche verbergen (aus der
     * gespeicherten Alpha-Liste des Logo-Abgleichs); an → leer.
     */
    internal val hiddenAlpha: StateFlow<Set<String>> = settingsRepository.settings
        .map { it.includeAlphaTokens }
        .distinctUntilChanged()
        .combine(coinLogoRepository.alphaSymbols) { include, symbols -> AssetScope.hiddenAlphaSymbols(include, symbols) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AssetScope.hiddenAlphaSymbols(settingsRepository.cached.includeAlphaTokens, coinLogoRepository.alphaSymbols.value),
        )

    /** Umrechnungswährung (Einstellungen): Gegenwährung, die der Paar-Vorschlag zuerst nimmt. */
    internal val displayCurrency: StateFlow<String> = settingsRepository.settings
        .map { it.portfolioCurrency }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioCurrency)

    // =============================================================================================
    // Öffentliche Flows
    // =============================================================================================

    val searchQuery: StateFlow<String> = query.asStateFlow()

    /** Coins zum Suchbegriff (Coin zuerst). */
    val coinHits: StateFlow<List<CoinHit>> = hits.coins.asStateFlow()

    /** Einzelne Paare zum Suchbegriff — gezeigt, wenn [showPairHits] (z. B. «eth usdt», «BTCUSDT»). */
    val pairHits: StateFlow<List<SearchHit>> = hits.pairs.asStateFlow()
    val showPairHits: StateFlow<Boolean> = hits.pairsShown.asStateFlow()

    /** Angetippter Coin und seine Paare über alle geladenen Listen, der Vorschlag zuerst. */
    val pickedCoin: StateFlow<CoinHit?> = hits.chosenCoin.asStateFlow()
    val pickedCoinPairs: StateFlow<List<SearchHit>> = hits.chosenCoinPairs.asStateFlow()

    /** Stand der Paarlisten für die Suche (fehlende, Laden). */
    val searchLists: StateFlow<SearchLists> = searchData.lists.asStateFlow()

    /** Markt-Chip über Börsen und Treffern ([setMarketFilter]); «Alle» zu Beginn. */
    val marketFilter: StateFlow<MarketFilter> = hits.market.asStateFlow()


    /** «Zuletzt genutzte Börsen» (Schlüssel, neueste zuerst), nur auf dem Gerät. */
    val recentExchanges: StateFlow<List<String>> = recentSearchStore.exchanges

    /** «Zuletzt gesucht» (höchstens 8, neueste zuerst) als Chips unter dem leeren Suchfeld. */
    val recentSearches: StateFlow<List<String>> = recentSearchStore.items

    /** Gruppen der Merkliste, für «In Gruppe». */
    val groups: StateFlow<List<String>> = watchRepository.observeWatches()
        .map { groupsOf(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Schon beobachtete Paare (Schlüssel: [watchKey]) für das Häkchen bei den Treffern. */
    val watchedKeys: StateFlow<Set<String>> = watchRepository.observeWatches()
        .map { watches -> watches.mapTo(LinkedHashSet<String>()) { watchKey(it.exchangeKey, it.baseAsset, it.quoteAsset, it.contractType.name) } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Gruppe für neue Paare; null = «Keine Gruppe». Gilt bis die App beendet wird. */
    val targetGroup: StateFlow<String?> = sessionTargetGroup.asStateFlow()

    /** Entwickleroption: HTTP-Protokoll unten zeigen (nur Debug-Build; Release schneidet nichts mit). */
    val showHttpLog: StateFlow<Boolean> = settingsRepository.settings
        .map { BuildConfig.DEBUG && it.showHttpLog && it.developerUnlocked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Favoriten der Auswahllisten (langes Drücken)
    val favoriteExchanges: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.EXCHANGE)
    val favoriteCoins: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.COIN)
    val favoriteQuotes: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.QUOTE)

    val dexResults: StateFlow<List<DexPool>> = dex.pools.asStateFlow()
    val dexSearching: StateFlow<Boolean> = dex.busy.asStateFlow()
    val dexMessage: StateFlow<DexMessage?> = dex.notice.asStateFlow()
    val dexPrefill: StateFlow<DexPrefill?> = dex.prefill.asStateFlow()

    // =============================================================================================
    // Abgeleitete Auswahl (Reihenfolge = Abhängigkeiten)
    // =============================================================================================

    /**
     * Paarliste der gewählten Börse, nur mit dem, was «Token & TradFi» zeigt; bei Binance samt
     * Alpha-Paaren. Neue Alpha-Liste oder Schalter «inkl. Alpha-Tokens» wirken sofort. Unter dem Chip «Devisen» zeigt
     * eine Börse der Gruppe «Live (Börse)» nur ihre Fiat-Paare ([ForexLive]).
     */
    internal val pairList: StateFlow<ExchangePairsInfo?> = exchange
        .combine(pairListChanged) { selected, _ -> selected }
        .combine(assetScope) { selected, scope -> selected to scope }
        .combine(hiddenAlpha) { (selected, scope), hidden -> Triple(selected, scope, hidden) }
        .combine(coinLogoRepository.alphaTokens) { choice, _ -> choice }
        .combine(coinLogoRepository.alphaNames) { choice, alphaNames -> choice to alphaNames }
        .combine(hits.market) { choice, market -> choice to market }
        .map { (named, market) ->
            val (choice, alphaNames) = named
            val (selected, scope, hidden) = choice
            selected?.let {
                val info = exchangeRepository.getExchangeCurrencyPairsInfo(it, withAlpha = true)
                    .scoped(scope, it.key, hidden, tokenIdentityStore.exchangeNames(it.key), alphaNames)
                if (market != MarketFilter.FOREX || !ForexLive.isLive(it.key)) info
                else info.copy(pairs = info.pairs.filter { p -> !p.tradFi && ForexLive.isFiatPair(p.base, p.quote) })
            }
        }
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Gegenwährungen für «Alle …-Paare», häufigste zuerst. */
    internal val bulkQuotes: StateFlow<List<String>> = pairList
        .map { it?.bulkQuoteCurrencies.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val canReloadPairs: StateFlow<Boolean> = exchange
        .map { selected -> selected != null && exchangeRepository.isExchangeSupportsUpdatePairs(selected) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    internal val coins: StateFlow<List<String>> = pairList
        .map { it?.baseCurrencies?.toList().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val quotes: StateFlow<List<String>> = pairList
        .combine(coin) { info, base ->
            if (info == null || base == null) emptyList() else info.getQuoteCurrencies(base).toList()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val contracts: StateFlow<List<FuturesContractType>> =
        combine(pairList, coin, quote) { info, base, counter ->
            if (info == null || base == null || counter == null) emptyList()
            else info.getAvailableFuturesContractsTypes(base, counter)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Das vollständig gewählte Paar (ohne Kontrakt = Spot), sonst null. */
    internal val selectedPair: StateFlow<ExchangePair?> =
        combine(pairList, coin, quote, contract) { info, base, counter, type ->
            if (info == null || base == null || counter == null) null
            else info.getExchangePair(base, counter, type ?: FuturesContractType.NONE)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val httpLogText: StateFlow<String> = httpLog.text

    init {
        viewModelScope.launch {
            pairListChanged.emit(Unit)
            reloadExchanges.emit(Unit)

            refilterOnScopeChange()
            keepChoiceValid(coins, coin)
            keepChoiceValid(quotes, quote)
            keepBulkQuoteValid()
            keepChoiceValid(contracts, contract)
            loadPriceOnSelection()
            collectHttpLog()
        }
        viewModelScope.launch { collectBulkRunner() }
        // Volumen-Ranking einmal nach der Installation (höchstens ein Versuch je Sitzung)
        viewModelScope.launch { exchangeOrder.ranking.ensureLoaded() }
    }

    val pageState: StateFlow<ExplorerLoad> = reloadExchanges
        .combine(assetScope) { _, scope -> scope }
        .combine(exchangeOrder.ranking.volumes) { scope, volumes -> scope to volumes }
        .map { (scope, volumes) ->
            // Feste Börsenliste (ExchangesConfig), ohne Netz — kann nicht scheitern. «Nur TradFi»: nur Börsen mit
            // TradFi-Kontrakten
            val alphabetical = exchangeRepository.getExchangeList()
                .filter { scope.allowsExchange(it.key) }
                .sortedBy { it.name.lowercase() }
            val (exchangeList, categories) = exchangeSections(alphabetical, volumes)

            // Keine Börse vorwählen (erst die bewusste Wahl macht «Sync» aktiv); eine
            // verschwundene Börse wird abgewählt
            val selected = exchange.value
            if (selected != null && selected !in exchangeList) exchange.value = null

            ExplorerLoad.Ready(buildSelection(exchangeList, categories))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExplorerLoad.Loading)

    // =============================================================================================
    // Sammler aus init
    // =============================================================================================

    /** «inkl. Alpha-Tokens», «Token & TradFi» oder neue Alpha-Liste: Treffer gleich neu filtern. */
    private fun CoroutineScope.refilterOnScopeChange() = launch {
        combine(hiddenAlpha, assetScope, coinLogoRepository.alphaTokens) { _, _, _ -> }
            .drop(1)
            .collect {
                refreshBinancePairs()
                if (query.value.isNotBlank()) runSearch()
            }
    }

    /** Fällt die Wahl aus der Liste (oder fehlt sie), gilt der erste Eintrag. */
    private fun <T> CoroutineScope.keepChoiceValid(options: StateFlow<List<T>>, choice: MutableStateFlow<T?>) = launch {
        options.collectLatest { list ->
            val current = choice.value
            if (current == null || current !in list) choice.value = list.firstOrNull()
        }
    }

    /** Neue Börse oder neu abgeglichen: USDT vorwählen, sonst die häufigste Gegenwährung. */
    private fun CoroutineScope.keepBulkQuoteValid() = launch {
        pairList.collectLatest { info ->
            val current = bulk.quote.value
            if (current == null || current !in info?.bulkQuoteCurrencies.orEmpty()) {
                bulk.quote.value = info?.defaultBulkQuote
            }
        }
    }

    /**
     * Kurs laden, sobald ein Paar gewählt ist — kurz entprellt, weil Coin und Gegenwährung oft
     * unmittelbar nacheinander wechseln.
     */
    private fun CoroutineScope.loadPriceOnSelection() = launch {
        selectedPair.collectLatest { pair ->
            price.value = null
            val selected = exchange.value
            if (pair == null || selected == null || selected.key == DEX_KEY) return@collectLatest
            delay(PRICE_DEBOUNCE_MS)
            price.value = priceOrError(selected, pair)
        }
    }

    /** Die letzten Zeilen des HTTP-Mitschnitts ([ExplorerHttpLog.collect]). */
    private fun CoroutineScope.collectHttpLog() = launch { httpLog.collect() }

    // =============================================================================================
    // Ziel-Gruppe, Favoriten, Meldungen (Suche, Auswahl, Sammelaufnahme, DEX: Explorer…Actions.kt)
    // =============================================================================================

    fun setTargetGroup(group: String?) {
        sessionTargetGroup.value = group?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun toggleFavorite(kind: FavoriteKind, item: String) = favoritesRepository.toggle(kind, item)

    fun clearAddResult() {
        addResult.value = null
    }
}

private const val PRICE_DEBOUNCE_MS = 300L
