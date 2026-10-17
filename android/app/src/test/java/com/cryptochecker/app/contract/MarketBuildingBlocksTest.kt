package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.forEachJSONArray
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachName
import com.cryptochecker.marketdata.util.forEachString
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optStrings
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die gemeinsamen Bausteine aller Börsen: [SimpleMarket] (Adressen, Fehlerfeld), [Market.bulkRowIfActive] und
 * der Standard-Fehlertext sowie die JSON-Helfer. Die Börsen selbst prüft ExchangeContractTest; hier geht es um die
 * Regeln, auf die sich alle verlassen. Gegenstück: `Tests/MarketBuildingBlocksTests.swift`.
 */
class MarketBuildingBlocksTest {

    /** Kleinste SimpleMarket-Börse: Fehlertext im Feld «err». */
    private class Probe(errorField: String? = "err") : SimpleMarket(
        name = "Probe",
        pairListUrl = "https://example.test/pairs",
        tickerUrl = "https://example.test/ticker/%1\$s?depth=1",
        errorField = errorField,
    ) {
        override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
            ticker.last = json.getDouble("price")
        }
    }

    /** Wie [Probe], bildet die Kennung aber selbst («BASE-QUOTE»). */
    private class DashProbe : SimpleMarket(
        name = "DashProbe",
        pairListUrl = "https://example.test/pairs",
        tickerUrl = "https://example.test/ticker?symbol=%1\$s",
    ) {
        override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}-${info.quote}"
    }

    /** Sammelabfrage aus Zeilen {"id", "last"} über [bulkRowIfActive]. */
    private class BulkProbe : Market("BulkProbe", "BulkProbe") {
        override fun tickerAddress(call: Int, info: PairQuery): String = ""

        override fun parseBulkTickers(
            call: Int,
            body: String,
            tickers: MutableMap<String, Ticker>,
            failed: MutableSet<String>,
        ) {
            JSONArray(body).forEachJSONObject { row ->
                bulkRowIfActive(row.optString("id"), row, "last", tickers, failed) { it.last = row.getDouble("last") }
            }
        }
    }

    private fun info(id: String?) = PairQuery("BTC", "USD", id, FuturesContractType.NONE)

    @Test
    fun simpleMarketInsertsThePairIdIntoTheTickerUrl() {
        val market = Probe()
        assertEquals("https://example.test/ticker/BTCUSD?depth=1", market.tickerAddress(0, info("BTCUSD")))
        // Ohne Kennung steht wie seit jeher «null» in der Adresse (die Börse antwortet mit ihrem Fehler)
        assertEquals("https://example.test/ticker/null?depth=1", market.tickerAddress(0, info(null)))
        assertEquals("https://example.test/ticker?symbol=BTC-USD", DashProbe().tickerAddress(0, info(null)))
        assertEquals("https://example.test/ticker?symbol=X_Y", DashProbe().tickerAddress(0, info("X_Y")))
    }

    @Test
    fun simpleMarketHasOneFixedPairListAddress() {
        val market = Probe()
        assertEquals(1, market.pairListCallCount)
        assertEquals("https://example.test/pairs", market.pairListAddress(0))
        assertEquals("https://example.test/pairs", market.pairListAddress(3))
    }

    @Test
    fun simpleMarketReadsTheErrorField() {
        val market = Probe()
        assertEquals("boom", market.readError(0, """{"err":"boom"}""", info("X")))
        // JSON null ist kein Fehlertext «null»
        assertThrows(JSONException::class.java) { market.readError(0, """{"err":null}""", info("X")) }
        assertThrows(JSONException::class.java) { market.readError(0, """{"other":"x"}""", info("X")) }
    }

    @Test
    fun withoutErrorFieldThereIsNoErrorText() {
        val thrown = assertThrows(MarketParseException::class.java) {
            Probe(errorField = null).readError(0, """{"err":"boom"}""", info("X"))
        }
        assertEquals(Market.NO_ERROR_TEXT, thrown.message)
    }

    @Test
    fun readTickerUsesTheSubclassReader() {
        val ticker = Probe().readTicker(0, """{"price":"1.5"}""", SimpleTicker(), info("X"))
        assertEquals(1.5, ticker.last, 0.0)
        assertEquals(null, ticker.exchangeTime)
    }

    @Test
    fun bulkRowIfActiveSkipsIdleRowsAndReportsUnreadableOnes() {
        val body = """[
            {"id":"A","last":"10"},
            {"id":"B","last":2},
            {"id":"","last":"5"},
            {"last":"5"},
            {"id":"IDLE1","last":null},
            {"id":"IDLE2","last":""},
            {"id":"IDLE3","last":"0"},
            {"id":"IDLE4","last":0},
            {"id":"MISSING"},
            {"id":"TEXT","last":"abc"}
        ]"""
        val parsed = BulkProbe().parseBulkTickersDetailed(0, body)
        assertEquals(setOf("A", "B"), parsed.tickers.keys)
        assertEquals(10.0, parsed.tickers.getValue("A").last, 0.0)
        // Feld fehlt oder ist Unsinn: Fehler je Paar statt still «unverändert»
        assertEquals(setOf("MISSING", "TEXT"), parsed.failed)
    }

    @Test
    fun mostlyUnreadableRowsFailTheWholeBatch() {
        val body = """[{"id":"A","last":"1"},{"id":"X"},{"id":"Y","last":"abc"}]"""
        val thrown = assertThrows(MarketParseException::class.java) { BulkProbe().parseBulkTickersDetailed(0, body) }
        assertTrue(BulkTickerRows.isFormatError(thrown.message))
    }

    @Test
    fun jsonIterationHelpersVisitEveryEntryInOrder() {
        val seen = ArrayList<String>()
        JSONArray("""["a","b"]""").forEachString { seen += it }
        JSONArray("""[{"k":"c"},{"k":"d"}]""").forEachJSONObject { seen += it.getString("k") }
        JSONArray("""[["e",1],["f",2]]""").forEachJSONArray { seen += it.getString(0) }
        JSONObject("""{"g":{},"h":{}}""").forEachName { name, _ -> seen += name }
        JSONObject("{}").forEachName { name, _ -> seen += name }
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), seen.take(6))
        assertEquals(setOf("g", "h"), seen.drop(6).toSet())
        // Ein Eintrag vom falschen Typ wirft, statt still zu fehlen
        assertThrows(JSONException::class.java) { JSONArray("""[1]""").forEachJSONObject { } }
    }

    @Test
    fun jsonFieldHelpersTreatNullAsMissing() {
        val row = JSONObject("""{"t":"x","n":null,"d":"2.5","list":["TradFi","","Pre-IPO"]}""")
        assertEquals("x", row.optText("t"))
        assertEquals("", row.optText("n"))
        assertEquals("", row.optText("absent"))
        assertEquals("x", row.getText("t"))
        assertThrows(JSONException::class.java) { row.getText("n") }
        assertEquals(2.5, row.optDoubleOrMissing("d"), 0.0)
        assertEquals(Ticker.MISSING, row.optDoubleOrMissing("absent"), 0.0)
        assertEquals(listOf("TradFi", "Pre-IPO"), row.optStrings("list"))
        assertEquals(emptyList<String>(), row.optStrings("absent"))
    }
}
