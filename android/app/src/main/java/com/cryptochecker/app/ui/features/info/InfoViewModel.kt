package com.cryptochecker.app.ui.features.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.MacroCalendarRepository
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.CycleDataSource
import com.cryptochecker.app.data.remote.GasDataSource
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.data.remote.UnusualDataSource
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.BitcoinHalving
import com.cryptochecker.app.domain.market.BitcoinReport
import com.cryptochecker.app.domain.market.CoinReport
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.domain.market.HalvingEstimate
import com.cryptochecker.app.domain.market.HalvingInfo
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.UnusualReport
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    internal val cycleDataSource: CycleDataSource,
    internal val insights: InsightsDataSource,
    internal val marketRepository: MarketRepository,
    private val favoritesRepository: FavoritesRepository,
    internal val gasDataSource: GasDataSource,
    internal val pulseDataSource: PulseDataSource,
    internal val cacheStore: CycleCacheStore,
    private val settingsRepository: SettingsRepository,
    internal val unusualDataSource: UnusualDataSource,
    internal val macroCalendar: MacroCalendarRepository,
    watchRepository: WatchRepository,
    activityRepository: ActivityRepository,
    internal val activityMonitor: ActivityMonitor,
    /** Netz da? Status-Pille «Offline · Stand 14:05» wie in der Merkliste. */
    internal val connectivity: com.cryptochecker.app.util.ConnectivityMonitor,
) : ViewModel() {

    /**
     * Gerät online. Ohne Netz bleibt der letzte Stand stehen (kein Abruf, kein Fehlerzustand);
     * kommt das Netz zurück, wird Abgelaufenes neu geladen (wie die Merkliste).
     */
    val online: StateFlow<Boolean> = connectivity.online

    // ---- Zwischenspeicher: was gezeigt wird, von wann, was gerade lädt

    /** Zeitpunkt der angezeigten Daten je Bereich (Gerät oder frisch geholt). */
    internal val shownAt = MutableStateFlow<Map<CycleSource, Long>>(emptyMap())

    /** Bereiche, die gerade im Hintergrund neu laden. */
    internal val running = MutableStateFlow<Set<CycleSource>>(emptySet())

    internal val _stamps = MutableStateFlow<Map<CycleSource, DataStamp>>(emptyMap())

    /**
     * Herkunft und Stand der gezeigten Daten je Bereich (Anbieter, Zeitpunkt, Gültigkeit) für
     * die Nebenzeilen von «Einordnung» und «Daten» («CoinGecko · vor 3 Min.»).
     */
    val stamps: StateFlow<Map<CycleSource, DataStamp>> = _stamps.asStateFlow()

    /** Welcher Eintrag (z. B. «coin_ETH») gerade angezeigt wird, mit seinem Zeitpunkt. */
    internal val shownEntry = HashMap<CycleSource, Pair<String, Long>>()
    internal val jobs = HashMap<CycleSource, Pair<String, Job>>()

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
    internal val _pulse = MutableStateFlow<LoadState<PulseReport>>(LoadState.Loading)
    val pulse: StateFlow<LoadState<PulseReport>> = _pulse.asStateFlow()

    // ---- «Heute auffällig» (unter dem Pulse)
    internal val _unusual = MutableStateFlow<LoadState<UnusualReport>>(LoadState.Loading)
    val unusual: StateFlow<LoadState<UnusualReport>> = _unusual.asStateFlow()

    /** Paare der Merkliste: Tippen auf eine Zeile öffnet «Was bewegt sich mit?» (beobachtet) oder die Suche. */
    val watches: StateFlow<List<WatchEntity>> = watchRepository.observeWatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Signale «Ungewöhnliche Aktivität» je Paar, für das «Was bewegt sich mit?»-Blatt. */
    val activity: StateFlow<Map<Long, ActivityReport>> = activityRepository.reports

    // ---- Wirtschaftsdaten (Hinweis oben im Abschnitt «Jetzt»)
    internal val _macroEvents = MutableStateFlow<List<MacroEvent>>(emptyList())

    /** Termine wichtiger US-Wirtschaftsdaten; die Ansicht wählt daraus, was heute gilt. */
    val macroEvents: StateFlow<List<MacroEvent>> = _macroEvents.asStateFlow()

    // ---- Netzwerkgebühren (#167)
    internal val _gas = MutableStateFlow<LoadState<GasReport>>(LoadState.Loading)
    val gas: StateFlow<LoadState<GasReport>> = _gas.asStateFlow()

    /** Für den Hinweis auf aktive Gas-Alarme in der Karte. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** «Mehr Marktdaten» im Tab aufgeklappt (Standard zu; nur auf diesem Gerät, nicht in der Sicherung). */
    val moreExpanded: StateFlow<Boolean> = settingsRepository.settings
        .map { it.marketMoreExpanded }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.marketMoreExpanded)

    fun setMoreExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setMarketMoreExpanded(expanded) }
    }

    // ---- Weitere Bereiche: Fear & Greed, Dominanz, Altcoin-Saison, Zyklus-Vergleich, Coin
    internal val _fearGreed = MutableStateFlow<LoadState<FearGreed>>(LoadState.Loading)
    internal val _dominance = MutableStateFlow<LoadState<Dominance>>(LoadState.Loading)
    internal val _marketTotals = MutableStateFlow<LoadState<MarketTotals>>(LoadState.Loading)
    internal val _altSeason = MutableStateFlow<LoadState<AltSeason>>(LoadState.Loading)
    internal val _history = MutableStateFlow<LoadState<CycleHistory>>(LoadState.Loading)
    internal val _coin = MutableStateFlow<LoadState<CoinReport>>(LoadState.Loading)
    internal val _coins = MutableStateFlow(FALLBACK_COINS)
    internal val _selectedCoin = MutableStateFlow("ETH")

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

    /** Letztes und geschätzt nächstes Halving: reines Datum nach Kalender, auch ohne Internet. */
    val halving: HalvingInfo = BitcoinHalving.info()

    internal val _halvingEstimate = MutableStateFlow<HalvingEstimate?>(null)

    /** Nächstes Halving rechnerisch aus der Blockhöhe ([HalvingMath]); null = noch nicht geladen (dann Kalender). */
    val halvingEstimate: StateFlow<HalvingEstimate?> = _halvingEstimate.asStateFlow()
    internal var halvingEstimateAt = 0L

    internal val _market = MutableStateFlow<MarketState>(MarketState.Loading)

    /** Bitcoin-Kennzahlen (reine Messwerte), sofort aus dem Zwischenspeicher, stündlich neu. */
    val market: StateFlow<MarketState> = _market.asStateFlow()

    // ---- Schrittweises Erscheinen der Karten (einmal je App-Sitzung, siehe [MarketReveal])

    /** Bereiche, deren Zwischenspeicher schon gelesen ist (gezeigt oder Ladezustand). */
    internal val cacheChecked = MutableStateFlow<Set<CycleSource>>(emptySet())

    // Gewähltes Register des Tabs: [MarketRegister] (gilt für die App-Sitzung)

    private val _reveal = MutableStateFlow(MarketReveal.State())

    /**
     * Welche Teile des Tabs ([MarketRevealSlot], je Register von oben) sichtbar sind; die in
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
            val instant = MarketReveal.instantSlots(readyAt.asList(), start)
            while (true) {
                val now = clockMillis()
                note(revealReadiness(), now)
                val plan = MarketReveal.plan(readyAt.asList(), start, now)
                _reveal.value = MarketReveal.State(shown = plan.revealed, instant = instant)
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
        // Netz wieder da: Abgelaufenes nachladen
        viewModelScope.launch {
            connectivity.online.drop(1).filter { it }.collect { startAll(force = false) }
        }
    }
}

/**
 * Id des Vorschau-Paars aus «Heute auffällig» (nie gespeichert): negativ, damit es mit keinem
 * Eintrag der Merkliste, keinem Alarm und keinen Signalen zusammenfällt.
 */
internal const val PREVIEW_WATCH_ID = -1L

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
    data class Loaded(val report: BitcoinReport) : MarketState
    data object Failed : MarketState
}
