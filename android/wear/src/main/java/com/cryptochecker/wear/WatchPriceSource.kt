package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchCoins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kurse direkt bei den Börsen (kein Server, kein Google-Daten-Layer): zuerst Binance (nur die gewünschten
 * Coins in einer Abfrage), fehlende Coins bzw. bei Fehler/Sperre (z. B. HTTP 451) Bybit, dann OKX — dort je fehlendem
 * Coin eine kleine Abfrage (parallel), nie die ganze Spot-Liste. Höchstens [WatchCoins.MAX] Coins, also höchstens so
 * viele Abfragen je Ausweich-Börse. Antworten höchstens [MAX_BYTES] gross, je Abfrage [TIMEOUT_MS].
 */
object WatchPriceSource {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val USER_AGENT = "CryptoChecker-Wear/" + BuildConfig.VERSION_NAME

    suspend fun fetch(coins: List<String>): Map<String, WatchQuote> = withContext(Dispatchers.IO) {
        fetch(coins) { url -> get(url) }
    }

    /**
     * Ablauf mit austauschbarer Abfrage [get] (URL → Antwort; wirft bei HTTP-Fehler, Sperre oder ohne Netz) — für die
     * Tests (`WatchQuotesParityTest`). Ergebnis in der Reihenfolge von [coins].
     */
    suspend fun fetch(coins: List<String>, get: suspend (String) -> String): Map<String, WatchQuote> {
        val wanted = coins.distinct().take(WatchCoins.MAX)
        if (wanted.isEmpty()) return emptyMap()
        val binance = runCatching { WatchQuotes.binance(get(WatchQuotes.binanceUrl(wanted)), wanted) }.getOrDefault(emptyList())
        var merged = WatchQuotes.merge(binance)
        if (merged.size < wanted.size) {
            val bybit = perCoin(wanted - merged.keys, get, WatchQuotes::bybitUrl, WatchQuotes::bybit)
            merged = WatchQuotes.merge(merged.values.toList(), bybit)
        }
        if (merged.size < wanted.size) {
            val okx = perCoin(wanted - merged.keys, get, WatchQuotes::okxUrl, WatchQuotes::okx)
            merged = WatchQuotes.merge(merged.values.toList(), okx)
        }
        // Reihenfolge der Favoriten
        return wanted.mapNotNull { c -> merged[c]?.let { c to it } }.toMap(LinkedHashMap())
    }

    /** Je Coin eine Abfrage (gleichzeitig); ein Fehler betrifft nur diesen Coin. */
    private suspend fun perCoin(
        coins: List<String>,
        get: suspend (String) -> String,
        url: (String) -> String,
        parse: (String, Collection<String>) -> List<WatchQuote>,
    ): List<WatchQuote> = coroutineScope {
        coins.map { coin ->
            async { runCatching { parse(get(url(coin)), listOf(coin)) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return connection.inputStream.use { input ->
                val bytes = input.readNBytesLimited(MAX_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IllegalStateException("response too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
