package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.market.ExchangeLogos
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** CoinGecko-Abfrage; bei «429» einmal nach der verlangten Pause ([CoinLogos.rateLimitWaitMillis]) wiederholen. */
internal suspend fun OkHttpClient.callCoinGecko(url: String): String = try {
    callExchange(url, null, HttpBodyLimit.BULK_MAX_BYTES)
} catch (e: HttpExchangeError) {
    if (e.httpCode != HTTP_TOO_MANY) throw e
    delay(CoinLogos.rateLimitWaitMillis(e.retryAfterSeconds))
    callExchange(url, null, HttpBodyLimit.BULK_MAX_BYTES)
}

private const val HTTP_TOO_MANY = 429
private const val HTTP_NOT_FOUND = 404

/**
 * Börsen-Logos für die Listen («Paar hinzufügen»): Bild-Adressen aus der öffentlichen Börsenliste von CoinGecko
 * ([ExchangeLogos]; Seite 1 kommt aus dem Abruf der Reihenfolge, [offerFirstPage]), weitere Seiten nur, solange
 * zugeordnete Börsen fehlen, für in der Liste fehlende Börsen die Einzelseite — Pause zwischen den Abfragen
 * ([CoinLogos.PAGE_PAUSE_MILLIS]), «429» einmal nach der Pause wiederholt. Fehlte etwas, frühestens nach
 * [ExchangeLogos.RETRY_MILLIS] erneut, Gefundenes bleibt. Bilder über die Pipeline der Coin-Logos
 * ([LogoImageStore.downloadMissing]: Grenzen, Datei-Speicher, Tagessperre, Pausen) in [ExchangeLogos.ORDER] — für alle
 * gleich, unabhängig von der Merkliste. Nur mit Schalter «In der App» aufgerufen. Wie iOS `ExchangeLogoStore`.
 */
@Singleton
class ExchangeLogoStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val logoImages: LogoImageStore,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    /** Seite 1 aus dem Abruf der Reihenfolge in dieser Sitzung (spart die zweite gleiche Abfrage). */
    @Volatile private var firstPage: String? = null

    /** In dieser Sitzung schon versucht. */
    @Volatile private var tried = false

    fun offerFirstPage(body: String) {
        firstPage = body
    }

    /** Bild-Adressen holen, falls fällig, dann die fehlenden Logos laden (vorhandene: kein Abruf). */
    suspend fun ensure() {
        val urls = mutex.withLock {
            val saved = loadFound()
            val now = System.currentTimeMillis()
            val due = saved.isEmpty() ||
                ExchangeLogos.retryDue(prefs.getBoolean(KEY_COMPLETE, false), prefs.getLong(KEY_ATTEMPT, 0L), now)
            if (due && !tried) {
                tried = true
                fetch(saved)
            } else {
                saved
            }
        }
        if (urls.isEmpty()) return
        logoImages.downloadMissing(ExchangeLogos.images(urls))
    }

    /** Seiten und Einzelseiten lesen; Ergebnis (Kennung → Adresse) samt Vollständigkeit gespeichert. */
    private suspend fun fetch(saved: Map<String, String>): Map<String, String> {
        val found = LinkedHashMap(saved)
        var complete = true
        var listEnded = false
        var page = 1
        try {
            while (true) {
                if (page > 1) delay(CoinLogos.PAGE_PAUSE_MILLIS)
                val body = (if (page == 1) firstPage else null) ?: httpClient.callCoinGecko(ExchangeLogos.pageUrl(page))
                val parsed = ExchangeLogos.parsePage(body)
                parsed.images.forEach { (id, url) -> found[id] = url }
                val missing = ExchangeLogos.missing(found).size
                if (!ExchangeLogos.needsNextPage(page, parsed.rows, missing)) {
                    listEnded = missing == 0 || parsed.rows < ExchangeLogos.PER_PAGE || page >= ExchangeLogos.MAX_PAGES
                    break
                }
                page++
            }
            // Bei CoinGecko inaktiv, also nicht in der Liste: Einzelseite
            if (listEnded) {
                for (id in ExchangeLogos.missing(found)) {
                    delay(CoinLogos.PAGE_PAUSE_MILLIS)
                    detail(id)?.let { found[id] = it }
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCritical()
            complete = false
            Timber.i("Börsen-Logos: Liste unvollständig (Seite %d): %s", page, e.message)
        }
        save(found, complete)
        return found
    }

    /** Bild der Einzelseite; 404 = Börse fehlt bei CoinGecko (gilt als erledigt), andere Fehler → Ausnahme. */
    private suspend fun detail(id: String): String? = try {
        ExchangeLogos.parseDetail(httpClient.callCoinGecko(ExchangeLogos.detailUrl(id)))
    } catch (e: HttpExchangeError) {
        if (e.httpCode != HTTP_NOT_FOUND) throw e
        null
    }

    private fun save(found: Map<String, String>, complete: Boolean) {
        prefs.edit {
            putString(KEY_FOUND, JSONObject(found as Map<*, *>).toString())
            putBoolean(KEY_COMPLETE, complete)
            putLong(KEY_ATTEMPT, System.currentTimeMillis())
        }
    }

    private fun loadFound(): Map<String, String> {
        val raw = prefs.getString(KEY_FOUND, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
                .filter { (id, url) -> id in ExchangeLogos.IDS && CoinLogos.isAllowedUrl(url) }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREFS = "exchange_logos"
        const val KEY_FOUND = "found"
        const val KEY_COMPLETE = "complete"
        const val KEY_ATTEMPT = "attempt"
    }
}
