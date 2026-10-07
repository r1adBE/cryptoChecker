package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Kerzenintervall, unabhängig von der Börse. */
enum class CandleInterval(val binanceCode: String) {
    H1("1h"),
    H4("4h"),
    D1("1d"),
    W1("1w"),
}

/**
 * Quellen, die mit 451/403 geantwortet haben (Geo-Sperre, z. B. Binance in den USA).
 * Sie werden für [BLOCK_MILLIS] übersprungen. Prozessweit geteilt von Kerzen und
 * Futures-Kennzahlen; Schlüssel ist der Hostname.
 */
internal object BlockedSources {
    private const val BLOCK_MILLIS = 6 * 60 * 60_000L
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    fun isBlocked(key: String): Boolean {
        val until = blockedUntil[key] ?: return false
        if (System.currentTimeMillis() < until) return true
        blockedUntil.remove(key, until)
        return false
    }

    /** Wertet einen Fehler aus; true = Sperr-Antwort, Quelle ist jetzt vermerkt. */
    fun noteFailure(key: String, error: Throwable): Boolean {
        if (error is HttpMarketError && (error.httpCode == 451 || error.httpCode == 403)) {
            blockedUntil[key] = System.currentTimeMillis() + BLOCK_MILLIS
            Timber.i("Quelle gesperrt (HTTP %d), überspringe sie 6 h: %s", error.httpCode, key)
            return true
        }
        return false
    }
}

/**
 * Kerzen (OHLCV) mit Ausweich-Kette («Weiche»), weil Binance in manchen Ländern
 * (u. a. USA) gesperrt ist. Reihenfolge:
 *  1. data-api.binance.vision (öffentlicher Binance-Spiegel, Spot)
 *  2. api.binance.com (Spot)
 *  3. fapi.binance.com (USDⓈ-M-Perpetual)
 *  4. api.binance.us (Binance.US; USDT, sonst USD)
 *  5. Coinbase Exchange (USD, sonst USDC; 4 h und 1 Woche zusammengesetzt)
 *
 * Die erste Quelle mit mindestens zwei Kerzen gewinnt und wird je Paar und
 * Intervall eine Stunde lang zuerst gefragt. Gesperrte Quellen siehe [BlockedSources].
 * Rückgabe zeitlich aufsteigend; die letzte Kerze läuft in der Regel noch.
 * Als Kerzentyp dient [HourCandle] (gilt hier für jedes Intervall).
 */
@Singleton
class CandleDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private enum class Source(val key: String) {
        BINANCE_VISION("data-api.binance.vision"),
        BINANCE_SPOT("api.binance.com"),
        BINANCE_FUTURES("fapi.binance.com"),
        BINANCE_US("api.binance.us"),
        COINBASE("api.exchange.coinbase.com"),
    }

    /** Zuletzt erfolgreiche Quelle je «BASE|QUOTE|INTERVALL» mit Zeitpunkt. */
    private val working = ConcurrentHashMap<String, Pair<Source, Long>>()

    /**
     * Bis zu [limit] Kerzen, aufsteigend; null, wenn keine Quelle das Paar liefert.
     * Coinbase liefert höchstens 300 Rohkerzen — bei langen Reihen also weniger.
     */
    suspend fun candles(base: String, quote: String, interval: CandleInterval, limit: Int): List<HourCandle>? {
        val b = base.trim().uppercase()
        val q = quote.trim().uppercase()
        if (!isAsset(b) || !isAsset(q)) return null
        val n = limit.coerceIn(2, MAX_LIMIT)
        val memoKey = "$b|$q|${interval.name}"

        return withContext(Dispatchers.IO) {
            val preferred = working[memoKey]
                ?.takeIf { System.currentTimeMillis() - it.second in 0 until WORKING_MILLIS }
                ?.first
            val order = if (preferred == null) Source.entries else listOf(preferred) + (Source.entries - preferred)

            for (source in order) {
                if (BlockedSources.isBlocked(source.key)) continue
                val result = fetchFrom(source, b, q, interval, n)
                if (result != null && result.size >= 2) {
                    working[memoKey] = source to System.currentTimeMillis()
                    return@withContext result
                }
            }
            working.remove(memoKey)
            null
        }
    }

    private suspend fun fetchFrom(
        source: Source, b: String, q: String, interval: CandleInterval, limit: Int,
    ): List<HourCandle>? {
        val binanceSymbol = b + binanceQuote(q)
        return when (source) {
            Source.BINANCE_VISION ->
                binance(source, "https://data-api.binance.vision/api/v3/klines", listOf(binanceSymbol), interval, limit)
            Source.BINANCE_SPOT ->
                binance(source, "https://api.binance.com/api/v3/klines", listOf(binanceSymbol), interval, limit)
            Source.BINANCE_FUTURES ->
                binance(source, "https://fapi.binance.com/fapi/v1/klines", listOf(binanceSymbol), interval, limit)
            Source.BINANCE_US -> {
                // Binance.US führt viele Paare nur gegen USD
                val symbols = listOfNotNull(binanceSymbol, if (binanceQuote(q) == "USDT") b + "USD" else null).distinct()
                binance(source, "https://api.binance.us/api/v3/klines", symbols, interval, limit)
            }
            Source.COINBASE -> coinbase(b, q, interval, limit)
        }
    }

    private suspend fun binance(
        source: Source, endpoint: String, symbols: List<String>, interval: CandleInterval, limit: Int,
    ): List<HourCandle>? {
        for (symbol in symbols) {
            if (BlockedSources.isBlocked(source.key)) return null
            val body = get(source, "$endpoint?symbol=$symbol&interval=${interval.binanceCode}&limit=$limit") ?: continue
            val candles = runCatching { parseBinance(body) }.getOrNull()
            if (candles != null && candles.size >= 2) return candles
        }
        return null
    }

    private suspend fun coinbase(b: String, q: String, interval: CandleInterval, limit: Int): List<HourCandle>? {
        // Erlaubt sind nur 60, 300, 900, 3600, 21600, 86400 s
        val granularity = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> 3600
            CandleInterval.D1, CandleInterval.W1 -> 86400
        }
        val quotes = if (q == "USD" || q == "USDT" || q == "USDC") listOf("USD", "USDC") else listOf(q)
        for (cq in quotes) {
            if (BlockedSources.isBlocked(Source.COINBASE.key)) return null
            val url = "https://api.exchange.coinbase.com/products/$b-$cq/candles?granularity=$granularity"
            val body = get(Source.COINBASE, url) ?: continue
            val raw = runCatching { parseCoinbase(body) }.getOrNull() ?: continue
            val candles = when (interval) {
                CandleInterval.H1, CandleInterval.D1 -> raw
                CandleInterval.H4 -> aggregate(raw, 4 * HOUR_MILLIS, 0L, 4)
                CandleInterval.W1 -> aggregate(raw, WEEK_MILLIS, MONDAY_OFFSET_MILLIS, 7)
            }.takeLast(limit)
            if (candles.size >= 2) return candles
        }
        return null
    }

    /** Eine Anfrage mit eigener Zeitgrenze; null bei Fehler. 451/403 sperrt die Quelle. */
    private suspend fun get(source: Source, url: String): String? {
        val body = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callMarket(url, null) }
        } catch (e: Exception) {
            // Echten Abbruch des Aufrufers nicht verschlucken
            currentCoroutineContext().ensureActive()
            if (!BlockedSources.noteFailure(source.key, e)) Timber.d(e, "Kerzen nicht verfügbar: %s", url)
            null
        }
        currentCoroutineContext().ensureActive()
        return body
    }

    companion object {
        private const val MAX_LIMIT = 1000
        private const val REQUEST_TIMEOUT_MILLIS = 8_000L
        private const val WORKING_MILLIS = 60 * 60_000L
        private const val HOUR_MILLIS = 60 * 60_000L
        private const val DAY_MILLIS = 24 * HOUR_MILLIS
        private const val WEEK_MILLIS = 7 * DAY_MILLIS

        /** 1.1.1970 war ein Donnerstag; Binance-Wochen beginnen Montag 00:00 UTC (5.1.1970). */
        private const val MONDAY_OFFSET_MILLIS = 4 * DAY_MILLIS

        private fun isAsset(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() }

        /** Binance führt kein USD, sondern USDT. */
        private fun binanceQuote(q: String) = if (q == "USD") "USDT" else q

        /** Zahl aus String oder Number lesen (Binance: Strings, Coinbase: Zahlen). */
        private fun JSONArray.num(i: Int): Double = when (val v = get(i)) {
            is Number -> v.toDouble()
            else -> v.toString().toDouble()
        }

        /** Binance-Kline: [openTime, open, high, low, close, volume, ...], älteste zuerst. */
        internal fun parseBinance(json: String): List<HourCandle> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val k = array.getJSONArray(i)
                HourCandle(
                    openTime = k.getLong(0),
                    open = k.num(1),
                    high = k.num(2),
                    low = k.num(3),
                    close = k.num(4),
                    volume = k.num(5),
                )
            }.filter { it.close > 0.0 }.sortedBy { it.openTime }
        }

        /** Coinbase: [time (s), low, high, open, close, volume], NEUESTE zuerst → aufsteigend sortieren. */
        internal fun parseCoinbase(json: String): List<HourCandle> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val k = array.getJSONArray(i)
                HourCandle(
                    openTime = k.getLong(0) * 1000L,
                    open = k.num(3),
                    high = k.num(2),
                    low = k.num(1),
                    close = k.num(4),
                    volume = k.num(5),
                )
            }.filter { it.close > 0.0 }.distinctBy { it.openTime }.sortedBy { it.openTime }
        }

        /**
         * Fasst aufsteigende Kerzen zu Blöcken von [bucketMillis] zusammen (Beginn bei [offsetMillis]).
         * Der erste Block ist meist angeschnitten (Anfang des Abfragefensters) und fällt weg,
         * wenn er weniger als [fullCount] Kerzen hat; der letzte läuft noch und bleibt.
         */
        internal fun aggregate(candles: List<HourCandle>, bucketMillis: Long, offsetMillis: Long, fullCount: Int): List<HourCandle> {
            val groups = candles.groupBy { (it.openTime - offsetMillis) / bucketMillis }
            val result = groups.map { (bucket, list) ->
                HourCandle(
                    openTime = bucket * bucketMillis + offsetMillis,
                    open = list.first().open,
                    high = list.maxOf { it.high },
                    low = list.minOf { it.low },
                    close = list.last().close,
                    volume = list.sumOf { it.volume },
                )
            }
            val firstSize = groups.values.firstOrNull()?.size ?: 0
            return if (firstSize < fullCount) result.drop(1) else result
        }
    }
}
