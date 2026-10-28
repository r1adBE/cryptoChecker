package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchFreshness
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Collections

/**
 * Uhr-App (`testdata/parity/watch_quotes.json`) — wie iOS `WatchQuotesParityTests.swift`: Antworten je Börse (Abfrage
 * je Coin), Ausweich-Reihenfolge Binance → Bybit → OKX mit nur den fehlenden Coins, höchstens 8 Coins, Abfrage-Adressen,
 * Kurse je Coin zusammenführen samt Alter je Coin ([WatchCache]) und Laufnummer der Abrufe ([WatchFetchGate]).
 */
class WatchQuotesParityTest {

    private val data = JSONObject(fixtureDir().resolve("watch_quotes.json").readText(Charsets.UTF_8))

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

    /** Schlüssel einer Abfrage wie im Fixture: `binance`, `bybit:<SYMBOL>`, `okx:<INST-ID>`. */
    private fun key(url: String): String = when (URI(url).host) {
        "api.binance.com" -> "binance"
        "api.bybit.com" -> "bybit:" + query(url)["symbol"]
        "www.okx.com" -> "okx:" + query(url)["instId"]
        else -> error("unexpected host in $url")
    }

    @Test
    fun maxCoins() {
        assertEquals(data.getInt("maxCoins"), WatchCoins.MAX)
    }

    @Test
    fun urls() {
        val u = data.getJSONObject("urls")
        val coins = strings(u.getJSONArray("coins"))
        val binance = WatchQuotes.binanceUrl(coins)
        assertEquals(u.getString("binanceBase"), binance.substringBefore("?"))
        assertEquals(u.getString("binanceSymbols"), query(binance)["symbols"])
        u.getJSONObject("bybit").let { m -> m.keys().forEach { coin -> assertEquals(m.getString(coin), WatchQuotes.bybitUrl(coin)) } }
        u.getJSONObject("okx").let { m -> m.keys().forEach { coin -> assertEquals(m.getString(coin), WatchQuotes.okxUrl(coin)) } }
    }

    @Test
    fun parse() {
        for (c in objects(data.getJSONArray("parse"))) {
            val name = c.getString("name")
            val wanted = strings(c.getJSONArray("wanted"))
            val body = c.getString("body")
            // Unlesbares wirft hier; der Abruf fängt das ab (wie leer)
            val quotes = runCatching {
                when (c.getString("source")) {
                    "binance" -> WatchQuotes.binance(body, wanted)
                    "bybit" -> WatchQuotes.bybit(body, wanted)
                    "okx" -> WatchQuotes.okx(body, wanted)
                    else -> error("unknown source")
                }
            }.getOrDefault(emptyList())
            val expected = objects(c.getJSONArray("expected"))
            assertEquals(name, expected.size, quotes.size)
            expected.zip(quotes).forEach { (e, q) ->
                assertEquals(name, e.getString("symbol"), q.symbol)
                assertEquals(name, e.getDouble("price"), q.price, 1e-9)
                assertEquals(name, e.getString("source"), q.source)
                if (e.isNull("change24h")) {
                    assertNull(name, q.change24h)
                } else {
                    assertNotNull(name, q.change24h)
                    assertEquals(name, e.getDouble("change24h"), q.change24h!!, 1e-9)
                }
            }
        }
    }

    @Test
    fun fallback() {
        for (c in objects(data.getJSONArray("fallback"))) {
            val name = c.getString("name")
            val responses = c.getJSONObject("responses")
            val requests = Collections.synchronizedList(ArrayList<String>())
            val result = runBlocking {
                WatchPriceSource.fetch(strings(c.getJSONArray("coins"))) { url ->
                    val key = key(url)
                    requests += key
                    // null bzw. fehlend = HTTP-Fehler, Sperre oder kein Netz
                    if (responses.isNull(key)) throw IllegalStateException("HTTP 451") else responses.getString(key)
                }
            }
            assertEquals(name, strings(c.getJSONArray("requests")).sorted(), requests.sorted())
            val expected = (0 until c.getJSONArray("expected").length()).map { i ->
                c.getJSONArray("expected").getJSONArray(i).let { it.getString(0) to it.getString(1) }
            }
            assertEquals(name, expected, result.map { (coin, q) -> coin to q.source })
        }
    }

    private fun quote(o: JSONObject) = WatchQuote(
        o.getString("symbol"), o.getDouble("price"), if (o.isNull("change24h")) null else o.getDouble("change24h"),
        o.getString("source"),
    )

    @Test
    fun cache() {
        val cache = data.getJSONObject("cache")
        val now = cache.getLong("now")
        for (c in objects(cache.getJSONArray("cases"))) {
            val name = c.getString("name")
            val old = objects(c.getJSONArray("old")).associate { it.getString("symbol") to WatchCached(quote(it), it.getLong("time")) }
            val fresh = objects(c.getJSONArray("fresh")).associate { it.getString("symbol") to quote(it) }
            val merged = WatchCache.merge(old, fresh, c.getLong("time"), strings(c.getJSONArray("keep")))
            val expected = objects(c.getJSONArray("expected"))
            assertEquals(name, expected.map { it.getString("symbol") }.toSet(), merged.keys)
            for (e in expected) {
                val got = merged.getValue(e.getString("symbol"))
                assertEquals(name, quote(e), got.quote)
                assertEquals(name, e.getLong("time"), got.time)
                assertEquals(name, e.getBoolean("outdated"), WatchFreshness.isOutdated(got.time, now))
                val age = WatchFreshness.age(got.time, now)
                assertEquals(name, e.getString("unit"), unitKey(age.unit))
                assertEquals(name, e.getInt("count"), age.count)
            }
            assertEquals(name, c.getLong("listTime"), WatchCache.listTime(merged, strings(c.getJSONArray("coins"))))
        }
    }

    private fun unitKey(unit: WatchFreshness.AgeUnit): String = when (unit) {
        WatchFreshness.AgeUnit.JUST_NOW -> "justNow"
        WatchFreshness.AgeUnit.MINUTES -> "minutes"
        WatchFreshness.AgeUnit.HOURS -> "hours"
        WatchFreshness.AgeUnit.DAYS -> "days"
    }

    @Test
    fun gate() {
        for (c in objects(data.getJSONArray("gate"))) {
            val name = c.getString("name")
            val gate = WatchFetchGate()
            var shown = strings(c.getJSONArray("shown"))
            val runs = HashMap<String, Pair<Long, List<String>>>()
            var finished = 0
            for (step in objects(c.getJSONArray("steps"))) {
                when {
                    step.has("shown") -> shown = strings(step.getJSONArray("shown"))
                    step.has("start") -> runs[step.getString("start")] = gate.start() to shown
                    else -> {
                        val (run, asked) = runs.getValue(step.getString("finish"))
                        assertEquals(name, step.getBoolean("accepted"), gate.accepts(run, asked, shown))
                        finished++
                    }
                }
            }
            assertTrue(name, finished > 0)
        }
    }
}
