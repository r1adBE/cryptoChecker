package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.PairEditResult
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import javax.inject.Inject

/** Zustand des Formulars «Paar bearbeiten». */
data class WatchEditState(
    /** Börsen ohne DEX (Pools findet nur die DEX-Suche beim Hinzufügen). null = lädt. */
    val markets: List<MarketInfo>? = null,
    val market: MarketInfo? = null,
    val pairs: MarketPairsInfo? = null,
    val syncing: Boolean = false,
    val syncError: String? = null,
    val base: String? = null,
    val quote: String? = null,
    val contract: FuturesContractType? = null,
    val saving: Boolean = false,
    /** Gewähltes Paar steht schon als anderer Eintrag in der Merkliste (nach «Speichern»). */
    val duplicate: Boolean = false,
    /** Zählt erfolgreiche «Speichern»: steigt er, schliesst sich das Blatt (auch über erneutes Öffnen hinweg eindeutig). */
    val savedCount: Int = 0,
) {
    val bases: List<String> get() = pairs?.baseCurrencies?.toList().orEmpty()
    val quotes: List<String> get() = base?.let { pairs?.getQuoteCurrencies(it)?.toList() }.orEmpty()
    val contracts: List<FuturesContractType>
        get() = if (base == null || quote == null) emptyList()
        else pairs?.getAvailableFuturesContractsTypes(base, quote).orEmpty()

    /** Gewähltes Paar der Börse; null, solange nichts Vollständiges gewählt ist. */
    val pair: CurrencyPairInfo?
        get() {
            val b = base ?: return null
            val q = quote ?: return null
            return pairs?.getCurrencyPairInfo(b, q, contract ?: FuturesContractType.NONE)
        }

    /** Schlüssel der Auswahl ([WatchEdit.Key]); null ohne vollständige Auswahl. */
    val key: WatchEdit.Key?
        get() {
            val m = market ?: return null
            val p = pair ?: return null
            return WatchEdit.Key(m.key, p.currencyBase, p.currencyCounter, p.contractType.name)
        }
}

/**
 * «Paar bearbeiten» im Aktionsblatt: dieselben Schritte wie «Genau auswählen» im Explorer
 * (Börse → Coin → Gegenwert → Kontrakt), vorbelegt mit dem Eintrag. Speichern ändert
 * denselben Eintrag ([WatchRepository.changePair]), verwirft Signale und Meldung des alten
 * Paars, holt sofort den neuen Kurs und aktualisiert die Widgets.
 */
@HiltViewModel
class WatchEditViewModel @Inject constructor(
    private val marketRepository: MarketRepository,
    private val watchRepository: WatchRepository,
    private val favoritesRepository: FavoritesRepository,
    private val priceRefresher: PriceRefresher,
    private val notifier: AppNotifier,
    private val widgetUpdater: WidgetUpdater,
    private val activityRepository: ActivityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchEditState())
    val state: StateFlow<WatchEditState> = _state.asStateFlow()

    val favoriteMarkets: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.MARKET)
    val favoriteCoins: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.COIN)
    val favoriteQuotes: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.QUOTE)

    fun toggleFavorite(kind: FavoriteKind, item: String) = favoritesRepository.toggle(kind, item)

    private var startJob: Job? = null
    private var pairsJob: Job? = null

    /** Formular für [watch] vorbelegen (bei jedem Öffnen). */
    fun start(watch: WatchEntity) {
        pairsJob?.cancel()
        startJob?.cancel()
        _state.value = WatchEditState(
            base = watch.baseAsset,
            quote = watch.quoteAsset,
            contract = watch.contractType,
            savedCount = _state.value.savedCount,
        )
        startJob = viewModelScope.launch {
            val markets = try {
                marketRepository.getMarketList().filter { it.key != DEX_KEY }.sortedBy { it.name.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Börsenliste für «Paar bearbeiten» nicht verfügbar")
                emptyList()
            }
            _state.value = _state.value.copy(markets = markets)
            markets.firstOrNull { it.key == watch.marketKey }?.let { loadPairs(it) }
        }
    }

    fun setMarket(market: MarketInfo) {
        if (market == _state.value.market) return
        _state.value = _state.value.copy(duplicate = false)
        loadPairs(market)
    }

    fun setBase(base: String) = select { it.copy(base = base) }

    fun setQuote(quote: String) = select { it.copy(quote = quote) }

    fun setContract(contract: FuturesContractType) = select { it.copy(contract = contract) }

    /** Paare der Börse neu laden («Paare aktualisieren» bzw. «Erneut versuchen»). */
    fun sync() {
        val market = _state.value.market ?: return
        loadPairs(market, forceSync = true)
    }

    private fun select(change: (WatchEditState) -> WatchEditState) {
        _state.value = fitSelection(change(_state.value).copy(duplicate = false))
    }

    /**
     * Wie im Explorer: Coin, Gegenwert und Kontrakt bleiben, solange die Börse sie führt,
     * sonst der erste verfügbare.
     */
    private fun fitSelection(s: WatchEditState): WatchEditState {
        if (s.pairs == null) return s
        val bases = s.bases
        val base = s.base?.takeIf { it in bases } ?: bases.firstOrNull()
        val withBase = s.copy(base = base)
        val quotes = withBase.quotes
        val quote = s.quote?.takeIf { it in quotes } ?: quotes.firstOrNull()
        val withQuote = withBase.copy(quote = quote)
        val contracts = withQuote.contracts
        val contract = s.contract?.takeIf { it in contracts } ?: contracts.firstOrNull()
        return withQuote.copy(contract = contract)
    }

    private fun loadPairs(market: MarketInfo, forceSync: Boolean = false) {
        pairsJob?.cancel()
        _state.value = _state.value.copy(market = market, pairs = null, syncError = null, syncing = false)
        pairsJob = viewModelScope.launch {
            var info = runCatching { marketRepository.getMarketCurrencyPairsInfo(market) }.getOrNull()
            val canSync = marketRepository.isMarketSupportsUpdatePairs(market)
            if ((forceSync || info == null || info.pairs.isEmpty()) && canSync) {
                _state.value = _state.value.copy(syncing = true)
                try {
                    withContext(Dispatchers.IO) { marketRepository.updateMarketCurrencyPairs(market) }
                    info = marketRepository.getMarketCurrencyPairsInfo(market)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(syncError = e.message ?: e.javaClass.simpleName)
                }
            }
            _state.value = fitSelection(_state.value.copy(pairs = info ?: MarketPairsInfo(), syncing = false))
        }
    }

    /** Auswahl für [watchId] übernehmen; ein Doppel meldet [WatchEditState.duplicate]. */
    fun save(watchId: Long) {
        val s = _state.value
        val market = s.market ?: return
        val pair = s.pair ?: return
        if (s.saving) return
        _state.value = s.copy(saving = true, duplicate = false)
        viewModelScope.launch {
            val result = watchRepository.changePair(watchId, market, pair)
            val done = result == PairEditResult.SAVED || result == PairEditResult.UNCHANGED
            _state.value = _state.value.copy(
                saving = false,
                duplicate = result == PairEditResult.DUPLICATE,
                savedCount = _state.value.savedCount + if (done) 1 else 0,
            )
            if (result != PairEditResult.SAVED) return@launch
            // Alles, was am alten Paar hing: Signale, ⚡-Meldung, Kurs-Meldung
            activityRepository.forget(watchId)
            notifier.cancelActivity(watchId)
            notifier.cancelPrice(watchId)
            widgetUpdater.updateAll()
            // Gleich den neuen Kurs holen (aktualisiert auch die Widgets dieses Paars)
            priceRefresher.refreshOne(watchId)
            watchRepository.getWatch(watchId)?.lastPrice?.takeIf { it > 0.0 }?.let {
                watchRepository.setMissingPercentReferences(watchId, it)
            }
        }
    }

    private companion object {
        const val DEX_KEY = "DexScreener"
    }
}
