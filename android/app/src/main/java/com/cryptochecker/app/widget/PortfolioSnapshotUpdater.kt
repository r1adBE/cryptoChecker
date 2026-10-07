package com.cryptochecker.app.widget

import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berechnet die Momentaufnahme für das Portfolio-Widget (Gesamtwert, «heute»,
 * Währung, Zeit; dazu USDT-Gesamtwert, grösste Positionen und Wertverlauf aus den
 * vorhandenen Kursen — ohne zusätzliche Abfragen) und zeichnet die Portfolio-Widgets neu.
 *
 * Aufgerufen, wenn die App die Portfolio-Werte berechnet ([record], ohne Netz) und
 * bei jeder Hintergrund-Aktualisierung ([refreshIfWidgets], holt Kurse — nur wenn ein
 * Portfolio-Widget liegt). So hängt das Widget nicht vom Öffnen der App ab.
 */
@Singleton
class PortfolioSnapshotUpdater @Inject constructor(
    private val repository: PortfolioRepository,
    private val priceSource: PortfolioPriceSource,
    private val fxSource: FxRateSource,
    private val settingsRepository: SettingsRepository,
    private val store: PortfolioSnapshotStore,
    private val widgetUpdater: WidgetUpdater,
) {
    private val mutex = Mutex()

    /** Hintergrund-Aktualisierung: nur mit Portfolio-Widget auf dem Startbildschirm. */
    suspend fun refreshIfWidgets() {
        if (widgetUpdater.portfolioWidgetIds().isEmpty()) return
        refresh()
    }

    /** Kurse (60 s zwischengespeichert) und Devisenkurs holen, dann aufnehmen. Fehler bleiben still. */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        try {
            val transactions = repository.getTransactions()
            val coins = transactions.map { it.coin }.toSet()
            val prices = if (coins.isEmpty()) emptyMap() else priceSource.prices(coins).prices
            val currency = settingsRepository.current().portfolioCurrency
            record(transactions, prices, currency, fxSource.usdTo(currency))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Portfolio-Widget: Aktualisierung fehlgeschlagen")
        }
    }

    /**
     * Nimmt die aktuelle Lage auf und zeichnet die Widgets neu.
     * @param prices USDT-Kurs je Coin
     * @param fxRate USD → [currency]; null = unbekannt, dann in USD
     * @return die neue Momentaufnahme (auch für «heute» im Portfolio-Tab); null, wenn
     *   mangels Kursen nichts aufgenommen wurde
     */
    suspend fun record(
        transactions: List<PortfolioTxEntity>,
        prices: Map<String, Double>,
        currency: String,
        fxRate: Double?,
    ): PortfolioSnapshot? = withContext(Dispatchers.IO) {
        val recorded = mutex.withLock {
            val summary = PortfolioCalculator.summarize(transactions.map { it.toTrade() }, prices)
            // Noch kein einziger Kurs bekannt: lieber die letzte Aufnahme stehen lassen als 0 zeigen
            if (summary.open.isNotEmpty() && summary.open.all { it.value == null }) return@withLock null

            val useFx = fxRate != null && fxRate > 0.0
            val rate = if (useFx) fxRate else 1.0
            val code = if (useFx) currency else "USD"
            val now = System.currentTimeMillis()
            val history = store.history()
            val holdings = summary.open.associate { it.coin to it.holdings }
            // Aktuelle Kurse der offenen Coins (USDT = 1 setzt der Rechner selbst)
            val current = summary.open.mapNotNull { p -> p.currentPrice?.let { p.coin to it } }.toMap()

            val snapshot = PortfolioSnapshotMath.snapshot(
                holdings = holdings,
                totalUsd = summary.totalValue,
                current = current,
                history = history,
                now = now,
                fxRate = rate,
                currency = code,
            )
            store.save(snapshot, PortfolioSnapshotMath.addSample(history, PriceSample(now, current)))
            snapshot
        }
        widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
        recorded
    }
}
