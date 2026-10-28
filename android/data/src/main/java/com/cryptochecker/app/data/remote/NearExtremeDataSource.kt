package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.data.BoundedCache
import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.domain.alarm.NearExtreme
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tageskerzen über die bestehende Ausweich-Kette [CandleDataSource] (Binance-Spiegel,
 * Binance Spot/Futures, Binance.US, Coinbase): zuerst <BASE><QUOTE>, sonst <BASE>USDT.
 * Eine Anfrage je Paar (bis 370 Tage) liefert alle drei Zeiträume. Zwischenspeicher
 * 6 h im Speicher und auf dem Gerät ([CycleCacheStore], Datei je Paar) — auch über
 * Neustarts des Hintergrund-Jobs hinweg. Nur öffentliche Kursdaten, keine neuen Hosts.
 * Die Reihe stammt nie von der Börse des Paars: Der Aufrufer prüft Name und Kursband
 * ([NearExtreme.seriesAllowed], wie der Mini-Chart) und lässt den Alarm sonst ungeprüft.
 */
@Singleton
class NearExtremeDataSource @Inject constructor(
    private val candleDataSource: CandleDataSource,
    private val cacheStore: CycleCacheStore,
    /** Namensprüfung der Ausweich-Reihe wie beim Mini-Chart ([seriesAllowed]). */
    private val tokenIdentityStore: TokenIdentityStore,
) {
    /** Begrenzt (sonst blieb jedes je geprüfte Paar bis zum Prozessende). */
    private val memory = BoundedCache<String, WindowRanges>(MEMORY_CACHE_ENTRIES)

    /** Paare ohne Kerzen (oder Netzfehler): eine Stunde nicht erneut fragen (nur im Speicher). */
    private val missing = BoundedCache<String, Long>(MEMORY_CACHE_ENTRIES)

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

    /**
     * Gilt die Reihe von [ranges] für das Paar ([exchangeKey], [baseAsset]) mit Kurs [price]? Ohne Namens-Widerspruch
     * ([TokenIdentityStore.fallbackSeriesAllowed]) und im Kursband ([NearExtreme.seriesAllowed]); [quoteToSeries] =
     * Faktor Quote → [WindowRanges.currency] (1 bei gleicher Währung). Sonst kein Hoch/Tief-Alarm.
     */
    fun seriesAllowed(ranges: WindowRanges, exchangeKey: String, baseAsset: String, price: Double?, quoteToSeries: Double?): Boolean =
        NearExtreme.seriesAllowed(
            nameAllowed = tokenIdentityStore.fallbackSeriesAllowed(exchangeKey, baseAsset, ranges.provider),
            price = price,
            lastClose = ranges.lastClose,
            quoteToSeries = quoteToSeries,
        )

    private suspend fun fetch(base: String, quote: String, now: Long): WindowRanges? {
        val own = candleDataSource.candlesSourced(base, quote, CandleInterval.D1, NearExtreme.CANDLE_LIMIT)
        val (currency, sourced) = when {
            own != null -> quote to own
            quote == "USDT" || quote == "USD" -> return null
            else -> "USDT" to (candleDataSource.candlesSourced(base, "USDT", CandleInterval.D1, NearExtreme.CANDLE_LIMIT) ?: return null)
        }
        val candles = sourced.value
        val ranges = NearExtreme.WINDOWS.mapNotNull { days -> NearExtreme.range(candles, days, now)?.let { days to it } }.toMap()
        if (ranges.isEmpty()) return null
        val lastClose = NearExtreme.lastClose(candles) ?: return null
        return WindowRanges(currency, ranges, now, lastClose, sourced.provider)
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
                JSONObject().put("currency", w.currency).put("at", w.savedAt).put("ranges", ranges).put("last", w.lastClose)
                    .apply { w.provider?.let { put("provider", it) } }
            },
            decode = { o ->
                val ranges = o.getJSONObject("ranges")
                val map = ranges.keys().asSequence().mapNotNull { key ->
                    val days = key.toIntOrNull() ?: return@mapNotNull null
                    val r = ranges.getJSONObject(key)
                    NearExtreme.Range(r.getDouble("h"), r.getDouble("l")).takeIf { it.isValid }?.let { days to it }
                }.toMap()
                require(map.isNotEmpty()) { "keine Zeiträume" }
                // Ältere Einträge ohne letzten Schluss: neu laden (Kursband-Prüfung braucht ihn)
                val last = o.optDouble("last", Double.NaN).takeIf { it.isFinite() && it > 0.0 } ?: error("ohne letzten Schluss")
                WindowRanges(o.getString("currency"), map, o.getLong("at"), last, o.optString("provider").ifBlank { null })
            },
        )
    }
}
