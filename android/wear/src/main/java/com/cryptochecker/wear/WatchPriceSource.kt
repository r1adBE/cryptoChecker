package com.cryptochecker.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Kurse direkt bei den Börsen (Build 154, kein Server, kein Google-Daten-Layer): zuerst Binance (nur die gewünschten
 * Coins in einer Abfrage), fehlende Coins bzw. bei Fehler/Sperre (z. B. HTTP 451) Bybit, dann OKX. Antworten höchstens
 * [MAX_BYTES] gross, je Abfrage [TIMEOUT_MS].
 */
object WatchPriceSource {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val USER_AGENT = "CryptoChecker-Wear/16.2.2"

    suspend fun fetch(coins: List<String>): Map<String, WatchQuote> = withContext(Dispatchers.IO) {
        if (coins.isEmpty()) return@withContext emptyMap()
        val symbols = coins.joinToString(",", "[", "]") { "\"${it}USDT\"" }
        val binance = runCatching {
            WatchQuotes.binance(get("https://api.binance.com/api/v3/ticker/24hr?symbols=" + URLEncoder.encode(symbols, "UTF-8")), coins)
        }.getOrDefault(emptyList())
        var merged = WatchQuotes.merge(binance)
        if (merged.size < coins.size) {
            val missing = coins - merged.keys
            val bybit = runCatching { WatchQuotes.bybit(get("https://api.bybit.com/v5/market/tickers?category=spot"), missing) }
                .getOrDefault(emptyList())
            merged = WatchQuotes.merge(binance, bybit)
        }
        if (merged.size < coins.size) {
            val missing = coins - merged.keys
            val okx = runCatching { WatchQuotes.okx(get("https://www.okx.com/api/v5/market/tickers?instType=SPOT"), missing) }
                .getOrDefault(emptyList())
            merged = WatchQuotes.merge(merged.values.toList(), okx)
        }
        // Reihenfolge der Favoriten
        coins.mapNotNull { c -> merged[c]?.let { c to it } }.toMap(LinkedHashMap())
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
