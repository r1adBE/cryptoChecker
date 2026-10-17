package com.cryptochecker.app.data

import android.content.Context
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.macro.MacroEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kalender wichtiger US-Wirtschaftsdaten (CPI, PPI, Arbeitsmarkt, Fed-Zinsentscheid, PCE)
 * für den Hinweis im Markt-Tab und die Morgen-Meldung.
 *
 * Quelle: eine öffentliche Datei auf der App-Webseite ([MacroCalendar.URL], GitHub Pages,
 * wöchentlich aus den offiziellen Terminplänen erzeugt) — höchstens einmal am Tag geholt,
 * ohne Schlüssel und ohne Angaben zum Gerät. Zwischenspeicher 24 h ([CycleCacheStore]);
 * bei Fehlern der Zwischenspeicher (auch abgelaufen), sonst die mitgelieferte Datei
 * `assets/macro_events.json`.
 */
@Singleton
class MacroCalendarRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val cacheStore: CycleCacheStore,
) {
    private val mutex = Mutex()

    /** Zuletzt geliefertes Ergebnis mit Zeitpunkt, ab dem die Gültigkeit ([MacroCalendar.TTL_MILLIS]) zählt. */
    @Volatile
    private var memory: Pair<Long, List<MacroEvent>>? = null

    /** Termine; aus dem Netz nur, wenn der Zwischenspeicher älter als einen Tag ist. Wirft nie. */
    suspend fun events(): List<MacroEvent> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            memory?.let { (at, list) -> if (MacroCalendar.isFresh(at, now)) return@withContext list }
            val cached = cacheStore.read(CACHE_NAME, MarketExtraCodecs.macroEvents)
            if (cached != null && MacroCalendar.isFresh(cached.savedAt, now)) {
                memory = cached.savedAt to cached.value
                return@withContext cached.value
            }
            val fresh = download(now)
            if (fresh != null) {
                cacheStore.write(CACHE_NAME, fresh, now, MarketExtraCodecs.macroEvents)
                memory = now to fresh
                return@withContext fresh
            }
            // Netz gescheitert: Zwischenspeicher (auch abgelaufen), sonst die mitgelieferte Datei.
            // Im Speicher so merken, dass es frühestens in 30 Min. wieder versucht wird.
            val fallback = cached?.value ?: bundled(now)
            memory = (now - MacroCalendar.TTL_MILLIS + RETRY_MILLIS) to fallback
            fallback
        }
    }

    private suspend fun download(now: Long): List<MacroEvent>? = try {
        withTimeoutOrNull(TIMEOUT_MILLIS) { MacroCalendar.parse(httpClient.callMarket(MacroCalendar.URL, null), now) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Timber.d(e, "Wirtschaftsdaten: Zeitüberschreitung")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Wirtschaftsdaten nicht verfügbar")
        null
    }

    private fun bundled(now: Long): List<MacroEvent> = try {
        context.assets.open(BUNDLED_FILE).bufferedReader().use { MacroCalendar.parse(it.readText(), now) }.orEmpty()
    } catch (e: Exception) {
        Timber.d(e, "Mitgelieferte Wirtschaftsdaten fehlen")
        emptyList()
    }

    private companion object {
        const val CACHE_NAME = "macro"
        const val BUNDLED_FILE = "macro_events.json"
        const val TIMEOUT_MILLIS = 12_000L
        const val RETRY_MILLIS = 30 * 60_000L
    }
}
