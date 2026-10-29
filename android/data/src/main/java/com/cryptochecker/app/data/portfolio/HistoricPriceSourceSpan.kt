// Kerzen für die Kette der Tagesschlüsse (Teil von `HistoricPriceSource`): je Tag eine Abfrage ([DayFetch]) oder für
// viele Tage desselben Coins je Quelle in Stücken ([SpanFetch], Steuer-Export). Eigene Datei, weil die Abfrageart vom
// Ablauf der Kette getrennt lesbar sein soll; beide Arten liefern je UTC-Tag dieselbe Kerze, die Kette und ihre
// Prüfungen ([HistoricPriceSource.closeUsdt]) sind dieselben.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Kerzen der Binance-Gruppe und von Coinbase für [HistoricPriceSource.closeUsdt], dazu der Devisenkurs eines Tags (eigene
 * Börse in Fiat). Schluss = Kerze, die genau bei `start` beginnt (gültig, > 0); null = keine (auch: Abfrage gescheitert).
 */
internal interface DayCloseFetch {
    suspend fun binanceDay(host: String, endpoint: String, symbol: String, start: Long): Double?

    /** Jüngster Schluss derselben Reihe (Identitätsprüfung). */
    suspend fun binanceLatest(host: String, endpoint: String, symbol: String): Double?

    /** Coinbase-Produkt [product] (z. B. «ETH-USD»), Kerze des Tags [date]. */
    suspend fun coinbaseDay(product: String, date: LocalDate, start: Long): Double?

    /** Jüngster Schluss desselben Produkts (ohne Zeitfenster = die neuesten Kerzen). */
    suspend fun coinbaseLatest(product: String): Double?

    suspend fun usdTo(currency: String, date: LocalDate): HistoricFx?
}

/** Je Tag eine Abfrage (Stichtag, Vorschlag beim Erfassen): Binance mit `startTime` und `limit=1`, Coinbase genau über den Tag. */
internal class DayFetch(private val source: HistoricPriceSource) : DayCloseFetch {
    override suspend fun binanceDay(host: String, endpoint: String, symbol: String, start: Long): Double? {
        val body = source.get(host, "$endpoint?symbol=$symbol&interval=1d&startTime=$start&limit=1") ?: return null
        val candles = runCatchingCancellable { CandleDataSource.parseBinance(body) }.getOrNull() ?: return null
        return source.closeOf(candles, start)
    }

    override suspend fun binanceLatest(host: String, endpoint: String, symbol: String): Double? =
        source.get(host, "$endpoint?symbol=$symbol&interval=1d&limit=1")
            ?.let { runCatchingCancellable { CandleDataSource.parseBinance(it) }.getOrNull() }
            ?.lastOrNull()?.close

    override suspend fun coinbaseDay(product: String, date: LocalDate, start: Long): Double? {
        val body = source.get(HistoricPriceSource.COINBASE_HOST, coinbaseUrl(product, date, date)) ?: return null
        val candles = runCatchingCancellable { CandleDataSource.parseCoinbase(body) }.getOrNull() ?: return null
        return source.closeOf(candles, start)
    }

    override suspend fun coinbaseLatest(product: String): Double? =
        source.get(HistoricPriceSource.COINBASE_HOST, coinbaseUrl(product, null, null))
            ?.let { runCatchingCancellable { CandleDataSource.parseCoinbase(it) }.getOrNull() }
            ?.maxByOrNull { it.openTime }?.close

    override suspend fun usdTo(currency: String, date: LocalDate): HistoricFx? = source.usdTo(currency, date)
}

/** Coinbase-Tageskerzen von [from] bis [to] (ISO 8601, UTC, ganze Tage); ohne Fenster die neuesten. */
internal fun coinbaseUrl(product: String, from: LocalDate?, to: LocalDate?): String {
    val base = "https://${HistoricPriceSource.COINBASE_HOST}/products/$product/candles?granularity=86400"
    return if (from == null || to == null) base else "$base&start=${from}T00:00:00Z&end=${to}T23:59:59Z"
}

/**
 * Viele Tage EINES Coins (Steuer-Export): je Quelle die Kerzen der verlangten Tage in Stücken (Binance höchstens
 * [BINANCE_SPAN_DAYS], Coinbase [COINBASE_SPAN_DAYS] Tage je Abfrage, Stücke nur dort, wo Tage verlangt sind) und der
 * jüngste Schluss einmal je Reihe; erst geholt, wenn die Kette diese Quelle für einen Tag braucht. Je Tag gilt die Kerze,
 * die genau an ihm beginnt — dieselbe, die [DayFetch] einzeln holt. Ein Tag ausserhalb der Stücke fragt einzeln.
 * Nicht threadsicher: je Coin eine Instanz, Tage nacheinander.
 */
internal class SpanFetch(private val source: HistoricPriceSource, days: Collection<LocalDate>) : DayCloseFetch {
    private val sorted = days.distinct().sorted()
    private val binanceSpans = spans(sorted, BINANCE_SPAN_DAYS)
    private val coinbaseSpans = spans(sorted, COINBASE_SPAN_DAYS)

    /** «Quelle|Reihe|erster Tag des Stücks» → Beginn der Kerze → Schluss. */
    private val loaded = HashMap<String, Map<Long, Double>>()

    /** «Quelle|Reihe» → jüngster Schluss (auch null = keiner). */
    private val latest = HashMap<String, Double?>()
    private val fx = HashMap<Pair<String, LocalDate>, HistoricFx?>()

    override suspend fun binanceDay(host: String, endpoint: String, symbol: String, start: Long): Double? {
        val span = spanOf(binanceSpans, start) ?: return source.dayFetch.binanceDay(host, endpoint, symbol, start)
        val closes = loaded.getOrPut("$host|$symbol|${span.first}") {
            val from = startMillis(span.first)
            val to = startMillis(span.second)
            val body = source.get(host, "$endpoint?symbol=$symbol&interval=1d&startTime=$from&endTime=$to&limit=$BINANCE_SPAN_DAYS")
            body?.let { runCatchingCancellable { CandleDataSource.parseBinance(it) }.getOrNull() }.byOpenTime()
        }
        return closes[start]?.takeIf { it > 0.0 && it.isFinite() }
    }

    override suspend fun binanceLatest(host: String, endpoint: String, symbol: String): Double? =
        remembered("$host|$symbol") { source.dayFetch.binanceLatest(host, endpoint, symbol) }

    override suspend fun coinbaseDay(product: String, date: LocalDate, start: Long): Double? {
        val span = spanOf(coinbaseSpans, start) ?: return source.dayFetch.coinbaseDay(product, date, start)
        val closes = loaded.getOrPut("cb|$product|${span.first}") {
            val body = source.get(HistoricPriceSource.COINBASE_HOST, coinbaseUrl(product, span.first, span.second))
            body?.let { runCatchingCancellable { CandleDataSource.parseCoinbase(it) }.getOrNull() }.byOpenTime()
        }
        return closes[start]?.takeIf { it > 0.0 && it.isFinite() }
    }

    override suspend fun coinbaseLatest(product: String): Double? =
        remembered("cb|$product") { source.dayFetch.coinbaseLatest(product) }

    override suspend fun usdTo(currency: String, date: LocalDate): HistoricFx? {
        val key = currency to date
        if (key in fx) return fx[key]
        return source.usdTo(currency, date).also { fx[key] = it }
    }

    private suspend fun remembered(key: String, load: suspend () -> Double?): Double? {
        if (key in latest) return latest[key]
        return load().also { latest[key] = it }
    }

    /** Erste Kerze je Beginn (wie [HistoricPriceSource.closeOf]); keine Antwort = keine Kerzen. */
    private fun List<HourCandle>?.byOpenTime(): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        this?.forEach { out.putIfAbsent(it.openTime, it.close) }
        return out
    }

    private fun spanOf(spans: List<Pair<LocalDate, LocalDate>>, start: Long): Pair<LocalDate, LocalDate>? {
        val day = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
        if (startMillis(day) != start) return null
        return spans.firstOrNull { !day.isBefore(it.first) && !day.isAfter(it.second) }
    }

    companion object {
        /** Binance-Tageskerzen je Abfrage (Grenze von Spot und Binance.US: 1000). */
        const val BINANCE_SPAN_DAYS = 1000

        /** Coinbase-Tageskerzen je Abfrage (Grenze: 300). */
        const val COINBASE_SPAN_DAYS = 300

        fun startMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        /**
         * Stücke über die sortierten Tage [days]: jedes beginnt beim ersten noch nicht gedeckten Tag und endet beim letzten
         * verlangten Tag innerhalb von höchstens [size] Tagen (nie über den Bedarf hinaus, nie in der Zukunft).
         */
        fun spans(days: List<LocalDate>, size: Int): List<Pair<LocalDate, LocalDate>> {
            val out = ArrayList<Pair<LocalDate, LocalDate>>()
            for (day in days) {
                val open = out.lastOrNull()
                if (open != null && day.isBefore(open.first.plusDays(size.toLong()))) {
                    out[out.lastIndex] = open.first to day
                } else {
                    out += day to day
                }
            }
            return out
        }
    }
}

/**
 * Tagesschlüsse vieler (Coin, Tag) für den Steuer-Export: je Coin die Tage aus [wanted] mit derselben Kette und
 * Prüfung wie [HistoricPriceSource.dailyCloseUsdt] (gleicher Schluss je UTC-Tag, gleiche Reihenfolge der Quellen), die
 * Kerzen aber je Quelle in Stücken ([SpanFetch]); eigene Börsen und Namen der Merkliste einmal gelesen, höchstens
 * [HistoricPriceSource.PARALLEL] Coins gleichzeitig. Schlüssel wie übergeben; ein Fehler bei einem Tag = kein Kurs
 * (wie die Einzelabfrage beim Aufrufer). [current]: Bezugskurse wie bei [HistoricPriceSource.dailyCloseUsdt].
 */
suspend fun HistoricPriceSource.dayClosesUsdt(
    wanted: Map<String, Collection<LocalDate>>,
    current: Map<String, Double>,
): Map<String, Map<LocalDate, HistoricPriceSource.CloseResult>> = withContext(Dispatchers.IO) {
    val bases = wanted.keys.associateWith { PortfolioCalculator.normalizeCoin(it) }
    val assets = bases.values.filter { PortfolioStables.needsQuote(it) && HistoricPriceSource.isAsset(it) }.distinct()
    val own = ownRoutes(assets)
    val names = tokenIdentityStore.watchNames(assets)
    val limiter = Semaphore(HistoricPriceSource.PARALLEL)
    coroutineScope {
        wanted.map { (coin, days) ->
            async {
                limiter.withPermit {
                    val b = bases.getValue(coin)
                    val fetch = SpanFetch(this@dayClosesUsdt, days)
                    coin to days.distinct().sorted().associateWith { day ->
                        try {
                            closeUsdt(b, day, current[coin], own[b], fetch, names)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            HistoricPriceSource.CloseResult(null)
                        }
                    }
                }
            }
        }.awaitAll().toMap()
    }
}
