package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.market.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kennzahlen auf Schlusskursen (`testdata/parity/indicators.json`): gleitender Durchschnitt der letzten n Werte und
 * RSI nach Wilder, null bei zu wenig Daten oder nicht positivem n bzw. Zeitraum. Gegenstück:
 * `Tests/IndicatorsParityTests.swift`.
 */
class IndicatorsParityTest {

    private val data = ParityJson.load("indicators.json")

    private fun closes(raw: Any?): List<Double> = raw.list().map { it.num()!! }

    private fun check(name: String, expected: Double?, actual: Double?) {
        if (expected == null) {
            assertNull(name, actual)
        } else {
            assertNotNull(name, actual)
            assertEquals(name, expected, actual!!, 1e-9)
        }
    }

    @Test
    fun smaWieGemeinsameTestfaelle() {
        val cases = data["sma"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val actual = Indicators.smaOfLast(closes(c["values"]), c["n"].num()!!.toInt())
            check("sma: ${c["name"]}", c["expected"].num(), actual)
        }
    }

    @Test
    fun rsiWieGemeinsameTestfaelle() {
        val cases = data["rsi"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val actual = Indicators.rsi(closes(c["closes"]), c["period"].num()!!.toInt())
            check("rsi: ${c["name"]}", c["expected"].num(), actual)
        }
    }

    @Test
    fun rsiBleibtZwischenNullUndHundert() {
        for (raw in data["rsi"].list()) {
            val c = raw.obj()
            val value = Indicators.rsi(closes(c["closes"]), c["period"].num()!!.toInt()) ?: continue
            assertTrue("${c["name"]}: $value", value in 0.0..100.0)
        }
    }
}
