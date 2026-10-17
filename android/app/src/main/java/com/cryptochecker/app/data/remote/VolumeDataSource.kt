package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.CandlePairs
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.market.Sourced
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volumen der letzten abgeschlossenen Stunde im Vergleich zum Schnitt der
 * 24 Stunden davor.
 *
 * @property ratio z. B. 4.2 = «4,2-mal so viel wie üblich»
 * @property candleOpenTime Startzeit der bewerteten Stundenkerze (ms, UTC)
 */
data class VolumeSpike(val ratio: Double, val candleOpenTime: Long)

/**
 * Holt Stundenkerzen für den Volumen-Spike-Alarm und die Auswertung «Ungewöhnliche
 * Aktivität» bzw. «Warum?». Für ein Paar der Merkliste ([pairCandles]) wie der Chart: zuerst die
 * EIGENE Börse ([OwnCandleDataSource]), sonst die Ausweich-Kette ([CandleDataSource]: Binance,
 * Binance.US, Coinbase) nur mit Prüfung auf gleichen Token ([OwnCandlePolicy.acceptsFallback]; dazu wie der
 * Chart der Name des Coins an der Börse des Paars — Binance bei klar anderem Namen nie, [TokenIdentityStore]);
 * ohne Kurs, für TradFi, DEX und Laufzeit-Kontrakte keine Ausweich-Reihe. Markt-Referenzen
 * (BTC/USDT …) direkt aus der Kette ([hourlyCandlesSourced]).
 */
@Singleton
class VolumeDataSource @Inject constructor(
    private val candleDataSource: CandleDataSource,
    private val ownCandles: OwnCandleDataSource,
    private val candlePairs: CandlePairs,
    private val tokenIdentityStore: TokenIdentityStore,
) {
    /** Kerzen je Symbol (und Anzahl), kurz zwischengespeichert (auch «nicht gefunden»), mit Anbieter. */
    private val cache = ConcurrentHashMap<String, Pair<Long, Sourced<List<HourCandle>>?>>()

    /** Volumen-Spike eines Paars der Merkliste (Volumen-Alarm, Schnell-Alarme); [price] für die Prüfung auf gleichen Token. */
    suspend fun hourlySpike(watch: WatchEntity, price: Double? = watch.lastPrice): VolumeSpike? =
        pairCandles(candlePairs.of(watch), price, CandleInterval.H1, LIMIT)?.value?.let { spikeOf(it) }

    /**
     * Kerzen eines Paars der Merkliste ([pair], Kurs [price]) wie der Chart, siehe [pairCandlesOf];
     * kurz zwischengespeichert (auch «nicht gefunden»).
     */
    suspend fun pairCandles(pair: CandlePair, price: Double?, interval: CandleInterval, limit: Int): Sourced<List<HourCandle>>? {
        val key = "P|${pair.cacheKey}|${interval.name}|$limit"
        val now = System.currentTimeMillis()
        cache[key]?.let { (time, value) ->
            if (now - time in 0 until CACHE_MILLIS) return value
        }
        val result = pairCandlesOf(
            pair, price,
            // Name des Coins an der Börse des Paars (gleicher Token, K-8) — wie der Chart
            identityName = tokenIdentityStore.pairName(pair.marketKey, pair.base),
            ownSupported = ownCandles.supports(pair, interval),
            own = { ownCandles.candles(pair, interval, limit) },
            chain = { name, accept ->
                candleDataSource.candlesSourced(
                    pair.base, pair.quote, interval, limit, preferFutures = pair.isFutures,
                    identityName = name, accept = accept,
                )
            },
        )
        cache[key] = System.currentTimeMillis() to result
        return result
    }

    /**
     * Die letzten [LIMIT] Stundenkerzen, zeitlich aufsteigend; die letzte läuft noch.
     * null, wenn keine Quelle der Ausweich-Kette das Paar führt.
     * [futures] = Futures-Paar: Kerzen zuerst vom USDⓈ-M-Markt (fapi), nicht vom Spot.
     */
    suspend fun hourlyCandles(baseAsset: String, quoteAsset: String, futures: Boolean = false): List<HourCandle>? =
        hourlyCandlesSourced(baseAsset, quoteAsset, futures)?.value

    /**
     * Wie [hourlyCandles], mit wählbarer Anzahl [limit] (z. B. 100 für «Warum?») und dem Anbieter,
     * der geliefert hat («Binance», «Binance.US», «Coinbase», siehe [CandleDataSource.candlesSourced]).
     */
    suspend fun hourlyCandlesSourced(
        baseAsset: String,
        quoteAsset: String,
        futures: Boolean = false,
        limit: Int = LIMIT,
    ): Sourced<List<HourCandle>>? {
        val symbol = symbol(baseAsset, quoteAsset) ?: return null
        val key = (if (futures) "$symbol|F" else symbol) + "|$limit"
        val now = System.currentTimeMillis()
        cache[key]?.let { (time, value) ->
            if (now - time in 0 until CACHE_MILLIS) return value
        }

        val result = candleDataSource.candlesSourced(baseAsset, quoteAsset, CandleInterval.H1, limit, preferFutures = futures)
        cache[key] = System.currentTimeMillis() to result
        return result
    }

    companion object {
        /** Genug für 24 Vergleichsrenditen + letzte abgeschlossene + laufende Stunde, mit Reserve. */
        const val LIMIT = 30

        /** 24 Vergleichsstunden + letzte abgeschlossene + laufende Stunde. */
        private const val MIN_SPIKE_CANDLES = 26
        private const val CACHE_MILLIS = 5 * 60_000L

        /**
         * Kerzen eines Paars wie der Chart: 1. eigene Börse ([ownSupported], [own]); 2. nur wenn die
         * Ausweich-Kette erlaubt ist ([OwnCandlePolicy.allowsFallback]: nicht TradFi, DEX, Laufzeit-
         * Kontrakt) und der Kurs [price] bekannt ist, die Kette [chain] — jede Reihe nur bei gleichem
         * Token ([OwnCandlePolicy.acceptsFallback]), sonst die nächste Quelle der Kette. [identityName] (Name
         * des Coins an der Börse des Paars) geht wie beim Chart an die Kette: Binance-Quellen nur ohne klaren
         * Namens-Widerspruch ([com.cryptochecker.app.domain.watch.TokenIdentity.compatible], K-8).
         */
        internal suspend fun pairCandlesOf(
            pair: CandlePair,
            price: Double?,
            identityName: String?,
            ownSupported: Boolean,
            own: suspend () -> Sourced<List<HourCandle>>?,
            chain: suspend (identityName: String?, accept: suspend (List<HourCandle>) -> Boolean) -> Sourced<List<HourCandle>>?,
        ): Sourced<List<HourCandle>>? {
            if (ownSupported) own()?.let { return it }
            if (!OwnCandlePolicy.allowsFallback(pair)) return null
            val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return chain(identityName) { OwnCandlePolicy.acceptsFallback(it, p) }
        }

        /** Binance-Symbol, z. B. BTC + USD → BTCUSDT. */
        fun symbol(baseAsset: String, quoteAsset: String): String? {
            val base = baseAsset.trim().uppercase()
            val quote = quoteAsset.trim().uppercase().let { if (it == "USD") "USDT" else it }
            val symbol = base + quote
            return symbol.takeIf { base.isNotEmpty() && quote.isNotEmpty() && symbol.all { it.isLetterOrDigit() } }
        }

        /** Binance-Kerze: [openTime, open, high, low, close, volume, ...], älteste zuerst. */
        fun parseCandles(json: String): List<HourCandle> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val k = array.getJSONArray(i)
                HourCandle(
                    openTime = k.getLong(0),
                    open = k.getString(1).toDouble(),
                    high = k.getString(2).toDouble(),
                    low = k.getString(3).toDouble(),
                    close = k.getString(4).toDouble(),
                    volume = k.getString(5).toDouble(),
                )
            }
        }

        /**
         * Die letzte Kerze läuft noch; bewertet wird die vorletzte gegen die 24 davor.
         */
        fun spikeOf(candles: List<HourCandle>): VolumeSpike? {
            val n = candles.size
            if (n < MIN_SPIKE_CANDLES) return null
            val lastIndex = n - 2
            val average = (lastIndex - 24 until lastIndex).sumOf { candles[it].volume } / 24
            if (average <= 0.0) return null
            return VolumeSpike(
                ratio = candles[lastIndex].volume / average,
                candleOpenTime = candles[lastIndex].openTime,
            )
        }

        /** Wie früher: Spike direkt aus der Binance-Antwort. */
        fun parse(json: String): VolumeSpike? = spikeOf(parseCandles(json))
    }
}
