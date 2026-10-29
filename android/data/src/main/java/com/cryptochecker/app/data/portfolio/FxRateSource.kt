package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.convert.FxCurrencies
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.marketdata.util.EpochTime
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

    /**
     * Währung → Zeitpunkt des Kurses laut Quelle (EZB-Datum bzw. letzte Aktualisierung von open.er-api), für das Alter im
     * Fiat-Alarm ([rateTime]); fehlt er, gilt die Abrufzeit.
     */
    private val rateTimes = ConcurrentHashMap<String, Long>()

    /** Letzter Abruf ohne eigenen BGN-Kurs (Epoch-ms; nur unter [mutex]). */
    private var bgnMissedAt = 0L

    /** Wie viele Einheiten [currency] ein USD ist; null, wenn unbekannt. USD = 1. */
    suspend fun usdTo(currency: String): Double? {
        val target = currency.trim().uppercase()
        if (target == "USD") return 1.0
        if (target.length != 3 || !target.all { it in 'A'..'Z' }) return null

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                // Lew nach der Euro-Einführung: fehlt BGN, dann EUR × 1.95583 — aus dem EUR-Kurs samt DESSEN Abrufzeit
                // (nicht als frischer BGN-Kurs gespeichert; [fetchedAt] nennt die Zeit des EUR-Kurses)
                val derive = derivesBgn(target)
                // Ohne eigenen BGN-Kurs nicht bei jedem Aufruf erneut abfragen
                val skipOwn = derive && now - bgnMissedAt in 0 until TTL_MILLIS
                val own = if (skipOwn) cache[target]?.takeIf { now - it.second in 0 until STALE_MILLIS } else load(target, now)
                if (own != null || !derive) return@withLock own?.first
                if (!skipOwn) bgnMissedAt = now
                CurrencyConversion.fxWithTime(null, load("EUR", now), deriveBgn = true)?.first
            }
        }
    }

    /**
     * Kurs von [target] mit Abrufzeit: jünger als [TTL_MILLIS] aus dem Zwischenspeicher, sonst neu geholt; scheitert das,
     * der letzte Kurs bis [STALE_MILLIS] (älterer Kurs ist besser als keiner, aber nicht älter als ein Tag). Nur unter
     * [mutex] aufrufen.
     */
    private suspend fun load(target: String, now: Long): Pair<Double, Long>? {
        cache[target]?.let { if (now - it.second in 0 until TTL_MILLIS) return it }
        val fresh = frankfurter(target) ?: openErApi(target)
        if (fresh != null) {
            fresh.second?.let { rateTimes[target] = it } ?: rateTimes.remove(target)
            return (fresh.first to now).also { cache[target] = it }
        }
        return cache[target]?.takeIf { now - it.second in 0 until STALE_MILLIS }
    }

    /** Zuletzt bekannter Kurs ohne Netz (für den ersten Aufbau) — höchstens 24 h alt wie bei [usdTo]; BGN ohne eigenen aus EUR. */
    fun cached(currency: String): Double? {
        val target = currency.trim().uppercase()
        if (target == "USD") return 1.0
        return cachedWithTime(target)?.first
    }

    /**
     * Abrufzeit (Epoch-ms) des Kurses von [currency], den [usdTo]/[cached] gerade liefern; null für USD
     * (kein Devisenkurs nötig) oder ohne höchstens 24 h alten Kurs. BGN ohne eigenen Kurs: die des EUR-Kurses.
     */
    fun fetchedAt(currency: String): Long? {
        val target = currency.trim().uppercase()
        if (target == "USD") return null
        return cachedWithTime(target)?.second
    }

    /**
     * Zeitpunkt des Kurses von [currency] laut Quelle (EZB-Tageskurs: Datum um 15:00 UTC,
     * [com.cryptochecker.app.domain.convert.FiatAlarmGuard.rateTimeOf]); ohne Angabe die Abrufzeit ([fetchedAt]). null für
     * USD oder ohne Kurs. BGN ohne eigenen Kurs: die des EUR-Kurses. Für das Alter im Fiat-Alarm (36 h Bankarbeitszeit).
     */
    fun rateTime(currency: String): Long? {
        val target = currency.trim().uppercase()
        if (target == "USD") return null
        val own = cache[target]?.takeIf { System.currentTimeMillis() - it.second in 0 until STALE_MILLIS }
        val code = if (own == null && derivesBgn(target)) "EUR" else target
        val fetched = fetchedAt(target) ?: return null
        return rateTimes[code] ?: fetched
    }

    /** Kurs und Abrufzeit aus dem Zwischenspeicher (höchstens 24 h alt); BGN ohne eigenen aus EUR ([CurrencyConversion.fxWithTime]). */
    private fun cachedWithTime(target: String): Pair<Double, Long>? {
        val now = System.currentTimeMillis()
        fun entry(code: String) = cache[code]?.takeIf { now - it.second in 0 until STALE_MILLIS }
        return CurrencyConversion.fxWithTime(entry(target), entry("EUR"), derivesBgn(target))
    }

    /** BGN ab der Euro-Einführung aus EUR ableiten ([CurrencyConversion.deriveBgnFromEur]). */
    private fun derivesBgn(target: String): Boolean = CurrencyConversion.deriveBgnFromEur(target, LocalDate.now(ZoneOffset.UTC))

    /** Kurs und Zeitpunkt des EZB-Tageskurses («date», [com.cryptochecker.app.domain.convert.FiatAlarmGuard.rateTimeOf]). */
    private suspend fun frankfurter(target: String): Pair<Double, Long?>? =
        get("https://api.frankfurter.app/latest?from=USD&to=$target")?.let { body ->
            runCatching {
                val root = JSONObject(body)
                root.getJSONObject("rates").getDouble(target) to
                    com.cryptochecker.app.domain.convert.FiatAlarmGuard.rateTimeOf(root.optString("date"))
            }.getOrNull()
        }?.takeIf { it.first > 0.0 }

    /** Kurs und Zeitpunkt der letzten Aktualisierung («time_last_update_unix», Sekunden). */
    private suspend fun openErApi(target: String): Pair<Double, Long?>? =
        get("https://open.er-api.com/v6/latest/USD")?.let { body ->
            runCatching {
                val root = JSONObject(body)
                root.getJSONObject("rates").getDouble(target) to
                    EpochTime.secondsToMillis(root.optLong("time_last_update_unix", 0L))
            }.getOrNull()
        }?.takeIf { it.first > 0.0 }

    private suspend fun get(url: String): String? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callExchange(url, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Devisenkurs nicht verfügbar: %s", url)
        null
    }

    companion object {
        /** Neu holen ab 3 h: ältere Kurse zeigt die Merkliste mit Uhrzeit. */
        private const val TTL_MILLIS = CurrencyConversion.FX_STALE_MILLIS
        private const val STALE_MILLIS = 24 * 60 * 60_000L
        private const val REQUEST_TIMEOUT_MILLIS = 10_000L

        /** Wählbare Zielwährungen (EZB-Referenzkurse), die gebräuchlichsten zuerst ([FxCurrencies.ALL]). */
        val CURRENCIES = FxCurrencies.ALL
    }
}
