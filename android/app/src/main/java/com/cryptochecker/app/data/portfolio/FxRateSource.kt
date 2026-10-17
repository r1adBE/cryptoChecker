package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.convert.CurrencyConversion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Devisenkurs USD → Zielwährung für die Umrechnungszeile im Portfolio.
 * USDT gilt als USD. Quelle: Frankfurter (EZB, ohne Schlüssel), sonst
 * open.er-api.com. Neu geholt nach 3 h ([CurrencyConversion.FX_STALE_MILLIS]: ab da zeigt die
 * Merkliste die Uhrzeit des Kurses); scheitert das, gilt der letzte Kurs bis 24 h. Ohne Kurs bleibt
 * die Zeile weg. Abrufzeit je Währung: [fetchedAt].
 */
@Singleton
class FxRateSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /** Währung → (Kurs, Abfragezeit). */
    private val cache = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val mutex = Mutex()

    /** Wie viele Einheiten [currency] ein USD ist; null, wenn unbekannt. USD = 1. */
    suspend fun usdTo(currency: String): Double? {
        val target = currency.trim().uppercase()
        if (target == "USD") return 1.0
        if (target.length != 3 || !target.all { it in 'A'..'Z' }) return null

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                cache[target]?.let { (rate, at) ->
                    if (now - at in 0 until TTL_MILLIS) return@withLock rate
                }
                val fresh = frankfurter(target) ?: openErApi(target) ?: bgnFallback(target)
                if (fresh != null) {
                    cache[target] = fresh to now
                    fresh
                } else {
                    // Älterer Kurs ist besser als keiner, aber nicht älter als ein Tag
                    cache[target]?.takeIf { now - it.second in 0 until STALE_MILLIS }?.first
                }
            }
        }
    }

    /** Zuletzt bekannter Kurs ohne Netz (für den ersten Aufbau) — höchstens 24 h alt wie bei [usdTo]. */
    fun cached(currency: String): Double? {
        val target = currency.trim().uppercase()
        if (target == "USD") return 1.0
        val (rate, at) = cache[target] ?: return null
        return rate.takeIf { System.currentTimeMillis() - at in 0 until STALE_MILLIS }
    }

    /**
     * Abrufzeit (Epoch-ms) des Kurses von [currency], den [usdTo]/[cached] gerade liefern; null für USD
     * (kein Devisenkurs nötig) oder ohne höchstens 24 h alten Kurs.
     */
    fun fetchedAt(currency: String): Long? {
        val target = currency.trim().uppercase()
        if (target == "USD") return null
        val (_, at) = cache[target] ?: return null
        return at.takeIf { System.currentTimeMillis() - it in 0 until STALE_MILLIS }
    }

    /** Lew nach der Euro-Einführung: fehlt BGN, dann EUR × 1.95583 (fester Kurs). */
    private suspend fun bgnFallback(target: String): Double? {
        if (!CurrencyConversion.deriveBgnFromEur(target, LocalDate.now(ZoneOffset.UTC))) return null
        val eur = frankfurter("EUR") ?: openErApi("EUR") ?: cache["EUR"]?.first
        return CurrencyConversion.bgnFromEur(eur)
    }

    private suspend fun frankfurter(target: String): Double? =
        get("https://api.frankfurter.app/latest?from=USD&to=$target")?.let { body ->
            runCatching { JSONObject(body).getJSONObject("rates").getDouble(target) }.getOrNull()
        }?.takeIf { it > 0.0 }

    private suspend fun openErApi(target: String): Double? =
        get("https://open.er-api.com/v6/latest/USD")?.let { body ->
            runCatching { JSONObject(body).getJSONObject("rates").getDouble(target) }.getOrNull()
        }?.takeIf { it > 0.0 }

    private suspend fun get(url: String): String? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callMarket(url, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Devisenkurs nicht verfügbar: %s", url)
        null
    }

    companion object {
        /** Neu holen ab 3 h (vorher 6 h): ältere Kurse zeigt die Merkliste mit Uhrzeit. */
        private const val TTL_MILLIS = CurrencyConversion.FX_STALE_MILLIS
        private const val STALE_MILLIS = 24 * 60 * 60_000L
        private const val REQUEST_TIMEOUT_MILLIS = 10_000L

        /** Wählbare Zielwährungen (EZB-Referenzkurse), die gebräuchlichsten zuerst. */
        val CURRENCIES = listOf(
            "CHF", "EUR", "USD", "GBP", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK", "PLN", "CZK",
            "HUF", "RON", "BGN", "ISK", "TRY", "CNY", "HKD", "SGD", "KRW", "INR", "IDR", "THB",
            "MYR", "PHP", "NZD", "ZAR", "BRL", "MXN", "ILS",
        )
    }
}
