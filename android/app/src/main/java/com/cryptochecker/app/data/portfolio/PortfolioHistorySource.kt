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

/**
 * Tagesschlusskurse für den Wertverlauf im Portfolio: Binance-Tageskerzen
 * `<BASE>USDT` (bis [PortfolioHistory.MAX_DAYS]), eine Abfrage je Coin —
 * data-api.binance.vision, sonst api.binance.com. Zwischenspeicher je Coin 12 h auf
 * dem Gerät ([CycleCacheStore], Eintrag «phist_<COIN>») und im Speicher. Ein Coin, den
 * Binance nicht führt (HTTP 400), wird als «ohne Verlauf» ebenfalls 12 h gemerkt.
 * Stablecoins brauchen keine Abfrage (Kurs 1, siehe [PortfolioHistory]).
 */
@Singleton
class PortfolioHistorySource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val cacheStore: CycleCacheStore,
) {
    /** Coin → (UTC-Tag → Schluss, Abfragezeit); leere Karte = kein Binance-Paar. */
    private val memory = ConcurrentHashMap<String, Pair<Map<Long, Double>, Long>>()

    /**
     * Schlusskurse je Coin (Grossschreibung). Coins ohne Verlauf (kein Paar, Netz weg
     * und nichts gespeichert) fehlen in der Rückgabe.
     */
    suspend fun dailyCloses(coins: Collection<String>): Map<String, Map<Long, Double>> =
        withContext(Dispatchers.IO) {
            val symbols = coins.map { PortfolioCalculator.normalizeCoin(it) }
                .filter { isAsset(it) && !PortfolioHistory.isStable(it) }
                .distinct()
            val limiter = Semaphore(PARALLEL)
            coroutineScope {
                symbols.map { coin -> async { coin to limiter.withPermit { closesOf(coin) } } }.awaitAll()
            }.mapNotNull { (coin, closes) -> closes?.takeIf { it.isNotEmpty() }?.let { coin to it } }.toMap()
        }

    private suspend fun closesOf(coin: String): Map<Long, Double>? {
        val now = System.currentTimeMillis()
        memory[coin]?.let { (closes, at) -> if (CycleCachePolicy.isFresh(at, now, TTL_MILLIS)) return closes }
        val stored = cacheStore.read(cacheName(coin), CODEC)
        if (stored != null && CycleCachePolicy.isFresh(stored.savedAt, now, TTL_MILLIS)) {
            memory[coin] = stored.value to stored.savedAt
            return stored.value
        }
        val fresh = fetch(coin)
        if (fresh != null) {
            memory[coin] = fresh to now
            cacheStore.write(cacheName(coin), fresh, now, CODEC)
            return fresh
        }
        // Netz weg: älterer Stand ist besser als keiner
        return stored?.value ?: memory[coin]?.first
    }

    /** Kerzen holen; leere Karte = Binance kennt das Paar nicht, null = nicht erreichbar. */
    private suspend fun fetch(coin: String): Map<Long, Double>? {
        for ((host, endpoint) in HOSTS) {
            if (BlockedSources.isBlocked(host)) continue
            val url = "$endpoint?symbol=${coin}USDT&interval=1d&limit=${PortfolioHistory.MAX_DAYS}"
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

        val HOSTS = listOf(
            "data-api.binance.vision" to "https://data-api.binance.vision/api/v3/klines",
            "api.binance.com" to "https://api.binance.com/api/v3/klines",
        )

        fun isAsset(s: String) = s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() }

        fun cacheName(coin: String) = "phist_" + coin.filter { it.isLetterOrDigit() }

        /** {"days":[[epochDay, close], …]} — leere Liste = kein Paar. */
        val CODEC = CacheCodec<Map<Long, Double>>(
            encode = { closes ->
                val days = JSONArray()
                closes.entries.sortedBy { it.key }.forEach { (day, close) ->
                    if (close.isFinite()) days.put(JSONArray().put(day).put(close))
                }
                JSONObject().put("days", days)
            },
            decode = { o ->
                val days = o.getJSONArray("days")
                (0 until days.length()).associate { i ->
                    val pair = days.getJSONArray(i)
                    pair.getLong(0) to pair.getDouble(1)
                }
            },
        )
    }
}
