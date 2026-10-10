package com.cryptochecker.app.data

import android.content.Context
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.settings.SettingsRepository
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
 * Für die Tages-Basen der %-Änderung ([ChangeBasis]) dieselbe Abfrage mit 26 statt 24 Kerzen:
 * Bezug ist die Eröffnung der Kerze, in der der Tagesbeginn liegt ([dayStartReference]) —
 * gemerkt werden nur die Kerzen der heutigen Tagesbeginne (UTC, Ortszeit und die gewählte Zone).
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
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Stundenkerzen eines Paars: Schlusskurse (Mini-Chart, die jüngsten 24), 24-h-Bezug (Pille)
     * und die Eröffnung je Kerze (Startzeit → Eröffnung) für die Tages-Basen.
     */
    private class Series(
        val closes: List<Double>,
        val day: DayReference?,
        val opens: Map<Long, Double>,
        /** Anbieter der Kerzen ([CandleDataSource.candlesSourced]). */
        val provider: String? = null,
    )

    private class Entry(val time: Long, val series: Series?)

    /**
     * Zuletzt erfolgreich geladener 24-h-Bezug mit Abrufzeit (auch aus der Datei); [opens]
     * Eröffnungen der Stundenkerzen (aus der Datei nur die der Tagesbeginne).
     */
    private class TimedDay(val time: Long, val day: DayReference, val opens: Map<Long, Double>, val provider: String? = null)

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
     * Zwischengespeicherte Stundenkurse mit Abrufzeit (ohne Netz, auch wenn schon älter) — für
     * den Wertverlauf des Portfolio-Widgets; null, wenn nichts geladen ist.
     */
    fun cachedWithTime(base: String): Pair<Long, List<Double>>? =
        cache[key(base, QUOTE)]?.let { entry -> entry.series?.closes?.let { entry.time to it } }

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

    /**
     * Anbieter der Kerzen hinter dem gemerkten Bezug ([cachedDayReference]) für [base] gegen [quote]
     * — «Binance», «Binance.US», «Coinbase»; null, wenn unbekannt oder zu alt.
     */
    fun cachedProvider(base: String, quote: String): String? =
        days[key(base, quote)]?.takeIf { DayReferenceCache.usable(it.time, System.currentTimeMillis()) }?.provider

    /** Wartet, bis die gespeicherten 24-h-Bezüge gelesen sind (einmal nach dem Start, sonst sofort). */
    suspend fun awaitRestored() = restored.await()

    /**
     * Bezug seit Tagesbeginn [dayStart] (Eröffnung der Kerze, in der er liegt, und letzter
     * Schluss) für [base] gegen [quote]. Liegt ein höchstens 60 Minuten alter Abruf vor, der
     * diese Kerze schon enthält (auch aus der Datei), ohne Netz. null ohne Quelle.
     */
    suspend fun dayStartReference(base: String, quote: String, dayStart: Long): DayReference? {
        awaitRestored()
        val k = key(base, quote)
        if (k.isEmpty()) return null
        val hour = ChangeBasisMath.hourOf(dayStart)
        days[k]?.takeIf { DayReferenceCache.fresh(it.time, System.currentTimeMillis()) && hour in it.opens }
            ?.let { return ChangeBasisMath.reference(it.opens, it.day.lastClose, dayStart) }
        val loaded = series(base, quote, DayReferenceCache.FRESH_MILLIS, needsHour = hour) ?: return null
        return ChangeBasisMath.reference(loaded.opens, loaded.day?.lastClose, dayStart)
    }

    /**
     * Gemerkter Bezug seit [dayStart] (auch aus der Datei), Abruf höchstens
     * [DayReferenceCache.MAX_AGE_MILLIS] alt; sonst null. Vorher einmal [awaitRestored] aufrufen.
     */
    fun cachedDayStartReference(base: String, quote: String, dayStart: Long): DayReference? =
        days[key(base, quote)]?.takeIf { DayReferenceCache.usable(it.time, System.currentTimeMillis()) }
            ?.let { ChangeBasisMath.reference(it.opens, it.day.lastClose, dayStart) }

    /**
     * [needsHour]: Kerze ab dieser Startzeit muss enthalten sein — ein Abruf von vor
     * Mitternacht kennt die Kerze des neuen Tags noch nicht und gilt dann nicht als frisch.
     */
    private suspend fun series(base: String, quote: String, ttl: Long, needsHour: Long? = null): Series? {
        val k = key(base, quote)
        if (k.isEmpty()) return null
        fresh(k, ttl, needsHour)?.let { return it.series }
        val lock = locks.getOrPut(k) { Mutex() }
        return lock.withLock {
            fresh(k, ttl, needsHour)?.let { return@withLock it.series }
            val series = try {
                permits.withPermit {
                    val sourced = candleDataSource.candlesSourced(
                        base.trim().uppercase(), quote.trim().uppercase(), CandleInterval.H1, ChangeBasisMath.CANDLES,
                    )
                    sourced?.value
                        ?.filter { it.close.isFinite() && it.close > 0.0 }
                        ?.takeIf { it.size >= 2 }
                        ?.let { candles ->
                            // Mini-Chart und rollender Bezug wie bisher aus den jüngsten 24 Kerzen
                            val recent = candles.takeLast(ChangeBasisMath.ROLLING_CANDLES)
                            Series(
                                closes = recent.map { it.close },
                                day = DayReference.of(recent.first().open, recent.last().close),
                                opens = candles.associate { it.openTime to it.open },
                                provider = sourced.provider,
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
            series?.let { s ->
                s.day?.let {
                    days[k] = TimedDay(now, it, s.opens, s.provider)
                    scheduleSave()
                }
            }
            series
        }
    }

    private fun fresh(k: String, ttl: Long, needsHour: Long? = null): Entry? {
        val entry = cache[k] ?: return null
        val age = System.currentTimeMillis() - entry.time
        val limit = if (entry.series != null) ttl else FAILURE_TTL_MILLIS
        // Abruf von vor (oder kurz nach) Beginn dieser Stunde ohne ihre Kerze: neu laden; später
        // abgerufen und trotzdem ohne sie, liefert die Quelle sie nicht — dann bis zum Ablauf nicht
        // bei jeder Aktualisierung erneut fragen
        if (needsHour != null && entry.series != null && needsHour !in entry.series.opens &&
            entry.time < needsHour + NEW_CANDLE_GRACE_MILLIS
        ) return null
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
                val starts = HashMap<Long, Double>()
                o.optJSONArray("s")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val s = arr.optJSONObject(j) ?: continue
                        starts[s.optLong("t", 0L)] = s.optDouble("o", Double.NaN)
                    }
                }
                entries += DayReferenceCache.Stored(
                    key = o.optString("k"),
                    time = o.optLong("t", 0L),
                    open = o.optDouble("o", Double.NaN),
                    lastClose = o.optDouble("c", Double.NaN),
                    starts = starts,
                    provider = o.optString("p").takeIf { it.isNotEmpty() },
                )
            }
            DayReferenceCache.restore(version, entries, System.currentTimeMillis()).forEach { (k, stored) ->
                val day = DayReference.of(stored.open, stored.lastClose) ?: return@forEach
                // Nie einen frischeren Wert aus dem Netz überschreiben
                days.merge(k, TimedDay(stored.time, day, stored.starts, stored.provider)) { current, disk -> if (current.time >= disk.time) current else disk }
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
            // Nur die Kerzen der heutigen Tagesbeginne (UTC, Ortszeit, gewählte Zone) mitschreiben
            val dayStarts = ChangeBasisMath.keptDayStarts(settingsRepository.cached.changeBasis, now)
            val stored = days.map { (k, v) ->
                DayReferenceCache.Stored(
                    k, v.time, v.day.open, v.day.lastClose, DayReferenceCache.keepStarts(v.opens, dayStarts), v.provider,
                )
            }
            val list = JSONArray()
            DayReferenceCache.toSave(stored, now).forEach {
                val o = JSONObject().put("k", it.key).put("t", it.time).put("o", it.open).put("c", it.lastClose)
                it.provider?.let { provider -> o.put("p", provider) }
                if (it.starts.isNotEmpty()) {
                    o.put("s", JSONArray().apply {
                        it.starts.forEach { (t, open) -> put(JSONObject().put("t", t).put("o", open)) }
                    })
                }
                list.put(o)
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
        /** So lange nach Beginn einer Stunde darf ihre Kerze bei der Quelle noch fehlen. */
        private const val NEW_CANDLE_GRACE_MILLIS = 10 * 60_000L
        private const val MAX_PARALLEL = 6
        private const val QUOTE = "USDT"
        private const val SAVE_DELAY_MILLIS = 3_000L
        private const val FILE_NAME = "day_references_v1.json"
        private const val KEY_VERSION = "v"
        private const val KEY_ENTRIES = "e"
    }
}
