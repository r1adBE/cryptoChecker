package com.cryptochecker.app.widget

import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioAlarmChecker
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.watch.ChangeBasisMath
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
 *
 * Im Live-Dienst bei ausgeschaltetem Bildschirm wird weiter aufgenommen (Wertverlauf,
 * Alarme), aber nicht gezeichnet ([LiveWidgetGate]) — nachgezeichnet wird beim Einschalten.
 */
@Singleton
class PortfolioSnapshotUpdater @Inject constructor(
    private val repository: PortfolioRepository,
    private val priceSource: PortfolioPriceSource,
    private val fxSource: FxRateSource,
    private val settingsRepository: SettingsRepository,
    private val store: PortfolioSnapshotStore,
    private val widgetUpdater: WidgetUpdater,
    private val liveWidgetGate: LiveWidgetGate,
    private val sparklineRepository: SparklineRepository,
    private val alarmChecker: PortfolioAlarmChecker,
) {
    private val mutex = Mutex()

    /**
     * Hintergrund-Aktualisierung: nur mit Portfolio-Widget auf dem Startbildschirm oder einem
     * scharfen Portfolio-Alarm (der wird nach jeder Aufnahme geprüft, siehe [record]).
     * @param deferWidgetsWhenScreenOff true im Live-Dienst: siehe [record]
     */
    suspend fun refreshIfWidgets(deferWidgetsWhenScreenOff: Boolean = false) {
        if (widgetUpdater.portfolioWidgetIds().isEmpty() && !hasActiveAlarms()) return
        refresh(deferWidgetsWhenScreenOff)
    }

    /** Portfolio-Widgets ohne neue Aufnahme neu zeichnen (z. B. «Beträge verbergen» umgeschaltet). */
    suspend fun redrawWidgets() = widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())

    private suspend fun hasActiveAlarms(): Boolean = try {
        alarmChecker.hasActiveAlarms()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Kurse (60 s zwischengespeichert) und Devisenkurs holen, dann aufnehmen. Fehler bleiben still. */
    suspend fun refresh(deferWidgetsWhenScreenOff: Boolean = false): Unit = withContext(Dispatchers.IO) {
        try {
            val transactions = repository.getTransactions()
            val coins = transactions.map { it.coin }.toSet()
            val prices = if (coins.isEmpty()) emptyMap() else priceSource.prices(coins).prices
            val currency = settingsRepository.current().portfolioCurrency
            record(transactions, prices, currency, fxSource.usdTo(currency), deferWidgetsWhenScreenOff)
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
     * @param deferWidgetsWhenScreenOff true im Live-Dienst: bei ausgeschaltetem Bildschirm nur
     *   aufnehmen, das Zeichnen holt [LiveWidgetGate] beim Einschalten nach
     * @return die neue Momentaufnahme (auch für «heute» im Portfolio-Tab); null, wenn
     *   mangels Kursen nichts aufgenommen wurde
     */
    suspend fun record(
        transactions: List<PortfolioTxEntity>,
        prices: Map<String, Double>,
        currency: String,
        fxRate: Double?,
        deferWidgetsWhenScreenOff: Boolean = false,
    ): PortfolioSnapshot? = withContext(Dispatchers.IO) {
        val basis = settingsRepository.current().changeBasis
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

            // Stundenkurse für den 24-h-Wertverlauf: gemerkte, dazu was ohnehin schon geladen
            // ist (Mini-Charts, Einzel-Widgets, eigene Kursaufnahmen) — keine eigene Abfrage
            val hourlyPrices = PortfolioWidgetSeries.merge(
                stored = store.hourlyPrices(),
                fresh = cachedHourlyPrices(holdings.keys, history, current, now),
                now = now,
            ).filterKeys { coin -> holdings.keys.any { it.equals(coin, ignoreCase = true) } }

            val snapshot = PortfolioSnapshotMath.snapshot(
                holdings = holdings,
                totalUsd = summary.totalValue,
                current = current,
                history = history,
                now = now,
                fxRate = rate,
                currency = code,
                hourlyPrices = hourlyPrices,
                stables = CurrencyConversion.USD_STABLES,
                // %-Basis: rollend oder seit Tagesbeginn (UTC/Ortszeit)
                stamp = ChangeBasisMath.stamp(basis, now),
            )
            store.save(snapshot, PortfolioSnapshotMath.addSample(history, PriceSample(now, current)), hourlyPrices)
            snapshot
        }
        if (liveWidgetGate.allowPartial(deferWidgetsWhenScreenOff)) {
            widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
        }
        // Portfolio-Alarme nach jeder neuen Aufnahme (Fehler bleiben still)
        if (recorded != null) {
            try {
                alarmChecker.check(recorded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Portfolio-Alarme: Prüfung fehlgeschlagen")
            }
        }
        recorded
    }

    /**
     * Bereits geladene Stundenkurse der [coins] — ohne Netz: Mini-Charts der Merkliste
     * ([SparklineRepository]), Kerzen der Einzel-Widgets, die Kursaufnahmen ([history]) und
     * der aktuelle Kurs. Zusammengeführt wird später ([PortfolioWidgetSeries.merge]).
     */
    private fun cachedHourlyPrices(
        coins: Set<String>,
        history: List<PriceSample>,
        current: Map<String, Double>,
        now: Long,
    ): Map<String, List<TimedPrice>> {
        val out = HashMap<String, List<TimedPrice>>()
        for (coin in coins) {
            if (coin.uppercase() in CurrencyConversion.USD_STABLES) continue
            val fromSparkline = sparklineRepository.cachedWithTime(coin)
                ?.let { (time, closes) -> PortfolioWidgetSeries.fromHourlyCloses(closes, time) }
                .orEmpty()
            val fromWidget = widgetUpdater.cachedHourlyPrices(coin).orEmpty()
            val fromSamples = history.mapNotNull { sample ->
                sample.prices[coin]?.takeIf { now - sample.time in 0..PortfolioWidgetSeries.KEEP_MILLIS }
                    ?.let { TimedPrice(sample.time, it) }
            }
            val all = fromSamples + fromSparkline + fromWidget + listOfNotNull(current[coin]?.let { TimedPrice(now, it) })
            if (all.isNotEmpty()) out[coin] = all
        }
        return out
    }
}
