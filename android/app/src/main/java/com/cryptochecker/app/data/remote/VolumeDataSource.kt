package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.HourCandle
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
 * Aktivität» — Binance (Spot, sonst USDⓈ-M-Futures), bei Sperre oder Fehlen Binance.US
 * bzw. Coinbase (siehe [CandleDataSource]). Gleiches Paar, auch wenn es an einer anderen
 * Börse hängt; Paare, die keine Quelle führt, liefern null.
 */
@Singleton
class VolumeDataSource @Inject constructor(
    private val candleDataSource: CandleDataSource,
) {
    /** Kerzen je Symbol, kurz zwischengespeichert (auch «nicht gefunden»). */
    private val cache = ConcurrentHashMap<String, Pair<Long, List<HourCandle>?>>()

    suspend fun hourlySpike(baseAsset: String, quoteAsset: String): VolumeSpike? =
        hourlyCandles(baseAsset, quoteAsset)?.let { spikeOf(it) }

    /**
     * Die letzten [LIMIT] Stundenkerzen, zeitlich aufsteigend; die letzte läuft noch.
     * null, wenn keine Quelle der Ausweich-Kette das Paar führt.
     */
    suspend fun hourlyCandles(baseAsset: String, quoteAsset: String): List<HourCandle>? {
        val symbol = symbol(baseAsset, quoteAsset) ?: return null
        val now = System.currentTimeMillis()
        cache[symbol]?.let { (time, value) ->
            if (now - time in 0 until CACHE_MILLIS) return value
        }

        val result = fetch(baseAsset, quoteAsset)
        cache[symbol] = System.currentTimeMillis() to result
        return result
    }

    /** Über die Ausweich-Kette (Binance, Binance.US, Coinbase), siehe [CandleDataSource]. */
    private suspend fun fetch(baseAsset: String, quoteAsset: String): List<HourCandle>? =
        candleDataSource.candles(baseAsset, quoteAsset, CandleInterval.H1, LIMIT)

    companion object {
        /** Genug für 24 Vergleichsrenditen + letzte abgeschlossene + laufende Stunde, mit Reserve. */
        const val LIMIT = 30

        /** 24 Vergleichsstunden + letzte abgeschlossene + laufende Stunde. */
        private const val MIN_SPIKE_CANDLES = 26
        private const val CACHE_MILLIS = 5 * 60_000L

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
