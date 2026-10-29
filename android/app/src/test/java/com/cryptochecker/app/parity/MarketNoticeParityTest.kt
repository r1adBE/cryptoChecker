package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.gas.GasAlertLogic
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.IndicatorAlertKind
import com.cryptochecker.app.domain.market.IndicatorAlertRules
import com.cryptochecker.app.domain.market.MarketNoticeGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gemeinsame Testfälle `market_notice.json` (iOS: `MarketNoticeParityTests.swift`): Der gemerkte Stand einer
 * Markt-Meldung gilt nur für die Grenze, mit der er gespeichert wurde — fehlt sie oder ist sie eine andere (auch 0 =
 * zwischendurch aus), ist die Meldung wieder scharf. Dazu die eingeschalteten Kennzahl-Grenzen, die erneuten Versuche
 * der Prüfung und die Zeitgrenzen des Markt-Tabs.
 */
class MarketNoticeParityTest {

    private val data = ParityJson.load("market_notice.json")

    private fun cases(key: String) = data[key].list().map { it.obj() }

    @Test
    fun boundChanged() {
        cases("boundChanged").forEach { c ->
            assertEquals(
                c["name"] as String, c["changed"],
                MarketNoticeGate.boundChanged(c["stored"].num()?.toInt(), c["current"].num()!!.toInt()),
            )
        }
    }

    @Test
    fun indicatorWasCrossed() {
        cases("indicatorWasCrossed").forEach { c ->
            assertEquals(
                c["name"] as String, c["wasCrossed"],
                IndicatorAlertRules.wasCrossed(
                    c["storedCrossed"] as Boolean, c["storedThreshold"].num()?.toInt(), c["current"].num()!!.toInt(),
                ),
            )
        }
    }

    @Test
    fun gasArmedFor() {
        cases("gasArmedFor").forEach { c ->
            assertEquals(
                c["name"] as String, c["armed"],
                GasAlertLogic.armedFor(c["storedArmed"] as Boolean, c["storedThreshold"].num(), c["threshold"].num()!!),
            )
        }
    }

    @Test
    fun depegArmedFor() {
        cases("depegArmedFor").forEach { c ->
            assertEquals(
                c["name"] as String, c["armed"],
                DepegAlarm.armedFor(c["storedArmed"] as Boolean, c["storedTenths"].num()?.toInt(), c["tenths"].num()!!.toInt()),
            )
        }
    }

    @Test
    fun indicatorActive() {
        cases("indicatorActive").forEach { c ->
            val stored = c["stored"].obj().entries.associate { (k, v) -> IndicatorAlertKind.valueOf(k) to v.num()!!.toInt() }
            val expected = c["active"].obj().entries.associate { (k, v) -> IndicatorAlertKind.valueOf(k) to v.num()!! }
            val actual = IndicatorAlertRules.active(stored)
            assertEquals(c["name"] as String, expected.keys, actual.keys)
            expected.forEach { (kind, t) -> assertEquals("${c["name"]} $kind", t, actual.getValue(kind), 1e-12) }
        }
    }

    @Test
    fun zoneRetry() {
        val retry = data["zoneRetry"].obj()
        assertEquals(retry["maxRetries"].num()!!.toInt(), MarketNoticeGate.MAX_RETRIES)
        val delays = retry["delaysMillis"].list().map { it.num()!!.toLong() }
        assertEquals(delays, (1..MarketNoticeGate.MAX_RETRIES).map { MarketNoticeGate.retryDelayMillis(it) })
        assertEquals(delays.first(), MarketNoticeGate.RETRY_BASE_MILLIS)
    }

    @Test
    fun marketTabTimeouts() {
        val expected = data["marketTabTimeouts"].obj().mapValues { it.value.num()!!.toLong() }
        assertEquals(expected, CycleSource.entries.associate { it.key to it.timeoutMillis })
    }
}
