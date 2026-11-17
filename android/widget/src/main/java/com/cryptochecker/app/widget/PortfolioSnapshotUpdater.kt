package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
import com.cryptochecker.app.data.portfolio.withAddresses
import com.cryptochecker.app.domain.portfolio.AddressAlarmState
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressBooked
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFigures
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFx
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioPrices
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.PortfolioSummaryCache
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Berechnet die Momentaufnahme für das Portfolio-Widget (Gesamtwert, «heute»,
 * Währung, Zeit; dazu USDT-Gesamtwert, grösste Positionen und Wertverlauf aus den
 * vorhandenen Kursen; fehlen einem Coin Stundenkurse, lädt [refresh] einmal je Stunde
 * seine Stundenkerzen) und zeichnet die Portfolio-Widgets neu.
 *
 * Aufgerufen, wenn die App die Portfolio-Werte berechnet ([record], ohne Netz) und
 * bei jeder Hintergrund-Aktualisierung ([refreshIfWidgets], holt Kurse — nur wenn ein
 * Portfolio-Widget liegt). So hängt das Widget nicht vom Öffnen der App ab.
 */
@Singleton
class PortfolioSnapshotUpdater @Inject constructor(
    internal val repository: PortfolioRepository,
    private val priceSource: PortfolioPriceSource,
    internal val fxSource: FxRateSource,
    internal val settingsRepository: SettingsRepository,
    internal val store: PortfolioSnapshotStore,
    internal val widgetUpdater: WidgetUpdater,
    /** Stundenkurse für den 24-h-Wertverlauf (fehlende Kerzen laden, vorhandene einsammeln). */
    internal val hourlyCandles: PortfolioHourlyCandles,
    internal val alarmChecker: PortfolioAlarmCheck,
    private val historySource: PortfolioHistorySource,
    /** Guthaben beobachteter Adressen (10 min Zwischenspeicher; ohne Adressen keine Anfrage). */
    private val addressRefresher: WatchedAddressRefresher,
    /** Live-Kurs USDT/USD: Faktor USDT → Währung der Aufnahme ([PortfolioWidgetChart.usdtFactor]). */
    internal val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
    /** Portfolio-Wert auf der Uhr (nur mit Schalter): nach jeder Aufnahme senden, ohne eigene Abfrage. */
    internal val watchFeed: dagger.Lazy<PortfolioWatchFeed>,
    @param:IoDispatcher internal val ioDispatcher: CoroutineDispatcher,
) {
    internal val mutex = Mutex()

    /**
     * Gesamtsicht je Aktualisierung nur einmal ([PortfolioSummaryCache]): Frische-Prüfung (auch die der App,
     * [com.cryptochecker.app.ui.features.portfolio.PortfolioViewModel]), Stundenkerzen und [record] mit denselben Eingaben.
     */
    val summaries = PortfolioSummaryCache()

    /**
     * Hintergrund-Aktualisierung: nur mit Portfolio-Widget auf dem Startbildschirm oder einem
     * scharfen Portfolio-Alarm (der wird nach jeder Aufnahme geprüft, siehe [record]).
     */
    suspend fun refreshIfWidgets() {
        if (!shouldRefresh(widgetUpdater.portfolioWidgetIds().isNotEmpty(), activeAlarms())) return
        refresh()
    }

    /** Portfolio-Widgets ohne neue Aufnahme neu zeichnen (z. B. «Beträge verbergen» umgeschaltet). */
    suspend fun redrawWidgets() = widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())

    /** Scharfe Portfolio-Alarme? null = gerade nicht lesbar (z. B. Datenbank kurz gesperrt). */
    private suspend fun activeAlarms(): Boolean? = try {
        alarmChecker.hasActiveAlarms()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Portfolio-Alarme nicht lesbar – Aktualisierung läuft trotzdem")
        null
    }

    companion object {
        /**
         * Hintergrund-Aktualisierung nötig? Mit Portfolio-Widget immer; sonst nur mit scharfem Portfolio-Alarm. Nicht
         * lesbar ([activeAlarms] null) zählt als «vielleicht scharf»: lieber einmal zu viel aktualisieren, als einen
         * Alarm auszulassen — [refresh] kommt selbst mit gesperrtem Speicher zurecht.
         */
        fun shouldRefresh(hasPortfolioWidget: Boolean, activeAlarms: Boolean?): Boolean =
            hasPortfolioWidget || activeAlarms != false

        /** Eigene Transaktionen von [used] (ohne Adress-Einträge) = gespeicherter Stand [stored]? Reihenfolge egal. */
        fun sameLedger(used: List<PortfolioTxEntity>, stored: List<PortfolioTxEntity>): Boolean =
            used.filterNot { AddressBalance.isAddressTrade(it.id) }.sortedBy { it.id } == stored.sortedBy { it.id }
    }

    /**
     * Kurse (60 s zwischengespeichert) und Devisenkurs holen, fehlende Stundenkerzen laden
     * ([PortfolioHourlyCandles.loadMissingCandles]), dann aufnehmen. Fehler bleiben still.
     */
    suspend fun refresh(): Unit = withContext(ioDispatcher) {
        try {
            // Adress-Guthaben (nur fällige) und als Einzahlung ohne Kaufpreis mitrechnen — wie der Portfolio-Tab;
            // höchstens 8 s zusammen (je Anfrage 5 s), damit Kurse, Aufnahme und Alarme nie ausfallen
            addressRefresher.refresh(force = false, background = true)
            val transactions = repository.getTransactions().withAddresses(repository.getWatchedAddresses())
            val coins = transactions.flatMap { it.coins() }.toSet()
            val fetched = if (coins.isEmpty()) PortfolioPrices() else priceSource.prices(coins)
            val prices = fetched.prices
            // Devisenkurs zuerst holen und merken — auch wenn danach mangels frischer Kurse nichts aufgenommen wird;
            // so zeigt das Widget «Umrechnung» trotzdem in der eingestellten Währung (statt USDT)
            val currency = settingsRepository.current().portfolioCurrency
            val fx = fxSource.usdTo(currency)
            rememberFx(currency, fx)
            // Wie im Portfolio-Tab: nur aufnehmen, wenn mindestens 80 % des Werts einen frischen Kurs haben — je
            // Coin geprüft; Alarme prüfen nur, wenn ALLE bewerteten Coins frisch sind ([record], stalePrices)
            val trades = transactions.map { it.toTrade() }
            val method = settingsRepository.current().costMethod
            val summary = summaries.summarize(trades, prices, method)
            if (coins.isNotEmpty() && !mayRecord(summary, fetched)) {
                Timber.d("Portfolio: zu wenig frische Kurse, keine neue Aufnahme")
                return@withContext
            }
            // Offene Coins für die Stundenkerzen nach der Standardmethode: dieselbe Gesamtsicht, wenn sie eingestellt ist
            val defaultOpen = if (method == CostMethod.DEFAULT) {
                summary.open
            } else {
                PortfolioCalculator.summarize(trades, prices, CostMethod.DEFAULT, withParts = false).open
            }
            hourlyCandles.loadMissingCandles(defaultOpen.map { it.coin })
            record(transactions, prices, currency, fx, fetched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Portfolio-Widget: Aktualisierung fehlgeschlagen")
        }
    }

    /**
     * Frischen Devisenkurs USD → [currency] für das Widget merken ([PortfolioWidgetChart.fxMemoToSave]), unabhängig von
     * einer Aufnahme. @return true, wenn ein Kurs gemerkt wurde
     */
    fun rememberFx(currency: String, fxRate: Double?): Boolean {
        val memo = PortfolioWidgetChart.fxMemoToSave(
            currency,
            fxRate,
            fxSource.cached(currency),
            fxSource.fetchedAt(currency),
            System.currentTimeMillis(),
        ) ?: return false
        store.saveFxMemo(memo)
        return true
    }

    /**
     * 7 T / 30 T / 1 J fürs Widget: Tagesverlauf und Veränderung je Zeitraum aus den Tageskursen,
     * die der Wertverlauf im Portfolio-Tab schon geladen hat ([PortfolioHistorySource.cachedDailyPrices]) —
     * ohne Netz ([PortfolioWidgetChart.dailyData]). Fehler bleiben still (dann ohne Tagesverlauf).
     */
    internal suspend fun PortfolioSnapshot.withDaily(
        transactions: List<PortfolioTxEntity>,
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        now: Long,
        routing: PortfolioPrices?,
        addresses: List<AddressBalanceHistory>,
        method: CostMethod,
        memo: PortfolioDisplayFx?,
        code: String,
        rate: Double,
    ): PortfolioSnapshot {
        if (empty || holdings.isEmpty()) return this
        return try {
            val coins = transactions.flatMap { it.coins() }.map { PortfolioCalculator.normalizeCoin(it) }.toSet()
            val cached = historySource.cachedDailyPrices(coins)
            // Dieselben Reihen wie Verlauf, «7 T» und «1 J» im Portfolio-Tab (eigene Börse, sonst Binance skaliert)
            val routed = PortfolioExchangeRoute.historyCloses(
                cached.closes,
                cached.ranges,
                cached.own,
                cached.routed + routing?.sources?.keys.orEmpty(),
                routing?.exchangeFailed?.keys.orEmpty(),
                current,
                routing?.binance.orEmpty(),
            )
            val data = PortfolioWidgetChart.dailyData(
                trades = transactions.map { it.toTrade() },
                closes = routed.closes,
                ranges = routed.ranges,
                holdings = holdings,
                current = current,
                // Stichtag «7 T/1 J» und Verlauf in UTC-Tagen wie die Tageskerzen (nicht das lokale Datum)
                todayEpochDay = PortfolioHistory.epochDayOfUtcMillis(now),
                todayUtcDay = PortfolioHistory.epochDayOfUtcMillis(now),
                addresses = addresses,
                method = method,
            )
            // «7 T» in der Anzeigewährung wie die Gesamtkarte (Tagesfaktoren der App, [PortfolioDisplayFigures.periodFx]);
            // ohne passende Faktoren null — das Widget zeigt dann die USDT-Zahl mit Einheit «USDT»
            val today = PortfolioHistory.epochDayOfUtcMillis(now)
            val weekDisplay = PortfolioDisplayFigures.periodFx(memo, code, rate, today)?.let { fx ->
                PortfolioDisplayFigures.periodChange(
                    PortfolioPeriodChange.gain(
                        transactions.map { it.toTrade() },
                        current,
                        PortfolioWidgetChart.usableCloses(routed.closes, today),
                        today - PortfolioDisplayFigures.WEEK_DAYS,
                        addresses = addresses,
                        method = method,
                        fx = fx,
                    )
                )
            }
            copy(daily = data.points, periodChanges = data.changes, weekDisplay = weekDisplay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Portfolio-Widget: Tagesverlauf nicht berechnet")
            this
        }
    }
}
