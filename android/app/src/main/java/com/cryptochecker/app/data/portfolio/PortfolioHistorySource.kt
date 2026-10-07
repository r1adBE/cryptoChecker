package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Tagesschlüsse eines Coins und wie viele Tage bis heute dafür abgefragt wurden ([span]). */
private data class CoinCloses(val closes: Map<Long, Double>, val span: Int)

/**
 * Tagesschlusskurse für den Wertverlauf im Portfolio: Binance-Tageskerzen
 * `<BASE>USDT` — mindestens [PortfolioHistory.MAX_DAYS] Tage, für «Seit 1. Kauf» so viele wie
 * nötig ([PortfolioHistory.candleDays]) in Stücken von höchstens
 * [PortfolioHistory.MAX_CANDLES_PER_REQUEST] ([PortfolioHistory.candleChunks]) —
 * data-api.binance.vision, sonst api.binance.com. Zwischenspeicher je Coin 12 h auf
 * dem Gerät ([CycleCacheStore], Eintrag «phist_<COIN>», samt abgefragter Spanne) und im
 * Speicher; ein Eintrag mit kürzerer Spanne als verlangt wird neu geholt. Ein Coin, den
 * Binance nicht führt (HTTP 400), wird als «ohne Verlauf» ebenfalls 12 h gemerkt.
 * Stablecoins brauchen keine Abfrage (Kurs 1, siehe [PortfolioHistory]).
 */
@Singleton
class PortfolioHistorySource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val cacheStore: CycleCacheStore,
) {
    /** Coin → (Schlüsse samt Spanne, Abfragezeit); leere Karte = kein Binance-Paar. */
    private val memory = ConcurrentHashMap<String, Pair<CoinCloses, Long>>()

    /**
     * Schlusskurse je Coin (Grossschreibung) für mindestens die letzten [days] Tage. Coins
     * ohne Verlauf (kein Paar, Netz weg und nichts gespeichert) fehlen in der Rückgabe.
     */
    suspend fun dailyCloses(
        coins: Collection<String>,
        days: Int = PortfolioHistory.MAX_DAYS,
    ): Map<String, Map<Long, Double>> =
        withContext(Dispatchers.IO) {
            val symbols = coins.map { PortfolioCalculator.normalizeCoin(it) }
                .filter { isAsset(it) && !PortfolioHistory.isStable(it) }
                .distinct()
            val span = days.coerceAtLeast(PortfolioHistory.MAX_DAYS)
            val limiter = Semaphore(PARALLEL)
            coroutineScope {
                symbols.map { coin -> async { coin to limiter.withPermit { closesOf(coin, span) } } }.awaitAll()
            }.mapNotNull { (coin, closes) -> closes?.takeIf { it.isNotEmpty() }?.let { coin to it } }.toMap()
        }

    private suspend fun closesOf(coin: String, span: Int): Map<Long, Double>? {
        val now = System.currentTimeMillis()
        memory[coin]?.let { (cached, at) ->
            if (cached.span >= span && CycleCachePolicy.isFresh(at, now, TTL_MILLIS)) return cached.closes
        }
        val stored = cacheStore.read(cacheName(coin), CODEC)
        if (stored != null && stored.value.span >= span && CycleCachePolicy.isFresh(stored.savedAt, now, TTL_MILLIS)) {
            memory[coin] = stored.value to stored.savedAt
            return stored.value.closes
        }
        val fresh = fetch(coin, span, now)
        if (fresh != null) {
            val entry = CoinCloses(fresh, span)
            memory[coin] = entry to now
            cacheStore.write(cacheName(coin), entry, now, CODEC)
            return fresh
        }
        // Netz weg: älterer (oder kürzerer) Stand ist besser als keiner
        return stored?.value?.closes ?: memory[coin]?.first?.closes
    }

    /**
     * Kerzen der letzten [span] Tage holen, neueste Stücke zuerst; leere Karte = Binance kennt
     * das Paar nicht, null = nicht erreichbar. Beginnt ein Stück später als verlangt (Coin
     * damals noch nicht gelistet), werden die älteren nicht mehr abgefragt.
     */
    private suspend fun fetch(coin: String, span: Int, now: Long): Map<Long, Double>? {
        val today = PortfolioHistory.epochDayOfUtcMillis(now)
        val out = HashMap<Long, Double>()
        for (chunk in PortfolioHistory.candleChunks(span, today).asReversed()) {
            val candles = fetchChunk(coin, chunk) ?: return null
            if (candles.isEmpty() && out.isEmpty()) return emptyMap()
            out.putAll(candles)
            if (candles.isEmpty() || candles.keys.min() > chunk.first) break
        }
        return out
    }

    /** Ein Stück Tageskerzen (UTC-Tage [days]); leere Karte = kein Paar oder keine Kerzen, null = nicht erreichbar. */
    private suspend fun fetchChunk(coin: String, days: LongRange): Map<Long, Double>? {
        val startMillis = days.first * DAY_MILLIS
        val endMillis = (days.last + 1) * DAY_MILLIS - 1
        val limit = (days.last - days.first + 1).toInt()
        for ((host, endpoint) in HOSTS) {
            if (BlockedSources.isBlocked(host)) continue
            val url = "$endpoint?symbol=${coin}USDT&interval=1d&startTime=$startMillis&endTime=$endMillis&limit=$limit"
            val body = try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callMarket(url, null) }
            } catch (e: Exception) {
                // Echten Abbruch des Aufrufers nicht verschlucken (eine Zeitgrenze darunter schon)
                currentCoroutineContext().ensureActive()
                // «Invalid symbol»: Paar gibt es nicht — der Spiegel und der Spot führen dieselben Paare
                if (e is HttpMarketError && e.httpCode == 400) return emptyMap()
                if (!BlockedSources.noteFailure(host, e)) Timber.d(e, "Portfolio-Verlauf: %s", url)
                null
            } ?: continue
            val candles = runCatching { CandleDataSource.parseBinance(body) }.getOrNull() ?: continue
            return candles.associate { PortfolioHistory.epochDayOfUtcMillis(it.openTime) to it.close }
        }
        return null
    }

    private companion object {
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val TTL_MILLIS = 12 * 60 * 60_000L
        const val DAY_MILLIS = 86_400_000L

        val HOSTS = listOf(
            "data-api.binance.vision" to "https://data-api.binance.vision/api/v3/klines",
            "api.binance.com" to "https://api.binance.com/api/v3/klines",
        )

        fun isAsset(s: String) = s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() }

        fun cacheName(coin: String) = "phist_" + coin.filter { it.isLetterOrDigit() }

        /**
         * {"span": 366, "days":[[epochDay, close], …]} — leere Liste = kein Paar. Ältere
         * Einträge ohne «span» stammen aus der festen Jahres-Abfrage ([PortfolioHistory.MAX_DAYS]).
         */
        val CODEC = CacheCodec<CoinCloses>(
            encode = { entry ->
                val days = JSONArray()
                entry.closes.entries.sortedBy { it.key }.forEach { (day, close) ->
                    if (close.isFinite()) days.put(JSONArray().put(day).put(close))
                }
                JSONObject().put("span", entry.span).put("days", days)
            },
            decode = { o ->
                val days = o.getJSONArray("days")
                val closes = (0 until days.length()).associate { i ->
                    val pair = days.getJSONArray(i)
                    pair.getLong(0) to pair.getDouble(1)
                }
                CoinCloses(closes, o.optInt("span", PortfolioHistory.MAX_DAYS))
            },
        )
    }
}
