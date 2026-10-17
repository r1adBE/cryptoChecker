package com.cryptochecker.app.widget

import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioAlarmChecker
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
import com.cryptochecker.app.data.portfolio.withAddresses
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
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
    private val sparklineRepository: SparklineRepository,
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

    /** Letzter Kerzen-Abruf je Coin (Grossschreibung) — höchstens einer je Stunde. */
    private val candleAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Kurse (60 s zwischengespeichert) und Devisenkurs holen, fehlende Stundenkerzen laden
     * ([loadMissingCandles]), dann aufnehmen. Fehler bleiben still.
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
            loadMissingCandles(transactions, prices)
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
    ): PortfolioSnapshot? = withContext(Dispatchers.IO) {
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
                fresh = cachedHourlyPrices(holdings.keys, history, freshCurrent, now),
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
            ).withDaily(transactions, holdings, current, now, routing, addresses, settings.costMethod)
                // «Gesamt» der Übersicht: ± seit Kauf in USDT wie in der Gesamtkarte des Portfolio-Tabs
                .copy(unrealized = summary.unrealized?.takeIf { it.isFinite() }?.let { PortfolioChange(it, summary.unrealizedPercent) })
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
     * «Heute» für Karte, Widget und Portfolio-Alarm aus EINER Rechnung: Kursänderung je Coin mit
     * Vergleichskurs ([PortfolioPeriodChange.todayFromCoinChanges]; Coins ohne fehlen statt flach
     * mitzuzählen), um Käufe/Verkäufe seit Beginn der %-Basis berichtigt ([PortfolioPeriodChange.todayAdjusted]).
     * Betrag in der Anzeigewährung ([rate]); unbekannt = null. Dazu dieselbe Rechnung je Coin
     * ([PortfolioPeriodChange.todayByCoin]) für die Zeilen: [PortfolioSnapshot.coinPnl] (USDT) und
     * [PortfolioPosition.pnlAmount]/[PortfolioPosition.pnlPercent] (Anzeigewährung).
     */
    private fun PortfolioSnapshot.withHonestToday(
        transactions: List<PortfolioTxEntity>,
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        basis: com.cryptochecker.app.domain.watch.ChangeBasis,
        now: Long,
        rate: Double,
        addresses: List<AddressBalanceHistory>,
        method: CostMethod,
    ): PortfolioSnapshot {
        if (empty) return this
        val start = ChangeBasisMath.dayStart(basis, now) ?: (now - DAY_MILLIS)
        val raw = PortfolioPeriodChange.todayFromCoinChanges(holdings, current, coinChanges)
        val trades = transactions.map { t -> t.toTrade() }
        val adjusted = raw?.let {
            PortfolioPeriodChange.todayAdjusted(it, trades, current, coinChanges, start, addresses = addresses, method = method)
        }
        val byCoin = PortfolioPeriodChange.todayByCoin(holdings, trades, current, coinChanges, start, addresses, method)
            .mapValues { (_, c) -> PortfolioChange(c.amountUsd, c.percent?.takeIf { it.isFinite() }) }
        return copy(
            changeAmount = adjusted?.amountUsd?.takeIf { it.isFinite() }?.times(rate),
            changePercent = adjusted?.percent?.takeIf { it.isFinite() },
            todaySkipped = adjusted?.skipped.orEmpty(),
            coinPnl = byCoin,
            positions = positions.map { p ->
                val pnl = byCoin[PortfolioCalculator.normalizeCoin(p.symbol)]
                p.copy(pnlAmount = pnl?.amount?.times(rate), pnlPercent = pnl?.percent)
            },
        )
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
                todayEpochDay = LocalDate.now(zone).toEpochDay(),
                todayUtcDay = PortfolioHistory.epochDayOfUtcMillis(now),
                dayEndMillis = { day -> LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 },
                addresses = addresses,
                method = method,
            )
            copy(daily = data.points, periodChanges = data.changes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Portfolio-Widget: Tagesverlauf nicht berechnet")
            this
        }
    }

    /**
     * Bestand für «Heute» ([PortfolioPeriodChange]): eigener Bestand ([CoinPosition.ledgerHoldingsExact]) plus zuletzt
     * beobachtetes Adress-Guthaben ([AddressBalanceHistory.latestByCoin]) — passend zur Reihe [addresses].
     */
    private fun todayHoldings(open: List<CoinPosition>, addresses: List<AddressBalanceHistory>): Map<String, Double> {
        val observed = AddressBalanceHistory.latestByCoin(addresses)
        return open.associate { p ->
            p.coin to (p.ledgerHoldingsExact + (observed[PortfolioCalculator.normalizeCoin(p.coin)] ?: java.math.BigDecimal.ZERO)).toDouble()
        }
    }

    /**
     * Stundenkerzen für offene Coins, denen Stundenkurse fehlen — z. B. Coins, die nur im
     * Portfolio und nicht in der Merkliste sind (dort liefern sonst die Mini-Charts die Kurse).
     * Die grössten Positionen zuerst, je Coin höchstens ein Abruf pro Stunde
     * ([PortfolioWidgetSeries.candleCoins]), gemeinsam höchstens [CANDLE_LOAD_TIMEOUT_MILLIS]
     * (das Widget wartet insgesamt nur 8 s). Die Kerzen landen im Zwischenspeicher von
     * [SingleWidgetRenderer]; [record] führt sie mit den gemerkten Kursen zusammen. Fehler bleiben still.
     */
    private suspend fun loadMissingCandles(transactions: List<PortfolioTxEntity>, prices: Map<String, Double>) {
        val open = PortfolioCalculator.summarize(transactions.map { it.toTrade() }, prices).open.map { it.coin }
        if (open.isEmpty()) return
        val now = System.currentTimeMillis()
        val known = PortfolioWidgetSeries.merge(
            stored = store.hourlyPrices(),
            fresh = cachedHourlyPrices(open.toSet(), emptyList(), emptyMap(), now),
            now = now,
        )
        val due = PortfolioWidgetSeries.candleCoins(open, known, candleAttempts, now, PortfolioStables.COINS)
        if (due.isEmpty()) return
        due.forEach { candleAttempts[it] = now }
        withTimeoutOrNull(CANDLE_LOAD_TIMEOUT_MILLIS) {
            coroutineScope {
                due.map { coin ->
                    async {
                        try {
                            widgetUpdater.loadHourlyPrices(coin)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.d(e, "Portfolio-Widget: Stundenkerzen für %s nicht geladen", coin)
                        }
                    }
                }.awaitAll()
            }
        }
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
            // Nur USDT gilt immer 1; USDC, DAI … brauchen Stundenkurse (Abkopplung sichtbar)
            if (!PortfolioStables.needsQuote(coin)) continue
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

    private companion object {
        /** Kerzen für alle fehlenden Coins zusammen höchstens so lange laden. */
        const val CANDLE_LOAD_TIMEOUT_MILLIS = 4_000L
        const val DAY_MILLIS = 86_400_000L
    }
}
