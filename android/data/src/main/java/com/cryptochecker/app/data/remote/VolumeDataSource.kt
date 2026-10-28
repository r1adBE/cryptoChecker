package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.BoundedCache
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.CandlePairs
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.domain.activity.CandleSeries
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.market.Sourced
import com.cryptochecker.app.domain.watch.DayChange
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

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
    /**
     * Kerzen je Symbol (und Anzahl), kurz zwischengespeichert (auch «nicht gefunden»), mit Anbieter.
     * Begrenzt ([BoundedCache]): abgelaufene Einträge wurden nie entfernt, jedes je geprüfte Paar blieb im Speicher.
     */
    private val cache = BoundedCache<String, Pair<Long, Sourced<List<HourCandle>>?>>(MAX_CACHE_ENTRIES)

    /** Volumen-Spike eines Paars der Merkliste (Volumen-Alarm, Schnell-Alarme); [price] für die Prüfung auf gleichen Token. */
    suspend fun hourlySpike(watch: WatchEntity, price: Double? = watch.lastPrice): VolumeSpike? {
        val sourced = pairCandles(candlePairs.of(watch), price, CandleInterval.H1, LIMIT) ?: return null
        return spikeOf(sourced.value)?.withSource(watch, sourced.provider)
    }

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
            // Name des Coins an der Börse des Paars (gleicher Token) — wie der Chart
            identityName = tokenIdentityStore.pairName(pair.exchangeKey, pair.base),
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

        private const val CACHE_MILLIS = 5 * 60_000L

        /** Höchstens so viele zwischengespeicherte Reihen (Paare × Intervall × Anzahl); ältere fallen heraus. */
        const val MAX_CACHE_ENTRIES = 512

        /**
         * Kerzen eines Paars wie der Chart: 1. eigene Börse ([ownSupported], [own]); 2. nur wenn die
         * Ausweich-Kette erlaubt ist ([OwnCandlePolicy.allowsFallback]: nicht TradFi, DEX, Laufzeit-
         * Kontrakt) und der Kurs [price] bekannt ist, die Kette [chain] — jede Reihe nur bei gleichem
         * Token ([OwnCandlePolicy.acceptsFallback]), sonst die nächste Quelle der Kette. [identityName] (Name
         * des Coins an der Börse des Paars) geht wie beim Chart an die Kette: Binance-Quellen nur ohne klaren
         * Namens-Widerspruch ([com.cryptochecker.app.domain.watch.TokenIdentity.compatible]).
         */
        suspend fun pairCandlesOf(
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
         * Bewertet wird die letzte ABGESCHLOSSENE Stunde gegen die 24 davor — nach Uhrzeit, nicht nach
         * Position: Kerzen werden je Stunde (`openTime / 1 h`) zusammengefasst, fehlende Stunden (Börsen
         * lassen Stunden ohne Handel aus) zählen mit Volumen 0. Vorher war bei Lücken «die vorletzte Kerze»
         * womöglich Stunden alt und der Schnitt über mehr als 24 Stunden gerechnet.
         *
         * Laufende Stunde = die spätere aus Uhr ([now]) und neuester Kerze (Uhr des Geräts etwas nach).
         * null, wenn die Reihe nicht mehr läuft ([CandleSeries.isLive]) oder nicht alle 24 Vergleichsstunden
         * abdeckt (sonst zählten unbekannte Stunden als 0 und blähten den Spike auf).
         */
        fun spikeOf(candles: List<HourCandle>, now: Long = System.currentTimeMillis()): VolumeSpike? {
            if (candles.size < 2 || !CandleSeries.isLive(candles, HOUR_MILLIS, now)) return null
            val volumes = HashMap<Long, Double>()
            for (candle in candles) {
                if (!candle.volume.isFinite() || candle.volume < 0.0) continue
                val hour = Math.floorDiv(candle.openTime, HOUR_MILLIS)
                volumes[hour] = (volumes[hour] ?: 0.0) + candle.volume
            }
            if (volumes.isEmpty()) return null
            val running = maxOf(Math.floorDiv(now, HOUR_MILLIS), volumes.keys.max())
            val evaluated = running - 1
            // Die Reihe muss bis an den Anfang der 24 Vergleichsstunden zurückreichen
            if (volumes.keys.min() > evaluated - COMPARE_HOURS) return null
            val average = (evaluated - COMPARE_HOURS until evaluated).sumOf { volumes[it] ?: 0.0 } / COMPARE_HOURS
            if (average <= 0.0) return null
            return VolumeSpike(
                ratio = (volumes[evaluated] ?: 0.0) / average,
                candleOpenTime = evaluated * HOUR_MILLIS,
            )
        }

        private const val HOUR_MILLIS = 3_600_000L

        /** Börse der Kerzen [provider] am Spike, wenn sie nicht die des Paars ist ([DayChange.foreignCandleSource]). */
        fun VolumeSpike.withSource(watch: WatchEntity, provider: String?): VolumeSpike =
            copy(source = DayChange.foreignCandleSource(listOf(watch.exchangeKey, watch.exchangeName), provider))

        /** Vergleichsstunden vor der bewerteten. */
        private const val COMPARE_HOURS = 24

        /** Wie früher: Spike direkt aus der Binance-Antwort. */
        fun parse(json: String, now: Long = System.currentTimeMillis()): VolumeSpike? = spikeOf(parseCandles(json), now)
    }
}
