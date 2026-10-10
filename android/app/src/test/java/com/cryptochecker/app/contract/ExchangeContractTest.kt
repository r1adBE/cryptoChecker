package com.cryptochecker.app.contract

import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveParser
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Vertragstests der Börsen-Adapter: echte Antwortformate (Einzel- und Sammelabfrage, WebSocket)
 * aus `testdata/exchanges/exchange_*.json` laufen durch die Parser, das Ergebnis muss den
 * erwarteten Werten entsprechen. Das iOS-Gegenstück (`Tests/ExchangeContractTests.swift`) liest
 * dieselben Dateien (vom Projekt-Generator nach `Tests/Exchanges` kopiert) — benennt eine Börse
 * ein Feld um, schlagen beide Plattformen gleich an.
 *
 * Ablauf wie in der App (`MarketRemoteDataSource.updateMarketTicker`): `parseTickerMain`; wirft
 * das oder bleibt `last` bei NO_DATA, gilt die Antwort als Fehler und `parseErrorMain` liefert
 * den Fehlertext der Börse (oder wirft: dann gibt es keinen).
 *
 * `knownIssue`: bekannte Abweichung (siehe Notiz im Fall). Der Fall läuft, ein Fehlschlag wird
 * nur gemeldet; besteht er auf einer genannten Plattform plötzlich, ebenfalls nur ein Hinweis.
 *
 * `org.json` braucht hier eine echte Implementierung statt der Android-Stubs
 * (testImplementation `com.vaadin.external.google:android-json`, der AOSP-Code).
 */
class ExchangeContractTest {

    private class CaseFailure(message: String) : Exception(message)

    private fun dir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/exchanges")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException("testdata/exchanges not found above ${System.getProperty("user.dir")}")
    }

    private fun fixtures(): List<Pair<String, Map<String, Any?>>> =
        dir().listFiles { f -> f.name.startsWith("exchange_") && f.name.endsWith(".json") }!!
            .sortedBy { it.name }
            .map { it.name to ParityJson.parse(it.readText(Charsets.UTF_8)).obj() }

    private fun market(key: String): Market =
        MarketsConfig.MARKETS[key] ?: throw CaseFailure("unknown market key $key")

    private fun close(expected: Double, actual: Double): Boolean {
        if (expected == actual) return true
        return abs(expected - actual) <= max(abs(expected) * 1e-9, 1e-15)
    }

    private fun check(label: String, expected: Any?, actual: Double?) {
        val want = expected.num()
        if (want == null) {
            if (actual != null) throw CaseFailure("$label: expected null, got $actual")
        } else if (actual == null || !close(want, actual)) {
            throw CaseFailure("$label: expected $want, got $actual")
        }
    }

    /** Vergleicht nur die Felder, die der Fall nennt. */
    private fun checkTicker(prefix: String, expect: Map<String, Any?>, t: Ticker) {
        for ((key, value) in expect) {
            when (key) {
                "last" -> check("$prefix last", value, t.last)
                "bid" -> check("$prefix bid", value, t.bid)
                "ask" -> check("$prefix ask", value, t.ask)
                "high" -> check("$prefix high", value, t.high)
                "low" -> check("$prefix low", value, t.low)
                "vol" -> check("$prefix vol", value, t.vol)
                "volQuote" -> check("$prefix volQuote", value, t.volQuote)
                "change24hPercent" -> check("$prefix change24hPercent", value, t.change24hPercent)
                "timestamp" -> if (value.num()!!.toLong() != t.timestamp) {
                    throw CaseFailure("$prefix timestamp: expected ${value.num()!!.toLong()}, got ${t.timestamp}")
                }
                else -> throw CaseFailure("$prefix: unknown expectation '$key'")
            }
        }
    }

    private fun checkerInfo(spec: Map<String, Any?>) = CheckerInfo(
        spec["base"] as String,
        spec["quote"] as String,
        spec["id"] as String?,
        FuturesContractType.valueOf(spec["contract"] as? String ?: "NONE"),
    )

    private fun runSingle(c: Map<String, Any?>) {
        val market = market(c["market"] as String)
        val info = checkerInfo(c["pair"].obj())
        val requestId = c["requestId"].num()?.toInt() ?: 0
        val body = c["body"] as String
        val expect = c["expect"].obj()

        val ticker = SimpleTicker()
        var failure: Exception? = null
        try {
            market.parseTickerMain(requestId, body, ticker, info)
            if (ticker.last <= Ticker.NO_DATA) failure = IllegalStateException("no ticker data")
        } catch (e: Exception) {
            failure = e
        }

        if (expect["error"] != true) {
            if (failure != null) throw CaseFailure("expected a ticker, parser failed: $failure")
            checkTicker("ticker", expect, ticker)
            return
        }

        if (failure == null) throw CaseFailure("expected an error, got last=${ticker.last}")
        (expect["thrown"] as? String)?.let {
            if (failure.message != it) throw CaseFailure("thrown: expected '$it', got '${failure.message}'")
        }
        val message = try {
            market.parseErrorMain(requestId, body, info)
        } catch (e: Exception) {
            null
        }
        val wanted = expect["messageBodyPrefix"].num()?.let { body.take(it.toInt()) } ?: expect["message"] as String?
        if (message != wanted) throw CaseFailure("error text: expected '$wanted', got '$message'")
    }

    private fun runBulk(c: Map<String, Any?>) {
        val market = market(c["market"] as String)
        val requestId = c["requestId"].num()?.toInt() ?: 0
        val expect = c["expect"].obj()
        val tickers = try {
            market.parseBulkTickersMain(requestId, c["body"] as String)
        } catch (e: Exception) {
            if (expect["error"] == true) return
            throw CaseFailure("bulk parser failed: $e")
        }
        if (expect["error"] == true) throw CaseFailure("expected the batch to fail, got ${tickers.keys}")

        expect["count"].num()?.let {
            if (tickers.size != it.toInt()) throw CaseFailure("count: expected ${it.toInt()}, got ${tickers.size} ${tickers.keys}")
        }
        (expect["absent"] as? List<*>)?.forEach { id ->
            if (tickers.containsKey(id)) throw CaseFailure("'$id' should be absent")
        }
        (expect["tickers"] as? Map<*, *>)?.forEach { (id, fields) ->
            val t = tickers[id] ?: throw CaseFailure("missing ticker '$id' (have ${tickers.keys})")
            checkTicker("[$id]", fields.obj(), t)
        }
    }

    private fun runLive(c: Map<String, Any?>) {
        val exchange = LiveExchange.valueOf(c["exchange"] as String)
        val ticks = LiveParser.parse(exchange, c["message"] as String)
        val expected = c["ticks"].list()
        if (ticks.size != expected.size) throw CaseFailure("tick count: expected ${expected.size}, got $ticks")
        expected.forEachIndexed { i, raw ->
            val want = raw.obj()
            val got = ticks[i]
            if (got.symbol != want["symbol"]) throw CaseFailure("tick $i symbol: expected ${want["symbol"]}, got ${got.symbol}")
            check("tick $i price", want["price"], got.price)
            check("tick $i change24h", want["change24h"], got.change24h)
            val time = want["time"].num()?.toLong()
            if (time != got.time) throw CaseFailure("tick $i time: expected $time, got ${got.time}")
        }
    }

    private fun knownOnAndroid(c: Map<String, Any?>): Boolean {
        val issue = c["knownIssue"] as? Map<*, *> ?: return false
        return (issue["platforms"] as? List<*>)?.contains("android") ?: true
    }

    @Test
    fun exchangeResponsesParseAsDocumented() {
        val files = fixtures()
        assertTrue("exchange fixtures found: ${files.size}", files.size >= 25)

        val failures = ArrayList<String>()
        val notes = ArrayList<String>()
        var cases = 0
        val kinds = HashMap<String, Int>()

        for ((file, data) in files) {
            for (raw in data["cases"].list()) {
                val c = raw.obj()
                val kind = c["kind"] as String
                val label = "$file › ${c["name"]}"
                cases++
                kinds[kind] = (kinds[kind] ?: 0) + 1
                val error = try {
                    when (kind) {
                        "single" -> runSingle(c)
                        "bulk" -> runBulk(c)
                        "live" -> runLive(c)
                        else -> throw CaseFailure("unknown kind $kind")
                    }
                    null
                } catch (e: CaseFailure) {
                    e.message
                } catch (e: Exception) {
                    "unexpected ${e.javaClass.simpleName}: ${e.message}"
                }
                when {
                    knownOnAndroid(c) && error != null -> notes += "known issue still present: $label — $error"
                    knownOnAndroid(c) -> notes += "known issue no longer reproduces (update the fixture): $label"
                    error != null -> failures += "$label: $error"
                }
            }
        }
        notes.forEach { println("ExchangeContractTest: $it") }

        assertTrue("cases read: $cases", cases >= 150)
        assertTrue("single/bulk/live cases: $kinds", (kinds["single"] ?: 0) >= 80 && (kinds["bulk"] ?: 0) >= 30 && (kinds["live"] ?: 0) >= 15)
        assertTrue("${failures.size} contract failure(s):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Jede registrierte Börse braucht mindestens einen Einzelabruf-Fall — neue Adapter fallen sonst auf. */
    @Test
    fun everyRegisteredMarketHasAContractFixture() {
        val covered = HashSet<String>()
        val bulkCovered = HashSet<String>()
        for ((_, data) in fixtures()) {
            for (raw in data["cases"].list()) {
                val c = raw.obj()
                val key = c["market"] as? String ?: continue
                if (c["kind"] == "single") covered += key
                if (c["kind"] == "bulk") bulkCovered += key
            }
        }
        val missing = MarketsConfig.MARKETS.keys.filter { it !in covered }.sorted()
        assertTrue("markets without a single-ticker contract case: $missing", missing.isEmpty())

        val bulkMissing = MarketsConfig.MARKETS.values
            .filter { it.bulkTickersNumOfRequests > 0 && it.key !in bulkCovered }
            .map { it.key }.sorted()
        assertTrue("markets with a batch endpoint but no bulk contract case: $bulkMissing", bulkMissing.isEmpty())
    }

    /** Jede Börse mit WebSocket hat mindestens einen Live-Fall. */
    @Test
    fun everyLiveExchangeHasAContractFixture() {
        val covered = HashSet<String>()
        for ((_, data) in fixtures()) {
            for (raw in data["cases"].list()) {
                val c = raw.obj()
                if (c["kind"] == "live") covered += c["exchange"] as String
            }
        }
        val missing = LiveExchange.entries.map { it.name }.filter { it !in covered }
        assertTrue("live exchanges without a contract case: $missing", missing.isEmpty())
    }
}
