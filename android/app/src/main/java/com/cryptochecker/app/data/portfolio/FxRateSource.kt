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
 * open.er-api.com. Zwischenspeicher 6 h; ohne Kurs bleibt die Zeile weg.
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

    /** Zuletzt bekannter Kurs ohne Netz (für den ersten Aufbau). */
    fun cached(currency: String): Double? {
        val target = currency.trim().uppercase()
        if (target == "USD") return 1.0
        return cache[target]?.first
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
        private const val TTL_MILLIS = 6 * 60 * 60_000L
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
