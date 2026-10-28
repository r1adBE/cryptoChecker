// Start-Merkliste der leeren Merkliste (Teil von `WatchlistViewModel`): Coins, Logos und Kurse laden,
// Auswahl umschalten, gewählte Start-Paare hinzufügen.
// Eigene Klasse, weil der Ablauf nur bis zum ersten Paar gebraucht wird; sie besitzt ihren Zustand (privat,
// nach aussen nur lesbar) und läuft im viewModelScope, den das ViewModel als [scope] mitgibt.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.app.domain.starter.StarterSelection
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class WatchlistStarterController @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val starterCoinsRepository: StarterCoinsRepository,
    private val settingsRepository: SettingsRepository,
    private val coinLogoRepository: CoinLogoRepository,
    private val watchRepository: WatchRepository,
    private val addMoments: AddMoments,
    private val widgetUpdater: WidgetUpdater,
    /** Nach dem Anlegen gleich aktualisieren, ohne 15-s-Sperre ([WatchlistRefreshController.refreshAll]). */
    @Assisted private val refreshAll: () -> Unit,
) {
    /** Erzeugt den Controller für ein ViewModel ([scope], [refreshAll]); die übrigen Abhängigkeiten setzt Hilt ein. */
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, refreshAll: () -> Unit): WatchlistStarterController
    }

    /**
     * Coins der Start-Merkliste: sofort aus dem Zwischenspeicher bzw. die
     * Ausweich-Liste, frischere Daten ersetzen sie still ([loadStarterCoins]).
     */
    private val _coins = MutableStateFlow(starterCoinsRepository.cached(starterRegion()))
    val coins: StateFlow<List<StarterPairs.Coin>> = _coins.asStateFlow()

    private var starterJob: Job? = null
    private var starterAddJob: Job? = null

    /** Prüfung der Binance-Standort-Sperre ([StarterCoinsRepository.probeBinance]); das Hinzufügen wartet darauf. */
    private var starterProbeJob: Job? = null

    /**
     * Logos der Start-Coins geladen (oder Logos aus bzw. nach [STARTER_LOGO_WAIT_MILLIS] aufgegeben)?
     * Bis dahin zeigt die Start-Auswahl statt der Logos einen ruhigen Platzhalter — kein Umspringen
     * von Initialen auf Logos ein paar Sekunden nach dem Installieren.
     */
    private val _logosReady = MutableStateFlow(!settingsRepository.cached.coinLogos)
    val logosReady: StateFlow<Boolean> = _logosReady.asStateFlow()

    /**
     * Abgewählte Start-Coins (Symbole). Leer = alle ausgewählt (Standard); so sind auch
     * Coins einer frischeren Liste gleich ausgewählt. Siehe [StarterSelection].
     */
    private val _deselected = MutableStateFlow<Set<String>>(emptySet())
    val deselected: StateFlow<Set<String>> = _deselected.asStateFlow()

    /** Quote der Start-Paare: USDT (Binance und Binance.US). */
    val quote: String = StarterPairs.startQuote()

    /**
     * Anzeigename der Start-Börse («Binance», bei Standort-Sperre «Binance.US»); wechselt nach der Prüfung der
     * Sperre ([loadStarterCoins]). Die Start-Auswahl nennt ihn samt [quote].
     */
    private val _exchangeName = MutableStateFlow(starterExchangeName())

    /** Kurse der Start-Coins; [StarterPricesState.loading] solange der Abruf läuft. */
    private val _prices = MutableStateFlow(StarterPricesState(loading = true, prices = emptyMap()))
    val prices: StateFlow<StarterPricesState> = _prices.asStateFlow()

    /** Läuft das Hinzufügen der Start-Coins gerade? (Knopf gesperrt, kein Doppeltipp) */
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /** Alles für die Start-Auswahl in einem Stand ([StarterUiState]). */
    val state: StateFlow<StarterUiState> = combine(
        combine(coins, deselected, prices, adding, logosReady) { c, d, p, a, l ->
            StarterUiState(coins = c, deselected = d, prices = p, adding = a, logosReady = l, quote = quote, exchangeName = "")
        },
        _exchangeName,
    ) { s, m -> s.copy(exchangeName = m) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), currentState())

    private fun currentState() = StarterUiState(
        coins = _coins.value,
        deselected = _deselected.value,
        prices = _prices.value,
        adding = _adding.value,
        logosReady = _logosReady.value,
        quote = quote,
        exchangeName = _exchangeName.value,
    )

    /** Region der Startpaare: Geräte-Region, bei Binance-Standort-Sperre «US» (Binance.US). */
    private fun starterRegion(): String? = starterCoinsRepository.starterRegion(deviceRegionLocale().country)

    private fun starterExchangeName(): String =
        StarterPairs.exchangeName(starterRegion()) { key -> ExchangesConfig.EXCHANGES[key]?.name }

    private suspend fun ensureStarterLogos(coins: List<StarterPairs.Coin>) {
        if (!settingsRepository.cached.coinLogos) {
            _logosReady.value = true
            return
        }
        kotlinx.coroutines.withTimeoutOrNull(STARTER_LOGO_WAIT_MILLIS) {
            coinLogoRepository.ensureStarterLogos(coins.map { it.symbol })
        }
        _logosReady.value = true
    }

    /** Nur wenn die leere Merkliste gezeigt wird; höchstens ein Abruf gleichzeitig. */
    fun loadStarterCoins() {
        if (starterJob?.isActive == true) return
        starterJob = scope.launch {
            // Binance am Standort gesperrt (451/403)? Parallel prüfen; gesperrt → Startpaare auf Binance.US
            val probe = async { starterCoinsRepository.probeBinance(deviceRegionLocale().country) }
            starterProbeJob = probe
            loadStarterList()
            val changed = probe.await()
            // Börse für den Satz «Kurse von … in USDT» nach der Prüfung (gesperrt → Binance.US)
            _exchangeName.value = starterExchangeName()
            if (changed) {
                // Andere Startbörse: deren Liste, Kurse und Logos
                _coins.value = starterCoinsRepository.cached(starterRegion())
                loadStarterList()
            }
        }
    }

    private suspend fun loadStarterList(): Unit = kotlinx.coroutines.coroutineScope {
        // Kurse und Logos gleich für die sofort gezeigte Liste (parallel zur frischeren Liste)
        val pricesJob = launch { loadStarterPrices(_coins.value) }
        val logosJob = launch { ensureStarterLogos(_coins.value) }
        starterCoinsRepository.fresh(starterRegion())?.let { fresh ->
            val changed = fresh.map { it.symbol } != _coins.value.map { it.symbol }
            _coins.value = fresh
            if (changed) {
                pricesJob.cancel()
                // Neue Coins: deren Logos noch dazu (die Liste bleibt dabei sichtbar)
                launch { logosJob.join(); ensureStarterLogos(fresh) }
                loadStarterPrices(fresh)
            }
        }
    }

    fun toggleStarter(symbol: String) {
        _deselected.value = StarterSelection.toggle(_deselected.value, symbol)
    }

    /** «Alle auswählen» bzw. «Keine auswählen». */
    fun toggleAllStarters() {
        _deselected.value = StarterSelection.toggleAll(_coins.value, _deselected.value)
    }

    private suspend fun loadStarterPrices(coins: List<StarterPairs.Coin>) {
        _prices.value = _prices.value.copy(loading = true, failed = false)
        val prices = starterCoinsRepository.prices(coins.map { it.symbol }, starterRegion())
        // Fehler: Zeilen ohne Kurs (alte Kurse bleiben, falls die Coins dieselben sind); gar keiner da → «Keine
        // Verbindung» mit «Erneut versuchen»
        val shown = prices.ifEmpty { _prices.value.prices }
        _prices.value = StarterPricesState(
            loading = false,
            prices = shown,
            failed = shown.isEmpty(),
        )
    }

    /**
     * Start-Merkliste: Paare der gewählten Coins anlegen — Börse nach [StarterPairs.exchangeKey]: Binance …/USDT in allen
     * Regionen (auch CH/LI), in den USA und bei Standort-Sperre von Binance (451/403) Binance.US …/USDT. Genau wie
     * «Hinzufügen» (WatchRepository.addWatch, gleiche Standardwerte samt Kurs-Benachrichtigung). Vorhandene Paare werden
     * nicht doppelt angelegt. Danach gleich aktualisieren. Einstellungen (z. B. Umrechnung) bleiben unverändert.
     */
    private fun addStarter(symbols: List<String>) {
        // Doppeltipp: kein zweiter Durchlauf, der doppelt anlegt oder den Moment verwirft
        if (starterAddJob?.isActive == true || symbols.isEmpty()) return
        _adding.value = true
        starterAddJob = scope.launch {
            try {
                addStarterPairs(symbols)
            } finally {
                _adding.value = false
            }
        }
    }

    /** Die gewählten Start-Coins in der angezeigten Reihenfolge anlegen. */
    fun addSelectedStarters() {
        addStarter(StarterSelection.selected(_coins.value, _deselected.value))
    }

    private suspend fun addStarterPairs(symbols: List<String>) {
        // Prüfung der Standort-Sperre läuft noch: abwarten, damit die Paare gleich an der richtigen Börse landen
        starterProbeJob?.join()
        val region = starterRegion()
        val wanted = symbols.map { symbol ->
            StarterPairs.pairFor(symbol, region) { key -> ExchangesConfig.EXCHANGES[key]?.name }
        }
        val existing = watchRepository.getWatches().map { Triple(it.exchangeKey, it.baseAsset, it.quoteAsset) }
        val toAdd = StarterPairs.missing(wanted, existing)
        if (toAdd.isEmpty()) return
        // Rückfrage «nicht gespeichert» EINMAL für alle Startpaare, vor dem Erst-Moment; «Abbrechen» = nichts
        if (!watchRepository.confirmUserWrite()) return
        // Erst-Moment vor dem Speichern bereitstellen: Zeilen, Häkchen, Haptik und Banner
        addMoments.postStarter(toAdd.map { AddMoment.Added(it.exchangeKey, it.base, it.quote, "${it.base}/${it.quote}") })
        var added = 0
        for (pair in toAdd) {
            val id = watchRepository.addWatch(
                ExchangeInfo(key = pair.exchangeKey, name = pair.exchangeName),
                ExchangePair(pair.base, pair.quote, pair.pairId),
                // Kurs-Benachrichtigung an wie jedes neue Paar; die Erlaubnis fragt die Start-Auswahl (WatchlistScreen)
            )
            if (id != null) added++
        }
        if (added > 0) {
            widgetUpdater.updateAll()
            refreshAll()
        } else {
            // Nichts gespeichert: den bereitgestellten Moment verwerfen
            addMoments.pending.value?.let(addMoments::consume)
        }
    }
}

/**
 * Kurse der Start-Merkliste: [loading] = Abruf läuft; fehlende Coins haben keinen Kurs. [failed] = Abruf fertig,
 * aber kein einziger Kurs (offline bzw. Börse nicht erreichbar) — die Auswahl zeigt dann «Erneut versuchen».
 */
data class StarterPricesState(
    val loading: Boolean,
    val prices: Map<String, StarterPrice>,
    val failed: Boolean = false,
)

/** So lange wartet die Start-Auswahl höchstens auf die Logos der Start-Coins, dann Initialen. */
private const val STARTER_LOGO_WAIT_MILLIS = 4_000L
