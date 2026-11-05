package com.cryptochecker.app.parity

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.MultiResponseSource
import com.cryptochecker.marketdata.model.ReferenceSource
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.BankReference
import com.cryptochecker.marketdata.util.ReferenceRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Referenzkurse von NBU und NBP ([ReferenceRates], [ReferenceSource], [BankReference]) als gemeinsame Testfälle
 * (`testdata/parity/reference_rates.json`, Kopie in iOS `Tests/Parity/`, gelesen von `ReferenceRatesParityTests.swift`):
 * Quelle je Währung, Ausschlüsse, Lesen der Antworten, Kreuzkurse, Veränderung gegen die vorige Veröffentlichung,
 * Gültigkeit der behaltenen Antworten und der Hinweis «umgerechnet».
 */
class ReferenceRatesParityTest {

    private val data = ParityJson.load("reference_rates.json")
    private val bodies = data["bodies"].obj().mapValues { it.value as String }

    private fun close(name: String, expected: Double?, actual: Double?) {
        if (expected == null) return assertNull(name, actual)
        assertNotNull(name, actual)
        assertTrue("$name: expected $expected, got $actual", abs(expected - actual!!) <= abs(expected) * 1e-9)
    }

    private fun exchange(source: String): BankReference =
        ExchangesConfig.EXCHANGES.getValue(ReferenceSource.valueOf(source).exchangeKey) as BankReference

    private fun joined(c: Map<String, Any?>): List<String> = c["bodies"].list().map { bodies.getValue(it as String) }

    @Test
    fun tickersFromJoinedResponses() {
        for (c in data["tickers"].list().map { it.obj() }) {
            val exchange = exchange(c["source"] as String)
            val body = MultiResponseSource.join(joined(c))
            val name = "${c["source"]} ${c["bodies"]}"
            val tickers: Map<String, Ticker> = runCatching { exchange.parseBulkTickersMain(0, body) }.getOrDefault(emptyMap())
            for ((id, raw) in c["expected"].obj()) {
                val expect = raw.obj()
                val ticker = tickers[id] ?: throw AssertionError("$name: missing $id (have ${tickers.keys.sorted()})")
                close("$name $id last", expect["last"].num(), ticker.last)
                close("$name $id change", expect["change"].num(), ticker.change24hPercent)
            }
            for (id in c["absent"].list().map { it as String }) assertFalse("$name: $id must be absent", id in tickers)
        }
    }

    @Test
    fun nbuRangeUrl() {
        val c = data["nbuRangeUrl"].obj()
        assertEquals(c["expected"], ReferenceRates.nbuRangeUrl(LocalDate.parse(c["today"] as String)))
    }

    @Test
    fun assignPrefersEcbThenNbuThenNbp() {
        val c = data["assign"].obj()
        fun codes(key: String) = c[key].list().map { it as String }.toSet()
        val assigned = ReferenceRates.assign(codes("ecb"), codes("nbu"), codes("nbp"))
        val expected = c["expected"].obj().mapValues { ReferenceSource.valueOf(it.value as String) }
        assertEquals(expected, assigned)
        for (code in c["absent"].list().map { it as String }) assertFalse("$code must be excluded", code in assigned)
        assertEquals(c["nbuPairs"], ReferenceRates.pairs(assigned, ReferenceSource.NBU).map { it.pairId })
        val nbp = ReferenceRates.pairs(assigned, ReferenceSource.NBP).map { it.pairId }
        assertEquals(c["nbpPairsCount"].num()!!.toInt(), nbp.size)
        assertTrue(nbp.containsAll(c["nbpPairsSample"].list().map { it as String }))
    }

    @Test
    fun expectedWednesdayAndValidity() {
        for (c in data["expectedWednesday"].list().map { it.obj() }) {
            val now = c["now"].num()!!.toLong()
            assertEquals(c["utc"] as String, c["expected"], ReferenceRates.nbpExpectedWednesday(now).toString())
        }
        for (c in data["validUntil"].list().map { it.obj() }) {
            val now = c["now"].num()!!.toLong()
            val actual = exchange(c["source"] as String).validUntil(joined(c), now)
            assertEquals("${c["source"]} ${c["utc"]} ${c["bodies"]}", c["expected"].num()!!.toLong(), actual)
        }
    }

    @Test
    fun sourcesAndConvertedNotice() {
        for (c in data["sources"].list().map { it.obj() }) {
            val source = ReferenceSource.of(c["exchangeKey"] as String)
            assertEquals(c["exchangeKey"] as String, c["source"], source?.name)
            if (source == null) continue
            assertEquals(c["weekly"], source.weekly)
            assertEquals(c["home"], source.home)
            val exchange = ExchangesConfig.EXCHANGES.getValue(source.exchangeKey)
            assertFalse("${source.name}: no alarms", exchange.allowsAlarms)
            assertEquals("${source.name}: daily label", !source.weekly, exchange.priceIsDailyReference)
        }
        for (c in data["converted"].list().map { it.obj() }) {
            val source = ReferenceSource.valueOf(c["source"] as String)
            assertEquals("$c", c["expected"], source.converted(c["base"] as String, c["hasChange"] as Boolean))
        }
    }
}
