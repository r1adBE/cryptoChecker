// Abgeleitete Flows im Portfolio (Teil von `PortfolioViewModel`): Wertverlauf, «7 T»/«1 J», Skalierungs-Hinweis,
// «Seit Kauf» zum Kurs der Erwerbstage und Merkzettel des Widgets.
// Eigene Datei, weil diese Rechnungen den Grossteil der Klasse ausmachten; Erweiterungsfunktionen, die die
// Klasse in ihren Property-Initialisierern aufruft, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCompare
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFigures
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFx
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.TodayChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/** «Seit Kauf» und Einstand in der Anzeigewährung zum Kurs der Erwerbstage ([PortfolioViewModel.costFx]). */
internal fun PortfolioViewModel.costFxFlow(): StateFlow<com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures?> = combine(
    summary, currency, displayFxRate, _costFx,
) { s, code, rate, load ->
    if (s == null || !cardUnit(code, rate).converted) return@combine null
    val current = rate?.let { com.cryptochecker.app.domain.portfolio.PortfolioDecimal.of(it) } ?: return@combine null
    // Heute als UTC-Tag wie die Erwerbstage ([com.cryptochecker.app.domain.portfolio.PortfolioCostFx.dayOf])
    val today = PortfolioHistory.utcToday()
    val first = com.cryptochecker.app.domain.portfolio.PortfolioCostFx.firstDay(s)
    // Ohne Teile mit Datum keine Kurse nötig; sonst nur mit einer Ladung, die den frühesten Tag abdeckt
    val usable = load?.takeIf { it.currency == code && (first == null || !it.from.isAfter(LocalDate.ofEpochDay(first))) }
    if (first != null && usable == null) return@combine null
    com.cryptochecker.app.domain.portfolio.PortfolioCostFx.figures(
        s, code, current, today, usable?.rates, usable?.usdtUsd ?: emptyMap(),
    )
}.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

/** Merkzettel fürs Portfolio-Widget in der Anzeigewährung ([PortfolioViewModel.widgetDisplayFx]). */
internal fun PortfolioViewModel.widgetDisplayFxFlow(): StateFlow<PortfolioDisplayFx?> = combine(
    summary, currency, costFx, _fxRate, combine(_fxSeries, _closes) { series, loaded -> series to loaded },
) { s, code, figures, fxRate, (fxSeries, loaded) ->
    if (s == null || figures == null) return@combine null
    val today = PortfolioHistory.utcToday()
    val from = today - PortfolioDisplayFigures.MEMO_DAYS
    val rate = fxRate?.takeIf { PortfolioHistoryFx.convertsUsdt(code, usdtMarket = true) && it > 0.0 && it.isFinite() }
    // Wie «7 T» der Karte: Devisen-Tageskurse, die bis zum ersten gemerkten Tag zurückreichen (USD: an jedem Tag 1)
    val rates = if (PortfolioHistoryFx.convertsUsdtAsUsd(code)) {
        fxSeries?.takeIf { it.currency == code && !it.from.isAfter(LocalDate.ofEpochDay(from)) }?.rates?.takeIf { it.isNotEmpty() }
    } else {
        PortfolioHistoryFx.usdParity(from - PortfolioHistoryFx.LEAD_DAYS, today)
    }
    val usdt = loaded?.daily?.usdtUsd
    val dayFactor = if (rate != null && rates != null && usdt != null) {
        PortfolioHistoryFx.rateFunction(rates, rate, today, com.cryptochecker.app.domain.portfolio.UsdtUsdRates(null, usdt))
    } else null
    PortfolioDisplayFigures.memo(code, figures, s.open, dayFactor, today)
}.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

/**
 * Gewinn/Verlust seit dem Tagesschluss vor [days] Tagen ([PortfolioPeriodChange.gain]). [display]: in der
 * Anzeigewährung mit denselben Tageskursen wie der Wertverlauf ([PortfolioHistoryFx.rateFunction]) — Kopf und Kurve
 * zeigen dieselbe Zahl; als Gegenwert zum heutigen Kurs ([PortfolioHistoryFx.forCard]), weil die Karte Beträge mit
 * dem heutigen Kurs anzeigt. Ohne passende Tageskurse wie bisher USDT (× heutiger Kurs in der Karte).
 */
internal fun PortfolioViewModel.periodChange(days: Int, display: Boolean): StateFlow<TodayChange?> = combine(
    summary, _closes, calcTransactions, _prices,
    combine(addressHistories, currency, _fxRate, _fxSeries, liveUsdtUsd) { a, code, fx, series, live ->
        PeriodFx(a, code, fx, series, live)
    },
) { current, loaded, txs, p, (addresses, code, fxRate, fxSeries, live) ->
    if (current == null || loaded == null || txs == null) return@combine null
    val holdings = current.open.associate { it.coin to it.holdings }
    if (holdings.isEmpty()) return@combine null
    // Ladung für ältere Coins: neue Coins fehlen noch → lieber «—»
    if (!loaded.request.coins.containsAll(holdings.keys.map { PortfolioCalculator.normalizeCoin(it) })) {
        return@combine null
    }
    val prices = current.open.mapNotNull { p -> p.currentPrice?.let { p.coin to it } }.toMap()
    // Stichtag in UTC-Tagen wie die Tageskerzen (lokales Datum lag zwischen Mitternacht lokal und UTC einen Tag daneben)
    val today = PortfolioHistory.utcToday()
    // Tageskerzen der eingetragenen Börse, sonst Binance skaliert (PortfolioExchangeRoute.historyCloses)
    // Tageskurse, die bis zum Stichtag zurückreichen: umrechnen wie der Wertverlauf
    // Auch USD: USDT zum Marktkurs ([PortfolioHistoryFx.convertsUsdt]); Devisenkurs dort an jedem Tag 1
    val rate = fxRate?.takeIf { display && PortfolioHistoryFx.convertsUsdt(code, usdtMarket = true) && it > 0.0 && it.isFinite() }
    val rates = if (PortfolioHistoryFx.convertsUsdtAsUsd(code)) {
        fxSeries?.takeIf { it.currency == code && !it.from.isAfter(LocalDate.ofEpochDay(today - days)) }?.rates
    } else {
        PortfolioHistoryFx.usdParity(today - days - PortfolioHistoryFx.LEAD_DAYS, today)
    }
    // USDT zum Marktkurs: je Tag × USDT/USD-Tagesschluss, ab heute × Live-Kurs (wie der Wertverlauf)
    val usdt = com.cryptochecker.app.domain.portfolio.UsdtUsdRates(live, loaded.daily.usdtUsd)
    val fx = if (rate != null && !rates.isNullOrEmpty()) PortfolioHistoryFx.rateFunction(rates, rate, today, usdt) else null
    // Bestände nach derselben Einstandsmethode wie die Karte (FIFO je Wallet: je Wallet gekappt)
    val change = PortfolioPeriodChange.gain(
        txs.map { it.toTrade() }, prices, loaded.routed(p).closes, today - days, addresses = addresses, method = current.method,
        fx = fx,
    )
    // Gegenwert zum Faktor der Karte (Devisenkurs × USDT/USD jetzt, [displayFxRate])
    if (change != null && fx != null && rate != null) PortfolioHistoryFx.forCard(change, rate * usdt.current().rate) else change
}
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

/** Hinweis «Binance skaliert» an «7 T»/«1 J» ([PortfolioViewModel.periodScaled]). */
internal fun PortfolioViewModel.periodScaledFlow(): StateFlow<List<String>> = combine(summary, _closes, _prices) { current, loaded, p ->
    if (current == null || loaded == null) return@combine emptyList()
    val today = PortfolioHistory.utcToday()
    PortfolioExchangeRoute.scaledHint(
        loaded.routed(p).scaled, loaded.daily.binanceBefore, today - PortfolioPeriodChange.YEAR_DAYS,
        current.open.map { it.coin },
    )
}
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

/** Wertverlauf des gewählten Zeitraums ([PortfolioViewModel.history]). */
internal fun PortfolioViewModel.historyFlow(): StateFlow<PortfolioHistoryUi?> = combine(
    combine(calcTransactions, addressHistories, costMethod) { txs, addresses, method -> Triple(txs, addresses, method) },
    _closes,
    _prices,
    historyRange,
    combine(currency, _fxRate, _fxSeries, liveUsdtUsd) { code, fx, series, live -> HistoryFx(code, fx, series, live) },
) { (txs, addresses, method), loaded, p, range, (code, fx, fxSeries, live) ->
    if (txs == null || loaded == null) return@combine null
    if (!loaded.covers(historyRequest(txs, range))) return@combine null
    // Auch USD: USDT zum Marktkurs (je Tag × USDT/USD-Tagesschluss), Devisenkurs dort an jedem Tag 1
    val converted = PortfolioHistoryFx.convertsUsdt(code, usdtMarket = true) && fx != null
    val viaFx = PortfolioHistoryFx.convertsUsdtAsUsd(code)
    // Devisen-Tageskurse dieses Zeitraums noch nicht da: Platzhalter statt still falscher Kurve
    if (converted && viaFx && (fxSeries == null || fxSeries.currency != code || fxSeries.from.isAfter(fxSeriesFrom(txs, range)))) {
        return@combine null
    }
    val zone = ZoneId.systemDefault()
    // Heute als UTC-Tag: der Verlauf zählt in UTC-Tagen ([PortfolioHistory.build]), wie «7 T/1 J»
    val today = PortfolioHistory.utcToday()
    // Eigene Börse, sonst Binance skaliert — Dochte und Eröffnung mit demselben Faktor wie die Schlüsse
    val routed = loaded.routed(p)
    val series = PortfolioHistory.build(
        trades = txs.map { it.toTrade() },
        closes = routed.closes,
        livePrices = p.prices,
        range = range,
        todayEpochDay = today,
        dayEndMillis = dayEndIn(zone),
        ranges = routed.ranges,
        // Adress-Guthaben ab ihrer ersten Beobachtung (Wert und Gewinn/Verlust)
        addresses = addresses,
        method = method,
    )
    val drawn = txs.flatMap { it.coins() }.map { PortfolioCalculator.normalizeCoin(it) }.distinct().filter { it !in series.skipped }
    val scaled = series.points.firstOrNull()?.let { first ->
        PortfolioExchangeRoute.scaledHint(routed.scaled, loaded.daily.binanceBefore, first.epochDay, drawn)
    }.orEmpty()
    // Eigene Börse nicht erreichbar: älterer Stand — «Stand …» statt eines scheinbar heutigen Verlaufs
    val staleSince = com.cryptochecker.app.domain.portfolio.PortfolioHistoryAsOf.of(
        loaded.daily.ownStale.filterKeys { it in routed.closes }, drawn,
    )
    // Tageskurse aus der Ausweich-Kette (Binance führt den Coin nicht): Anbieter nennen
    val chainSources = loaded.daily.chainSources.filterKeys { it in drawn }
    if (!converted) {
        return@combine PortfolioHistoryUi(
            range, series, PortfolioFormat.USDT, false, scaled = scaled, staleSince = staleSince, chainSources = chainSources,
        )
    }
    // USDT zum Marktkurs: je Tag × USDT/USD-Tagesschluss (Tag ohne Schluss: 1, als Näherung markiert)
    val usdt = com.cryptochecker.app.domain.portfolio.UsdtUsdRates(live, loaded.daily.usdtUsd)
    val dailyRates = if (viaFx) fxSeries?.rates else PortfolioHistoryFx.usdParity(fxSeriesFrom(txs, range).toEpochDay(), today)
    val result = PortfolioHistoryFx.convert(series, dailyRates, fx, today, usdt)
    // Vergleich mit USDT nur mit Tageskursen — mit dem heutigen Kurs wäre er bedeutungslos
    if (result.approximate) {
        return@combine PortfolioHistoryUi(
            range, result.series, code, true, true,
            scaled = scaled, usdtApproximate = result.usdtApproximate, staleSince = staleSince, chainSources = chainSources,
        )
    }
    PortfolioHistoryUi(
        range = range,
        series = result.series,
        unit = code,
        converted = true,
        approximateFx = false,
        usdtSeries = series,
        compare = PortfolioCompare.build(result.series.points, series.points),
        scaled = scaled,
        usdtApproximate = result.usdtApproximate,
        staleSince = staleSince,
        chainSources = chainSources,
    )
}
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

/**
 * Marktkurs eines Coins zu einem Zeitpunkt: Tagesschluss seines Tags, danach aktueller Kurs
 * ([PortfolioViewModel.summary], [PortfolioViewModel.feePrice]).
 */
internal fun marketPriceAt(p: PortfolioPrices, loaded: HistoryCloses?): (String, Long) -> Double? {
    val closes = loaded?.routed(p)?.closes.orEmpty()
    return { coin, time -> PortfolioPeriodChange.marketPriceAt(coin, p.prices[PortfolioCalculator.normalizeCoin(coin)], closes, time) }
}
