package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.DayReference
import com.cryptochecker.app.domain.watch.DayReferenceCache
import com.cryptochecker.app.data.BoundedCache
import com.cryptochecker.app.data.KeyedMutex
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reine Regeln für die Stundenkerzen der eigenen Börse (Mini-Chart und 24-h-Bezug), wie
 * [com.cryptochecker.app.data.SparklineRepository] für die USDT-Reihe. Wie `OwnHourly` (iOS).
 */
object OwnHourly {

    /** Schlusskurse (die jüngsten 24), 24-h-Bezug, Eröffnung je Stunde, Anbieter, letzter Schluss. */
    data class Series(
        val closes: List<Double>,
        val day: DayReference?,
        val opens: Map<Long, Double>,
        val provider: String?,
        /** Letzter Schluss (auch ohne 24-h-Bezug, für den Tages-Bezug). */
        val lastClose: Double,
    )

    /** Gültigkeit des Mini-Charts (wie die USDT-Reihe). */
    const val TTL_MILLIS = 15 * 60_000L
    const val FAILURE_TTL_MILLIS = 5 * 60_000L

    /** So lange nach Beginn einer Stunde darf ihre Kerze bei der Quelle noch fehlen. */
    const val NEW_CANDLE_GRACE_MILLIS = 10 * 60_000L

    /**
     * Reihe aus aufsteigenden Stundenkerzen, abgerufen um [now]; null mit weniger als zwei gültigen.
     * Mini-Chart aus den jüngsten 24 Kerzen, 24-h-Bezug nach [rollingReference].
     */
    fun seriesOf(candles: List<HourCandle>?, provider: String?, now: Long): Series? {
        val valid = candles.orEmpty().filter { it.close.isFinite() && it.close > 0.0 }
        val recent = valid.takeLast(ChangeBasisMath.ROLLING_CANDLES)
        if (recent.size < 2) return null
        return Series(
            closes = recent.map { it.close },
            day = rollingReference(valid, now),
            opens = valid.associate { it.openTime to it.open },
            provider = provider,
            lastClose = valid.last().close,
        )
    }

    /**
     * Rollender 24-h-Bezug aus gültigen, aufsteigenden Stundenkerzen zum Zeitpunkt [now]. Manche
     * Börsen (Upbit u. a.) liefern nur Stunden mit Handel — die jüngsten 24 Kerzen können dann
     * Tage umfassen. Darum zählt die Zeit, nicht die Anzahl: Fenster = Kerzen ab [now] − 24 h.
     * Eröffnung = Schluss der letzten Kerze VOR dem Fenster (letzter Kurs vor 24 h); fehlt sie,
     * die Eröffnung der ersten Kerze im Fenster, nur wenn sie höchstens eine Stunde nach
     * Fensterbeginn startet. Sonst null (dann der übliche Ausweichweg).
     */
    fun rollingReference(valid: List<HourCandle>, now: Long): DayReference? {
        val last = valid.lastOrNull() ?: return null
        val windowStart = now - ChangeBasisMath.DAY_MILLIS
        val before = valid.lastOrNull { it.openTime < windowStart }
        if (before != null) return DayReference.of(before.close, last.close)
        val first = valid.first()
        if (first.openTime >= windowStart + ChangeBasisMath.HOUR_MILLIS) return null
        return DayReference.of(first.open, last.close)
    }

    /**
     * Gilt ein Eintrag (Abruf [time], [series] oder Fehlschlag) noch? Erfolg [ttl], Fehlschlag
     * [FAILURE_TTL_MILLIS]. [needsHour]: Kerze ab dieser Startzeit muss enthalten sein — ein Abruf
     * von vor (oder kurz nach) Beginn dieser Stunde ohne sie gilt nicht als frisch.
     */
    fun isFresh(time: Long, series: Series?, ttl: Long, now: Long, needsHour: Long? = null): Boolean {
        val age = now - time
        val limit = if (series != null) ttl else FAILURE_TTL_MILLIS
        if (needsHour != null && series != null && needsHour !in series.opens && time < needsHour + NEW_CANDLE_GRACE_MILLIS) return false
        return age in 0 until limit
    }
}

/**
 * Stundenkerzen der EIGENEN Börse je Paar ([CandlePair.cacheKey]) für den Mini-Chart der Merkliste
 * und den 24-h- bzw. Tages-Bezug der %-Änderung — ein Abruf für beides (26 × 1 h). Nur im
 * Speicher; gleichzeitige Anfragen für dasselbe Paar warten aufeinander. Wie `OwnHourlyStore` (iOS).
 */
@Singleton
class OwnHourlyCandles @Inject constructor(
    private val ownCandles: OwnCandleDataSource,
) {
    private class Entry(val time: Long, val series: OwnHourly.Series?)

    // Begrenzt (zuletzt benutzte Paare); Sperren nur, solange ein Abruf läuft — wächst nicht mit jedem je
    // abgefragten Paar
    private val cache = BoundedCache<String, Entry>(MAX_ENTRIES)
    private val locks = KeyedMutex<String>()

    fun supports(pair: CandlePair): Boolean = ownCandles.supports(pair, CandleInterval.H1)

    /** Geladene Schlusskurse (auch etwas ältere) — für den ersten Frame; null = nichts geladen. */
    fun cachedCloses(pair: CandlePair): List<Double>? = cache[pair.cacheKey]?.series?.closes

    /** Frische Schlusskurse (die jüngsten 24) oder null. */
    suspend fun closes(pair: CandlePair): List<Double>? = series(pair, OwnHourly.TTL_MILLIS)?.closes

    /** 24-h-Bezug; ein höchstens 60 Minuten alter Abruf gilt ohne Netz. */
    suspend fun dayReference(pair: CandlePair): DayReference? =
        series(pair, DayReferenceCache.FRESH_MILLIS)?.day

    /** Bezug seit Tagesbeginn [dayStart] (Eröffnung der Kerze, in der er liegt, und letzter Schluss). */
    suspend fun dayStartReference(pair: CandlePair, dayStart: Long): DayReference? {
        val loaded = series(pair, DayReferenceCache.FRESH_MILLIS, ChangeBasisMath.hourOf(dayStart)) ?: return null
        return ChangeBasisMath.reference(loaded.opens, loaded.lastClose, dayStart)
    }

    /** Gemerkter 24-h-Bezug, Abruf höchstens [DayReferenceCache.MAX_AGE_MILLIS] alt; sonst null. */
    fun cachedDayReference(pair: CandlePair): DayReference? = usable(pair)?.day

    /** Gemerkter Bezug seit [dayStart], Abruf höchstens [DayReferenceCache.MAX_AGE_MILLIS] alt. */
    fun cachedDayStartReference(pair: CandlePair, dayStart: Long): DayReference? =
        usable(pair)?.let { ChangeBasisMath.reference(it.opens, it.lastClose, dayStart) }

    /** Aktualisieren von Hand: alles neu laden. */
    fun invalidateAll() = cache.clear()

    private fun usable(pair: CandlePair): OwnHourly.Series? {
        val entry = cache[pair.cacheKey] ?: return null
        return entry.series?.takeIf { DayReferenceCache.usable(entry.time, System.currentTimeMillis()) }
    }

    private suspend fun series(pair: CandlePair, ttl: Long, needsHour: Long? = null): OwnHourly.Series? {
        if (!supports(pair)) return null
        val key = pair.cacheKey
        fresh(key, ttl, needsHour)?.let { return it.series }
        return locks.withLock(key) {
            fresh(key, ttl, needsHour)?.let { return@withLock it.series }
            val series = try {
                val sourced = ownCandles.candles(pair, CandleInterval.H1, ChangeBasisMath.CANDLES)
                OwnHourly.seriesOf(sourced?.value, sourced?.provider, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val previous = cache[key]
            // Fehlschlag: ein noch benutzbarer Erfolg bleibt stehen (Bezug bis MAX_AGE_MILLIS)
            if (series != null || previous?.series == null ||
                !DayReferenceCache.usable(previous.time, System.currentTimeMillis())
            ) {
                cache[key] = Entry(System.currentTimeMillis(), series)
            }
            series
        }
    }

    private fun fresh(key: String, ttl: Long, needsHour: Long?): Entry? {
        val entry = cache[key] ?: return null
        return entry.takeIf { OwnHourly.isFresh(it.time, it.series, ttl, System.currentTimeMillis(), needsHour) }
    }

    companion object {
        /** Höchstens so viele Paare im Speicher (mehr als jede übliche Merkliste). */
        const val MAX_ENTRIES = 512
    }
}
