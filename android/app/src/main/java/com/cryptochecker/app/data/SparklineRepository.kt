package com.cryptochecker.app.data

import android.content.Context
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.watch.DayReference
import com.cryptochecker.app.domain.watch.DayReferenceCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mini-Charts der Merkliste: 24 Stundenkurse (Schlusskurse) je Basis-Asset
 * gegen USDT. Gezeigt wird nur die Form, deshalb reicht die USDT-Reihe für
 * jede Quote-Währung. Gleiche Quelle wie das Einzel-Widget ([CandleDataSource]
 * mit Ausweich-Kette).
 *
 * Dieselbe Abfrage liefert den 24-h-Bezug der Prozent-Pille ([dayReference], erste
 * Eröffnung und letzter Schluss) — nur noch als Ausweich-Weg für Paare, deren Ticker
 * keinen 24-h-Wert liefert; für andere Quotes als USDT (z. B. BTC/EUR) mit eigener Reihe.
 *
 * Getrennte Gültigkeit: Mini-Chart 15 Minuten ([TTL_MILLIS]), 24-h-Bezug 60 Minuten
 * ([DayReferenceCache.FRESH_MILLIS]). Die 24-h-Bezüge liegen zusätzlich als kleine
 * JSON-Datei im Cache-Ordner (Version, kaputte Datei = leer), damit die Pillen gleich
 * nach dem App-Start Werte haben. Höchstens [MAX_PARALLEL] Abrufe gleichzeitig.
 * Fehlschläge werden kürzer gemerkt, damit ein Paar ohne Kerzen nicht bei jedem
 * Scrollen neu abgefragt wird.
 */
@Singleton
class SparklineRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val candleDataSource: CandleDataSource,
) {
    /** Stundenkerzen eines Paars: Schlusskurse (Mini-Chart) und 24-h-Bezug (Pille). */
    private class Series(val closes: List<Double>, val day: DayReference?)

    private class Entry(val time: Long, val series: Series?)

    /** Zuletzt erfolgreich geladener 24-h-Bezug mit Abrufzeit (auch aus der Datei). */
    private class TimedDay(val time: Long, val day: DayReference)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val days = ConcurrentHashMap<String, TimedDay>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val permits = Semaphore(MAX_PARALLEL)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Datei einmal im Hintergrund lesen; wer 24-h-Bezüge braucht, wartet darauf ([awaitRestored]). */
    private val restored: Deferred<Unit> = ioScope.async { restore() }
    private val savePending = AtomicBoolean(false)

    /** Zwischengespeicherte Reihe, auch wenn sie schon etwas alt ist — für den ersten Frame. */
    fun cached(base: String): List<Double>? = cache[key(base, QUOTE)]?.series?.closes

    /**
     * Frische Reihe (mindestens zwei Werte) oder null. Liegt eine gültige im
     * Speicher, ohne Netz; gleichzeitige Anfragen für dasselbe Asset warten
     * aufeinander statt doppelt zu laden.
     */
    suspend fun closes(base: String): List<Double>? = series(base, QUOTE, TTL_MILLIS)?.closes

    /**
     * 24-h-Bezug (Eröffnung vor 24 h, letzter Schluss) für [base] gegen [quote] —
     * gleiche Abfrage wie der Mini-Chart, wenn [quote] USDT ist (siehe `DayChange.candleQuote`).
     * Liegt ein höchstens 60 Minuten alter Bezug vor (auch aus der Datei), ohne Netz.
     * null, wenn keine Quelle das Paar liefert.
     */
    suspend fun dayReference(base: String, quote: String): DayReference? {
        awaitRestored()
        val k = key(base, quote)
        if (k.isEmpty()) return null
        days[k]?.takeIf { DayReferenceCache.fresh(it.time, System.currentTimeMillis()) }?.let { return it.day }
        return series(base, quote, DayReferenceCache.FRESH_MILLIS)?.day
    }

    /**
     * Gemerkter 24-h-Bezug (auch aus der Datei), höchstens [DayReferenceCache.MAX_AGE_MILLIS] alt;
     * sonst null. Vorher einmal [awaitRestored] aufrufen, sonst fehlen die Werte aus der Datei.
     */
    fun cachedDayReference(base: String, quote: String): DayReference? =
        days[key(base, quote)]?.takeIf { DayReferenceCache.usable(it.time, System.currentTimeMillis()) }?.day

    /** Wartet, bis die gespeicherten 24-h-Bezüge gelesen sind (einmal nach dem Start, sonst sofort). */
    suspend fun awaitRestored() = restored.await()

    private suspend fun series(base: String, quote: String, ttl: Long): Series? {
        val k = key(base, quote)
        if (k.isEmpty()) return null
        fresh(k, ttl)?.let { return it.series }
        val lock = locks.getOrPut(k) { Mutex() }
        return lock.withLock {
            fresh(k, ttl)?.let { return@withLock it.series }
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
            val now = System.currentTimeMillis()
            cache[k] = Entry(now, series)
            // Fehlschlag: der zuletzt erfolgreiche Bezug bleibt (begrenzt durch MAX_AGE_MILLIS)
            series?.day?.let {
                days[k] = TimedDay(now, it)
                scheduleSave()
            }
            series
        }
    }

    private fun fresh(k: String, ttl: Long): Entry? {
        val entry = cache[k] ?: return null
        val age = System.currentTimeMillis() - entry.time
        val limit = if (entry.series != null) ttl else FAILURE_TTL_MILLIS
        return entry.takeIf { age in 0 until limit }
    }

    private fun key(base: String, quote: String): String {
        val b = base.trim().uppercase()
        val q = quote.trim().uppercase()
        if (b.isEmpty() || q.isEmpty()) return ""
        return "$b|$q"
    }

    // ---------------- Datei ----------------

    private val file: File get() = File(context.cacheDir, FILE_NAME)

    private fun restore() {
        try {
            val f = file
            if (!f.isFile) return
            val root = JSONObject(f.readText())
            val version = if (root.has(KEY_VERSION)) root.optInt(KEY_VERSION, -1) else null
            val list = root.optJSONArray(KEY_ENTRIES) ?: return
            val entries = ArrayList<DayReferenceCache.Stored>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                entries += DayReferenceCache.Stored(
                    key = o.optString("k"),
                    time = o.optLong("t", 0L),
                    open = o.optDouble("o", Double.NaN),
                    lastClose = o.optDouble("c", Double.NaN),
                )
            }
            DayReferenceCache.restore(version, entries, System.currentTimeMillis()).forEach { (k, stored) ->
                val day = DayReference.of(stored.open, stored.lastClose) ?: return@forEach
                // Nie einen frischeren Wert aus dem Netz überschreiben
                days.merge(k, TimedDay(stored.time, day)) { current, disk -> if (current.time >= disk.time) current else disk }
            }
        } catch (e: Exception) {
            Timber.d(e, "24-h-Bezüge: Zwischenspeicher unlesbar, wird ignoriert")
        }
    }

    /** Schreibt höchstens alle [SAVE_DELAY_MILLIS] (viele Abrufe hintereinander = eine Datei). */
    private fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        ioScope.launch {
            delay(SAVE_DELAY_MILLIS)
            savePending.set(false)
            save()
        }
    }

    private fun save() {
        try {
            val now = System.currentTimeMillis()
            val stored = days.map { (k, v) -> DayReferenceCache.Stored(k, v.time, v.day.open, v.day.lastClose) }
            val list = JSONArray()
            DayReferenceCache.toSave(stored, now).forEach {
                list.put(JSONObject().put("k", it.key).put("t", it.time).put("o", it.open).put("c", it.lastClose))
            }
            val root = JSONObject()
                .put(KEY_VERSION, DayReferenceCache.FORMAT_VERSION)
                .put(KEY_ENTRIES, list)
            val target = file
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (e: Exception) {
            Timber.d(e, "24-h-Bezüge: Zwischenspeicher nicht geschrieben")
        }
    }

    companion object {
        /** Gültigkeit der Mini-Chart-Kurve (Zeilen laden danach neu). */
        const val TTL_MILLIS = 15 * 60_000L
        private const val FAILURE_TTL_MILLIS = 5 * 60_000L
        private const val MAX_PARALLEL = 6
        private const val POINTS = 24
        private const val QUOTE = "USDT"
        private const val SAVE_DELAY_MILLIS = 3_000L
        private const val FILE_NAME = "day_references_v1.json"
        private const val KEY_VERSION = "v"
        private const val KEY_ENTRIES = "e"
    }
}
