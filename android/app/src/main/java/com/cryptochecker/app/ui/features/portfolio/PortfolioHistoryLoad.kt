// Daten des Wertverlaufs im Portfolio: Zustand für die Ansicht (`PortfolioHistoryUi`), benötigte Tagesschlüsse
// und Devisen-Reihe je Zeitraum, Tagesende in der Zeitzone.
// Eigene Datei, weil es reine Hilfstypen der Verlaufs-Abfragen sind, die das ViewModel nur hält.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioDailyPrices
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCompareSeries
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistorySeries
import com.cryptochecker.app.domain.portfolio.RoutedHistory
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import com.cryptochecker.app.domain.portfolio.candleDays

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
    /** Coins mit Binance-Verlauf, auf den Börsenkurs skaliert ([PortfolioExchangeRoute.scaledHint]); leer = keiner. */
    val scaled: List<String> = emptyList(),
    /**
     * Mit USDT zum Marktkurs umgerechnet, aber an mindestens einem Tag ohne USDT/USD-Kurs (dort 1 als Näherung,
     * [com.cryptochecker.app.domain.portfolio.PortfolioHistoryConverted.usdtApproximate]) — Hinweis «USDT = 1 angenommen».
     */
    val usdtApproximate: Boolean = false,
    /**
     * Eigene Börse nicht erreichbar, Verlauf aus einem älteren Stand: dessen Zeit
     * ([com.cryptochecker.app.domain.portfolio.PortfolioHistoryAsOf]) — die Karte sagt «Stand …»; null = frisch.
     */
    val staleSince: Long? = null,
)

/** Tageskurse USD → [currency] ab [from] bis [to] (letzter abgefragter Tag); [rates] null = nicht zu haben. */
internal data class FxSeriesLoad(val currency: String, val from: LocalDate, val rates: Map<Long, Double>?, val to: LocalDate)

/**
 * Kurse für «Seit Kauf»/Einstand in der Anzeigewährung ([com.cryptochecker.app.domain.portfolio.PortfolioCostFx]): ab
 * [from] (frühester Erwerbstag der offenen Posten) Devisen-Tageskurse USD → [currency] ([rates], USD leer; null = nicht
 * zu haben) und USDT/USD-Tagesschlüsse ([usdtUsd], leer = keine: dann USDT = 1 als Näherung) — exakt wie im
 * Steuer-Export.
 */
internal data class CostFxLoad(
    val currency: String,
    val from: LocalDate,
    val rates: Map<Long, java.math.BigDecimal>?,
    val usdtUsd: Map<Long, java.math.BigDecimal>,
    /** Letzter abgefragter Tag ([PortfolioHistoryFx.fxRequestEnd]): davor ein neuer UTC-Tag → neu laden. */
    val to: LocalDate,
)

/** Adressen, Devisenkurse und Live-Kurs USDT/USD für «7 T»/«1 J» ([PortfolioViewModel.weekChange]). */
internal data class PeriodFx(
    val addresses: List<AddressBalanceHistory>,
    val currency: String,
    val fxRate: Double?,
    val fxSeries: FxSeriesLoad?,
    val usdtLive: Double?,
)

/** Währung, Devisenkurs, Devisen-Reihe und Live-Kurs USDT/USD für den Wertverlauf ([PortfolioViewModel.history]). */
internal data class HistoryFx(
    val currency: String,
    val fxRate: Double?,
    val fxSeries: FxSeriesLoad?,
    val usdtLive: Double?,
)

/** Jede Minute die Uhrzeit (Frische des Live-Kurses USDT/USD, [com.cryptochecker.app.domain.alarm.DepegAlarm.liveUsdtUsd]). */
internal fun minuteTicks(): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flow {
    while (true) {
        emit(System.currentTimeMillis())
        kotlinx.coroutines.delay(60_000L)
    }
}

/**
 * Faktor USDT → Anzeigewährung einer Aufnahme ([com.cryptochecker.app.widget.PortfolioWidgetChart.fxFactor]: Gesamtwert ÷
 * Gesamtwert in USDT — enthält Devisenkurs und USDT/USD); ohne USDT-Wert: in USD 1, sonst [fxRate] (USD → Währung).
 */
internal fun snapshotUsdtFactor(snapshot: com.cryptochecker.app.domain.portfolio.PortfolioSnapshot, fxRate: Double?): Double? =
    com.cryptochecker.app.widget.PortfolioWidgetChart.fxFactor(snapshot)
        ?: if (snapshot.currency == "USD") 1.0 else fxRate?.takeIf { it > 0.0 }

/** Beginn der Devisenkurs-Abfrage für den Verlauf [range] (siehe [PortfolioHistoryFx.requestRange]). */
internal fun fxSeriesFrom(txs: List<PortfolioTxEntity>, range: PortfolioHistoryRange): LocalDate {
    val today = LocalDate.ofEpochDay(PortfolioHistory.utcToday())
    val days = if (range == PortfolioHistoryRange.SINCE_FIRST) historyRequest(txs, range).days else range.days
    return PortfolioHistoryFx.requestRange(days, today).first
}

/** Welche Tagesschlusskurse der gewählte Zeitraum braucht: Coins und Tage bis heute. */
internal data class HistoryRequest(val coins: Set<String>, val days: Int)

/** Geladene Tagesschlusskurse (samt Eröffnung/Hoch/Tief für die Kerzen, eigene Börse) und für welche Anfrage. */
internal data class HistoryCloses(
    val request: HistoryRequest,
    val daily: PortfolioDailyPrices,
) {
    /**
     * Reihen für Verlauf, «7 T» und «1 J» zu den Kursen [p] ([PortfolioExchangeRoute.historyCloses]): eigene
     * Börse, sonst Binance skaliert — ohne Binance-Bezugskurs jetzt fehlt der Coin («ohne …»).
     */
    fun routed(p: PortfolioPrices): RoutedHistory = PortfolioExchangeRoute.historyCloses(
        daily.closes, daily.ranges, daily.own, daily.routed + p.sources.keys, p.exchangeFailed.keys, p.prices, p.binance,
    )

    /** Deckt diese Ladung [needed] ab (alle Coins, genug Tage)? */
    fun covers(needed: HistoryRequest): Boolean =
        request.coins.containsAll(needed.coins) && request.days >= needed.days
}

/** Letzte Millisekunde eines Tags in [zone]. */
internal fun dayEndIn(zone: ZoneId): (Long) -> Long = { day ->
    LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
}

/** Anfrage für [range]: «Seit 1. Kauf» braucht je nach erstem Kauf mehr Tage (siehe [PortfolioHistory.candleDays]). */
internal fun historyRequest(txs: List<PortfolioTxEntity>, range: PortfolioHistoryRange): HistoryRequest {
    val zone = ZoneId.systemDefault()
    val first = txs.filter { it.amount.signum() > 0 }.minOfOrNull { it.time }
    val days = PortfolioHistory.candleDays(range, first, PortfolioHistory.utcToday(), dayEndIn(zone))
    return HistoryRequest(txs.flatMap { it.coins() }.map { PortfolioCalculator.normalizeCoin(it) }.toSet(), days)
}
