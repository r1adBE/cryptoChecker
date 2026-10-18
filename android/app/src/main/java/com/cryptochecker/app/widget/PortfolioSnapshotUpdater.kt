package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioAlarmChecker
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
import com.cryptochecker.app.data.portfolio.withAddresses
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFigures
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

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
    private val repository: PortfolioRepository,
    private val priceSource: PortfolioPriceSource,
    private val fxSource: FxRateSource,
    private val settingsRepository: SettingsRepository,
    private val store: PortfolioSnapshotStore,
    private val widgetUpdater: WidgetUpdater,
    /** Stundenkurse für den 24-h-Wertverlauf (fehlende Kerzen laden, vorhandene einsammeln). */
    private val hourlyCandles: PortfolioHourlyCandles,
    private val alarmChecker: PortfolioAlarmChecker,
    private val historySource: PortfolioHistorySource,
    /** Guthaben beobachteter Adressen (10 min Zwischenspeicher; ohne Adressen keine Anfrage). */
    private val addressRefresher: WatchedAddressRefresher,
    /** Live-Kurs USDT/USD: Faktor USDT → Währung der Aufnahme ([PortfolioWidgetChart.usdtFactor]). */
    private val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
) {
    private val mutex = Mutex()

    /**
     * Hintergrund-Aktualisierung: nur mit Portfolio-Widget auf dem Startbildschirm oder einem
     * scharfen Portfolio-Alarm (der wird nach jeder Aufnahme geprüft, siehe [record]).
     */
    suspend fun refreshIfWidgets() {
        if (widgetUpdater.portfolioWidgetIds().isEmpty() && !hasActiveAlarms()) return
        refresh()
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

    /**
     * Kurse (60 s zwischengespeichert) und Devisenkurs holen, fehlende Stundenkerzen laden
     * ([PortfolioHourlyCandles.loadMissingCandles]), dann aufnehmen. Fehler bleiben still.
     */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
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
            if (coins.isNotEmpty() && !mayRecord(transactions, fetched)) {
                Timber.d("Portfolio: zu wenig frische Kurse, keine neue Aufnahme")
                return@withContext
            }
            hourlyCandles.loadMissingCandles(transactions, prices)
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
            currency, fxRate, fxSource.cached(currency), fxSource.fetchedAt(currency), System.currentTimeMillis(),
        ) ?: return false
        store.saveFxMemo(memo)
        return true
    }

    /** [PortfolioPriceFallback.mayRecordShare] mit der Frische-Schwelle der Portfolio-Kurse. */
    private suspend fun mayRecord(transactions: List<PortfolioTxEntity>, fetched: PortfolioPrices): Boolean {
        // Bestände nach der eingestellten Einstandsmethode (wie Karte und Aufnahme)
        val open = PortfolioCalculator.summarize(transactions.map { it.toTrade() }, fetched.prices, settingsRepository.current().costMethod).open
        val values = open.mapNotNull { p -> p.value?.let { PortfolioCalculator.normalizeCoin(p.coin) to it } }.toMap()
        val staleAfter = PortfolioPriceFallback.staleAfterMillis(settingsRepository.current().backgroundIntervalMinutes)
        return PortfolioPriceFallback.mayRecordShare(
            values, fetched.times, System.currentTimeMillis(), staleAfter, anyPrice = fetched.updatedAt > 0L,
        )
    }

    /**
     * Nimmt die aktuelle Lage auf und zeichnet die Widgets neu.
     * @param prices USDT-Kurs je Coin
     * @param fxRate USD → [currency]; null = unbekannt, dann in USDT. USDT/USD (Marktkurs) kommt hier dazu.
     * @return die neue Momentaufnahme (auch für «heute» im Portfolio-Tab); null, wenn
     *   mangels Kursen nichts aufgenommen wurde
     */
    suspend fun record(
        transactions: List<PortfolioTxEntity>,
        prices: Map<String, Double>,
        currency: String,
        fxRate: Double?,
        /**
         * Kurse samt Börse je Coin ([PortfolioPrices.sources], [PortfolioPrices.exchangeFailed],
         * [PortfolioPrices.binance]) — wählen die Tagesreihe wie im Portfolio-Tab ([PortfolioExchangeRoute.historyCloses]).
         */
        routing: PortfolioPrices? = null,
        /**
         * Merkzettel der App für «Seit Kauf»/«7 T» in der Anzeigewährung ([PortfolioDisplayFx]); null = der zuletzt
         * gespeicherte (Hintergrund, Widget).
         */
        displayFx: PortfolioDisplayFx? = null,
    ): PortfolioSnapshot? = withContext(Dispatchers.IO) {
        // Portfolio nur leer im Arbeitsspeicher, obwohl die Datei mit dem gespeicherten Stand da ist (Schlüssel gerade
        // nicht lesbar): nichts aufnehmen — sonst zeigte das Widget ein leeres Portfolio und der Verlauf bekäme eine Null
        if (DatabaseEncryptionNotice.isStoredKept(PortfolioDatabase.DB_NAME)) return@withContext null
        val settings = settingsRepository.current()
        val basis = settings.changeBasis.storage
        // Beobachtete Adress-Guthaben: zählen in Heute, 7 T / 30 T / 1 J und im 24-h-Verlauf erst ab ihrer
        // ersten Beobachtung (nie rückwirkend); Fehler → ohne (dann nur der eigene Bestand)
        val addresses = try {
            AddressBalanceHistory.of(repository.getWatchedAddresses())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val recorded = mutex.withLock {
            val summary = PortfolioCalculator.summarize(transactions.map { it.toTrade() }, prices, settings.costMethod)
            // Noch kein einziger Kurs bekannt: lieber die letzte Aufnahme stehen lassen als 0 zeigen
            if (summary.open.isNotEmpty() && summary.open.all { it.value == null }) return@withLock null

            val now = System.currentTimeMillis()
            // Frischen Kurs merken; ohne (kein Netz, frischer Prozess) der gemerkte — sonst fiele die Aufnahme
            // auf USD zurück und das Widget zeigte trotz «Umrechnung» USDT
            PortfolioWidgetChart.fxMemoToSave(currency, fxRate, fxSource.cached(currency), fxSource.fetchedAt(currency), now)
                ?.let { store.saveFxMemo(it) }
            val resolvedFx = PortfolioWidgetChart.fxRateFor(currency, fxRate, store.fxMemo(), now)
            // USDT zum Marktkurs: Devisenkurs × USDT/USD (Widget, «Heute» und Portfolio-Alarme in der Währung)
            val rate = resolvedFx?.let { PortfolioWidgetChart.usdtFactor(currency, it, depegReadings.liveUsdtUsd(now)) } ?: 1.0
            // Ohne Devisenkurs in USDT (1:1 gerechnet) — nicht «USD», das in USD nun USDT zum Marktkurs meint
            val code = if (resolvedFx != null) currency else PortfolioStables.VALUATION
            // «Seit Kauf»/«7 T» in der Anzeigewährung: mit dem Merkzettel der App (frisch übergeben oder gespeichert),
            // nur für dieselbe Währung — in USDT gelten die USDT-Werte
            val memo = (displayFx ?: store.snapshot()?.displayFx)
                ?.takeIf { code != PortfolioStables.VALUATION && it.currency.equals(code.trim(), ignoreCase = true) }
            val history = store.history()
            val holdings = summary.open.associate { it.coin to it.holdings }
            // Aktuelle Kurse der offenen Coins (Stablecoin-Regel setzt der Rechner: USDT 1, andere Marktkurs, sonst 1)
            val current = summary.open.mapNotNull { p -> p.currentPrice?.let { p.coin to it } }.toMap()
            // Bewertete Coins ohne frischen Kurs (je Coin, [PortfolioPriceFallback.staleCoins]; ohne Abfragezeiten
            // gelten alle als alt): zählen mit dem letzten Kurs, die Aufnahme ist dann als «teilweise alter Kurs»
            // markiert, Portfolio-Alarme prüfen nicht, und ihr alter Kurs wird nie als Kurs «jetzt» gemerkt
            val staleCoins = PortfolioPriceFallback.staleCoins(
                summary.open.mapNotNull { p -> p.value?.let { PortfolioCalculator.normalizeCoin(p.coin) to it } }.toMap(),
                routing?.times.orEmpty(), now, PortfolioPriceFallback.staleAfterMillis(settings.backgroundIntervalMinutes),
            ).toSet()
            val freshCurrent = current.filterKeys { PortfolioCalculator.normalizeCoin(it) !in staleCoins }

            // Stundenkurse für den 24-h-Wertverlauf: gemerkte, dazu was ohnehin schon geladen
            // ist (Mini-Charts, Einzel-Widgets, eigene Kursaufnahmen) — keine eigene Abfrage
            val hourlyPrices = PortfolioWidgetSeries.merge(
                stored = store.hourlyPrices(),
                fresh = hourlyCandles.cachedHourlyPrices(holdings.keys, history, freshCurrent, now),
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
                stables = PortfolioStables.COINS,
                // %-Basis: rollend oder seit Tagesbeginn (UTC/Ortszeit)
                stamp = ChangeBasisMath.stamp(basis, now),
                // Adress-Anteil im 24-h-Verlauf nur mit dem damals beobachteten Guthaben
                addressSteps = AddressBalanceHistory.stepsByCoin(addresses),
            ).withDaily(transactions, holdings, current, now, routing, addresses, settings.costMethod, memo, code, rate)
                // «Gesamt» der Übersicht: ± seit Kauf in USDT wie in der Gesamtkarte des Portfolio-Tabs; in der
                // Anzeigewährung: Wert × Faktor jetzt − gemerkter Einstand zum Kurs der Erwerbstage
                .copy(
                    unrealized = summary.unrealized?.takeIf { it.isFinite() }?.let { PortfolioChange(it, summary.unrealizedPercent) },
                    unrealizedDisplay = PortfolioDisplayFigures.unrealized(summary.open, code, rate, memo),
                    displayFx = memo,
                )
                // «Heute»: eigener Bestand + zuletzt beobachtetes Adress-Guthaben; die Reihe rechnet Änderungen
                // seit Beginn der %-Basis als Zu-/Abfluss heraus (nie rückwirkend)
                .withHonestToday(
                    transactions, todayHoldings(summary.open, addresses),
                    current, settings.changeBasis, now, rate, addresses, settings.costMethod,
                )
                .copy(stalePrices = staleCoins.size)
            store.save(snapshot, PortfolioSnapshotMath.addSample(history, PriceSample(now, freshCurrent)), hourlyPrices)
            snapshot
        }
        widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
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
     * 7 T / 30 T / 1 J fürs Widget: Tagesverlauf und Veränderung je Zeitraum aus den Tageskursen,
     * die der Wertverlauf im Portfolio-Tab schon geladen hat ([PortfolioHistorySource.cachedDailyPrices]) —
     * ohne Netz ([PortfolioWidgetChart.dailyData]). Fehler bleiben still (dann ohne Tagesverlauf).
     */
    private suspend fun PortfolioSnapshot.withDaily(
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
                cached.closes, cached.ranges, cached.own,
                cached.routed + routing?.sources?.keys.orEmpty(), routing?.exchangeFailed?.keys.orEmpty(),
                current, routing?.binance.orEmpty(),
            )
            val zone = ZoneId.systemDefault()
            val data = PortfolioWidgetChart.dailyData(
                trades = transactions.map { it.toTrade() },
                closes = routed.closes,
                ranges = routed.ranges,
                holdings = holdings,
                current = current,
                // Stichtag «7 T/1 J» und Verlauf in UTC-Tagen wie die Tageskerzen (nicht das lokale Datum)
                todayEpochDay = PortfolioHistory.epochDayOfUtcMillis(now),
                todayUtcDay = PortfolioHistory.epochDayOfUtcMillis(now),
                dayEndMillis = { day -> LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 },
                addresses = addresses,
                method = method,
            )
            // «7 T» in der Anzeigewährung wie die Gesamtkarte (Tagesfaktoren der App, [PortfolioDisplayFigures.periodFx]);
            // ohne passende Faktoren null — das Widget zeigt dann die USDT-Zahl mit Einheit «USDT»
            val today = PortfolioHistory.epochDayOfUtcMillis(now)
            val weekDisplay = PortfolioDisplayFigures.periodFx(memo, code, rate, today)?.let { fx ->
                PortfolioDisplayFigures.periodChange(
                    PortfolioPeriodChange.gain(
                        transactions.map { it.toTrade() }, current, PortfolioWidgetChart.usableCloses(routed.closes, today),
                        today - PortfolioDisplayFigures.WEEK_DAYS, addresses = addresses, method = method, fx = fx,
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
