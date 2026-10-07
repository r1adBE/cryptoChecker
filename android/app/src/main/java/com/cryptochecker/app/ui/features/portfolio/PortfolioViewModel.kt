package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistorySeries
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Eingabe des Erfassen-Blatts; id 0 = neue Transaktion. */
data class TxDraft(
    val id: Long = 0,
    val coin: String = "",
    val type: PortfolioTxType = PortfolioTxType.BUY,
    val amount: Double? = null,
    val priceUsdt: Double? = null,
    val time: Long = System.currentTimeMillis(),
    val note: String? = null,
)

fun PortfolioTxEntity.toDraft() = TxDraft(
    id = id,
    coin = coin,
    type = type,
    amount = amount,
    priceUsdt = priceUsdt,
    time = time,
    note = note,
)

/**
 * Wertverlauf für die Karte über den Positionen: [series] in [unit]
 * ([converted] = mit dem heutigen Devisenkurs aus USDT umgerechnet).
 */
data class PortfolioHistoryUi(
    val range: PortfolioHistoryRange,
    val series: PortfolioHistorySeries,
    val unit: String,
    val converted: Boolean,
)

/** Welche Tagesschlusskurse der gewählte Zeitraum braucht: Coins und Tage bis heute. */
private data class HistoryRequest(val coins: Set<String>, val days: Int)

/** Geladene Tagesschlusskurse und für welche Anfrage. */
private data class HistoryCloses(val request: HistoryRequest, val closes: Map<String, Map<Long, Double>>) {
    /** Deckt diese Ladung [needed] ab (alle Coins, genug Tage)? */
    fun covers(needed: HistoryRequest): Boolean =
        request.coins.containsAll(needed.coins) && request.days >= needed.days
}

/** Letzte Millisekunde eines Tags in [zone]. */
private fun dayEndIn(zone: ZoneId): (Long) -> Long = { day ->
    LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
}

/** Anfrage für [range]: «Seit 1. Kauf» braucht je nach erstem Kauf mehr Tage (siehe [PortfolioHistory.candleDays]). */
private fun historyRequest(txs: List<PortfolioTxEntity>, range: PortfolioHistoryRange): HistoryRequest {
    val zone = ZoneId.systemDefault()
    val first = txs.filter { it.amount > 0.0 && !it.amount.isInfinite() }.minOfOrNull { it.time }
    val days = PortfolioHistory.candleDays(range, first, LocalDate.now(zone).toEpochDay(), dayEndIn(zone))
    return HistoryRequest(txs.map { PortfolioCalculator.normalizeCoin(it.coin) }.toSet(), days)
}

/** Was das Portfolio-Widget aus einer Berechnung braucht. */
private data class PortfolioWidgetInput(
    val transactions: List<PortfolioTxEntity>,
    val prices: PortfolioPrices,
    val currency: String,
    val fxRate: Double?,
)

/**
 * Portfolio-Tab, Detailansicht und Erfassen-Blatt (auch aus der Merkliste).
 * Kurse werden erst geladen, wenn eine Ansicht [start] aufruft — das Blatt
 * allein holt nur den Kurs des gewählten Coins.
 */
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val repository: PortfolioRepository,
    private val priceSource: PortfolioPriceSource,
    private val fxSource: FxRateSource,
    private val settingsRepository: SettingsRepository,
    private val snapshotUpdater: PortfolioSnapshotUpdater,
    private val historySource: PortfolioHistorySource,
) : ViewModel() {

    /** null, bis die Datenbank geantwortet hat. Neueste zuerst. */
    val transactions: StateFlow<List<PortfolioTxEntity>?> = repository.observeTransactions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _prices = MutableStateFlow(PortfolioPrices())
    val prices: StateFlow<PortfolioPrices> = _prices.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val currency: StateFlow<String> = settingsRepository.settings
        .map { it.portfolioCurrency }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioCurrency)

    /** USD → [currency]; null = unbekannt (Umrechnungszeile ausblenden). */
    private val _fxRate = MutableStateFlow<Double?>(null)
    val fxRate: StateFlow<Double?> = _fxRate.asStateFlow()

    /**
     * Veränderung «heute» in Prozent wie im Portfolio-Widget (aus der letzten
     * Momentaufnahme, siehe PortfolioSnapshotMath); null = noch keine Vergleichsbasis.
     */
    private val _todayPercent = MutableStateFlow<Double?>(null)
    val todayPercent: StateFlow<Double?> = _todayPercent.asStateFlow()

    val summary: StateFlow<PortfolioSummary?> = combine(transactions, _prices) { txs, p ->
        txs?.let { list -> PortfolioCalculator.summarize(list.map { it.toTrade() }, p.prices) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Zeitraum des Wertverlaufs (zuletzt gewählt, nur auf diesem Gerät). */
    val historyRange: StateFlow<PortfolioHistoryRange> = settingsRepository.settings
        .map { it.portfolioHistoryRange }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryRange)

    /** Karte «Wertverlauf» aufgeklappt (Standard zu; nur auf diesem Gerät). */
    val historyExpanded: StateFlow<Boolean> = settingsRepository.settings
        .map { it.portfolioHistoryExpanded }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryExpanded)

    private val _closes = MutableStateFlow<HistoryCloses?>(null)

    /**
     * Wertverlauf des gewählten Zeitraums; null, solange dessen Tageskurse noch laden
     * (Platzhalter). Auch zugeklappt gerechnet — die Zeile zeigt die Änderung —, aber nur
     * für den gewählten Zeitraum. Gerechnet abseits des Hauptthreads.
     */
    val history: StateFlow<PortfolioHistoryUi?> = combine(
        transactions,
        _closes,
        _prices,
        historyRange,
        combine(currency, _fxRate) { code, fx -> code to fx },
    ) { txs, loaded, p, range, (code, fx) ->
        if (txs == null || loaded == null) return@combine null
        if (!loaded.covers(historyRequest(txs, range))) return@combine null
        val converted = code != "USD" && fx != null
        val zone = ZoneId.systemDefault()
        val series = PortfolioHistory.build(
            trades = txs.map { it.toTrade() },
            closes = loaded.closes,
            livePrices = p.prices,
            range = range,
            todayEpochDay = LocalDate.now(zone).toEpochDay(),
            dayEndMillis = dayEndIn(zone),
            fxRate = fx?.takeIf { converted } ?: 1.0,
        )
        PortfolioHistoryUi(range, series, if (converted) code else PortfolioFormat.USDT, converted)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _coins = MutableStateFlow<List<String>>(emptyList())

    /** Wählbare Coins für die Suche (leer, solange nicht geladen). */
    val coins: StateFlow<List<String>> = _coins.asStateFlow()

    private var started = false

    init {
        viewModelScope.launch { repository.migrateHoldingsOnce() }
        // Portfolio-Widget: jede neue Berechnung (Transaktionen, Kurse, Währung) als Momentaufnahme
        viewModelScope.launch {
            combine(transactions.filterNotNull(), _prices, currency, _fxRate) { txs, p, code, fx ->
                PortfolioWidgetInput(txs, p, code, fx)
            }
                .distinctUntilChanged()
                .collect { input ->
                    // Ohne geladene Kurse nichts aufnehmen (sonst stünde kurz 0 im Widget)
                    if (input.transactions.isNotEmpty() && input.prices.updatedAt <= 0L) return@collect
                    safe { snapshotUpdater.record(input.transactions, input.prices.prices, input.currency, input.fxRate) }
                        ?.let { snapshot -> _todayPercent.value = snapshot.changePercent?.takeUnless { snapshot.empty } }
                }
        }
    }

    /** Von Tab und Detailansicht: Kurse laden und bei neuen Coins nachladen. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            transactions.filterNotNull()
                .map { list -> list.map { it.coin }.toSet() }
                .distinctUntilChanged()
                .collect { coins ->
                    _prices.value = priceSource.cached(coins)
                    loadPrices(coins, force = false)
                }
        }
        // Verlauf parallel und nur für den gewählten Zeitraum: Tageskerzen sind je Coin 12 h
        // zwischengespeichert; ein überholter Abruf (Coins oder Zeitraum geändert) wird abgebrochen
        viewModelScope.launch {
            combine(transactions.filterNotNull(), historyRange) { txs, range -> historyRequest(txs, range) }
                .distinctUntilChanged()
                .collectLatest { request -> loadCloses(request) }
        }
        viewModelScope.launch {
            currency.collect { code ->
                _fxRate.value = fxSource.cached(code)
                _fxRate.value = safe { fxSource.usdTo(code) }
            }
        }
    }

    /** Beim Öffnen (60 s Zwischenspeicher) bzw. per Ziehen ([force]). */
    fun refresh(force: Boolean) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val txs = transactions.value ?: repository.getTransactions()
                loadPrices(txs.map { it.coin }.toSet(), force)
                // Fehlgeschlagene Coins erneut versuchen (der Rest kommt aus dem Zwischenspeicher)
                loadCloses(historyRequest(txs, historyRange.value))
                _fxRate.value = safe { fxSource.usdTo(currency.value) } ?: _fxRate.value
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun loadPrices(coins: Set<String>, force: Boolean) {
        if (coins.isEmpty()) return
        safe { priceSource.prices(coins, force) }?.let { _prices.value = it }
    }

    private suspend fun loadCloses(request: HistoryRequest) {
        val closes = safe { historySource.dailyCloses(request.coins, request.days) } ?: emptyMap()
        // Ein überholter Abruf (Coins oder Zeitraum inzwischen geändert) darf den neueren nicht ersetzen
        val current = historyRequest(transactions.value.orEmpty(), historyRange.value)
        if (HistoryCloses(request, closes).covers(current)) _closes.value = HistoryCloses(request, closes)
    }

    fun setHistoryRange(range: PortfolioHistoryRange) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryRange(range) }
    }

    fun setHistoryExpanded(expanded: Boolean) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryExpanded(expanded) }
    }

    fun loadCoins() {
        if (_coins.value.isNotEmpty()) return
        viewModelScope.launch { _coins.value = safe { priceSource.coins() }.orEmpty() }
    }

    /** Aktueller USDT-Kurs zum Vorbelegen; null, wenn keiner zu haben ist. */
    suspend fun currentPrice(coin: String): Double? = safe { priceSource.price(coin) }

    /** Bestand eines Coins ohne die Transaktion [excludingId] (für die Verkaufs-Warnung). */
    fun holdingsOf(coin: String, excludingId: Long): Double {
        val trades = transactions.value.orEmpty().filter { it.id != excludingId }.map { it.toTrade() }
        return PortfolioCalculator.position(coin, trades, null).holdings
    }

    fun save(draft: TxDraft) {
        val amount = draft.amount ?: return
        viewModelScope.launch {
            runCatching {
                repository.save(
                    PortfolioTxEntity(
                        id = draft.id,
                        coin = draft.coin,
                        type = draft.type,
                        amount = amount,
                        priceUsdt = draft.priceUsdt,
                        time = draft.time,
                        note = draft.note,
                    )
                )
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun setCurrency(code: String) {
        viewModelScope.launch { settingsRepository.setPortfolioCurrency(code) }
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
