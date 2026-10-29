package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.market.HalvingMath
import com.cryptochecker.marketdata.util.EpochTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemeinsame Testfälle `server_values.json` (iOS: `ServerValuesParityTests.swift`): unplausible Server-Werte werden
 * verworfen statt überzulaufen (iOS stürzte dort ab) — Sekunden → ms, Zeitangabe unbekannter Einheit, Blockhöhe.
 */
class ServerValuesParityTest {

    private val data = ParityJson.load("server_values.json")

    @Test
    fun secondsToMillis() {
        val cases = data["secondsToMillis"].list().map { it.obj() }
        assertTrue(cases.size >= 8)
        cases.forEach { c ->
            val expected = (c["expected"] as String?)?.toLong()
            assertEquals("secondsToMillis: $c", expected, EpochTime.secondsToMillis((c["seconds"] as String).toLong()))
        }
    }

    @Test
    fun parseTimeToMillis() {
        data["parseTimeToMillis"].list().map { it.obj() }.forEach { c ->
            assertEquals("parseTimeToMillis: $c", (c["expected"] as String).toLong(), EpochTime.parseTimeToMillis((c["time"] as String).toLong()))
        }
    }

    @Test
    fun halving() {
        data["halving"].list().map { it.obj() }.forEach { c ->
            val estimate = HalvingMath.estimate(c["tipHeight"].num()!!.toInt(), 600.0, nowMillis = 1_700_000_000_000L)
            val expected = c["expected"]?.obj()
            assertEquals("halving: $c", expected?.get("height").num()?.toInt(), estimate?.height)
            assertEquals("halving: $c", expected?.get("remaining").num()?.toInt(), estimate?.remainingBlocks)
        }
    }
}
