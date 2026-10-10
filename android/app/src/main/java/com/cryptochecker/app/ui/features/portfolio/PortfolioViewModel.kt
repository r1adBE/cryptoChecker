package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCompare
import com.cryptochecker.app.domain.portfolio.PortfolioCompareSeries
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistorySeries
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.watch.ChangeBasis
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
 * ([converted] = aus USDT umgerechnet, je Tag mit dem Devisenkurs dieses Tags, siehe
 * [PortfolioHistoryFx]; [approximateFx] = Tageskurse fehlten, alles mit dem heutigen Kurs —
 * die Karte sagt das unter dem Chart). Mit Tageskursen umgerechnet zusätzlich [usdtSeries] (derselbe
 * Verlauf unumgerechnet, für «USDT») und [compare] (beide in Prozent, für «Vergleich»; null ohne
 * Ausgangswert, siehe [PortfolioCompare]) — sonst beide null und kein Umschalter.
 */
data class PortfolioHistoryUi(
    val range: PortfolioHistoryRange,
    val series: PortfolioHistorySeries,
    val unit: String,
    val converted: Boolean,
    val approximateFx: Boolean = false,
    val usdtSeries: PortfolioHistorySeries? = null,
    val compare: PortfolioCompareSeries? = null,
)

/** Tageskurse USD → [currency] ab [from] (bis heute); [rates] null = nicht zu haben. */
private data class FxSeriesLoad(val currency: String, val from: LocalDate, val rates: Map<Long, Double>?)

/** Beginn der Devisenkurs-Abfrage für den Verlauf [range] (siehe [PortfolioHistoryFx.requestRange]). */
private fun fxSeriesFrom(txs: List<PortfolioTxEntity>, range: PortfolioHistoryRange): LocalDate {
    val today = LocalDate.now(ZoneId.systemDefault())
    val days = if (range == PortfolioHistoryRange.SINCE_FIRST) historyRequest(txs, range).days else range.days
    return PortfolioHistoryFx.requestRange(days, today).first
}

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
    private val alarmRepository: PortfolioAlarmRepository,
    private val historicSource: HistoricPriceSource,
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

    /**
     * Kursveränderung je Coin (gleiche Basis wie [todayPercent], aus der letzten Momentaufnahme)
     * für «Grösste Bewegungen»; leer = noch keine Vergleichsbasis.
     */
    private val _coinChanges = MutableStateFlow<Map<String, Double>>(emptyMap())
    val coinChanges: StateFlow<Map<String, Double>> = _coinChanges.asStateFlow()

    /** %-Basis (Beschriftung von «heute» und «Grösste Bewegungen»). */
    val changeBasis: StateFlow<ChangeBasis> = settingsRepository.settings
        .map { it.changeBasis.storage }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.changeBasis.storage)

    /** «Beträge verbergen»: alle Beträge als «•••», Prozente bleiben. */
    val hideAmounts: StateFlow<Boolean> = settingsRepository.settings
        .map { it.hidePortfolioAmounts }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.hidePortfolioAmounts)

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

    /** Darstellung des Wertverlaufs: Währung, USDT oder Vergleich (zuletzt gewählt, nur auf diesem Gerät). */
    val historyView: StateFlow<PortfolioHistoryView> = settingsRepository.settings
        .map { it.portfolioHistoryView }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioHistoryView)

    private val _closes = MutableStateFlow<HistoryCloses?>(null)

    /** Tageskurse USD → Umrechnungswährung für den Verlauf; null = noch nicht geladen. */
    private val _fxSeries = MutableStateFlow<FxSeriesLoad?>(null)

    /**
     * Wertverlauf des gewählten Zeitraums; null, solange dessen Tageskurse (und bei einer
     * Umrechnungswährung die Devisen-Tageskurse) noch laden (Platzhalter). Auch zugeklappt
     * gerechnet — die Zeile zeigt die Änderung —, aber nur für den gewählten Zeitraum.
     * Umgerechnet wird je Punkt mit dem Kurs seines Tags ([PortfolioHistoryFx]), heute mit dem
     * aktuellen. Gerechnet abseits des Hauptthreads.
     */
    val history: StateFlow<PortfolioHistoryUi?> = combine(
        transactions,
        _closes,
        _prices,
        historyRange,
        combine(currency, _fxRate, _fxSeries) { code, fx, series -> Triple(code, fx, series) },
    ) { txs, loaded, p, range, (code, fx, fxSeries) ->
        if (txs == null || loaded == null) return@combine null
        if (!loaded.covers(historyRequest(txs, range))) return@combine null
        val converted = code != "USD" && fx != null
        // Devisen-Tageskurse dieses Zeitraums noch nicht da: Platzhalter statt still falscher Kurve
        if (converted && (fxSeries == null || fxSeries.currency != code || fxSeries.from.isAfter(fxSeriesFrom(txs, range)))) {
            return@combine null
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).toEpochDay()
        val series = PortfolioHistory.build(
            trades = txs.map { it.toTrade() },
            closes = loaded.closes,
            livePrices = p.prices,
            range = range,
            todayEpochDay = today,
            dayEndMillis = dayEndIn(zone),
        )
        if (!converted) return@combine PortfolioHistoryUi(range, series, PortfolioFormat.USDT, false)
        val result = PortfolioHistoryFx.convert(series, fxSeries?.rates, fx, today)
        // Vergleich mit USDT nur mit Tageskursen — mit dem heutigen Kurs wäre er bedeutungslos
        if (result.approximate) return@combine PortfolioHistoryUi(range, result.series, code, true, true)
        PortfolioHistoryUi(
            range = range,
            series = result.series,
            unit = code,
            converted = true,
            approximateFx = false,
            usdtSeries = series,
            compare = PortfolioCompare.build(result.series.points, series.points),
        )
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
                        ?.let { snapshot ->
                            _todayPercent.value = snapshot.changePercent?.takeUnless { snapshot.empty }
                            _coinChanges.value = snapshot.coinChanges
                        }
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
        // Devisen-Tageskurse für den Verlauf: eine Abfrage je Währung und Zeitraum (6 h zwischengespeichert)
        viewModelScope.launch {
            combine(currency, transactions.filterNotNull(), historyRange) { code, txs, range ->
                code to fxSeriesFrom(txs, range)
            }
                .distinctUntilChanged()
                .collectLatest { (code, from) -> loadFxSeries(code, from) }
        }
    }

    private suspend fun loadFxSeries(code: String, from: LocalDate) {
        if (code == "USD") return
        val rates = safe { historicSource.usdToSeries(code, from, LocalDate.now(ZoneId.systemDefault())) }
            ?.takeIf { it.isNotEmpty() }
        _fxSeries.value = FxSeriesLoad(code, from, rates)
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
                // Fehlten die Devisen-Tageskurse, erneut versuchen
                if (_fxSeries.value?.rates == null) loadFxSeries(currency.value, fxSeriesFrom(txs, historyRange.value))
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

    fun setHistoryView(view: PortfolioHistoryView) {
        viewModelScope.launch { settingsRepository.setPortfolioHistoryView(view) }
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

    /**
     * Wischen auf einem Coin: alle seine Transaktionen löschen; [onDeleted] erhält sie
     * für «Rückgängig» ([restore]).
     */
    fun deleteCoin(coin: String, onDeleted: (List<PortfolioTxEntity>) -> Unit) {
        viewModelScope.launch {
            val removed = safe { repository.deleteCoin(coin) }.orEmpty()
            if (removed.isNotEmpty()) onDeleted(removed)
        }
    }

    /** «Rückgängig»: gelöschte Transaktionen mit ihren ids wieder anlegen. */
    fun restore(transactions: List<PortfolioTxEntity>) {
        viewModelScope.launch { safe { repository.restore(transactions) } }
    }

    /** «Portfolio leeren» (nach Bestätigung): alle Transaktionen löschen. */
    fun clearAll() {
        viewModelScope.launch { safe { repository.clearAll() } }
    }

    /** «Beträge verbergen» umschalten; das Portfolio-Widget zeichnet gleich neu. */
    fun setHideAmounts(hidden: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHidePortfolioAmounts(hidden)
            safe { snapshotUpdater.redrawWidgets() }
        }
    }

    /** Neuer Portfolio-Alarm; Beträge in der Umrechnungswährung. */
    fun addAlarm(kind: PortfolioAlarmKind, threshold: Double, repeating: Boolean) {
        viewModelScope.launch {
            safe { alarmRepository.add(kind, threshold, currency.value, repeating) }
            // Neu und scharf: gleich mit den aktuellen Kursen prüfen (und das Widget nachziehen)
            val txs = transactions.value ?: return@launch
            if (txs.isNotEmpty() && _prices.value.updatedAt > 0L) {
                safe { snapshotUpdater.record(txs, _prices.value.prices, currency.value, _fxRate.value) }
            }
        }
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
