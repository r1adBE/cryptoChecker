package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchPair
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Collections

/**
 * Selbst abgefragte Kurse für Favoriten des Handys (`testdata/parity/watch_pairs.json`, Abschnitte «venues», «urls»,
 * «fetch») — wie iOS `WatchPairsParityTests.swift`: die Börse des Handys zuerst (die Quelle folgt dem Paar des Handys),
 * andere Börsen gleicher Art nur, wenn sie nicht antwortet oder das Paar fehlt; die Börse im Ergebnis ist die liefernde.
 */
class WatchPairQuotesParityTest {

    private val data = JSONObject(fixtureDir().resolve("watch_pairs.json").readText(Charsets.UTF_8))

    /** Ordner `testdata/parity`: Gradle startet Unit-Tests im Modulordner (`wear/`), andere Läufer im Projektordner. */
    private fun fixtureDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/parity")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("testdata/parity not found above ${System.getProperty("user.dir")}")
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }
        .associate { part -> part.substringBefore("=") to URLDecoder.decode(part.substringAfter("="), "UTF-8") }

    /** Schlüssel einer Abfrage wie im Fixture. */
    private fun key(url: String): String {
        val q = query(url)
        return when (URI(url).host) {
            "api.binance.com" -> q["symbol"]?.let { "binance:$it" } ?: "binance"
            "fapi.binance.com" -> "binanceFutures:" + q["symbol"]
            "api.bybit.com" -> (if (q["category"] == "linear") "bybitLinear:" else "bybit:") + q["symbol"]
            "www.okx.com" -> "okx:" + q["instId"]
            else -> error("unexpected host in $url")
        }
    }

    private fun venue(label: String) = WatchPairQuotes.Venue.entries.first { it.label == label }

    @Test
    fun venues() {
        for (c in objects(data.getJSONArray("venues"))) {
            val pair = WatchPair.parse(c.getString("key"))!!
            assertEquals(c.getString("key"), strings(c.getJSONArray("expected")), WatchPairQuotes.venues(pair).map { it.label })
        }
    }

    @Test
    fun urls() {
        for (c in objects(data.getJSONArray("urls"))) {
            val pair = WatchPair.parse(c.getString("key"))!!
            assertEquals(c.getString("url"), WatchPairQuotes.url(venue(c.getString("venue")), pair))
        }
    }

    @Test
    fun fetch() {
        for (c in objects(data.getJSONArray("fetch"))) {
            val name = c.getString("name")
            val responses = c.getJSONObject("responses")
            val requests = Collections.synchronizedList(ArrayList<String>())
            val result = runBlocking {
                WatchPriceSource.fetch(strings(c.getJSONArray("coins"))) { url ->
                    val key = key(url)
                    requests += key
                    // null bzw. fehlend = HTTP-Fehler, Sperre oder kein Netz
                    if (responses.isNull(key)) throw IllegalStateException("HTTP 503") else responses.getString(key)
                }
            }
            assertEquals(name, strings(c.getJSONArray("requests")).sorted(), requests.sorted())
            val expected = c.getJSONArray("expected")
            assertEquals(name, (0 until expected.length()).map { expected.getJSONArray(it).getString(0) }, result.keys.toList())
            for (i in 0 until expected.length()) {
                val e = expected.getJSONArray(i)
                val q = result.getValue(e.getString(0))
                assertEquals(name, e.getString(1), q.source)
                assertEquals(name, e.getDouble(2), q.price, 1e-9)
                assertEquals(name, e.getDouble(3), q.change24h!!, 1e-9)
            }
        }
    }
}
