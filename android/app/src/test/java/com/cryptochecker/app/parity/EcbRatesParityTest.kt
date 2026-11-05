package com.cryptochecker.app.parity

import com.cryptochecker.marketdata.util.EcbRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Euro-Referenzkurse der EZB ([EcbRates]) als gemeinsame Testfälle (`testdata/parity/ecb_rates.json`, Kopie in iOS
 * `Tests/Parity/`, gelesen von `EcbRatesParityTests.swift`): Rundung auf 5 Stellen, Kreuzkurse, Lesen der Dateien
 * (XML) und des Data Portals (SDMX-JSON), Zeitfenster, Adresse und Abdeckung der 90-Tage-Datei.
 */
class EcbRatesParityTest {

    private val data = ParityJson.load("ecb_rates.json")

    private fun close(name: String, expected: Double?, actual: Double?) {
        if (expected == null) return assertNull(name, actual)
        assertNotNull(name, actual)
        assertTrue("$name: expected $expected, got $actual", abs(expected - actual!!) <= abs(expected) * 1e-12)
    }

    private fun days(raw: Any?): Map<String, Map<String, Double>>? =
        raw?.obj()?.mapValues { (_, day) -> day.obj().mapValues { (_, rate) -> rate.num()!! } }

    private fun sameDays(name: String, expected: Map<String, Map<String, Double>>?, actual: Map<String, Map<String, Double>>?) {
        if (expected == null) return assertNull(name, actual)
        assertNotNull(name, actual)
        assertEquals(name, expected.keys, actual!!.keys)
        for ((day, rates) in expected) {
            assertEquals("$name $day", rates.keys, actual.getValue(day).keys)
            for ((code, rate) in rates) close("$name $day $code", rate, actual.getValue(day)[code])
        }
    }

    private fun cases(key: String): List<Map<String, Any?>> =
        data[key].list().map { it.obj() }.also { assertTrue("$key: keine Fälle", it.isNotEmpty()) }

    @Test
    fun round5() {
        for (c in cases("round5")) {
            close("round5 ${c["value"]}", c["expected"].num(), EcbRates.round5(c["value"].num()!!))
        }
    }

    @Test
    fun crossAndRebase() {
        val day = days(mapOf("d" to data["day"]))!!.getValue("d")
        for (c in cases("cross")) {
            val base = c["base"] as String
            val quote = c["quote"] as String
            close("cross $base/$quote", c["expected"].num(), EcbRates.cross(day, base, quote))
        }
        for (c in cases("rebase")) {
            val expected = c["expected"].obj().mapValues { it.value.num()!! }
            val actual = EcbRates.rebase(day, c["base"] as String)
            assertEquals("rebase ${c["base"]}", expected.keys, actual.keys)
            for ((code, rate) in expected) close("rebase ${c["base"]} $code", rate, actual[code])
        }
    }

    @Test
    fun parseXml() {
        for (c in cases("parseXml")) {
            val only = (c["only"] as List<*>?)?.map { it as String }?.toSet()
            sameDays(c["name"] as String, days(c["expected"]), EcbRates.parseXml(c["body"] as String, only))
        }
    }

    @Test
    fun parseSdmx() {
        for (c in cases("parseSdmx")) sameDays(c["name"] as String, days(c["expected"]), EcbRates.parseSdmx(c["body"] as String))
    }

    @Test
    fun recent() {
        for (c in cases("recent")) {
            val all = days(c["days"])!!
            val expected = c["expected"].list().map { all.getValue(it as String) }
            assertEquals(c["name"] as String, expected, EcbRates.recent(all, c["windowDays"].num()!!.toLong()))
        }
    }

    @Test
    fun seriesUrlAndCoverage() {
        for (c in cases("seriesUrl")) {
            val codes = c["codes"].list().map { it as String }
            val url = EcbRates.seriesUrl(codes, LocalDate.parse(c["from"] as String), LocalDate.parse(c["to"] as String))
            assertEquals(c["expected"], url)
        }
        for (c in cases("coveredByHist90d")) {
            val covered = EcbRates.coveredByHist90d(LocalDate.parse(c["from"] as String), LocalDate.parse(c["today"] as String))
            assertEquals("${c["from"]} / ${c["today"]}", c["expected"], covered)
        }
    }
}
