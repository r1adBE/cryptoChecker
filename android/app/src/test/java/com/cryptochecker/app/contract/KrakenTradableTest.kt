package com.cryptochecker.app.contract

import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.Kraken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kraken-Paare, die nicht «online» sind (z. B. «cancel_only»), an der echten Aufnahme vom 16.10.2026
 * (`testdata/exchanges/recorded/recorded_kraken.json`, vier solche Paare): Sie stehen nicht zur Wahl, und eine
 * bestehende Beobachtung endet als «nicht mehr gehandelt» statt still mit dem alten Kurs. Wie
 * `KrakenTradableTests.swift`.
 */
class KrakenTradableTest {

    private val kraken = Kraken()

    /** Die vier nicht handelbaren Paare der Aufnahme. */
    private val cancelOnly = setOf("ACAEUR", "ACAUSD", "ACXEUR", "ACXUSD")

    private fun recordedBody(kind: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "testdata/exchanges/recorded/recorded_kraken.json")
            if (file.isFile) {
                val cases = ParityJson.parse(file.readText(Charsets.UTF_8)).obj()["cases"].list()
                return cases.map { it.obj() }.first { it["kind"] == kind }["body"] as String
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("recorded_kraken.json not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun recordingReallyContainsTheFourCancelOnlyPairs() {
        val result = JSONObject(recordedBody("pairs")).getJSONObject("result")
        val notOnline = result.keys().asSequence().filter { result.getJSONObject(it).optString("status") != "online" }
        assertEquals(cancelOnly, notOnline.toSet())
    }

    @Test
    fun pairListOffersOnlyOnlinePairs() {
        val pairs = ArrayList<MarketPair>()
        kraken.readPairList(0, recordedBody("pairs"), pairs)
        val ids = pairs.map { it.pairId }.toSet()
        assertTrue("XXBTZUSD" in ids)
        assertTrue("cancel_only offered: ${ids.intersect(cancelOnly)}", ids.intersect(cancelOnly).isEmpty())
    }

    @Test
    fun bulkSkipsCancelOnlyRowsSilently() {
        val parsed = kraken.parseBulkTickersDetailed(0, recordedBody("bulk"))
        // Schlusskurs 0: weder Kurs noch Lesefehler — fehlt in der vollständigen Liste → Einzelabfrage
        assertTrue(parsed.tickers.keys.intersect(cancelOnly).isEmpty())
        assertTrue(parsed.failed.intersect(cancelOnly).isEmpty())
        assertTrue(parsed.tickers.getValue("XXBTZUSD").last > 0.0)
    }

    @Test
    fun singleTickerOfCancelOnlyPairHasNoPriceAndConfirmsNotTraded() {
        val bulk = JSONObject(recordedBody("bulk")).getJSONObject("result")
        for (id in cancelOnly) {
            // Einzelantwort = dieselbe Zeile unter `result`, wie Kraken sie für ?pair=<id> liefert
            val body = JSONObject().put("error", org.json.JSONArray()).put(
                "result", JSONObject().put(id, bulk.getJSONObject(id)),
            ).toString()
            val ticker = SimpleTicker()
            kraken.readTicker(0, body, ticker, PairQuery("ACA", "USD", id, FuturesContractType.NONE))
            assertEquals(id, Ticker.MISSING, ticker.last, 0.0)
        }
        // Ohne Kurs meldet die Einzelabfrage «Parsed ticker has no data» → bestätigt «nicht mehr gehandelt»
        assertTrue(NotTraded.confirmedBySingle(gotPrice = false, error = "Parsed ticker has no data"))
        assertFalse(NotTraded.confirmedBySingle(gotPrice = true, error = null))
    }
}
