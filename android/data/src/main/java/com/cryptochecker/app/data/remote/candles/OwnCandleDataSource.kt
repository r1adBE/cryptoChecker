package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.domain.BoundedCache
import com.cryptochecker.app.domain.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.domain.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.domain.activity.CandleSeries
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.market.Sourced
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.marketdata.model.PostRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pausen nach «zu viele Anfragen» (429/418 oder Fehlertext in der Antwort) je Host der eigenen
 * Kerzen — für Hosts ohne gemeinsame Gruppe ([ExchangeBackoff.sharedGroup]; Binance pausiert über
 * [BlockedSources]). Dauer wie dort ([ExchangeBackoff.groupPause]). Wie `CandleHostPauses` (iOS).
 */
object CandleHostPauses {
    private val pauses = ConcurrentHashMap<String, ExchangeBackoff.State>()

    fun isPaused(host: String, now: Long = System.currentTimeMillis()): Boolean =
        pauses[host]?.let { ExchangeBackoff.isPaused(it, now) } ?: false

    fun pause(host: String, httpCode: Int, retryAfterSeconds: Long?, now: Long = System.currentTimeMillis()) {
        pauses.compute(host) { _, old -> ExchangeBackoff.groupPause(old, now, httpCode, retryAfterSeconds) }
        Timber.i("Kerzen: zu viele Anfragen (HTTP %d) von %s, Pause", httpCode, host)
    }

    fun clear() = pauses.clear()
}

/**
 * Kerzen von der EIGENEN Börse eines Paars ([ExchangeCandleRegistry]). Gleiche Regeln wie die
 * Ausweich-Kette ([com.cryptochecker.app.data.remote.CandleDataSource]): Zeitgrenze je Anfrage,
 * 451/403 sperrt den Host 6 h ([BlockedSources]), 429/418 pausiert (Binance gemeinsam, sonst
 * [CandleHostPauses]); nur LAUFENDE Reihen ([CandleSeries.isLive]). Kurz im Speicher (Erfolg 60 s,
 * Fehlschlag 2 Min. — die Aufrufer halten ihre Ergebnisse länger), höchstens vier Abrufe gleichzeitig.
 * Anbieter = Name der Börse ([OwnCandlePolicy.provider]). Wie `OwnCandleDataSource` (iOS).
 */
@Singleton
class OwnCandleDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private class Entry(val time: Long, val load: Load)

    /**
     * Ergebnis eines Abrufs: Kerzen oder null; [unreachable] = eine Anfrage scheiterte vorübergehend (Netz, Zeitgrenze,
     * 5xx, Pause nach «zu viele Anfragen» — [HistoricCloseChain.fetchFailure]); null ohne [unreachable] = keine Reihe.
     * [silent] = dabei kam gar keine Antwort (Netz, DNS, Zeitgrenze), nie eine Fehler-Antwort oder Pause.
     */
    class Load(val value: Sourced<List<HourCandle>>?, val unreachable: Boolean = false, val silent: Boolean = false)

    // Begrenzt: Schlüssel je Paar, Intervall und Anzahl — sonst wüchse er über die Prozesslebensdauer
    private val cache = BoundedCache<String, Entry>(MEMORY_CACHE_ENTRIES)
    private val permits = Semaphore(MAX_PARALLEL)

    /** Hat die Börse von [pair] eigene Kerzen in diesem Intervall? */
    fun supports(pair: CandlePair, interval: CandleInterval): Boolean = ExchangeCandleRegistry.supports(pair, interval)

    /** Bis zu [limit] eigene Kerzen, aufsteigend; null ohne Quelle oder ohne laufende Reihe. */
    suspend fun candles(pair: CandlePair, interval: CandleInterval, limit: Int): Sourced<List<HourCandle>>? =
        load(pair, interval, limit).value

    /** Wie [candles], dazu ob eine Anfrage vorübergehend scheiterte ([Load.unreachable], Tagesschlüsse). */
    suspend fun load(pair: CandlePair, interval: CandleInterval, limit: Int): Load {
        if (interval !in ExchangeCandleRegistry.INTERVALS) return Load(null)
        val source = ExchangeCandleRegistry.sourceFor(pair) ?: return Load(null)
        val n = limit.coerceIn(2, MAX_LIMIT)
        val key = "${pair.cacheKey}|${interval.name}|$n"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (entry.load.value != null) CACHE_MILLIS else FAILURE_MILLIS
            if (now - entry.time in 0 until ttl) return entry.load
        }
        val result = withContext(ioDispatcher) { permits.withPermit { fetch(source, pair, interval, n) } }
        cache[key] = Entry(System.currentTimeMillis(), result)
        return result
    }

    /** Aktualisieren von Hand: nichts aus dem Speicher. */
    fun invalidate(pair: CandlePair) {
        val prefix = pair.cacheKey + "|"
        cache.removeKeysIf { it.startsWith(prefix) }
    }

    /** Nach unten ziehen in der Merkliste: alles neu laden. */
    fun invalidateAll() = cache.clear()

    private suspend fun fetch(
        source: ExchangeCandleSource, pair: CandlePair, interval: CandleInterval, limit: Int,
    ): Load {
        // Gescheitert mit Fehler-Antwort bzw. Pause ([answered]) oder ganz ohne Antwort ([silent])
        var answered = false
        var silent = false
        val fail: (Boolean) -> Unit = { noAnswer ->
            if (noAnswer) silent = true
            if (!noAnswer) answered = true
        }
        val http = object : CandleHttp {
            override suspend fun get(url: String): String? = get(source, url, null, fail)
            override suspend fun post(url: String, json: String): String? = get(source, url, json, fail)
        }
        val unreachable = { answered || silent }
        val candles = try {
            source.fetch(pair, interval, limit, http)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Eigene Kerzen nicht lesbar: %s", pair.cacheKey)
            null
        }
        val live = candles?.takeIf { it.size >= 2 && CandleSeries.isLive(it, interval.millis, System.currentTimeMillis()) }
            ?: return Load(null, unreachable(), silent && !answered)
        return Load(Sourced(live, OwnCandlePolicy.provider(pair)), unreachable(), silent && !answered)
    }

    /**
     * Eine Anfrage (GET, mit [json] POST) mit eigener Zeitgrenze; null bei Fehler, Sperre oder Pause. [unreachable]: der
     * Fehler war vorübergehend (Netz, Zeitgrenze, 5xx, Pause) — nicht «keine Daten» oder Geo-Sperre; true = gar keine Antwort.
     */
    private suspend fun get(source: ExchangeCandleSource, url: String, json: String?, unreachable: (Boolean) -> Unit): String? {
        val host = hostOf(url)
        if (BlockedSources.groupPausedUntil(host) != null || CandleHostPauses.isPaused(host)) {
            unreachable(false)
            return null
        }
        if (BlockedSources.isBlocked(host)) return null
        val post = json?.let { PostRequest(it, mapOf("Content-Type" to "application/json")) }
        val body = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callExchange(url, post) }
                .also { if (it == null) unreachable(true) }
        } catch (e: Exception) {
            // Echten Abbruch des Aufrufers nicht verschlucken
            currentCoroutineContext().ensureActive()
            val code = (e as? HttpExchangeError)?.httpCode
            when (HistoricCloseChain.fetchFailure(code)) {
                HistoricCloseChain.FetchFailure.TRANSIENT -> unreachable(false)
                HistoricCloseChain.FetchFailure.UNREACHABLE -> unreachable(true)
                else -> Unit
            }
            if (!BlockedSources.noteFailure(host, e)) {
                if (e is HttpExchangeError && ExchangeBackoff.isRateLimitCode(e.httpCode)) {
                    CandleHostPauses.pause(host, e.httpCode, e.retryAfterSeconds)
                } else {
                    Timber.d(e, "Eigene Kerzen nicht verfügbar: %s", url)
                }
            }
            null
        }
        currentCoroutineContext().ensureActive()
        if (body != null && source.isRateLimited(body)) {
            if (ExchangeBackoff.sharedGroup(host) == null) CandleHostPauses.pause(host, 429, null)
            unreachable(false)
            return null
        }
        return body
    }

    companion object {
        private const val MAX_LIMIT = 1000
        private const val MAX_PARALLEL = 4
        private const val REQUEST_TIMEOUT_MILLIS = 8_000L
        private const val CACHE_MILLIS = 60_000L
        private const val FAILURE_MILLIS = 2 * 60_000L

        /** Hostname einer Adresse («https://api.kraken.com/0/…» → «api.kraken.com»). */
        fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    }
}
