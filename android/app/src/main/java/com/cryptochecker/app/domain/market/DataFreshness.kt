package com.cryptochecker.app.domain.market

import java.time.Instant
import java.time.ZoneId

/** Ein geholter Wert und welcher Anbieter ihn geliefert hat ([provider] null = unbekannt). */
data class Sourced<out T>(val value: T, val provider: String?)

/**
 * Herkunft und Stand eines gezeigten Werts im Markt-Tab: Anbieter (z. B. «CoinGecko»),
 * Zeitpunkt (Epoch-ms) und Gültigkeit des Bereichs ([CycleSource.ttlMillis]).
 */
data class DataStamp(val provider: String?, val savedAt: Long, val ttlMillis: Long)

/**
 * Herkunft und Alter der Werte im Markt-Tab (reines Kotlin, testbar): welche Altersstufe
 * («gerade eben», «vor 3 Min.», «heute 02:00», Datum) gezeigt wird, ab wann ein Wert als
 * veraltet gilt, und die Anbieternamen. Wie `DataFreshness` (iOS).
 */
object DataFreshness {

    /** Veraltet = älter als so viele Gültigkeitsdauern ([CycleSource.ttlMillis]). */
    const val STALE_FACTOR = 3L

    const val ALTERNATIVE_ME = "alternative.me"
    const val COINGECKO = "CoinGecko"
    const val COIN_METRICS = "Coin Metrics"
    const val MEMPOOL = "mempool.space"
    const val BINANCE = "Binance"
    const val BINANCE_US = "Binance.US"
    const val COINBASE = "Coinbase"
    const val BYBIT = "Bybit"

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Altersstufe eines Werts für die Nebenzeile. */
    sealed interface Age {
        /** Jünger als eine Minute (auch: Zeitpunkt in der Zukunft, Uhr verstellt). */
        data object JustNow : Age

        /** «vor N Min.», 1–59. */
        data class Minutes(val minutes: Int) : Age

        /** Heute, mindestens eine Stunde her: «heute HH:MM». */
        data class Today(val at: Long) : Age

        /** Früherer Tag: Datum. */
        data class Date(val at: Long) : Age
    }

    /** Altersstufe von [savedAt] zum Zeitpunkt [now]; «heute» nach der Zeitzone [zone]. */
    fun age(savedAt: Long, now: Long, zone: ZoneId): Age {
        val diff = now - savedAt
        if (diff < MINUTE) return Age.JustNow
        if (diff < HOUR) return Age.Minutes((diff / MINUTE).toInt())
        val day = Instant.ofEpochMilli(savedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (day == today) Age.Today(savedAt) else Age.Date(savedAt)
    }

    /** Veraltet: älter als [STALE_FACTOR] × [ttlMillis]. Zukunft oder TTL ≤ 0: nie. */
    fun isStale(savedAt: Long, now: Long, ttlMillis: Long): Boolean =
        ttlMillis > 0 && now - savedAt > STALE_FACTOR * ttlMillis

    /** Mehrere Anbieter zu einem Namen, z. B. «Binance, Coin Metrics»; null ohne Namen. */
    fun providers(vararg names: String?): String? =
        names.mapNotNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }.distinct().joinToString(", ").ifEmpty { null }

    /**
     * Anzeigename eines Kerzen-Hosts der Ausweich-Kette: alle Binance-Hosts «Binance»,
     * api.binance.us «Binance.US», Coinbase «Coinbase»; sonst [siteName].
     */
    fun candleProvider(host: String): String {
        val h = host.lowercase()
        return when {
            h == "api.binance.us" || h.endsWith(".binance.us") -> BINANCE_US
            h.endsWith("binance.com") || h.endsWith("binance.vision") -> BINANCE
            h.endsWith("coinbase.com") -> COINBASE
            else -> siteName(host)
        }
    }

    /**
     * Kurzer Name einer Adresse: die letzten zwei Teile des Hosts, z. B.
     * «https://ethereum-rpc.publicnode.com» → «publicnode.com», «eth.llamarpc.com» → «llamarpc.com».
     */
    fun siteName(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val parts = host.split('.').filter { it.isNotEmpty() }
        return if (parts.size <= 2) parts.joinToString(".") else parts.takeLast(2).joinToString(".")
    }
}
