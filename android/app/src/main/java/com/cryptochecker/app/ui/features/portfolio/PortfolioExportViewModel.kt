package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.HistoricFx
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.portfolio.CutoffCsvTexts
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Stichtag-Export: Bestand am Ende des Tags (Zeitzone des Geräts), Tagesschlusskurse
 * und Devisenkurs dieses Tags holen, CSV bauen und in die gewählte Datei schreiben.
 */
@HiltViewModel
class PortfolioExportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: PortfolioRepository,
    private val historic: HistoricPriceSource,
    private val priceSource: PortfolioPriceSource,
) : ViewModel() {

    private val _state = MutableStateFlow<CutoffExportState>(CutoffExportState.Idle)
    val state: StateFlow<CutoffExportState> = _state.asStateFlow()

    fun export(uri: Uri, date: LocalDate, currency: String, texts: CutoffCsvTexts) {
        if (_state.value == CutoffExportState.Running) return
        _state.value = CutoffExportState.Running
        viewModelScope.launch {
            _state.value = try {
                val csv = build(date, currency, texts)
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

    private suspend fun build(date: LocalDate, currency: String, texts: CutoffCsvTexts): String {
        val trades = repository.getTransactions().map { it.toTrade() }
        val cutoff = CutoffExport.endOfDayMillis(date, ZoneId.systemDefault())
        val holdings = CutoffExport.holdingsAt(trades, cutoff)
        // USDT = 1 ohne Abfrage; andere Stablecoins wie jeder Coin (fehlt der Kurs, gilt 1)
        val coins = holdings.map { it.coin }.filter { PortfolioStables.needsQuote(it) }
        // Aktuelle Kurse als Bezug der Plausibilitätsprüfung (Ausweich-Quellen); ohne sie ungeprüft
        val current = try {
            if (coins.isEmpty()) emptyMap() else priceSource.prices(coins).prices
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val prices = historic.dailyClosesUsdt(coins, date, current)
        val fx: HistoricFx? = if (holdings.isEmpty() && currency != "USD") null else historic.usdTo(currency, date)
        val rows = CutoffExport.rows(holdings, prices, fx?.rate)
        return CutoffExport.csv(date, currency, fx?.date, rows, texts)
    }
}
