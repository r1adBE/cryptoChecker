package com.cryptochecker.app.parity

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.candles.CandleHttp
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.ExchangeCandleRegistry
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Eigene Kerzen je Börse (`testdata/parity/own_candles.json`): Adressen der Anfragen und gelesene
 * Kerzen aus Antworten nach den öffentlichen Dokumentationen. Gegenstück: `Tests/OwnCandlesParityTests.swift`.
 */
class OwnCandlesParityTest {

    private val data = ParityJson.load("own_candles.json")

    private fun pair(raw: Any?): CandlePair {
        val p = raw.obj()
        return CandlePair(
            exchangeKey = p["exchangeKey"] as String,
            exchangeName = p["exchangeName"] as String,
            base = p["base"] as String,
            quote = p["quote"] as String,
            pairId = p["pairId"] as String?,
            contractType = FuturesContractType.valueOf(p["contract"] as String),
            tradFi = p["tradFi"] as Boolean? ?: false,
        )
    }

    private fun near(label: String, expected: Double, actual: Double) {
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual) <= max(abs(expected) * 1e-9, 1e-9))
    }

    @Test
    fun fetch() = runBlocking {
        val cases = data["fetch"].list()
        assertTrue(cases.size >= 30)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val pair = pair(c["pair"])
            val responses = c["responses"].obj()
            val requested = mutableListOf<String>()
            val fixedNow = c["now"].num()?.toLong() ?: 0L
            // POST: Schlüssel «POST <Adresse> <Inhalt>»
            val http = object : CandleHttp {
                override suspend fun get(url: String): String? { requested += url; return responses[url] as String? }
                override suspend fun post(url: String, json: String): String? {
                    val key = "POST $url $json"
                    requested += key
                    return responses[key] as String?
                }
                override val now: Long get() = fixedNow
            }
            val source = ExchangeCandleRegistry.sourceFor(pair)
            assertNotNull("$name: source", source)
            val interval = CandleInterval.valueOf(c["interval"] as String)
            val result = source!!.fetch(pair, interval, (c["limit"].num()!!).toInt(), http)
            assertEquals("$name: urls", c["urls"].list(), requested)
            val expected = c["expected"]
            if (expected == null) {
                assertNull("$name: expected no candles", result)
                continue
            }
            val rows = expected.list()
            assertNotNull("$name: expected candles", result)
            assertEquals("$name: count", rows.size, result!!.size)
            rows.forEachIndexed { i, row ->
                val r = row.list()
                val k = result[i]
                assertEquals("$name[$i] openTime", r[0].num()!!.toLong(), k.openTime)
                near("$name[$i] open", r[1].num()!!, k.open)
                near("$name[$i] high", r[2].num()!!, k.high)
                near("$name[$i] low", r[3].num()!!, k.low)
                near("$name[$i] close", r[4].num()!!, k.close)
                near("$name[$i] volume", r[5].num()!!, k.volume)
                val qv = r[6].num()
                if (qv == null) assertNull("$name[$i] quoteVolume", k.quoteVolume)
                else near("$name[$i] quoteVolume", qv, k.quoteVolume ?: Double.NaN)
            }
        }
    }

    @Test
    fun supports() {
        val cases = data["supports"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val pair = pair(c["pair"])
            val source = ExchangeCandleRegistry.sourceFor(pair)
            val expected = c["source"] as String?
            if (expected == null) assertNull("$name: no own source", source)
            else assertTrue("$name: source for $expected", source != null && expected in source.exchangeKeys)
            assertEquals("$name: fallback", c["fallback"] as Boolean, OwnCandlePolicy.allowsFallback(pair))
            // Eigene Kerzen nur in 1 h, 4 h und 1 Tag
            if (source != null) {
                assertTrue(ExchangeCandleRegistry.supports(pair, CandleInterval.D1))
                assertTrue(!ExchangeCandleRegistry.supports(pair, CandleInterval.W1))
            }
        }
    }
}
