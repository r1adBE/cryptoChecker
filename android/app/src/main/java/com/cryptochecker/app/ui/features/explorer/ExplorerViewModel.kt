package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.settings.scoped
import com.cryptochecker.app.ui.features.watchlist.groupsOf
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.DexPool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Zustand der Seite «Paar hinzufügen»: Coin-Suche (Coin zuerst, dann seine Paare über alle Börsen), die
 * Auswahl «Erweitert / nach Börse» (Börse → Coin → Gegenwährung), «Alle …-Paare», DEX-Suche und das
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
    private val httpLogger: HttpLogger,
    private val settingsRepository: SettingsRepository,
    /** Allererstes Paar: ruhiger Erst-Moment in der Merkliste. */
    internal val addMoments: AddMoments,
    /** «Zuletzt gesucht», nur auf dem Gerät. */
    internal val recentSearchStore: RecentSearchStore,
    /** Gespeicherte Coin-Namen für die Suche über den Namen (kein eigener Abruf). */
    internal val coinLogoRepository: CoinLogoRepository,
    /** Coin-Namen je Börse (gespeichert): klar anderer Name als in der Alpha-Liste → kein «α». */
    internal val tokenIdentityStore: TokenIdentityStore,
    /** Start-Börse der Region (wie die Start-Merkliste) für den Paar-Vorschlag der Coin-Suche. */
    internal val starterCoinsRepository: StarterCoinsRepository,
    /** Liquidität der DEX-Pools aus der Suche: unter der Mindestmenge kein Alarm ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
    internal val dexLiquidityStore: com.cryptochecker.app.data.DexLiquidityStore,
    /** Token-Adresse der DEX-Pools aus der Suche, fürs Paar-Blatt. */
    internal val dexTokenAddressStore: com.cryptochecker.app.data.DexTokenAddressStore,
    /** «Mehrere Paare auf einmal» app-weit (läuft nach Verlassen des Bildschirms weiter). */
    internal val bulkAddRunner: com.cryptochecker.app.data.BulkAddRunner,
    /** Volumen der Börsen am Tag der Installation: Reihenfolge der Börsenauswahl ([ExchangeOrder]). */
    internal val exchangeRanking: com.cryptochecker.app.data.ExchangeRankingStore,
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

    // Suche über alle Börsen (nur gespeicherte Listen)
    internal val pairsByExchange = ConcurrentHashMap<String, ExchangePairsInfo>()
    internal var searchableExchanges: List<ExchangeInfo> = emptyList()
    /** Börsen, deren Liste noch nicht auf dem Gerät liegt, die sie aber laden können. */
    internal var unloadedExchanges: List<ExchangeInfo> = emptyList()
    internal var storedListsJob: Job? = null
    internal var missingListsJob: Job? = null
    internal var searchJob: Job? = null
    internal val query = MutableStateFlow("")
    /** Treffer der Suche und der gewählte Coin ([ExplorerSearchHits]). */
    internal val hits = ExplorerSearchHits()
    internal val lists = MutableStateFlow(SearchLists())

    /** Gefaltete Coin-Namen («bitcoin» → BTC), einmal je Namensliste gebaut. */
    @Volatile internal var names: NameIndex = NameIndex.EMPTY
    internal var namesJob: Job? = null

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
    val searchLists: StateFlow<SearchLists> = lists.asStateFlow()

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
     * Alpha-Paaren. Neue Alpha-Liste oder Schalter «inkl. Alpha-Tokens» wirken sofort.
     */
    internal val pairList: StateFlow<ExchangePairsInfo?> = exchange
        .combine(pairListChanged) { selected, _ -> selected }
        .combine(assetScope) { selected, scope -> selected to scope }
        .combine(hiddenAlpha) { (selected, scope), hidden -> Triple(selected, scope, hidden) }
        .combine(coinLogoRepository.alphaTokens) { choice, _ -> choice }
        .combine(coinLogoRepository.alphaNames) { choice, alphaNames -> choice to alphaNames }
        .map { (choice, alphaNames) ->
            val (selected, scope, hidden) = choice
            selected?.let {
                exchangeRepository.getExchangeCurrencyPairsInfo(it, withAlpha = true)
                    .scoped(scope, it.key, hidden, tokenIdentityStore.exchangeNames(it.key), alphaNames)
            }
        }
        .flowOn(Dispatchers.Default)
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

    // Vor init: Der Sammler schreibt sofort, wenn der Logger schon eine Zeile gepuffert hat
    private val logText = MutableStateFlow("")
    val httpLogText = logText.asStateFlow()

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
        viewModelScope.launch { exchangeRanking.ensureLoaded() }
    }

    val pageState: StateFlow<ExplorerLoad> = reloadExchanges
        .combine(assetScope) { _, scope -> scope }
        .combine(exchangeRanking.volumes) { scope, volumes -> scope to volumes }
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

    /** Die letzten Zeilen des HTTP-Mitschnitts, überlange gekürzt. */
    private fun CoroutineScope.collectHttpLog() = launch {
        val recent = ArrayDeque<String>(LOG_MAX_LINES)
        httpLogger.messageFlow.collect { line ->
            if (recent.size == LOG_MAX_LINES) recent.removeFirst()
            recent.addLast(if (line.length > LOG_MAX_LINE_LENGTH) line.take(LOG_MAX_LINE_LENGTH) + "..." else line)
            logText.value = recent.joinToString("\n")
        }
    }

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

private const val LOG_MAX_LINES = 10

private const val LOG_MAX_LINE_LENGTH = 400
