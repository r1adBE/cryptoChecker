package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PricePlausibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Devisenkurs eines Stichtags mit dem tatsächlichen Kursdatum (Wochenende → Freitag). */
data class HistoricFx(val rate: Double, val date: String?)

/**
 * Historische Kurse für den Stichtag-Export:
 *  - Tagesschlusskurs (Tageskerze UTC) in USDT, gleiche Ausweich-Kette wie
 *    [CandleDataSource]: data-api.binance.vision → api.binance.com → fapi.binance.com
 *    → api.binance.us (USDT, sonst USD) → Coinbase (USD, sonst USDC).
 *    Es zählt nur die Kerze, die genau an diesem Tag beginnt (Binance liefert sonst
 *    die nächste, wenn der Coin erst später gelistet wurde).
 *    Plausibilität ([PricePlausibility.acceptSourceClose]): Ausweich-Quellen (Futures,
 *    Binance.US, Coinbase) zählen nur, wenn dieselbe Quelle den Coin heute höchstens 25 % vom
 *    aktuellen Portfolio-Kurs entfernt führt (eine Abfrage mehr, nur bei einem Treffer dort);
 *    sonst ist die nächste Quelle dran.
 *  - Devisenkurs USD → Zielwährung dieses Tags (EZB über Frankfurter) und die Tageskurse eines
 *    Zeitraums für den Wertverlauf ([usdToSeries], gleicher Anbieter, 6 h im Speicher).
 * Stablecoins nach [PortfolioStables]: USDT = 1 ohne Abfrage, andere Stablecoins werden wie
 * jeder Coin abgefragt (fehlt der Kurs, setzt [CutoffExport.rows] 1). Kurse ohne Zwischenspeicher.
 */
@Singleton
class HistoricPriceSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /**
     * Schlusskurse der Coins am Tag [date]; Coins ohne (plausiblen) Kurs fehlen in der Rückgabe.
     * [current]: aktuelle Portfolio-Kurse in USDT für die Plausibilitätsprüfung (fehlt einer,
     * gilt der Kurs der ersten Quelle ungeprüft).
     */
    suspend fun dailyClosesUsdt(
        coins: Collection<String>,
        date: LocalDate,
        current: Map<String, Double> = emptyMap(),
    ): Map<String, Double> =
        withContext(Dispatchers.IO) {
            val symbols = coins.map { PortfolioCalculator.normalizeCoin(it) }.filter { isAsset(it) }.distinct()
            val limiter = Semaphore(PARALLEL)
            coroutineScope {
                symbols.map { coin ->
                    async { coin to limiter.withPermit { dailyCloseUsdt(coin, date, current[coin]) } }
                }.awaitAll()
            }.mapNotNull { (coin, price) -> price?.let { coin to it } }.toMap()
        }

    /**
     * Schlusskurs eines Coins am Tag [date] (UTC-Tageskerze); null, wenn keine Quelle einen
     * plausiblen hat. USDT = 1. [current] = aktueller Portfolio-Kurs (Plausibilität).
     */
    suspend fun dailyCloseUsdt(coin: String, date: LocalDate, current: Double? = null): Double? {
        val b = PortfolioCalculator.normalizeCoin(coin)
        if (!PortfolioStables.needsQuote(b)) return 1.0
        if (!isAsset(b)) return null
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // Binance-Spot liefert auch die aktuellen Portfolio-Kurse — keine Prüfung nötig
        val binance = listOf(
            Triple("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines", true),
            Triple("api.binance.com", "https://api.binance.com/api/v3/klines", true),
            Triple("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines", false),
        )
        for ((host, endpoint, trusted) in binance) {
            binanceClose(host, endpoint, "${b}USDT", start, trusted, current)?.let { return it }
        }
        for (symbol in listOf("${b}USDT", "${b}USD")) {
            binanceClose("api.binance.us", "https://api.binance.us/api/v3/klines", symbol, start, false, current)
                ?.let { return it }
        }
        return coinbaseClose(b, date, start, current)
    }

    private suspend fun binanceClose(
        host: String,
        endpoint: String,
        symbol: String,
        start: Long,
        trusted: Boolean,
        current: Double?,
    ): Double? {
        if (BlockedSources.isBlocked(host)) return null
        val body = get(host, "$endpoint?symbol=$symbol&interval=1d&startTime=$start&limit=1") ?: return null
        val candles = runCatching { CandleDataSource.parseBinance(body) }.getOrNull() ?: return null
        val close = closeOf(candles, start) ?: return null
        if (trusted || current == null) return close
        // Ausweich-Quelle: führt sie den Coin heute zum aktuellen Kurs? (jüngste Tageskerze)
        val latest = get(host, "$endpoint?symbol=$symbol&interval=1d&limit=1")
            ?.let { runCatching { CandleDataSource.parseBinance(it) }.getOrNull() }
            ?.lastOrNull()?.close
        return close.takeIf { PricePlausibility.acceptSourceClose(it, trusted = false, sourceLatest = latest, current = current) }
    }

    private suspend fun coinbaseClose(b: String, date: LocalDate, start: Long, current: Double?): Double? {
        val host = "api.exchange.coinbase.com"
        // Fenster genau über den einen Tag (ISO 8601, UTC)
        val from = "${date}T00:00:00Z"
        val to = "${date}T23:59:59Z"
        for (quote in listOf("USD", "USDC")) {
            if (BlockedSources.isBlocked(host)) return null
            val url = "https://$host/products/$b-$quote/candles?granularity=86400&start=$from&end=$to"
            val body = get(host, url) ?: continue
            val candles = runCatching { CandleDataSource.parseCoinbase(body) }.getOrNull() ?: continue
            val close = closeOf(candles, start) ?: continue
            if (current == null) return close
            // Jüngste Tageskerzen desselben Produkts (ohne Zeitfenster = die neuesten)
            val latest = get(host, "https://$host/products/$b-$quote/candles?granularity=86400")
                ?.let { runCatching { CandleDataSource.parseCoinbase(it) }.getOrNull() }
                ?.maxByOrNull { it.openTime }?.close
            if (PricePlausibility.acceptSourceClose(close, trusted = false, sourceLatest = latest, current = current)) return close
        }
        return null
    }

    private fun closeOf(candles: List<HourCandle>, start: Long): Double? =
        candles.firstOrNull { it.openTime == start }?.close?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * Devisenkurs USD → [currency] am Tag [date]; USD = 1 (ohne Datum).
     * Frankfurter liefert an Wochenenden/Feiertagen den letzten Geschäftstag — dessen
     * Datum steht in [HistoricFx.date]. null, wenn kein Kurs zu haben ist.
     */
    suspend fun usdTo(currency: String, date: LocalDate): HistoricFx? {
        val target = currency.trim().uppercase()
        if (target == "USD") return HistoricFx(1.0, null)
        if (target.length != 3 || !target.all { it in 'A'..'Z' }) return null
        return withContext(Dispatchers.IO) {
            val urls = listOf(
                "https://api.frankfurter.app/$date?from=USD&to=$target",
                "https://api.frankfurter.dev/v1/$date?from=USD&to=$target",
            )
            for (url in urls) {
                val body = get(FX_HOST, url) ?: continue
                val fx = runCatching {
                    val root = JSONObject(body)
                    val rate = root.getJSONObject("rates").getDouble(target)
                    HistoricFx(rate, root.optString("date").takeIf { it.isNotEmpty() })
                }.getOrNull()
                if (fx != null && fx.rate > 0.0 && fx.rate.isFinite()) return@withContext fx
            }
            // Lew nach der Euro-Einführung: EUR × 1.95583 (fester Kurs)
            if (CurrencyConversion.deriveBgnFromEur(target, date)) {
                val eur = usdTo("EUR", date)
                CurrencyConversion.bgnFromEur(eur?.rate)?.let { return@withContext HistoricFx(it, eur?.date) }
            }
            null
        }
    }

    /** Tageskurse je «Währung|von|bis» mit Abfragezeit (nur im Speicher). */
    private val fxSeriesCache = ConcurrentHashMap<String, Pair<Map<Long, Double>, Long>>()

    /**
     * Tageskurse USD → [currency] von [from] bis [to] (EZB über Frankfurter, eine Abfrage für den
     * ganzen Zeitraum): Tag (Tage seit 1970-01-01) → Kurs, nur Geschäftstage — Wochenenden und
     * Feiertage füllt [PortfolioHistoryFx.rateOn] mit dem Vortag. USD: leere Karte (kein Bedarf).
     * 6 h im Speicher. null, wenn kein Kurs zu haben ist.
     */
    suspend fun usdToSeries(currency: String, from: LocalDate, to: LocalDate): Map<Long, Double>? {
        val target = currency.trim().uppercase()
        if (target == "USD") return emptyMap()
        if (target.length != 3 || !target.all { it in 'A'..'Z' } || from.isAfter(to)) return null
        val key = "$target|$from|$to"
        val now = System.currentTimeMillis()
        fxSeriesCache[key]?.let { (rates, at) -> if (now - at in 0 until FX_SERIES_TTL_MILLIS) return rates }
        return withContext(Dispatchers.IO) {
            val symbols = PortfolioHistoryFx.requestCurrencies(target).joinToString(",")
            val urls = listOf(
                "https://api.frankfurter.app/$from..$to?from=USD&to=$symbols",
                "https://api.frankfurter.dev/v1/$from..$to?from=USD&to=$symbols",
            )
            for (url in urls) {
                val body = get(FX_HOST, url) ?: continue
                val byDate = runCatching {
                    val rates = JSONObject(body).getJSONObject("rates")
                    rates.keys().asSequence().associateWith { date ->
                        val day = rates.getJSONObject(date)
                        day.keys().asSequence().associateWith { day.getDouble(it) }
                    }
                }.getOrNull() ?: continue
                val series = PortfolioHistoryFx.ratesByDay(target, byDate)
                if (series.isNotEmpty()) {
                    fxSeriesCache[key] = series to System.currentTimeMillis()
                    return@withContext series
                }
            }
            null
        }
    }

    /** GET mit eigener Zeitgrenze; null bei Fehler. 451/403 sperrt die Quelle vorübergehend. */
    private suspend fun get(host: String, url: String): String? {
        val body = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callMarket(url, null) }
        } catch (e: Exception) {
            // Echten Abbruch des Aufrufers nicht verschlucken
            currentCoroutineContext().ensureActive()
            if (host == FX_HOST || !BlockedSources.noteFailure(host, e)) {
                Timber.d(e, "Stichtag: Abfrage fehlgeschlagen: %s", url)
            }
            null
        }
        currentCoroutineContext().ensureActive()
        return body
    }

    private companion object {
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val FX_HOST = "frankfurter"
        const val FX_SERIES_TTL_MILLIS = 6 * 60 * 60_000L

        fun isAsset(s: String) = s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() }
    }
}
