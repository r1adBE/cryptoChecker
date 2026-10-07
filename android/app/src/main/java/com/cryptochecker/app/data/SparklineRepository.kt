package com.cryptochecker.app.data

import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.watch.DayReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mini-Charts der Merkliste: 24 Stundenkurse (Schlusskurse) je Basis-Asset
 * gegen USDT. Gezeigt wird nur die Form, deshalb reicht die USDT-Reihe für
 * jede Quote-Währung. Gleiche Quelle wie das Einzel-Widget ([CandleDataSource]
 * mit Ausweich-Kette).
 *
 * Dieselbe Abfrage liefert den 24-h-Bezug der Prozent-Pille ([dayReference], erste
 * Eröffnung und letzter Schluss); für andere Quotes als USDT (z. B. BTC/EUR) mit
 * eigener Reihe des Paars.
 *
 * Nur im Speicher, 15 Minuten gültig; höchstens [MAX_PARALLEL] Abrufe
 * gleichzeitig. Fehlschläge werden kürzer gemerkt, damit ein Paar ohne
 * Kerzen nicht bei jedem Scrollen neu abgefragt wird.
 */
@Singleton
class SparklineRepository @Inject constructor(
    private val candleDataSource: CandleDataSource,
) {
    /** Stundenkerzen eines Paars: Schlusskurse (Mini-Chart) und 24-h-Bezug (Pille). */
    private class Series(val closes: List<Double>, val day: DayReference?)

    private class Entry(val time: Long, val series: Series?)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val permits = Semaphore(MAX_PARALLEL)

    /** Zwischengespeicherte Reihe, auch wenn sie schon etwas alt ist — für den ersten Frame. */
    fun cached(base: String): List<Double>? = cache[key(base, QUOTE)]?.series?.closes

    /**
     * Frische Reihe (mindestens zwei Werte) oder null. Liegt eine gültige im
     * Speicher, ohne Netz; gleichzeitige Anfragen für dasselbe Asset warten
     * aufeinander statt doppelt zu laden.
     */
    suspend fun closes(base: String): List<Double>? = series(base, QUOTE)?.closes

    /**
     * 24-h-Bezug (Eröffnung vor 24 h, letzter Schluss) für [base] gegen [quote] —
     * gleiche Abfrage wie der Mini-Chart, wenn [quote] USDT ist (siehe `DayChange.candleQuote`).
     * null, wenn keine Quelle das Paar liefert.
     */
    suspend fun dayReference(base: String, quote: String): DayReference? = series(base, quote)?.day

    /** Zwischengespeicherter 24-h-Bezug, auch wenn er schon etwas alt ist; null ohne Eintrag. */
    fun cachedDayReference(base: String, quote: String): DayReference? = cache[key(base, quote)]?.series?.day

    private suspend fun series(base: String, quote: String): Series? {
        val k = key(base, quote)
        if (k.isEmpty()) return null
        fresh(k)?.let { return it.series }
        val lock = locks.getOrPut(k) { Mutex() }
        return lock.withLock {
            fresh(k)?.let { return@withLock it.series }
            val series = try {
                permits.withPermit {
                    candleDataSource.candles(base.trim().uppercase(), quote.trim().uppercase(), CandleInterval.H1, POINTS)
                        ?.filter { it.close.isFinite() && it.close > 0.0 }
                        ?.takeIf { it.size >= 2 }
                        ?.let { candles ->
                            Series(
                                closes = candles.map { it.close },
                                day = DayReference.of(candles.first().open, candles.last().close),
                            )
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            cache[k] = Entry(System.currentTimeMillis(), series)
            series
        }
    }

    private fun fresh(k: String): Entry? {
        val entry = cache[k] ?: return null
        val age = System.currentTimeMillis() - entry.time
        val ttl = if (entry.series != null) TTL_MILLIS else FAILURE_TTL_MILLIS
        return entry.takeIf { age in 0 until ttl }
    }

    private fun key(base: String, quote: String): String {
        val b = base.trim().uppercase()
        val q = quote.trim().uppercase()
        if (b.isEmpty() || q.isEmpty()) return ""
        return "$b|$q"
    }

    companion object {
        const val TTL_MILLIS = 15 * 60_000L
        private const val FAILURE_TTL_MILLIS = 5 * 60_000L
        private const val MAX_PARALLEL = 4
        private const val POINTS = 24
        private const val QUOTE = "USDT"
    }
}
