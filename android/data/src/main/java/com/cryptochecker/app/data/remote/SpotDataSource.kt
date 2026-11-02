package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.BroadMarket
import com.cryptochecker.app.domain.activity.BroadMarketSnapshot
import com.cryptochecker.app.domain.spot.SpotData
import com.cryptochecker.app.domain.spot.SpotMarket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spot-Kennzahlen (Marktkapitalisierung, Volumen, Umlauf …) für den Spot-Tab der Karte «Marktdaten».
 * Zuerst aus der CoinGecko-Top-40 (direkt bei CoinGecko, für alle Nutzer gleich, verrät keinen
 * Coin); steht der Coin nicht darin, EINE Anfrage an CoinGecko `/coins/markets?symbols=<kürzel>`.
 * Antworten (auch «nichts gefunden») ~10 min im Speicher; welcher Treffer zum Paar gehört, entscheidet je Aufruf
 * der Name ([SpotMarket.pick] mit [SpotMarket.Identity]). Gleich in iOS `SpotDataSource`.
 */
@Singleton
class SpotDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private data class Cached(val at: Long, val entries: List<SpotData>)

    private val cache = HashMap<String, Cached>()
    private var topList: Pair<Long, List<SpotData>>? = null
    private var topText: Pair<Long, String?>? = null

    /**
     * Daten zum Kürzel aus [SpotMarket.lookupSymbol] für das Paar mit [identity]; null = keine passenden (oder nicht
     * erreichbar). Passt in der Top-40 kein Treffer, fragt es einmal nach dem Kürzel.
     */
    suspend fun fetch(symbol: String, identity: SpotMarket.Identity = SpotMarket.Identity()): SpotData? {
        val now = System.currentTimeMillis()
        SpotMarket.pick(topEntries(now), symbol, identity)?.let { return it }
        synchronized(cache) { cache[symbol] }?.takeIf { now - it.at in 0 until SpotMarket.CACHE_TTL_MILLIS }
            ?.let { return SpotMarket.pick(it.entries, symbol, identity) }
        val entries = try {
            SpotMarket.parse(httpClient.callExchange(SpotMarket.url(symbol), null))
        } catch (e: Exception) {
            // Blatt geschlossen: Abbruch weiterreichen; sonst nichts merken (nächstes Öffnen versucht es neu)
            currentCoroutineContext().ensureActive()
            Timber.d(e, "Spot-Daten nicht verfügbar: %s", symbol)
            return null
        }
        synchronized(cache) { cache[symbol] = Cached(now, entries) }
        return SpotMarket.pick(entries, symbol, identity)
    }

    /** Top 40 (gleiche Abfrage für alle; fehlt sie, gibt es die Einzelabfrage). */
    private suspend fun topEntries(now: Long): List<SpotData> {
        synchronized(cache) { topList }?.takeIf { now - it.first in 0 until SpotMarket.CACHE_TTL_MILLIS }?.let { return it.second }
        val entries = SpotMarket.parse(topBody(now))
        synchronized(cache) { topList = now to entries }
        return entries
    }

    /**
     * Breiter Markt für «Warum?» ([BroadMarket]): Median der 24-h-Veränderung der Top-Coins aus
     * derselben Top-40 (ohne [excludeBase]); null, wenn sie fehlt, zu alt ist oder zu wenige
     * Coins hat. Nichts Coin-Bezogenes wird gesendet.
     */
    suspend fun broadMarket(excludeBase: String): BroadMarketSnapshot? {
        val now = System.currentTimeMillis()
        return BroadMarket.snapshot(BroadMarket.parse(topBody(now)), excludeBase, now)
    }

    /**
     * Antwort der Top-40 von CoinGecko ([SpotMarket.TOP_URL]); ~10 Min. im Speicher, auch «fehlt»
     * (so auch Pause nach Fehler/429). null = keine.
     */
    private suspend fun topBody(now: Long): String? {
        synchronized(cache) { topText }?.takeIf { now - it.first in 0 until SpotMarket.CACHE_TTL_MILLIS }?.let { return it.second }
        val body = try {
            httpClient.callExchange(SpotMarket.TOP_URL, null).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.d(e, "Rangliste von CoinGecko nicht verfügbar")
            null
        }
        synchronized(cache) { topText = now to body }
        return body
    }
}
