package com.cryptochecker.app.contract

import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveParser
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.pairListPaging
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/**
 * Vertragstests der Börsen-Adapter: echte Antwortformate (Einzel- und Sammelabfrage, WebSocket)
 * aus `testdata/exchanges/exchange_*.json` laufen durch die Parser, das Ergebnis muss den
 * erwarteten Werten entsprechen. Das iOS-Gegenstück (`Tests/ExchangeContractTests.swift`) liest
 * dieselben Dateien (vom Projekt-Generator nach `Tests/Exchanges` kopiert) — benennt eine Börse
 * ein Feld um, schlagen beide Plattformen gleich an.
 *
 * Ablauf wie in der App (`MarketRemoteDataSource.fetchMarketTicker`): `readTicker`; wirft
 * das oder ergibt es keinen gültigen `last` (fehlt, 0, NaN/∞), gilt die Antwort als Fehler und `readError` liefert
 * den Fehlertext der Börse (oder wirft: dann gibt es keinen).
 *
 * `knownIssue`: bekannte Abweichung (siehe Notiz im Fall). Der Fall läuft, ein Fehlschlag wird
 * nur gemeldet; besteht er auf einer genannten Plattform plötzlich, ebenfalls nur ein Hinweis.
 *
 * Dazu echte, datierte Antworten aus `testdata/exchanges/recorded/recorded_*.json`
 * ([recordedResponsesStillParse]), sofern jemand sie mit `tools/record_exchange_fixtures.py`
 * aufgezeichnet hat — die von Hand geschriebenen Fälle folgen der Doku, die aufgezeichneten zeigen,
 * was die Börse heute wirklich schickt.
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

    /** Aufgezeichnete Antworten; leer, solange keine aufgezeichnet wurden (der Ordner ist optional). */
    private fun recordedFixtures(): List<Pair<String, Map<String, Any?>>> =
        File(dir(), "recorded").listFiles { f -> f.name.startsWith("recorded_") && f.name.endsWith(".json") }
            .orEmpty()
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
                "liquidityUsd" -> check("$prefix liquidityUsd", value, t.liquidityUsd)
                // Kurs ist der Mark-Preis, weil kein Mittelkurs kam (Hyperliquid)
                "markFallback" -> if (value != t.markFallback) {
                    throw CaseFailure("$prefix markFallback: expected $value, got ${t.markFallback}")
                }
                "timestamp" -> if (value.num()!!.toLong() != t.timestamp) {
                    throw CaseFailure("$prefix timestamp: expected ${value.num()!!.toLong()}, got ${t.timestamp}")
                }
                // null = die Börse liefert keine Zeit (timestamp ist dann die Abrufzeit)
                "exchangeTime" -> if (value.num()?.toLong() != t.exchangeTime) {
                    throw CaseFailure("$prefix exchangeTime: expected ${value.num()?.toLong()}, got ${t.exchangeTime}")
                }
                // Börsenzeit als Abstand vor dem Abruf (Lighter ohne Handel in 24 h: spätestens 24 h zuvor)
                "exchangeTimeAgeMillis" -> {
                    val want = value.num()!!.toLong()
                    val age = t.exchangeTime?.let { t.timestamp - it }
                    if (age == null || age < want || age > want + 60_000) {
                        throw CaseFailure("$prefix exchangeTimeAgeMillis: expected $want, got $age")
                    }
                }
                else -> throw CaseFailure("$prefix: unknown expectation '$key'")
            }
        }
    }

    private fun checkerInfo(spec: Map<String, Any?>) = PairQuery(
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
            market.readTicker(requestId, body, ticker, info)
            if (!ticker.last.isFinite() || ticker.last <= 0.0) failure = IllegalStateException("no ticker data")
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
            market.readError(requestId, body, info)
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
        val parsed = try {
            market.parseBulkTickersDetailed(requestId, c["body"] as String)
        } catch (e: Exception) {
            if (expect["error"] == true) return
            throw CaseFailure("bulk parser failed: $e")
        }
        val tickers = parsed.tickers
        if (expect["error"] == true) throw CaseFailure("expected the batch to fail, got ${tickers.keys}")
        // Unlesbare Zeilen werden je Paar gemeldet (BulkTickerRows), nicht still ausgelassen
        val failed = (expect["failed"] as? List<*>)?.map { it as String }?.toSet() ?: emptySet()
        if (parsed.failed != failed) throw CaseFailure("failed rows: expected $failed, got ${parsed.failed}")
        // Antwort meldet sich selbst als lückenhaft (Market.bulkResponseComplete)
        (expect["complete"] as? Boolean)?.let {
            if (parsed.complete != it) throw CaseFailure("complete: expected $it, got ${parsed.complete}")
        }

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

    /** Paarliste: Anzahl, fehlende Kennungen und je Paar Basis, Kotierung, Vertragsart, TradFi. */
    private fun runPairs(c: Map<String, Any?>) {
        val market = market(c["market"] as String)
        val requestId = c["requestId"].num()?.toInt() ?: 0
        val expect = c["expect"].obj()
        val pairs = ArrayList<com.cryptochecker.marketdata.model.MarketPair>()
        try {
            market.readPairList(requestId, c["body"] as String, pairs)
        } catch (e: Exception) {
            throw CaseFailure("pair parser failed: $e")
        }
        val byId = pairs.associateBy { it.pairId }
        // Adresse der Anfrage (Abfrage-Parameter wie Binance `symbolStatus`)
        if (expect.containsKey("address")) {
            val address = market.pairListAddress(requestId)
            if (address != expect["address"]) throw CaseFailure("address: expected ${expect["address"]}, got $address")
        }
        // Folgeseite nach dieser Antwort, mit frischem Blätterzustand wie zu Beginn einer Synchronisierung
        if (expect.containsKey("nextPage")) {
            val next = market.pairListPaging().nextPage(requestId, c["body"] as String)
            if (next != expect["nextPage"]) throw CaseFailure("nextPage: expected ${expect["nextPage"]}, got $next")
        }

        expect["count"].num()?.let {
            if (pairs.size != it.toInt()) throw CaseFailure("count: expected ${it.toInt()}, got ${pairs.size} ${byId.keys}")
        }
        (expect["absent"] as? List<*>)?.forEach { id ->
            if (byId.containsKey(id)) throw CaseFailure("pair '$id' should be absent")
        }
        (expect["pairs"] as? Map<*, *>)?.forEach { (id, raw) ->
            val p = byId[id] ?: throw CaseFailure("missing pair '$id' (have ${byId.keys})")
            val want = raw.obj()
            (want["base"] as? String)?.let { if (p.base != it) throw CaseFailure("[$id] base: expected $it, got ${p.base}") }
            (want["quote"] as? String)?.let { if (p.quote != it) throw CaseFailure("[$id] quote: expected $it, got ${p.quote}") }
            (want["contract"] as? String)?.let { if (p.contractType.name != it) throw CaseFailure("[$id] contract: expected $it, got ${p.contractType}") }
            (want["tradFi"] as? Boolean)?.let { if (p.tradFi != it) throw CaseFailure("[$id] tradFi: expected $it, got ${p.tradFi}") }
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

    /**
     * Ein aufgezeichneter Fall. Echte Kurse ändern sich, darum keine Sollwerte, nur Plausibilität:
     * Einzelkurs liest ohne Fehler mit `last` > 0, Sammelabfrage liefert Kurse, Paarliste enthält
     * das Paar des Einzelkurses (`contains`, gleiche Kennung wie in der App). Beim Einzelkurs dazu die
     * Werte selbst ([checkRecordedValues]), gemessen am Aufnahmezeitpunkt [recordedAt] (ms, null = unbekannt).
     */
    private fun runRecorded(c: Map<String, Any?>, recordedAt: Long?) {
        val market = market(c["market"] as String)
        val requestId = c["requestId"].num()?.toInt() ?: 0
        val body = c["body"] as String
        when (val kind = c["kind"] as String) {
            "single" -> {
                val ticker = SimpleTicker()
                try {
                    market.readTicker(requestId, body, ticker, checkerInfo(c["pair"].obj()))
                } catch (e: Exception) {
                    throw CaseFailure("parser failed: $e")
                }
                if (ticker.last <= 0.0) throw CaseFailure("no positive last price (${ticker.last})")
                checkRecordedValues(ticker, recordedAt)
            }
            "bulk" -> {
                val parsed = try {
                    market.parseBulkTickersDetailed(requestId, body)
                } catch (e: Exception) {
                    throw CaseFailure("bulk parser failed: $e")
                }
                if (parsed.tickers.isEmpty()) throw CaseFailure("batch without tickers (failed rows: ${parsed.failed.size})")
            }
            "pairs" -> {
                val pairs = ArrayList<com.cryptochecker.marketdata.model.MarketPair>()
                try {
                    market.readPairList(requestId, body, pairs)
                } catch (e: Exception) {
                    throw CaseFailure("pair parser failed: $e")
                }
                if (pairs.isEmpty()) throw CaseFailure("empty pair list")
                (c["contains"] as? String)?.let { id ->
                    if (pairs.none { it.pairId == id }) throw CaseFailure("pair list lacks '$id' (${pairs.size} pairs)")
                }
            }
            else -> throw CaseFailure("unknown kind $kind")
        }
    }

    /**
     * Werte eines aufgezeichneten Einzelkurses, seit Build 93: Eine Börsenzeit liegt höchstens 30 Tage vor und
     * kurz nach der Aufnahme ([RECORDED_FUTURE_SLACK_MS]; eine Zeitzone als Epoche geschrieben, wie Bithumbs
     * KST-Zeit, fiele sonst erst beim Nutzer auf). Liefert die Börse Hoch und Tief, liegt der Kurs dazwischen (kleine Toleranz für Rundung) —
     * sonst gehörten Hoch/Tief zu einem anderen Zeitraum als der Kurs. Nicht gelieferte Felder zählen nicht.
     * Gleich wie iOS `checkRecordedValues`.
     */
    private fun checkRecordedValues(ticker: Ticker, recordedAt: Long?) {
        val time = ticker.exchangeTime
        if (time != null && recordedAt != null) {
            if (time > recordedAt + RECORDED_FUTURE_SLACK_MS) {
                throw CaseFailure("exchangeTime $time lies after recordedAt $recordedAt")
            }
            if (time < recordedAt - RECORDED_MAX_AGE_MS) {
                throw CaseFailure("exchangeTime $time lies more than 30 days before recordedAt $recordedAt")
            }
        }
        val high = ticker.high
        val low = ticker.low
        if (high.isFinite() && high > 0.0 && low.isFinite() && low > 0.0) {
            val slack = high * 1e-9
            if (low > high + slack) throw CaseFailure("low $low above high $high")
            if (ticker.last < low - slack || ticker.last > high + slack) {
                throw CaseFailure("last ${ticker.last} outside low $low … high $high")
            }
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
                        "pairs" -> runPairs(c)
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

    /**
     * Aufgezeichnete echte Antworten (`tools/record_exchange_fixtures.py`, Aktion «Record exchange
     * fixtures»), seit Build 105 Pflicht (liegen im Repo). Jede Datei nennt
     * ihren Aufnahmezeitpunkt (`recordedAt`), damit ein Fehlschlag einem Stand der Börse zugeordnet
     * werden kann.
     */
    @Test
    fun recordedResponsesStillParse() {
        val files = recordedFixtures()
        // Seit Build 105 Pflicht (liegen im Repo): fehlen sie, nicht still grün
        assertTrue("keine recorded_*.json in testdata/exchanges/recorded", files.isNotEmpty())
        val failures = ArrayList<String>()
        var cases = 0
        for ((file, data) in files) {
            val recordedAt = data["recordedAt"] as? String
            if (recordedAt.isNullOrBlank()) failures += "$file: missing recordedAt"
            val recordedAtMillis = recordedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            for (raw in data["cases"].list()) {
                val c = raw.obj()
                cases++
                val error = try {
                    runRecorded(c, recordedAtMillis)
                    null
                } catch (e: CaseFailure) {
                    e.message
                } catch (e: Exception) {
                    "unexpected ${e.javaClass.simpleName}: ${e.message}"
                }
                if (error != null) failures += "$file › ${c["kind"]} (recorded $recordedAt): $error"
            }
        }
        println("ExchangeContractTest: ${files.size} recorded file(s), $cases case(s)")
        assertTrue("${failures.size} recorded response(s) no longer parse:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Bithumb schreibt in «trade_timestamp» die koreanische Uhrzeit als Epoche (9 h zu spät). Die gelesene
     * Börsenzeit der echten Antwort liegt darum höchstens 60 s nach der Aufnahme (Bithumb wird früh im Lauf
     * abgefragt) und nicht weiter als eine Stunde davor.
     */
    @Test
    fun recordedBithumbTradeTimeIsUtc() {
        val data = SourceGuard.android(recordedFixtures().firstOrNull { it.first == "recorded_bithumb.json" }?.second, "testdata/exchanges/recorded/recorded_bithumb.json")
        val recordedAt = Instant.parse(data["recordedAt"] as String).toEpochMilli()
        val single = data["cases"].list().map { it.obj() }.first { it["kind"] == "single" }
        val ticker = SimpleTicker()
        market("Bithumb").readTicker(0, single["body"] as String, ticker, checkerInfo(single["pair"].obj()))
        val time = ticker.exchangeTime
        assertNotNull("Bithumb: keine Börsenzeit gelesen", time)
        assertTrue("Bithumb: Börsenzeit $time nach Aufnahme $recordedAt + 60 s", time!! <= recordedAt + 60_000)
        assertTrue("Bithumb: Börsenzeit $time über 1 h vor Aufnahme $recordedAt", time >= recordedAt - 3_600_000)
    }

    private companion object {
        /**
         * Spielraum nach der Aufnahme: `recordedAt` ist der Beginn des ganzen Laufs, die Börsen werden danach der
         * Reihe nach abgefragt (Upbit am 2026-10-16 65 s später). 5 min fangen jede Zeitzonen-Verschiebung (≥ 1 h).
         */
        const val RECORDED_FUTURE_SLACK_MS = 5L * 60 * 1000

        /** Älter als 30 Tage ist für einen gehandelten Kurs keine plausible letzte Handelszeit mehr. */
        const val RECORDED_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
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
