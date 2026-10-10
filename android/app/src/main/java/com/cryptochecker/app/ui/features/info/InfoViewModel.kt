package com.cryptochecker.app.ui.features.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.remote.CycleDataSource
import com.cryptochecker.app.data.remote.GasDataSource
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.MacroCalendarRepository
import com.cryptochecker.app.data.MarketExtraCodecs
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.UnusualDataSource
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.MarketUnusual
import com.cryptochecker.app.domain.market.UnusualReport
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.CoinCycleModel
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.Sourced
import com.cryptochecker.app.domain.market.OnChainValues
import com.cryptochecker.app.domain.market.CoinReport
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.app.domain.market.BitcoinCycle
import com.cryptochecker.app.domain.market.CycleInfo
import com.cryptochecker.app.domain.market.CycleModel
import com.cryptochecker.app.domain.market.CycleReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/** Ladezustand eines Bereichs im Markt-Tab. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data object Failed : LoadState<Nothing>
}

/**
 * Markt-Tab. Für die ganze Activity einmal da (siehe AppNavHost), damit beim Wechsel
 * der Tabs nichts neu geladen wird. Jeder Bereich zeigt sofort seinen Zwischenspeicher
 * (Gerät, [CycleCacheStore]) und lädt nur im Hintergrund nach, wenn dieser älter als
 * seine Gültigkeit ist ([CycleSource.ttlMillis]); neue Daten ersetzen die alten still.
 * Jeder Bereich lädt für sich mit harter Zeitgrenze — ein langsamer hält keinen anderen auf.
 */
@HiltViewModel
class InfoViewModel @Inject constructor(
    private val cycleDataSource: CycleDataSource,
    private val insights: InsightsDataSource,
    private val marketRepository: MarketRepository,
    private val favoritesRepository: FavoritesRepository,
    private val gasDataSource: GasDataSource,
    private val pulseDataSource: PulseDataSource,
    private val cacheStore: CycleCacheStore,
    private val settingsRepository: SettingsRepository,
    private val unusualDataSource: UnusualDataSource,
    private val macroCalendar: MacroCalendarRepository,
    watchRepository: WatchRepository,
    activityRepository: ActivityRepository,
    private val activityMonitor: ActivityMonitor,
) : ViewModel() {

    // ---- Zwischenspeicher: was gezeigt wird, von wann, was gerade lädt

    /** Zeitpunkt der angezeigten Daten je Bereich (Gerät oder frisch geholt). */
    private val shownAt = MutableStateFlow<Map<CycleSource, Long>>(emptyMap())

    /** Bereiche, die gerade im Hintergrund neu laden. */
    private val running = MutableStateFlow<Set<CycleSource>>(emptySet())

    private val _stamps = MutableStateFlow<Map<CycleSource, DataStamp>>(emptyMap())

    /**
     * Herkunft und Stand der gezeigten Daten je Bereich (Anbieter, Zeitpunkt, Gültigkeit) für
     * die Nebenzeilen von «Einordnung» und «Daten» («CoinGecko · vor 3 Min.»).
     */
    val stamps: StateFlow<Map<CycleSource, DataStamp>> = _stamps.asStateFlow()

    /** Welcher Eintrag (z. B. «coin_ETH») gerade angezeigt wird, mit seinem Zeitpunkt. */
    private val shownEntry = HashMap<CycleSource, Pair<String, Long>>()
    private val jobs = HashMap<CycleSource, Pair<String, Job>>()

    /**
     * «Stand … · wird aktualisiert …» unter dem Titel: ältester angezeigter Zeitpunkt
     * der Bereiche, die gerade neu laden; null = Zeile ausblenden.
     */
    val dataAsOf: StateFlow<Long?> = combine(shownAt, running) { at, run -> CycleCachePolicy.dataAsOf(at, run) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pullRefreshing = MutableStateFlow(false)

    /** Nach unten gezogen: Anzeige läuft, bis alle Bereiche fertig sind. */
    val pullRefreshing: StateFlow<Boolean> = _pullRefreshing.asStateFlow()

    // ---- Crypto Pulse (oben im Tab)
    private val _pulse = MutableStateFlow<LoadState<PulseReport>>(LoadState.Loading)
    val pulse: StateFlow<LoadState<PulseReport>> = _pulse.asStateFlow()

    /** @param force true = Zwischenspeicher (5 Min.) übergehen. Ohne Marktdaten: Failed. */
    fun loadPulse(force: Boolean = false): Job = sync(
        source = CycleSource.PULSE,
        force = force,
        codec = CycleCacheCodecs.pulseInput,
        // Ohne Marktdaten keine Auswertung: weder zeigen noch speichern
        isUsable = { CryptoPulse.evaluate(it) != null },
        timeOf = { it.time },
        show = { input -> CryptoPulse.evaluate(input)?.let { _pulse.value = LoadState.Loaded(it) } },
        loading = { _pulse.value = LoadState.Loading },
        failed = { _pulse.value = LoadState.Failed },
        fetch = { Sourced(pulseDataSource.fetchInput(), null) },
    )

    // ---- «Heute auffällig» (unter dem Pulse)
    private val _unusual = MutableStateFlow<LoadState<UnusualReport>>(LoadState.Loading)
    val unusual: StateFlow<LoadState<UnusualReport>> = _unusual.asStateFlow()

    /** @param force true = Zwischenspeicher (10 Min.) übergehen (Ziehen nach unten, «Erneut»). */
    fun loadUnusual(force: Boolean = false): Job = sync(
        source = CycleSource.UNUSUAL,
        force = force,
        codec = MarketExtraCodecs.unusualInput,
        isUsable = { MarketUnusual.evaluate(it) != null },
        timeOf = { it.time },
        show = { input -> MarketUnusual.evaluate(input)?.let { _unusual.value = LoadState.Loaded(it) } },
        loading = { _unusual.value = LoadState.Loading },
        failed = { _unusual.value = LoadState.Failed },
        fetch = { Sourced(unusualDataSource.fetchInput(), null) },
    )

    /** Paare der Merkliste: Tippen auf eine Zeile öffnet «Warum?» (beobachtet) oder die Suche. */
    val watches: StateFlow<List<WatchEntity>> = watchRepository.observeWatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Signale «Ungewöhnliche Aktivität» je Paar, für das «Warum»-Blatt. */
    val activity: StateFlow<Map<Long, ActivityReport>> = activityRepository.reports

    /** Daten für «Warum bewegt sich das?»; null, wenn gar nichts geladen werden konnte. */
    suspend fun explain(watch: WatchEntity): WhyReport? =
        runCatching { activityMonitor.explain(watch) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    /**
     * «Heute auffällig» → Coin nicht in der Merkliste: Vorschau-Paar «COIN/USDT» auf Binance Spot
     * — nur im Speicher ([PREVIEW_WATCH_ID]), nichts wird gespeichert, bis der Nutzer es hinzufügt.
     * null, wenn es Binance nicht (mehr) gibt oder die gespeicherte Paarliste das Spot-Paar nicht
     * führt; dann wie bisher die Suche im Hinzufügen-Tab. (Die Zeilen kommen ohnehin aus den
     * Binance-Tickern «…USDT», die Prüfung ist nur die Absicherung.) Wie iOS `CycleViewModel.previewWatch`.
     */
    suspend fun previewWatch(symbol: String): WatchEntity? {
        val base = symbol.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
        val key = com.cryptochecker.app.domain.starter.StarterPairs.BINANCE_KEY
        val name = com.cryptochecker.marketdata.config.MarketsConfig.MARKETS[key]?.name ?: return null
        val market = com.cryptochecker.app.domain.model.MarketInfo(key, name)
        val pairs = runCatching { marketRepository.getMarketCurrencyPairsInfo(market).pairs }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrDefault(emptyList())
        var pairId = base + PREVIEW_QUOTE
        // Leere Liste (noch nie geladen): den Ticker selbst entscheiden lassen
        if (pairs.isNotEmpty()) {
            val listed = pairs.firstOrNull {
                it.contractType == FuturesContractType.NONE &&
                    it.currencyBase.equals(base, ignoreCase = true) &&
                    it.currencyCounter.equals(PREVIEW_QUOTE, ignoreCase = true)
            } ?: return null
            listed.currencyPairId?.let { pairId = it }
        }
        return WatchEntity(
            id = PREVIEW_WATCH_ID,
            marketKey = key,
            marketName = name,
            baseAsset = base,
            quoteAsset = PREVIEW_QUOTE,
            pairId = pairId,
        )
    }

    /**
     * Kurs und rollende 24-h-Veränderung für das Vorschau-Paar — eine Ticker-Abfrage wie beim
     * Aktualisieren eines Paars, aber ohne Speichern, Alarme oder Meldungen. Fehler stehen in
     * [WatchEntity.lastError] (die Oberfläche übersetzt sie wie in der Merkliste).
     */
    suspend fun previewQuote(watch: WatchEntity): WatchEntity {
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                marketRepository.getMarketTicker(
                    com.cryptochecker.app.domain.model.MarketInfo(watch.marketKey, watch.marketName),
                    com.cryptochecker.marketdata.model.CurrencyPairInfo(
                        watch.baseAsset, watch.quoteAsset, watch.pairId, watch.contractType
                    ),
                )
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        val last = result?.ticker?.last?.takeIf { it.isFinite() && it > 0.0 }
        return if (last != null) {
            watch.copy(
                lastPrice = last,
                change24h = result.ticker.change24hPercent?.takeIf { it.isFinite() },
                lastUpdate = System.currentTimeMillis(),
                lastError = null,
            )
        } else {
            watch.copy(
                lastError = result?.error
                    ?: com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError.NO_TICKER_DATA,
            )
        }
    }

    // ---- Wirtschaftsdaten (Hinweis oben im Abschnitt «Jetzt»)
    private val _macroEvents = MutableStateFlow<List<MacroEvent>>(emptyList())

    /** Termine wichtiger US-Wirtschaftsdaten; die Ansicht wählt daraus, was heute gilt. */
    val macroEvents: StateFlow<List<MacroEvent>> = _macroEvents.asStateFlow()

    /** Höchstens einmal am Tag aus dem Netz (siehe [MacroCalendarRepository]); wirft nie. */
    private fun loadMacro(): Job = viewModelScope.launch {
        _macroEvents.value = macroCalendar.events()
    }

    // ---- Netzwerkgebühren (#167)
    private val _gas = MutableStateFlow<LoadState<GasReport>>(LoadState.Loading)
    val gas: StateFlow<LoadState<GasReport>> = _gas.asStateFlow()

    /** Für den Hinweis auf aktive Gas-Alarme in der Karte. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** @param force true = Zwischenspeicher übergehen (Ziehen nach unten, «Erneut»). */
    fun loadGas(force: Boolean = false): Job = sync(
        source = CycleSource.GAS,
        force = force,
        codec = CycleCacheCodecs.gas,
        timeOf = { it.time },
        show = { _gas.value = LoadState.Loaded(it) },
        loading = { _gas.value = LoadState.Loading },
        failed = { _gas.value = LoadState.Failed },
        // Ohne force darf der Gas-Alarm-Abruf der letzten Minute mitgenutzt werden
        fetch = { gasDataSource.fetchSourced(if (force) 0L else CycleSource.GAS.ttlMillis) },
    )

    // ---- Weitere Bereiche: Fear & Greed, Dominanz, Altcoin-Saison, Zyklus-Vergleich, Coin
    private val _fearGreed = MutableStateFlow<LoadState<FearGreed>>(LoadState.Loading)
    private val _dominance = MutableStateFlow<LoadState<Dominance>>(LoadState.Loading)
    private val _marketTotals = MutableStateFlow<LoadState<MarketTotals>>(LoadState.Loading)
    private val _altSeason = MutableStateFlow<LoadState<AltSeason>>(LoadState.Loading)
    private val _history = MutableStateFlow<LoadState<CycleHistory>>(LoadState.Loading)
    private val _coin = MutableStateFlow<LoadState<CoinReport>>(LoadState.Loading)
    private val _coins = MutableStateFlow(FALLBACK_COINS)
    private val _selectedCoin = MutableStateFlow("ETH")

    val fearGreed: StateFlow<LoadState<FearGreed>> = _fearGreed.asStateFlow()
    val dominance: StateFlow<LoadState<Dominance>> = _dominance.asStateFlow()
    /** Karte «Krypto-Markt»; Failed = Zeile «gerade nicht verfügbar». Gleicher Abruf wie [dominance]. */
    val marketTotals: StateFlow<LoadState<MarketTotals>> = _marketTotals.asStateFlow()
    val altSeason: StateFlow<LoadState<AltSeason>> = _altSeason.asStateFlow()
    val history: StateFlow<LoadState<CycleHistory>> = _history.asStateFlow()
    val coin: StateFlow<LoadState<CoinReport>> = _coin.asStateFlow()
    /** Coins mit USDT-Paar auf Binance (sonst eine kurze Standardliste). */
    val coins: StateFlow<List<String>> = _coins.asStateFlow()
    val selectedCoin: StateFlow<String> = _selectedCoin.asStateFlow()
    val favoriteCoins: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.COIN)

    fun toggleFavoriteCoin(coin: String) = favoritesRepository.toggle(FavoriteKind.COIN, coin)

    fun selectCoin(coin: String) {
        _selectedCoin.value = coin
        loadCoin()
    }

    /** Coin-Karte; je Coin ein eigener Zwischenspeicher (15 Min.). */
    fun loadCoin(force: Boolean = false): Job {
        val symbol = _selectedCoin.value
        return sync(
            source = CycleSource.COIN,
            name = CycleCachePolicy.coinName(symbol),
            force = force,
            codec = CycleCacheCodecs.coinInputs,
            show = { _coin.value = LoadState.Loaded(CoinCycleModel.evaluate(symbol, it)) },
            loading = { _coin.value = LoadState.Loading },
            failed = {
                Timber.w("Coin-Daten für %s nicht verfügbar", symbol)
                _coin.value = LoadState.Failed
            },
            fetch = { insights.fetchCoin(symbol) },
        )
    }

    /** Beim Öffnen des Tabs (und Zurückkehren in die App): nur Abgelaufenes neu laden. */
    fun onOpen() {
        startAll(force = false)
    }

    /** Alles neu laden (Ziehen nach unten). */
    fun refreshAll() {
        val started = startAll(force = true)
        _pullRefreshing.value = true
        viewModelScope.launch {
            try {
                started.joinAll()
            } finally {
                _pullRefreshing.value = false
            }
        }
    }

    private fun startAll(force: Boolean): List<Job> = listOf(
        loadMacro(),
        loadPulse(force),
        loadUnusual(force),
        loadMarket(force),
        loadFearGreed(force),
        loadGlobal(force),
        loadAltSeason(force),
        loadHistory(force),
        loadCoin(force),
        loadGas(force),
    )

    private fun loadFearGreed(force: Boolean): Job = sync(
        source = CycleSource.FEAR_GREED,
        force = force,
        codec = CycleCacheCodecs.fearGreed,
        show = { _fearGreed.value = LoadState.Loaded(it) },
        loading = { _fearGreed.value = LoadState.Loading },
        failed = { _fearGreed.value = LoadState.Failed },
        fetch = { Sourced(insights.fearGreed(), DataFreshness.ALTERNATIVE_ME) },
    )

    /** Ein CoinGecko-Aufruf für Dominanz und Markt-Summen (kein zweiter Abruf). */
    private fun loadGlobal(force: Boolean): Job = sync(
        source = CycleSource.GLOBAL,
        force = force,
        codec = CycleCacheCodecs.global,
        show = { global ->
            _dominance.value = LoadState.Loaded(global.dominance)
            _marketTotals.value = global.totals?.let { LoadState.Loaded(it) } ?: LoadState.Failed
        },
        loading = {
            _dominance.value = LoadState.Loading
            _marketTotals.value = LoadState.Loading
        },
        failed = {
            _dominance.value = LoadState.Failed
            _marketTotals.value = LoadState.Failed
        },
        fetch = { Sourced(insights.global(), DataFreshness.COINGECKO) },
    )

    /** Zeitpunkt der angezeigten Altcoin-Saison («Stand 14:05»); null = noch nichts gezeigt. */
    val altSeasonAsOf: StateFlow<Long?> = shownAt.map { it[CycleSource.ALT_SEASON] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Altcoin-Saison lädt gerade neu. */
    val altSeasonRefreshing: StateFlow<Boolean> = running.map { CycleSource.ALT_SEASON in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** «Aktualisieren» an der Altcoin-Saison: höchstens alle 5 Min. ein neuer Abruf. */
    fun refreshAltSeason() {
        loadAltSeason(force = true)
    }

    private fun loadAltSeason(force: Boolean): Job = sync(
        source = CycleSource.ALT_SEASON,
        force = force,
        codec = CycleCacheCodecs.altSeason,
        show = { _altSeason.value = LoadState.Loaded(it) },
        loading = { _altSeason.value = LoadState.Loading },
        failed = { _altSeason.value = LoadState.Failed },
        fetch = { insights.altSeason() },
    )

    private fun loadHistory(force: Boolean): Job = sync(
        source = CycleSource.HISTORY,
        force = force,
        codec = CycleCacheCodecs.history,
        isUsable = { it.series.isNotEmpty() },
        show = { _history.value = LoadState.Loaded(it) },
        loading = { _history.value = LoadState.Loading },
        failed = { _history.value = LoadState.Failed },
        fetch = { Sourced(insights.cycleHistory(), null) },
    )

    private fun loadCoinList() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val binance = marketRepository.getMarketList().firstOrNull { it.key == "Binance" }
                        ?: return@withContext null
                    var info = marketRepository.getMarketCurrencyPairsInfo(binance)
                    if (info.pairs.isEmpty() && marketRepository.isMarketSupportsUpdatePairs(binance)) {
                        marketRepository.updateMarketCurrencyPairs(binance)
                        info = marketRepository.getMarketCurrencyPairsInfo(binance)
                    }
                    info.pairs
                        .filter { it.currencyCounter == "USDT" && it.contractType == FuturesContractType.NONE }
                        .map { it.currencyBase }
                        .distinct()
                        .sorted()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { _coins.value = it }
        }
    }

    /** Halving-Phase: rein nach Kalender, auch ohne Internet verfügbar. */
    val cycle: CycleInfo = BitcoinCycle.info()

    private val _market = MutableStateFlow<MarketState>(MarketState.Loading)

    /** Zyklus-Modell (Top-/Bottom-Score), sofort aus dem Zwischenspeicher, stündlich neu. */
    val market: StateFlow<MarketState> = _market.asStateFlow()

    // ---- Schrittweises Erscheinen der Karten (einmal je App-Sitzung, siehe [MarketReveal])

    /** Bereiche, deren Zwischenspeicher schon gelesen ist (gezeigt oder Ladezustand). */
    private val cacheChecked = MutableStateFlow<Set<CycleSource>>(emptySet())

    // Gewähltes Register des Tabs: [MarketRegister] (gilt für die App-Sitzung)

    private val _reveal = MutableStateFlow(MarketReveal.State())

    /**
     * Wie viele Teile des Tabs (von oben, [MarketRevealSlot]) sichtbar sind; die ersten
     * [MarketReveal.State.instant] erscheinen ohne Animation. Später bleibt alles stehen.
     */
    val reveal: StateFlow<MarketReveal.State> = _reveal.asStateFlow()

    /** Je Teil: Daten bereit (geladen, gescheitert oder aus dem Zwischenspeicher)? */
    private fun revealReadiness(): List<Boolean> = MarketRevealSlot.entries.map { slot ->
        when (slot) {
            MarketRevealSlot.PULSE -> _pulse.value !is LoadState.Loading
            MarketRevealSlot.UNUSUAL -> _unusual.value !is LoadState.Loading
            MarketRevealSlot.FEAR_GREED -> _fearGreed.value !is LoadState.Loading
            MarketRevealSlot.MARKET_TOTALS -> _marketTotals.value !is LoadState.Loading
            MarketRevealSlot.PHASE -> _market.value !is MarketState.Loading
            MarketRevealSlot.DOMINANCE ->
                _dominance.value !is LoadState.Loading && _altSeason.value !is LoadState.Loading
            MarketRevealSlot.HALVING -> _history.value !is LoadState.Loading
            MarketRevealSlot.COIN -> _coin.value !is LoadState.Loading
            MarketRevealSlot.GAS -> _gas.value !is LoadState.Loading
            MarketRevealSlot.HEADER_CONTEXT, MarketRevealSlot.HEADER_DATA -> true
        }
    }

    private val revealReadinessFlow: Flow<List<Boolean>> = combine(
        listOf<Flow<Any>>(_pulse, _unusual, _fearGreed, _marketTotals, _market, _dominance, _altSeason, _history, _coin, _gas)
    ) { revealReadiness() }.distinctUntilChanged()

    /**
     * Läuft einmal: wartet kurz auf den Zwischenspeicher (Gerät), zeigt sofort, was schon
     * bereit ist, und dann eine Karte nach der anderen ([MarketReveal.plan]).
     */
    private fun runReveal() {
        viewModelScope.launch {
            withTimeoutOrNull(MarketReveal.CACHE_WAIT_MILLIS) {
                cacheChecked.first { it.containsAll(REVEAL_SOURCES) }
            }
            val start = clockMillis()
            val readyAt = arrayOfNulls<Long>(MarketReveal.COUNT)
            fun note(ready: List<Boolean>, at: Long) {
                ready.forEachIndexed { i, isReady -> if (isReady && readyAt[i] == null) readyAt[i] = at }
            }
            note(revealReadiness(), start)
            val instant = MarketReveal.instantCount(readyAt.asList(), start)
            while (true) {
                val now = clockMillis()
                note(revealReadiness(), now)
                val plan = MarketReveal.plan(readyAt.asList(), start, now)
                _reveal.value = MarketReveal.State(count = plan.revealed, instant = instant)
                val wakeAt = plan.nextAt ?: break
                // Bis zur nächsten Karte warten — oder bis neue Daten da sind
                withTimeoutOrNull((wakeAt - now).coerceAtLeast(1L)) {
                    revealReadinessFlow.first { ready -> ready.indices.any { ready[it] && readyAt[it] == null } }
                }
            }
        }
    }

    init {
        loadCoinList()
        // Erstes Laden: Zwischenspeicher sofort, Abgelaufenes im Hintergrund
        startAll(force = false)
        runReveal()
    }

    /**
     * Marktphase (1 Std.). Die On-Chain-Werte darin (Coin Metrics) gelten 12 Std. und
     * werden bis dahin aus ihrem eigenen Zwischenspeicher übernommen statt neu geholt.
     */
    fun loadMarket(force: Boolean = false): Job = sync(
        source = CycleSource.MARKET,
        force = force,
        codec = CycleCacheCodecs.cycleInputs,
        show = { _market.value = MarketState.Loaded(CycleModel.evaluate(it)) },
        loading = { _market.value = MarketState.Loading },
        failed = { _market.value = MarketState.Failed },
        fetch = {
            val now = System.currentTimeMillis()
            val known = if (force) null else cacheStore.read(CycleSource.ON_CHAIN.key, CycleCacheCodecs.onChain)
                ?.takeIf { CycleCachePolicy.isFresh(it.savedAt, now, CycleSource.ON_CHAIN.ttlMillis) }
                ?.value
            val sourced = cycleDataSource.fetchSourced(knownOnChain = known)
            val inputs = sourced.value
            if (known == null) {
                val onChain = OnChainValues(inputs.mvrv, inputs.puell, inputs.hash30d, inputs.hash60d)
                if (onChain.hasAny) {
                    cacheStore.write(
                        CycleSource.ON_CHAIN.key, onChain, System.currentTimeMillis(), CycleCacheCodecs.onChain,
                        provider = DataFreshness.COIN_METRICS,
                    )
                }
            }
            sourced
        },
    )

    /**
     * Ein Bereich: zeigt sofort, was da ist (Speicher der Ansicht, sonst Gerät), und
     * lädt nur neu, wenn das älter als die Gültigkeit ist oder [force]. Ohne irgendeinen
     * Wert erscheint der Ladezustand bzw. bei Fehler «Failed»; mit Wert bleibt dieser
     * bei einem Fehler einfach stehen. Höchstens ein Abruf je Bereich; ein Wechsel des
     * Eintrags (anderer Coin) bricht den alten ab.
     *
     * @param fetch holt den Wert samt Anbieter ([Sourced.provider], gespeichert und in [stamps])
     * @param name Eintrag im Zwischenspeicher (Standard: Schlüssel des Bereichs)
     * @param timeOf Zeitpunkt der Daten selbst; sonst gilt der Abrufzeitpunkt
     * @param isUsable unbrauchbare Werte weder zeigen noch speichern
     */
    private fun <T> sync(
        source: CycleSource,
        force: Boolean,
        codec: CacheCodec<T>,
        show: (T) -> Unit,
        loading: () -> Unit,
        failed: () -> Unit,
        fetch: suspend () -> Sourced<T>,
        name: String = source.key,
        timeOf: ((T) -> Long)? = null,
        isUsable: (T) -> Boolean = { true },
    ): Job {
        jobs[source]?.let { (runningName, job) ->
            if (job.isActive) {
                // Gleicher Eintrag läuft schon: nicht doppelt laden
                if (runningName == name) return job
                job.cancel()
                running.update { it - source }
            }
        }
        val job = viewModelScope.launch {
            val self = coroutineContext.job
            var savedAt = shownEntry[source]?.takeIf { it.first == name }?.second
            if (savedAt == null) {
                // Noch nichts (oder anderer Coin) angezeigt: Gerät, sonst Ladezustand
                val cached = cacheStore.read(name, codec)?.takeIf { isUsable(it.value) }
                if (cached != null && showSafely(source, show, cached.value)) {
                    savedAt = cached.savedAt
                    markShown(source, name, cached.savedAt, cached.provider)
                } else {
                    shownEntry.remove(source)
                    shownAt.update { it - source }
                    _stamps.update { it - source }
                    loading()
                }
            }
            cacheChecked.update { it + source }
            // Langsame Bereiche (z. B. Altcoin-Saison) von Hand frühestens alle 5 Min. neu
            val minForce = CycleCachePolicy.manualMinInterval(source)
            if (!CycleCachePolicy.needsRefresh(savedAt, System.currentTimeMillis(), source.ttlMillis, force, minForce)) {
                return@launch
            }
            running.update { it + source }
            try {
                val sourced = fetchWithin(source, fetch)?.getOrNull()?.takeIf { isUsable(it.value) }
                if (sourced != null && showSafely(source, show, sourced.value)) {
                    val fresh = sourced.value
                    val at = timeOf?.invoke(fresh)?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    markShown(source, name, at, sourced.provider)
                    cacheStore.write(name, fresh, at, codec, sourced.provider)
                } else if (savedAt == null) {
                    failed()
                }
            } finally {
                // Ein abgelöster Abruf (anderer Coin) räumt nicht für den neuen auf
                if (jobs[source]?.second === self) running.update { it - source }
            }
        }
        jobs[source] = name to job
        return job
    }

    /**
     * Anzeigen (samt Auswertung, z. B. Zyklus-Modell); false, wenn das an unerwarteten
     * Werten scheitert — dann gilt der Wert als unbrauchbar statt die App zu beenden.
     */
    private fun <T> showSafely(source: CycleSource, show: (T) -> Unit, value: T): Boolean = try {
        show(value)
        true
    } catch (e: Exception) {
        Timber.w(e, "Zyklus-Daten %s nicht auswertbar", source.key)
        false
    }

    private fun markShown(source: CycleSource, name: String, at: Long, provider: String?) {
        shownEntry[source] = name to at
        shownAt.update { it + (source to at) }
        _stamps.update { it + (source to DataStamp(provider, at, source.ttlMillis)) }
    }

    /**
     * Abruf auf IO mit harter Zeitgrenze des Bereichs. null bei Fehler oder
     * Zeitüberschreitung (der Wert steckt in [Result], damit null nie «Zeit um» heisst).
     */
    private suspend fun <T> fetchWithin(source: CycleSource, fetch: suspend () -> T): Result<T>? = try {
        withTimeoutOrNull(source.timeoutMillis) {
            Result.success(withContext(Dispatchers.IO) { fetch() })
        }.also { if (it == null) Timber.w("Zyklus-Daten %s: Zeitüberschreitung", source.key) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        // Innere Zeitgrenze einer Abfrage: wie ein Fehler der Quelle, kein Abbruch des Bereichs
        Timber.w(e, "Zyklus-Daten %s: Zeitüberschreitung", source.key)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Zyklus-Daten %s nicht verfügbar", source.key)
        null
    }
}

/**
 * Id des Vorschau-Paars aus «Heute auffällig» (nie gespeichert): negativ, damit es mit keinem
 * Eintrag der Merkliste, keinem Alarm und keinen Signalen zusammenfällt.
 */
internal const val PREVIEW_WATCH_ID = -1L

/** Quote des Vorschau-Paars (Binance Spot «COIN/USDT», wie die Start-Merkliste). */
private const val PREVIEW_QUOTE = "USDT"

/** Bereiche, deren Zwischenspeicher vor dem ersten Erscheinen gelesen sein soll. */
private val REVEAL_SOURCES = setOf(
    CycleSource.PULSE, CycleSource.UNUSUAL, CycleSource.MARKET, CycleSource.FEAR_GREED, CycleSource.GLOBAL,
    CycleSource.ALT_SEASON, CycleSource.HISTORY, CycleSource.COIN, CycleSource.GAS,
)

/** Monotone Uhr für die Abfolge (springt nicht mit der Systemzeit). */
private fun clockMillis(): Long = System.nanoTime() / 1_000_000L

private val FALLBACK_COINS = listOf(
    "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT",
    "TON", "LTC", "BCH", "UNI", "NEAR", "APT", "ETC", "XLM", "SUI", "PEPE",
).sorted()

sealed interface MarketState {
    data object Loading : MarketState
    data class Loaded(val report: CycleReport) : MarketState
    data object Failed : MarketState
}
