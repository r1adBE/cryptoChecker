package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCsvFormat
import com.cryptochecker.app.domain.portfolio.CutoffCsvTexts
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.TaxCsvTexts
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.gainRows
import com.cryptochecker.app.domain.portfolio.gainsCsv
import com.cryptochecker.app.domain.portfolio.incomeCsv
import com.cryptochecker.app.domain.portfolio.incomeRows
import com.cryptochecker.app.domain.portfolio.summaryCsv
import com.cryptochecker.app.domain.portfolio.yearSummary
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.TaxYearSummary
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.app.settings.inputDecimalSeparator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Ablauf des Stichtag-Exports. */
sealed interface CutoffExportState {
    data object Idle : CutoffExportState
    data object Running : CutoffExportState
    data object Done : CutoffExportState
    data object Failed : CutoffExportState
}

/** Welche Datei des Steuer-Exports ([TaxExport]). */
enum class TaxExportKind { GAINS, INCOME }

/**
 * Stichtag-Export: Bestand am Ende des Tags (Zeitzone des Geräts), Tagesschlusskurse
 * und Devisenkurs dieses Tags holen, CSV bauen und in die gewählte Datei schreiben.
 * Arbeitsunterlage ([exportTax], [exportSummary]): nach der Steuer-Vorlage (Einstellung «tax_template») — Methode
 * [TaxTemplate.methodFor], Steuerjahr der Vorlage, Währung der Vorlage (Neutral: Anzeigewährung); Devisen-Tageskurse über
 * [HistoricPriceSource.usdToSeries]; FR zusätzlich Tagesschlusskurse für den Gesamtwert des Portfolios bei jedem Verkauf.
 */
@HiltViewModel
class PortfolioExportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    internal val repository: PortfolioRepository,
    internal val historic: HistoricPriceSource,
    internal val priceSource: PortfolioPriceSource,
    internal val settingsRepository: SettingsRepository,
    /** USDT/USD-Tagesschlüsse (Kraken, [com.cryptochecker.app.data.portfolio.PortfolioHistorySource.usdtUsdDaily]). */
    internal val historySource: com.cryptochecker.app.data.portfolio.PortfolioHistorySource,
    /** Live-Kurs USDT/USD für einen Stichtag heute. */
    internal val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
) : ViewModel() {

    private val _state = MutableStateFlow<CutoffExportState>(CutoffExportState.Idle)
    val state: StateFlow<CutoffExportState> = _state.asStateFlow()

    /** Einstandsmethode (Methode der Vorlage, [TaxTemplate.methodFor]). */
    val costMethod: StateFlow<CostMethod> = settingsRepository.settings
        .map { it.costMethod }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.costMethod)

    /** Steuer-Vorlage der Arbeitsunterlage (Standard Neutral, nie still aus der Region übernommen). */
    val taxTemplate: StateFlow<TaxTemplate> = settingsRepository.settings
        .map { it.taxTemplate }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.taxTemplate)

    /** Hinweis zu den Länder-Vorlagen (Beta) bestätigt ([TaxTemplateConfirmDialog]). */
    val taxTemplateConfirmed: StateFlow<Boolean> = settingsRepository.settings
        .map { it.taxTemplateConfirmed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.taxTemplateConfirmed)

    fun confirmTaxTemplate() {
        viewModelScope.launch { settingsRepository.setTaxTemplateConfirmed() }
    }

    /** Eigene Transaktionen; null, bis geladen. */
    private val _trades = MutableStateFlow<List<PortfolioTrade>?>(null)

    /** Steuerjahre (Beginnjahr) der Vorlage mit Transaktionen (neueste zuerst); leer, bis geladen. */
    val years: StateFlow<List<Int>> = combine(_trades, taxTemplate) { t, tpl ->
        t?.let { TaxExport.years(it, tpl.zone(), tpl) }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Jahresübersicht (Orientierung) der Vorlage und des gewählten Jahrs für die Karte; null = noch nicht gerechnet. */
    private val _summary = MutableStateFlow<TaxYearSummary?>(null)
    val summary: StateFlow<TaxYearSummary?> = _summary.asStateFlow()
    private var summaryJob: Job? = null

    init {
        viewModelScope.launch {
            _trades.value = try {
                repository.getTransactions().map { it.toTrade() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Arbeitsunterlage: Transaktionen nicht geladen")
                emptyList()
            }
        }
    }

    /** Vorlage ausdrücklich wählen (z. B. «Vorschlag für deine Region übernehmen»). */
    fun setTaxTemplate(template: TaxTemplate) {
        viewModelScope.launch { settingsRepository.setTaxTemplate(template) }
    }

    /** Währung der Arbeitsunterlage: die der Vorlage, Neutral die Anzeigewährung [display]. */
    fun workCurrency(template: TaxTemplate, display: String): String = template.currency ?: display

    /** Jahresübersicht für [year] rechnen (ohne Devisenkurse: Werte in der Währung leer, Prüfungen «nicht prüfbar»). */
    fun loadSummary(year: Int, displayCurrency: String) {
        val tpl = taxTemplate.value
        val method = tpl.methodFor(costMethod.value, year)
        val currency = workCurrency(tpl, displayCurrency)
        val have = _summary.value
        val walletFrom = tpl.walletFrom(costMethod.value, year)
        if (have != null && have.period.startYear == year && have.template == tpl && have.method == method && have.currency == currency &&
            have.walletFrom == walletFrom
        ) return
        summaryJob?.cancel()
        _summary.value = null
        summaryJob = viewModelScope.launch {
            _summary.value = try {
                buildSummary(year, tpl, method, currency)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Jahresübersicht nicht gerechnet")
                null
            }
        }
    }

    internal suspend fun trades(): List<PortfolioTrade> = _trades.value ?: repository.getTransactions().map { it.toTrade() }

    /**
     * Eigene Transaktionen + Eröffnungsposten beobachteter Adressen als Käufe (Build 84,
     * [com.cryptochecker.app.domain.portfolio.AddressCostLots.exportTrades]); FR dazu der Gesamtwert samt beobachteter
     * Adressen («im Portfolio zählen») — ihre Guthaben-Änderungen als Adress-Einträge ([AddressBalanceHistory.trades],
     * negative Id: keine Veräusserungen) ohne den belegten Teil. Fehler: nur die eigenen Transaktionen.
     */
    private suspend fun withAddressTrades(trades: List<PortfolioTrade>, method: TaxMethod): List<PortfolioTrade> {
        return try {
            com.cryptochecker.app.domain.portfolio.AddressCostLots.exportTrades(
                trades, repository.getWatchedAddresses(), frTotalValue = method == TaxMethod.FR_PTA,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Steuer-Vorlage FR: Adressen nicht geladen")
            trades
        }
    }

    private suspend fun buildSummary(year: Int, tpl: TaxTemplate, method: TaxMethod, currency: String): TaxYearSummary {
        val trades = withAddressTrades(trades(), method)
        // Daten in der Zeitzone der Vorlage (Neutral: Gerät), wie in der CSV genannt
        val zone = tpl.zone()
        val period = tpl.period(year)
        val priceAt = taxPrices(tpl, method, trades, period, zone)
        // US: Übergang 1.1.2025 (universelles FIFO bis dahin, [TaxTemplate.walletFrom])
        val walletFrom = tpl.walletFrom(costMethod.value, year)
        val from = withContext(Dispatchers.Default) { TaxExport.fxFrom(trades, method, period, zone, priceAt, tpl.eventRules, walletFrom) }
        val rates = rates(currency, from, period.end)
        val usdtUsd = usdtUsdCloses(currency, from)
        // CH: Vermögenswert per 31.12. (Orientierung) — Tagesschlüsse und Adress-Guthaben wie der Stichtag-Export
        val wealth = if (tpl == TaxTemplate.CH) chYearEnd(trades, method, period.end, zone) else null
        return withContext(Dispatchers.Default) {
            TaxExport.yearSummary(
                trades, tpl, method, year, zone, currency, rates, priceAt, walletFrom, usdtUsd,
                yearEndCloses = wealth?.first, yearEndAddresses = wealth?.second,
            )
        }
    }

    /** CSV «Jahresübersicht» (Vorlage, Orientierung) für [year] in die Datei [uri]. */
    fun exportSummary(uri: Uri, year: Int, displayCurrency: String, texts: TaxSummaryTexts, header: List<String>, zoneLine: String) {
        if (_state.value == CutoffExportState.Running) return
        _state.value = CutoffExportState.Running
        viewModelScope.launch {
            _state.value = try {
                val tpl = taxTemplate.value
                val method = tpl.methodFor(costMethod.value, year)
                val currency = workCurrency(tpl, displayCurrency)
                val summary = _summary.value?.takeIf {
                    it.period.startYear == year && it.template == tpl && it.method == method && it.currency == currency &&
                        it.walletFrom == tpl.walletFrom(costMethod.value, year)
                } ?: buildSummary(year, tpl, method, currency)
                val format = CutoffCsvFormat.forLocale(deviceRegionLocale().country, inputDecimalSeparator())
                // CH-Vermögen mit Adressen mit Einstand: Hinweiszeile (nicht lesbar: ohne, die Adressen fehlen dann ohnehin)
                val addressLots = try {
                    AddressCostLots.hasCountedLots(repository.getWatchedAddresses())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                write(uri, TaxExport.summaryCsv(summary, texts, header, zoneLine, format, addressLots))
                CutoffExportState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Jahresübersicht fehlgeschlagen")
                CutoffExportState.Failed
            }
        }
    }

    private suspend fun write(uri: Uri, csv: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Datei kann nicht geschrieben werden")
        stream.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Arbeitsunterlage [kind] für das Steuerjahr ab [year] in die Datei [uri]; [header] = übersetzte Kopfzeilen (Titel,
     * Vorlage/Methode/Steuerjahr/Währung, Arbeitsunterlage – keine Steuererklärung, keine Steuerberatung).
     */
    fun exportTax(uri: Uri, kind: TaxExportKind, year: Int, displayCurrency: String, texts: TaxCsvTexts, header: List<String>) {
        if (_state.value == CutoffExportState.Running) return
        _state.value = CutoffExportState.Running
        viewModelScope.launch {
            _state.value = try {
                write(uri, buildTax(kind, year, displayCurrency, texts, header))
                CutoffExportState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Steuer-Export fehlgeschlagen")
                CutoffExportState.Failed
            }
        }
    }

    private suspend fun buildTax(kind: TaxExportKind, year: Int, displayCurrency: String, texts: TaxCsvTexts, header: List<String>): String {
        val trades = trades()
        val tpl = taxTemplate.value
        // Daten in der Zeitzone der Vorlage (Neutral: Gerät), wie in der CSV genannt
        val zone = tpl.zone()
        val method = tpl.methodFor(costMethod.value, year)
        val currency = workCurrency(tpl, displayCurrency)
        val period = tpl.period(year)
        val format = CutoffCsvFormat.forLocale(deviceRegionLocale().country, inputDecimalSeparator())
        return when (kind) {
            TaxExportKind.GAINS -> {
                val trades = withAddressTrades(trades, method)
                val priceAt = taxPrices(tpl, method, trades, period, zone)
                // Devisen-Tageskurse ab dem frühesten Kauf-/Herkunftsdatum (Einstand zum Kurs seines Datums)
                val walletFrom = tpl.walletFrom(costMethod.value, year)
                val from = withContext(Dispatchers.Default) { TaxExport.fxFrom(trades, method, period, zone, priceAt, tpl.eventRules, walletFrom) }
                val rates = rates(currency, from, period.end)
                // USDT zum Marktkurs: USDT/USD-Tagesschluss je Datum (ohne Schluss 1, je Zeile vermerkt)
                val usdtUsd = usdtUsdCloses(currency, from)
                withContext(Dispatchers.Default) {
                    TaxExport.gainsCsv(
                        TaxExport.gainRows(
                            trades, method, period, zone, currency, rates, priceAt, rules = tpl.eventRules,
                            walletFrom = walletFrom, usdtUsd = usdtUsd,
                        ), texts, header, format,
                        lostFeeCosts = TaxExport.lostFeeCostsIn(trades, method, period, zone, tpl.eventRules),
                        zone = zone,
                        unassignedFees = TaxExport.unassignedFeesIn(trades, method, period, zone, tpl.eventRules),
                    )
                }
            }
            TaxExportKind.INCOME -> {
                val rates = rates(currency, period.start, period.end)
                val usdtUsd = usdtUsdCloses(currency, period.start)
                // Belohnungen ohne Wert (Kraken/Binance) zum Tagesschluss geschätzt, wie in der Jahresübersicht
                val priceAt = feeDisposalPrices(trades, period, zone)
                TaxExport.incomeCsv(TaxExport.incomeRows(trades, period, zone, currency, rates, priceAt, usdtUsd), texts, header, format)
            }
        }
    }

    /**
     * Stichtag-Export; [header] = Kopfzeilen davor (Bestand der Arbeitsunterlage), leer = keine. [zone]: Ende des Tags in
     * dieser Zeitzone — für den Bestand der Arbeitsunterlage die der Vorlage, sonst die des Geräts. [method]: Einstandsmethode
     * des Bestands — für die Arbeitsunterlage die der Vorlage, null = die der App.
     */
    fun export(
        uri: Uri,
        date: LocalDate,
        currency: String,
        texts: CutoffCsvTexts,
        header: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        method: CostMethod? = null,
    ) {
        if (_state.value == CutoffExportState.Running) return
        _state.value = CutoffExportState.Running
        viewModelScope.launch {
            _state.value = try {
                val csv = build(date, currency, texts, header, zone, method)
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Datei kann nicht geschrieben werden")
                    stream.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
                CutoffExportState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Stichtag-Export fehlgeschlagen")
                CutoffExportState.Failed
            }
        }
    }

    fun consume() {
        if (_state.value != CutoffExportState.Running) _state.value = CutoffExportState.Idle
    }
}
