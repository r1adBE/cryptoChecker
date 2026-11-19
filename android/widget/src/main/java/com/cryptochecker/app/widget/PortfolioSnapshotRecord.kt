// Stand des Portfolios für Widget und Uhr aufnehmen — Teil von PortfolioSnapshotUpdater.
package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
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
import com.cryptochecker.app.domain.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.portfolio.withAddresses
import com.cryptochecker.app.domain.settings.AppSettings
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
 * [PortfolioPriceFallback.mayRecordShare] mit der Frische-Schwelle der Portfolio-Kurse; [summary] = Gesamtsicht nach
 * der eingestellten Einstandsmethode (wie Karte und Aufnahme).
 */
internal suspend fun PortfolioSnapshotUpdater.mayRecord(summary: PortfolioSummary, fetched: PortfolioPrices): Boolean {
    val open = summary.open
    val values = open.mapNotNull { p -> p.value?.let { PortfolioCalculator.normalizeCoin(p.coin) to it } }.toMap()
    val staleAfter = PortfolioPriceFallback.staleAfterMillis(settingsRepository.current().backgroundIntervalMinutes)
    return PortfolioPriceFallback.mayRecordShare(
        values,
        fetched.times,
        System.currentTimeMillis(),
        staleAfter,
        anyPrice = fetched.updatedAt > 0L,
    )
}

/**
 * Nimmt die aktuelle Lage auf und zeichnet die Widgets neu.
 * @param prices USDT-Kurs je Coin
 * @param fxRate USD → [currency]; null = unbekannt, dann in USDT. USDT/USD (Marktkurs) kommt hier dazu.
 * @return die neue Momentaufnahme (auch für «heute» im Portfolio-Tab); null, wenn
 *   mangels Kursen nichts aufgenommen wurde
 */
suspend fun PortfolioSnapshotUpdater.record(
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
    /** Beginn einer Aktualisierung aus dem Widget ([PortfolioSnapshotUpdater.refresh]); 0 = sonst (App). */
    startedAt: Long = 0L,
): PortfolioSnapshot? = withContext(ioDispatcher) {
    // Portfolio nur leer im Arbeitsspeicher, obwohl die Datei mit dem gespeicherten Stand da ist (Schlüssel gerade
    // nicht lesbar): nichts aufnehmen — sonst zeigte das Widget ein leeres Portfolio und der Verlauf bekäme eine Null
    if (DatabaseEncryptionNotice.isStoredKept(PortfolioDatabase.DB_NAME)) return@withContext null
    val settings = settingsRepository.current()
    // Beobachtete Adress-Guthaben: zählen in Heute, 7 T / 30 T / 1 J und im 24-h-Verlauf erst ab ihrer
    // ersten Beobachtung (nie rückwirkend) und ohne das schon Gebuchte (wie die Karte, [AddressBooked]);
    // Fehler → ohne (dann nur der eigene Bestand)
    val watched = try {
        repository.getWatchedAddresses()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    val addresses = AddressBooked.histories(watched, transactions.map { it.toTrade() })
    // Zählende Adressen ohne je geladenes Guthaben: fehlen im Gesamtwert — Portfolio-Alarme prüfen dann nicht (seit über
    // 24 h scheiternde rechnen sie ohne); nicht bestätigte Token-Guthaben gelten wie ein alter Kurs
    val addressState = AddressAlarmState.of(watched, System.currentTimeMillis())
    val recorded = mutex.withLock {
        // Bestand inzwischen geändert (Position gelöscht, Sicherung eingespielt — während dieser Lauf Kurse holte):
        // nicht mit dem alten Stand darüber schreiben; der Aufrufer mit dem neuen nimmt auf
        if (!PortfolioSnapshotUpdater.sameLedger(transactions, repository.getTransactions())) return@withLock null
        // Gleiche Eingaben wie die Frische-Prüfung davor: aus dem Zwischenspeicher
        val summary = summaries.summarize(transactions.map { it.toTrade() }, prices, settings.costMethod)
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
        val pricedValues = summary.open.mapNotNull { p -> p.value?.let { PortfolioCalculator.normalizeCoin(p.coin) to it } }.toMap()
        val staleList = PortfolioPriceFallback.staleCoins(
            pricedValues,
            routing?.times.orEmpty(),
            now,
            PortfolioPriceFallback.staleAfterMillis(settings.backgroundIntervalMinutes),
        )
        val staleCoins = staleList.toSet()
        // «Kurs ungeprüft» (Token nicht bestätigt): zählt mit dem Kurs, für die Alarme aber wie ein alter Kurs
        val unverifiedList = routing?.unverified.orEmpty().map { PortfolioCalculator.normalizeCoin(it) }
            .filter { it in pricedValues && it !in staleCoins }.distinct().sorted()
        val freshCurrent = current.filterKeys { PortfolioCalculator.normalizeCoin(it) !in staleCoins }

        // Stundenkurse für den 24-h-Wertverlauf: gemerkte, dazu was ohnehin schon geladen
        // ist (Mini-Charts, Einzel-Widgets, eigene Kursaufnahmen) — keine eigene Abfrage
        val hourlyPrices = PortfolioWidgetSeries.merge(
            stored = store.hourlyPrices(),
            fresh = hourlyCandles.cachedHourlyPrices(holdings.keys, history, freshCurrent, now),
            now = now,
        ).filterKeys { coin -> holdings.keys.any { it.equals(coin, ignoreCase = true) } }

        val snapshot = buildSnapshot(
            transactions = transactions,
            summary = summary,
            holdings = holdings,
            current = current,
            history = history,
            now = now,
            rate = rate,
            code = code,
            hourlyPrices = hourlyPrices,
            settings = settings,
            addresses = addresses,
            routing = routing,
            memo = memo,
            staleCoins = staleCoins,
            staleList = staleList,
            pricedValues = pricedValues,
            addressState = addressState,
            unverifiedList = unverifiedList,
        )
        store.save(snapshot, PortfolioSnapshotMath.addSample(history, PriceSample(now, freshCurrent)), hourlyPrices)
        snapshot
    }
    // Aus dem Widget: «Uhrzeit · Dauer» in der Kopfzeile, danach 15 s Countdown statt Knopf — vor dem Zeichnen
    if (recorded != null && startedAt > 0L) {
        WidgetRefreshHeader.finished(widgetPrefs, WidgetRefreshHeader.Kind.PORTFOLIO, recorded.time, startedAt)
    }
    widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
    // Portfolio-Wert auf der Uhr (nur mit Schalter, sonst nichts)
    if (recorded != null) watchFeed.get().portfolioRecorded()
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
 * Momentaufnahme aus den Eingaben von [record] (im Schloss): Werte, Tagesreihe, Buchungen je Position, «Gesamt» seit
 * Kauf, ehrliches «Heute» und die Merker für alte bzw. ungeprüfte Kurse.
 */
private suspend fun PortfolioSnapshotUpdater.buildSnapshot(
    transactions: List<PortfolioTxEntity>,
    summary: PortfolioSummary,
    holdings: Map<String, Double>,
    current: Map<String, Double>,
    history: List<PriceSample>,
    now: Long,
    rate: Double,
    code: String,
    hourlyPrices: Map<String, List<TimedPrice>>,
    settings: AppSettings,
    addresses: List<AddressBalanceHistory>,
    routing: PortfolioPrices?,
    memo: PortfolioDisplayFx?,
    staleCoins: Set<String>,
    staleList: List<String>,
    pricedValues: Map<String, Double>,
    addressState: AddressAlarmState,
    unverifiedList: List<String>,
): PortfolioSnapshot {
    val basis = settings.changeBasis.storage
    return PortfolioSnapshotMath.snapshot(
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
        // Buchungen je Position für die Liste (Wahl im Widget: Käufe / Verkäufe / beides)
        .let { s ->
            PortfolioWidgetEntries.withEntries(
                s,
                transactions.map { it.toTrade() },
                transactions.associate { it.id to it.note },
                current,
                rate,
                settings.costMethod,
            )
        }
        // «Gesamt» der Übersicht: ± seit Kauf in USDT wie in der Gesamtkarte des Portfolio-Tabs; in der
        // Anzeigewährung: Wert × Faktor jetzt − gemerkter Einstand zum Kurs der Erwerbstage
        .copy(
            unrealized = summary.unrealized?.takeIf { it.isFinite() }?.let { PortfolioChange(it, summary.unrealizedPercent) },
            unrealizedDisplay = PortfolioDisplayFigures.unrealized(summary.open, code, rate, memo),
            displayFx = memo,
        )
        // «Heute»: Bestand der Karte (eigener samt Posten + Adress-Teil); die Reihe rechnet Änderungen
        // seit Beginn der %-Basis als Zu-/Abfluss heraus (nie rückwirkend)
        .withHonestToday(
            transactions,
            todayHoldings(summary.open, addresses),
            current,
            settings.changeBasis,
            now,
            rate,
            addresses,
            settings.costMethod,
        )
        // Wert der alten Kurse (unwesentlich unter 1 %: Alarme prüfen trotzdem) und nie geladene Adressen
        .copy(
            stalePrices = staleCoins.size,
            staleCoins = staleList,
            staleValueUsdt = staleList.sumOf { pricedValues[it] ?: 0.0 },
            addressesPending = addressState.pending,
            addressesFailing = addressState.failing,
            staleTokenAddresses = addressState.staleTokenAddresses,
            staleTokenValueUsdt = addressState.staleTokenValue(current),
            unverifiedCoins = unverifiedList,
            unverifiedValueUsdt = unverifiedList.sumOf { pricedValues[it] ?: 0.0 },
        )
}
