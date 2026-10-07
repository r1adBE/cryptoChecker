package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.domain.alarm.NearExtreme
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hoch und Tief der letzten 30 / 90 / 365 Tage je Paar für den Alarm «Nahe am Hoch/Tief».
 *
 * @property currency Währung der Werte: die Quote des Paars, oder «USDT», wenn nur
 *   das USDT-Paar Kerzen hat (der Aufrufer rechnet dann um)
 * @property ranges Zeitraum in Tagen → Hoch/Tief; fehlt, wenn die Reihe zu kurz ist
 */
data class WindowRanges(
    val currency: String,
    val ranges: Map<Int, NearExtreme.Range>,
    val savedAt: Long,
)

/**
 * Tageskerzen über die bestehende Ausweich-Kette [CandleDataSource] (Binance-Spiegel,
 * Binance Spot/Futures, Binance.US, Coinbase): zuerst <BASE><QUOTE>, sonst <BASE>USDT.
 * Eine Anfrage je Paar (bis 370 Tage) liefert alle drei Zeiträume. Zwischenspeicher
 * 6 h im Speicher und auf dem Gerät ([CycleCacheStore], Datei je Paar) — auch über
 * Neustarts des Hintergrund-Jobs hinweg. Nur öffentliche Kursdaten, keine neuen Hosts.
 */
@Singleton
class NearExtremeDataSource @Inject constructor(
    private val candleDataSource: CandleDataSource,
    private val cacheStore: CycleCacheStore,
) {
    private val memory = ConcurrentHashMap<String, WindowRanges>()

    /** Paare ohne Kerzen (oder Netzfehler): eine Stunde nicht erneut fragen (nur im Speicher). */
    private val missing = ConcurrentHashMap<String, Long>()

    /** null, wenn keine Quelle das Paar (oder <BASE>USDT) führt. */
    suspend fun ranges(baseAsset: String, quoteAsset: String): WindowRanges? {
        val base = baseAsset.trim().uppercase()
        val quote = quoteAsset.trim().uppercase()
        if (base.isEmpty() || quote.isEmpty()) return null
        val key = cacheName(base, quote)
        val now = System.currentTimeMillis()

        memory[key]?.takeIf { fresh(it.savedAt, now) }?.let { return it }
        cacheStore.read(key, CODEC)?.value?.takeIf { fresh(it.savedAt, now) }?.let {
            memory[key] = it
            return it
        }

        missing[key]?.let { if (now - it in 0 until MISSING_MILLIS) return null }

        val loaded = fetch(base, quote, now)
        if (loaded == null) {
            missing[key] = now
            return null
        }
        missing.remove(key)
        memory[key] = loaded
        cacheStore.write(key, loaded, loaded.savedAt, CODEC)
        return loaded
    }

    private suspend fun fetch(base: String, quote: String, now: Long): WindowRanges? {
        val own = candleDataSource.candles(base, quote, CandleInterval.D1, NearExtreme.CANDLE_LIMIT)
        val (currency, candles) = when {
            own != null -> quote to own
            quote == "USDT" || quote == "USD" -> return null
            else -> "USDT" to (candleDataSource.candles(base, "USDT", CandleInterval.D1, NearExtreme.CANDLE_LIMIT) ?: return null)
        }
        val ranges = NearExtreme.WINDOWS.mapNotNull { days -> NearExtreme.range(candles, days, now)?.let { days to it } }.toMap()
        if (ranges.isEmpty()) return null
        return WindowRanges(currency, ranges, now)
    }

    private fun fresh(savedAt: Long, now: Long): Boolean = now - savedAt in 0 until NearExtreme.CACHE_MILLIS

    private companion object {
        const val MISSING_MILLIS = 60 * 60_000L

        fun cacheName(base: String, quote: String) =
            "nearext_" + base.filter { it.isLetterOrDigit() } + "_" + quote.filter { it.isLetterOrDigit() }

        val CODEC = CacheCodec<WindowRanges>(
            encode = { w ->
                val ranges = JSONObject()
                w.ranges.forEach { (days, r) -> ranges.put(days.toString(), JSONObject().put("h", r.high).put("l", r.low)) }
                JSONObject().put("currency", w.currency).put("at", w.savedAt).put("ranges", ranges)
            },
            decode = { o ->
                val ranges = o.getJSONObject("ranges")
                val map = ranges.keys().asSequence().mapNotNull { key ->
                    val days = key.toIntOrNull() ?: return@mapNotNull null
                    val r = ranges.getJSONObject(key)
                    NearExtreme.Range(r.getDouble("h"), r.getDouble("l")).takeIf { it.isValid }?.let { days to it }
                }.toMap()
                require(map.isNotEmpty()) { "keine Zeiträume" }
                WindowRanges(o.getString("currency"), map, o.getLong("at"))
            },
        )
    }
}
