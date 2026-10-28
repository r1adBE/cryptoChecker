package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.PairEditResult
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.marketdata.model.ExchangePair
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
    val exchanges: List<ExchangeInfo>? = null,
    val exchange: ExchangeInfo? = null,
    val pairs: ExchangePairsInfo? = null,
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
    val pair: ExchangePair?
        get() {
            val b = base ?: return null
            val q = quote ?: return null
            return pairs?.getExchangePair(b, q, contract ?: FuturesContractType.NONE)
        }

    /** Schlüssel der Auswahl ([WatchEdit.Key]); null ohne vollständige Auswahl. */
    val key: WatchEdit.Key?
        get() {
            val m = exchange ?: return null
            val p = pair ?: return null
            return WatchEdit.Key.of(m.key, p.base, p.quote, p.contractType.name, p.pairId)
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
    private val exchangeRepository: ExchangeRepository,
    private val watchRepository: WatchRepository,
    private val favoritesRepository: FavoritesRepository,
    private val priceRefresher: PriceRefresher,
    private val notifier: AppNotifier,
    private val widgetUpdater: WidgetUpdater,
    private val activityRepository: ActivityRepository,
    private val livePriceStream: LivePriceStream,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchEditState())
    val state: StateFlow<WatchEditState> = _state.asStateFlow()

    val favoriteExchanges: StateFlow<Set<String>> = favoritesRepository.favorites(FavoriteKind.EXCHANGE)
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
            val exchanges = try {
                exchangeRepository.getExchangeList().filter { it.key != DEX_KEY }.sortedBy { it.name.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Börsenliste für «Paar bearbeiten» nicht verfügbar")
                emptyList()
            }
            _state.value = _state.value.copy(exchanges = exchanges)
            exchanges.firstOrNull { it.key == watch.exchangeKey }?.let { loadPairs(it) }
        }
    }

    fun setExchange(exchange: ExchangeInfo) {
        if (exchange == _state.value.exchange) return
        _state.value = _state.value.copy(duplicate = false)
        loadPairs(exchange)
    }

    fun setBase(base: String) = select { it.copy(base = base) }

    fun setQuote(quote: String) = select { it.copy(quote = quote) }

    fun setContract(contract: FuturesContractType) = select { it.copy(contract = contract) }

    /** Paare der Börse neu laden («Paare aktualisieren» bzw. «Erneut versuchen»). */
    fun sync() {
        val exchange = _state.value.exchange ?: return
        loadPairs(exchange, forceSync = true)
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

    private fun loadPairs(exchange: ExchangeInfo, forceSync: Boolean = false) {
        pairsJob?.cancel()
        _state.value = _state.value.copy(exchange = exchange, pairs = null, syncError = null, syncing = false)
        pairsJob = viewModelScope.launch {
            var info = runCatchingCancellable { exchangeRepository.getExchangeCurrencyPairsInfo(exchange) }.getOrNull()
            val canSync = exchangeRepository.isExchangeSupportsUpdatePairs(exchange)
            if ((forceSync || info == null || info.pairs.isEmpty()) && canSync) {
                _state.value = _state.value.copy(syncing = true)
                try {
                    withContext(Dispatchers.IO) { exchangeRepository.updateExchangeCurrencyPairs(exchange) }
                    info = exchangeRepository.getExchangeCurrencyPairsInfo(exchange)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(syncError = e.message ?: e.javaClass.simpleName)
                }
            }
            _state.value = fitSelection(_state.value.copy(pairs = info ?: ExchangePairsInfo(), syncing = false))
        }
    }

    /** Auswahl für [watchId] übernehmen; ein Doppel meldet [WatchEditState.duplicate]. */
    fun save(watchId: Long) {
        val s = _state.value
        val exchange = s.exchange ?: return
        val pair = s.pair ?: return
        if (s.saving) return
        _state.value = s.copy(saving = true, duplicate = false)
        viewModelScope.launch {
            // Rückfrage «nicht gespeichert» VOR der Refresh-Sperre — ein offener Dialog hält keine Kurse auf
            if (!watchRepository.confirmUserWrite()) {
                _state.value = _state.value.copy(saving = false)
                return@launch
            }
            // Unter der Refresh-Sperre: Kein laufender Durchlauf (REST oder Live) schreibt danach noch den
            // Kurs des alten Paars, der sonst zum Bezug des neuen würde (riesiger %-Alarm, falsche Kursmarke).
            // Gepufferte Live-Kurse des alten Paars gleich mit vergessen. Nie refreshOne in der Sperre (Verklemmung).
            val result = priceRefresher.withRefreshLock {
                watchRepository.changePair(watchId, exchange, pair).also {
                    if (it == PairEditResult.SAVED) livePriceStream.forget(listOf(watchId))
                }
            }
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
