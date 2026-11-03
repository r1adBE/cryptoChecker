package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kurse direkt bei den Börsen (kein Server, kein Google-Daten-Layer): zuerst Binance (nur die gewünschten
 * Coins in einer Abfrage), fehlende Coins bzw. bei Fehler/Sperre (z. B. HTTP 451) Bybit, dann OKX — dort je fehlendem
 * Coin eine kleine Abfrage (parallel), nie die ganze Spot-Liste. Höchstens [WatchCoins.MAX] Coins, also höchstens so
 * viele Abfragen je Ausweich-Börse. Antworten höchstens [MAX_BYTES] gross, je Abfrage [TIMEOUT_MS], der ganze Abruf
 * höchstens [FETCH_DEADLINE_MS] (wie die Apple Watch, `WatchQuoteFetch.complicationTimeoutSeconds`). Favoriten des
 * Handys mit Paar ([WatchPair]) zuerst bei dessen Börse und Kontraktart ([WatchPairQuotes]). Ein Abbruch
 * (Frist, Ansicht weg, neuer Abruf) wird nie geschluckt und schliesst die laufende Verbindung sofort.
 */
object WatchPriceSource {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val USER_AGENT = "CryptoChecker-Wear/" + BuildConfig.VERSION_NAME

    /** Obergrenze eines ganzen Abrufs (alle Börsen zusammen); danach leer, der gespeicherte Kurs bleibt. */
    const val FETCH_DEADLINE_MS = 20_000L

    suspend fun fetch(coins: List<String>): Map<String, WatchQuote> = withContext(Dispatchers.IO) {
        within(FETCH_DEADLINE_MS) { fetch(coins) { url -> get(url) } }
    }

    /**
     * [run] mit Gesamtfrist: Ist er nach [millis] nicht fertig, wird er abgebrochen und das Ergebnis ist leer. Ein
     * Abbruch von aussen geht weiter (nicht geschluckt). Wie iOS `WatchQuoteFetch.within`.
     */
    suspend fun within(millis: Long, run: suspend () -> Map<String, WatchQuote>): Map<String, WatchQuote> =
        withTimeoutOrNull(millis) { run() }.orEmpty()

    /**
     * Ablauf mit austauschbarer Abfrage [get] (URL → Antwort; wirft bei HTTP-Fehler, Sperre oder ohne Netz) — für die
     * Tests (`WatchQuotesParityTest`). Ergebnis in der Reihenfolge von [coins].
     */
    suspend fun fetch(coins: List<String>, get: suspend (String) -> String): Map<String, WatchQuote> {
        val all = coins.distinct().take(WatchCoins.MAX)
        if (all.isEmpty()) return emptyMap()
        // Favoriten des Handys (Paar mit Börse und Kontrakt) je für sich, Kürzel der eigenen Liste gemeinsam
        val pairs = all.mapNotNull { WatchPair.parse(it) }
        val wanted = all - pairs.map { it.key }.toSet()
        val (spot, paired) = coroutineScope {
            val favorites = pairs.map { p -> async { pairQuote(p, get) } }
            coinQuotes(wanted, get) to favorites.awaitAll().filterNotNull()
        }
        val merged = WatchQuotes.merge(spot.values.toList(), paired)
        return all.mapNotNull { c -> merged[c]?.let { c to it } }.toMap(LinkedHashMap())
    }

    /**
     * Paar eines Favoriten: nacheinander bei den Börsen aus [WatchPairQuotes.venues] (die des Handys zuerst), bis eine
     * einen Kurs liefert; Fehler oder fehlendes Paar → die nächste. Keine → null.
     */
    private suspend fun pairQuote(pair: WatchPair, get: suspend (String) -> String): WatchQuote? {
        for (venue in WatchPairQuotes.venues(pair)) {
            val quote = orEmpty { listOfNotNull(WatchPairQuotes.parse(venue, get(WatchPairQuotes.url(venue, pair)), pair)) }
            if (quote.isNotEmpty()) return quote.first()
        }
        return null
    }

    /** Kürzel gegen USDT (Spot): Binance für alle, dann nur die fehlenden bei Bybit, dann OKX. */
    private suspend fun coinQuotes(wanted: List<String>, get: suspend (String) -> String): Map<String, WatchQuote> {
        if (wanted.isEmpty()) return emptyMap()
        val binance = orEmpty { WatchQuotes.binance(get(WatchQuotes.binanceUrl(wanted)), wanted) }
        var merged = WatchQuotes.merge(binance)
        if (merged.size < wanted.size) {
            val bybit = perCoin(wanted - merged.keys, get, WatchQuotes::bybitUrl, WatchQuotes::bybit)
            merged = WatchQuotes.merge(merged.values.toList(), bybit)
        }
        if (merged.size < wanted.size) {
            val okx = perCoin(wanted - merged.keys, get, WatchQuotes::okxUrl, WatchQuotes::okx)
            merged = WatchQuotes.merge(merged.values.toList(), okx)
        }
        return merged
    }

    /** Je Coin eine Abfrage (gleichzeitig); ein Fehler betrifft nur diesen Coin. */
    private suspend fun perCoin(
        coins: List<String>,
        get: suspend (String) -> String,
        url: (String) -> String,
        parse: (String, Collection<String>) -> List<WatchQuote>,
    ): List<WatchQuote> = coroutineScope {
        coins.map { coin ->
            async { orEmpty { parse(get(url(coin)), listOf(coin)) } }
        }.awaitAll().flatten()
    }

    /**
     * Fehler einer Abfrage → leer (die nächste Börse versucht es); ein Abbruch geht weiter — auch wenn er sich als
     * Lesefehler der geschlossenen Verbindung zeigt ([ensureActive]).
     */
    private suspend inline fun orEmpty(block: () -> List<WatchQuote>): List<WatchQuote> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }

    /**
     * Blockierende Abfrage, abbrechbar: Wird der Aufruf abgebrochen (Gesamtfrist, Ansicht weg), schliesst ein Wächter
     * die Verbindung — das blockierte Lesen endet sofort statt erst nach [TIMEOUT_MS].
     */
    private suspend fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            coroutineScope {
                val watchdog = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    runInterruptible(Dispatchers.IO) { read(connection) }
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection): String {
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
